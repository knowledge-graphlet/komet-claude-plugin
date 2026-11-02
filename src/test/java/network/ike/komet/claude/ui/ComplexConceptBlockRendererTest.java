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

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dispatch of the {@code complex-concept} block renderer. The grammar is {@code ComplexConceptText}'s
 * and is tested there; the card needs a JavaFX toolkit and is exercised by running Komet.
 */
class ComplexConceptBlockRendererTest {

    private static final String BLOCK = """
            concept: k:Perrla[PERRLA panel] @id=perrla
              parent: k:AssessmentPanel[Clinical assessment panel] @id=assessment-panel
              expansion: uniform and k:Presence[presence] @id=presence
                member: k:PupilEquality[Pupil equality assessment] @id=pupil-equality
                member: k:PupilRoundness[Pupil roundness assessment] @id=pupil-roundness
            """;

    @Test
    void onlyItsOwnTagIsRenderedAndNothingWithoutAView() {
        ComplexConceptBlockRenderer viewless = new ComplexConceptBlockRenderer((ViewCalculator) null, 13, new BlockContext());
        assertTrue(viewless.render("complex-concept", BLOCK, StyleAttributeMap.EMPTY).isEmpty(),
                "with no view to name concepts, the block falls through to preformatted text");
        assertTrue(viewless.render("decision-tree", BLOCK, StyleAttributeMap.EMPTY).isEmpty());
        assertTrue(viewless.render("complex-concept", "decision: k:X[x] @id=x\n", StyleAttributeMap.EMPTY).isEmpty());
    }
}
