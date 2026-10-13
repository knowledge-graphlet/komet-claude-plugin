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

import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dispatch of the {@code koncept-definition} block renderer ({@code IKE-Network/ike-issues#1292}).
 * The grammar and the tree are {@code ProposedDefinition}'s and are tested there; the control
 * needs a JavaFX toolkit and is exercised by running Komet.
 */
class KonceptDefinitionBlockRendererTest {

    private static final String PROPOSAL = """
            proposed: Something
            necessary
              k:sctid=235599003[Eosinophilic esophagitis]
            """;

    @Test
    void onlyItsOwnTagIsRenderedAndNothingWithoutAView() {
        KonceptDefinitionBlockRenderer viewless = new KonceptDefinitionBlockRenderer(null, 13);
        assertTrue(viewless.render("koncept-definition", PROPOSAL, StyleAttributeMap.EMPTY).isEmpty(),
                "with no view to name concepts, the block falls through to preformatted text");
        assertTrue(viewless.render("koncept-tree", PROPOSAL, StyleAttributeMap.EMPTY).isEmpty());
    }
}
