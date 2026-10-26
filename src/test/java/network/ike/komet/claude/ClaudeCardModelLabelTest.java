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
package network.ike.komet.claude;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The label of the model menu beside Send. Before the Models API has named the models, the menu
 * shows the id without its {@code claude-} prefix.
 */
class ClaudeCardModelLabelTest {

    @Test
    void anUnnamedModelShowsItsIdWithoutThePrefix() {
        assertEquals("sonnet-5-5", ClaudeCard.shortModelLabel("claude-sonnet-5-5"));
    }

    @Test
    void anIdWithoutThePrefixIsShownWhole() {
        assertEquals("my-proxy-model", ClaudeCard.shortModelLabel("my-proxy-model"));
    }

    @Test
    void effortLevelsReadAsWords() {
        assertEquals("Model default", ClaudeCard.effortLabel(""));
        assertEquals("Low", ClaudeCard.effortLabel("low"));
        assertEquals("Extra high", ClaudeCard.effortLabel("xhigh"));
        assertEquals("Max", ClaudeCard.effortLabel("max"));
    }

    @Test
    void theMenuShowsTheEffortOnlyWhenOneIsAskedFor() {
        assertEquals("sonnet-5-5 · High", ClaudeCard.modelMenuLabel("claude-sonnet-5-5", "high"));
        assertEquals("sonnet-5-5", ClaudeCard.modelMenuLabel("claude-sonnet-5-5", ""));
    }
}
