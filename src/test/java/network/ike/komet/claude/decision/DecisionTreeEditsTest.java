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
package network.ike.komet.claude.decision;

import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A what-if restates one token on one line, and the parse says which line each number is on. */
class DecisionTreeEditsTest {

    static final DecisionTerms T = DecisionTerms.synthetic();

    static final String TREE = """
            decision: k:D[Treat?] @id=d
              parameter = [0.02,0.02] k:H[Hazard] @id=h basis=estimated
              alternative: k:A[treat] @id=a
                money = [700,700] k:Usd[US dollar] @id=usd basis=estimated
                outcome: k:O[done] @id=o
            """;

    @Test
    @DisplayName("The parse numbers the line each vertex was read from")
    void linesAreKnown() {
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(TREE, T, BlockIdentities.storeFree());
        DecisionTree tree = parsed.tree().orElseThrow();
        assertEquals(1, parsed.lineOf(tree.rootIndex()).orElseThrow());
        assertEquals(2, parsed.lineOf(tree.nodesOfType(Parameter.class).get(0).vertexIndex()).orElseThrow());
        assertEquals(4, parsed.lineOf(tree.nodesOfType(MoneyCost.class).get(0).vertexIndex()).orElseThrow());
        assertTrue(parsed.lineOf(99).isEmpty());
    }

    @Test
    @DisplayName("Restating a line moves its interval and nothing else, and reads back")
    void restatesOneLine() {
        String moved = DecisionTreeEdits.restate(TREE, 2, Bounds.of(0.01, 0.03)).orElseThrow();
        assertTrue(moved.contains("  parameter = [0.01,0.03] k:H[Hazard] @id=h basis=estimated\n"), moved);
        assertEquals(TREE.replace("[0.02,0.02]", "[0.01,0.03]"), moved);
        DecisionTree tree = DecisionTreeText.parse(moved, T, BlockIdentities.storeFree()).tree().orElseThrow();
        assertEquals(Bounds.of(0.01, 0.03), tree.nodesOfType(Parameter.class).get(0).measure().bounds());
        String cheaper = DecisionTreeEdits.restate(TREE, 4, Bounds.point(650)).orElseThrow();
        assertTrue(cheaper.contains("    money = [650,650] k:Usd[US dollar] @id=usd basis=estimated\n"), cheaper);
    }

    @Test
    @DisplayName("A line that states no interval, or no line, cannot be restated")
    void onlyStatedLinesMove() {
        assertEquals(Optional.empty(), DecisionTreeEdits.restate(TREE, 1, Bounds.point(1)));
        assertEquals(Optional.empty(), DecisionTreeEdits.restate(TREE, 3, Bounds.point(1)));
        assertEquals(Optional.empty(), DecisionTreeEdits.restate(TREE, 0, Bounds.point(1)));
        assertEquals(Optional.empty(), DecisionTreeEdits.restate(TREE, 40, Bounds.point(1)));
    }

    @Test
    @DisplayName("The health price is set in the info, added when it had none")
    void healthPriceInTheInfo() {
        assertEquals("decision-tree health-price=50000", DecisionTreeEdits.withHealthPrice("decision-tree", 50_000));
        assertEquals("decision-tree health-price=50000 title=x",
                DecisionTreeEdits.withHealthPrice("decision-tree health-price=100000 title=x", 50_000));
        assertEquals("decision-tree health-price=100000", DecisionTreeEdits.withHealthPrice(null, 100_000));
    }

    @Test
    @DisplayName("What a reader types reads as a point or an interval, separators forgiven")
    void typedBounds() {
        assertEquals(Optional.of(Bounds.point(150_000)), DecisionTreeEdits.typed("150,000"));
        assertEquals(Optional.of(Bounds.point(5_000)), DecisionTreeEdits.typed(" $5,000 "));
        assertEquals(Optional.of(Bounds.point(0.02)), DecisionTreeEdits.typed("0.02"));
        assertEquals(Optional.of(Bounds.of(0.01, 0.03)), DecisionTreeEdits.typed("[0.01, 0.03]"));
        assertEquals(Optional.empty(), DecisionTreeEdits.typed("lots"));
        assertEquals(Optional.empty(), DecisionTreeEdits.typed(""));
        assertEquals(Optional.empty(), DecisionTreeEdits.typed(null));
    }
}
