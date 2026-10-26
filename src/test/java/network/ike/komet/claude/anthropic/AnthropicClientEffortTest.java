/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.anthropic;

import network.ike.komet.claude.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which effort levels a model takes, read from the capabilities the Models API lists for it. The
 * client asks only for a level the model takes, so a level the model refuses never fails a request.
 */
class AnthropicClientEffortTest {

    private static AnthropicClient.ModelInfo model(String capabilities) {
        return Json.parse("{\"id\":\"claude-test\",\"display_name\":\"Claude Test\",\"created_at\":\"2026-10-01T00:00:00Z\","
                + "\"capabilities\":" + capabilities + "}", AnthropicClient.ModelInfo.class);
    }

    @Test
    void everySupportedLevelIsOfferedLeastToMostThorough() {
        AnthropicClient.ModelInfo listed = model("{\"effort\":{\"supported\":true,"
                + "\"max\":{\"supported\":true},\"low\":{\"supported\":true},\"medium\":{\"supported\":true},"
                + "\"high\":{\"supported\":true},\"xhigh\":{\"supported\":true}}}");
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"),
                AnthropicClient.effortLevelsIn(listed.capabilities()));
    }

    @Test
    void anUnsupportedLevelIsLeftOut() {
        // Sonnet 4.6 has no xhigh.
        AnthropicClient.ModelInfo listed = model("{\"effort\":{\"supported\":true,"
                + "\"low\":{\"supported\":true},\"medium\":{\"supported\":true},\"high\":{\"supported\":true},"
                + "\"xhigh\":{\"supported\":false},\"max\":{\"supported\":true}}}");
        assertEquals(List.of("low", "medium", "high", "max"), AnthropicClient.effortLevelsIn(listed.capabilities()));
    }

    @Test
    void aModelWithoutEffortTakesNoLevel() {
        // Haiku 4.5 has no effort parameter.
        AnthropicClient.ModelInfo listed = model("{\"effort\":{\"supported\":false,\"low\":{\"supported\":false}}}");
        assertEquals(List.of(), AnthropicClient.effortLevelsIn(listed.capabilities()));
    }

    @Test
    void missingCapabilitiesTakeNoLevel() {
        assertEquals(List.of(), AnthropicClient.effortLevelsIn(model("{}").capabilities()));
        assertEquals(List.of(), AnthropicClient.effortLevelsIn(null));
    }
}
