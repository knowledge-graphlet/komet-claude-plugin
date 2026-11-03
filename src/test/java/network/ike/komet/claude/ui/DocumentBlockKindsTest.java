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
package network.ike.komet.claude.ui;

import network.ike.komet.claude.decision.DecisionTreeText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decision blocks as structured kinds of a stored document: an attribute line read as an info string. */
class DocumentBlockKindsTest {

    @Test
    void anAttributeLineReadsAsTheInfoStringTheRenderersRead() {
        String info = DocumentBlockKinds.info("[decision-tree, cohort=100, title=\"Inspect the site, or not?\"]");
        assertEquals("decision-tree cohort=100 title=\"Inspect the site, or not?\"", info, "a quoted comma stays in its value");
        assertTrue(DecisionTreeText.isTagged(info));
        assertEquals(Optional.of(100.0), DecisionTreeText.cohort(info));
        assertEquals("anf type=request id=req-inspection", DocumentBlockKinds.info("[anf,type=request,id=req-inspection]"));
        assertEquals("complex-concept", DocumentBlockKinds.info("[complex-concept]"));
        assertEquals("", DocumentBlockKinds.info(null));
    }

    @Test
    void eachKindIsNamedByItsRenderersTag() {
        assertEquals(List.of("decision-tree", "complex-concept", "anf", "calculator", "calculate"),
                List.of(new DocumentBlockKinds.DecisionTree().style(), new DocumentBlockKinds.ComplexConcept().style(),
                        new DocumentBlockKinds.Anf().style(), new DocumentBlockKinds.Calculator().style(),
                        new DocumentBlockKinds.Calculation().style()));
    }
}
