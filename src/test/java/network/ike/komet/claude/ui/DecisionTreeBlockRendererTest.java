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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The renderer's store-free decisions: which blocks it takes, and the price a block's info sets. */
class DecisionTreeBlockRendererTest {

    private static final String TREE = """
            decision: k:ScanNow[Scan now?] @id=c1
              alternative: k:Scan[Scan] @id=c2
            """;

    @Test
    @DisplayName("A decision-tree block with a root decision line is accepted, under either spelling")
    void acceptsItsProfile() {
        assertTrue(DecisionTreeBlockRenderer.accepts("decision-tree", TREE));
        assertTrue(DecisionTreeBlockRenderer.accepts("decision-tree health-price=50000", TREE));
        assertTrue(DecisionTreeBlockRenderer.accepts("property-graph profile=decision-tree", TREE));
    }

    @Test
    @DisplayName("Another block, or one with no root decision, is declined to preformatted text")
    void declinesTheRest() {
        assertFalse(DecisionTreeBlockRenderer.accepts("koncept-tree", TREE));
        assertFalse(DecisionTreeBlockRenderer.accepts("property-graph", TREE));
        assertFalse(DecisionTreeBlockRenderer.accepts("decision-tree", "chance: k:X[x] @id=c1\n"));
        assertFalse(DecisionTreeBlockRenderer.accepts("decision-tree", "not a block at all"));
        assertFalse(DecisionTreeBlockRenderer.accepts(null, TREE));
    }

    @Test
    @DisplayName("The block's info may set the health price; otherwise the default stands")
    void readsTheHealthPrice() {
        assertEquals(Optional.of(50_000.0), DecisionTreeBlockRenderer.healthPrice("decision-tree health-price=50000"));
        assertEquals(Optional.of(150_000.0), DecisionTreeBlockRenderer.healthPrice("decision-tree health-price=150,000"));
        assertEquals(Optional.empty(), DecisionTreeBlockRenderer.healthPrice("decision-tree"));
        assertEquals(Optional.empty(), DecisionTreeBlockRenderer.healthPrice("decision-tree health-price=lots"));
        assertEquals(100_000, DecisionTreeBlockRenderer.DEFAULT_HEALTH_PRICE);
    }
}
