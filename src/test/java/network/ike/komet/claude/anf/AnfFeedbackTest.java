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
package network.ike.komet.claude.anf;

import network.ike.komet.claude.decision.DecisionTreeFeedback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The findings on a reply's anf blocks, as they go back to the assistant. */
class AnfFeedbackTest {

    @Test
    @DisplayName("Clean statements send nothing back")
    void clean() {
        String reply = """
                ```anf type=request id=req-scan
                topic:: k:MraHead[Magnetic resonance angiography of head without contrast] @id=437731000119100
                timing:: [2,2] k:Year[year] @id=258707000
                purpose:: candidate[detection of reopening] disp=PENDING
                ```

                ```anf type=performance id=perf-rupture
                topic:: k:Rupture[Ruptured cerebral aneurysm] @id=233983001
                result:: [1,1] k:Presence[presence] @id=705057003
                ```
                """;
        assertEquals(Optional.empty(), AnfFeedback.message(reply));
    }

    @Test
    @DisplayName("A word where a concept belongs, a measure with no interval, an unknown field and a field of the other type go back")
    void findings() {
        String reply = """
                ```anf type=performance id=perf-reanalysis
                topic:: candidate[re-analysis of the site's data] disp=PENDING
                disposition:: performed
                result:: present
                outcome:: k:X[x] @id=x
                priority:: k:Routine[routine] @id=50811001
                ```
                """;
        String message = AnfFeedback.message(reply).orElseThrow();
        assertTrue(message.startsWith(AnfFeedback.PREFIX), message);
        assertTrue(message.contains("The statement perf-reanalysis:"), message);
        assertTrue(message.contains("disposition:: takes a concept"), message);
        assertTrue(message.contains("result:: is a measure and starts with an interval"), message);
        assertTrue(message.contains("outcome:: is not a field of an ANF statement"), message);
        assertTrue(message.contains("priority:: belongs to a request, not a performance"), message);
        assertTrue(DecisionTreeFeedback.isFeedback(message), "a transcript knows the turn as Komet's");
    }
}
