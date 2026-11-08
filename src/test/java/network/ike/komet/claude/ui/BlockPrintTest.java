/*
 * Copyright © 2025 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.ui;

import network.ike.komet.document.DecisionFigure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A block of fields as a page reads it: names rather than keys, and none of the block's machinery. */
class BlockPrintTest {

    @Test
    void aLineReadsAsItsNames() {
        assertEquals("result:: [1,1] presence", BlockPrint.named("result:: [1,1] k:Presence[presence] @id=705057003"));
        assertEquals("topic:: material problem in the data", BlockPrint.named("topic:: candidate[material problem in the data] disp=PENDING"));
    }

    @Test
    void fieldsAreATable() {
        List<DecisionFigure> figures = BlockPrint.fields("ANF statement", List.of("--", "topic:: k:A[a site] @id=x", "", "method:: candidate[an inspection]", "--"));
        assertEquals(1, figures.size());
        List<String> words = figures.getFirst().marks().stream().filter(DecisionFigure.Text.class::isInstance)
                .map(m -> ((DecisionFigure.Text) m).text()).toList();
        assertTrue(words.containsAll(List.of("ANF statement", "topic", "a site", "method", "an inspection")), words.toString());
        assertFalse(words.stream().anyMatch(word -> word.contains("@id") || word.contains("--")), words.toString());
    }

    @Test
    void noFieldsNoFigure() {
        assertTrue(BlockPrint.fields("ANF statement", List.of("", "  ")).isEmpty());
    }
}
