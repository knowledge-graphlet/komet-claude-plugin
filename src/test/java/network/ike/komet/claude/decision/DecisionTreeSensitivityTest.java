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
import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import network.ike.komet.claude.decision.DecisionElement.Probability;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Flip;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Swing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one-way sweep of the regulatory scan tree, over the block's own text. */
class DecisionTreeSensitivityTest {

    static final DecisionTerms T = DecisionTerms.synthetic();
    static String body;
    static BlockIdentities ids;
    static DecisionTreeSensitivity sweep;
    static DecisionTree tree;

    @BeforeAll
    static void sweepTheScanTree() throws IOException {
        try (InputStream in = DecisionTreeSensitivityTest.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            body = text.substring(text.indexOf("----\n") + 5, text.lastIndexOf("----"));
        }
        ids = BlockIdentities.storeFree();
        sweep = BlockSweep.sweep(body, T, ids, 100_000, BlockSweep.DEFAULT_STEPS).orElseThrow();
        tree = sweep.tree();
    }

    static int vertexOf(String identifier) {
        long nid = ids.nid(identifier).orElseThrow();
        return tree.nodes().stream().filter(n -> n.concept().orElse(0) == nid).findFirst().orElseThrow().vertexIndex();
    }

    static int measureUnder(String identifier, Class<? extends DecisionElement> kind) {
        return tree.children(vertexOf(identifier), kind).get(0).vertexIndex();
    }

    @Test
    @DisplayName("The base is the lab's worked tree: $3,880 at the root, scanning chosen")
    void baseIsTheWorkedTree() {
        assertEquals(3_880, sweep.baseRoot(), 1.0);
        assertEquals(vertexOf("rda-scan"), sweep.baseChosen().orElseThrow());
        assertTrue(sweep.least() < 3_880 && sweep.greatest() > 3_880, sweep.least() + ".." + sweep.greatest());
    }

    @Test
    @DisplayName("Every stated point measure swings over an assumed range, hollow, and derived ones not at all")
    void pointsSwingOverAssumedRanges() {
        Swing scanCost = sweep.swing(measureUnder("rda-scan", MoneyCost.class)).orElseThrow();
        assertFalse(scanCost.stated());
        assertEquals(Bounds.of(350, 1_400), scanCost.range());
        assertEquals(700, scanCost.base());
        assertEquals(14, scanCost.samples().size(), "thirteen steps and the flip");
        assertTrue(scanCost.rootAtLow() < scanCost.rootAtHigh(), "a dearer scan costs more");
        // scanning costs 3,880 against 4,188 for not scanning: the choice flips when the scan is dearer by 308
        Flip flip = scanCost.flipsAt(tree.rootIndex()).get(0);
        assertEquals(1_008, flip.at(), 2.0);
        assertEquals(vertexOf("rda-scan"), flip.chosenBelow());
        assertEquals(vertexOf("rda-no-scan"), flip.chosenAbove());
        assertEquals(4_188, scanCost.rootAtHigh(), 1.0, "beyond the flip the root is what not scanning costs");

        Swing years = sweep.swing(vertexOf("rda-years-at-risk")).orElseThrow();
        assertEquals(Bounds.of(10, 40), years.range(), "a share above one is not a probability");
        Swing sensitivity = sweep.swing(vertexOf("rda-mra-sensitivity")).orElseThrow();
        assertEquals(Bounds.of(0, 1), sensitivity.range());
        Swing present = sweep.swing(measureUnder("rda-reopening-yes", Probability.class)).orElseThrow();
        assertEquals(Bounds.of(0, 1), present.range(), "the residual takes up the rest");

        assertTrue(sweep.swing(vertexOf("rda-rupture-risk-20")).isEmpty(), "a derived parameter is not swept");
        assertTrue(sweep.unswept().isEmpty(), sweep.unswept().toString());
        int stated = (int) tree.nodesOfType(Parameter.class).count(p -> p.measure().stated())
                + tree.nodesOfType(MoneyCost.class).count(c -> c.measure().stated())
                + tree.nodesOfType(DecisionElement.HealthCost.class).count(c -> c.measure().stated())
                + tree.nodesOfType(Probability.class).count(p -> p.measure().stated());
        assertEquals(stated + 1, sweep.swings().size(), "every stated measure and the health price");
    }

    @Test
    @DisplayName("A stated interval is the swing itself, solid")
    void statedIntervalsAreTheSwing() {
        String widened = body.replace("parameter = [0.9,0.9] k:Sensitivity", "parameter = [0.8,0.95] k:Sensitivity");
        DecisionTreeSensitivity again = BlockSweep.sweep(widened, T, BlockIdentities.storeFree(), 100_000, 4).orElseThrow();
        Swing sensitivity = again.swing(vertexOf("rda-mra-sensitivity")).orElseThrow();
        assertTrue(sensitivity.stated());
        assertEquals(Bounds.of(0.8, 0.95), sensitivity.range());
        assertEquals(0.875, sensitivity.base(), 1e-9);
        assertEquals(5, sensitivity.samples().size());
        // the base holds the widened measure at its midpoint, so the root moves a little off 3,880
        assertEquals(3_880, again.baseRoot(), 100.0);
    }

    @Test
    @DisplayName("The reopening hazard flips the root, and at the threshold the two alternatives tie")
    void hazardFlipsTheRoot() {
        int hazard = vertexOf("rda-reopening-hazard");
        Swing swing = sweep.swing(hazard).orElseThrow();
        assertEquals(Bounds.of(0, 1), swing.range());
        Flip flip = swing.flipsAt(tree.rootIndex()).get(0);
        // The block's no-scan arm states its own 2% rather than reading the parameter, so the parameter moves
        // only the scanned arm: more reopenings found means more retreatments paid for, until not scanning wins.
        assertEquals(vertexOf("rda-scan"), flip.chosenBelow());
        assertEquals(vertexOf("rda-no-scan"), flip.chosenAbove());
        assertTrue(flip.at() > 0.02 && flip.at() < 1, "the lab's 2% lies below the threshold: " + flip.at());
        BlockSweep mover = mover();
        Rollback at = mover.moved(hazard, Bounds.point(flip.at())).orElseThrow();
        double scan = at.value(vertexOf("rda-scan")).orElseThrow().midpoint();
        double noScan = at.value(vertexOf("rda-no-scan")).orElseThrow().midpoint();
        assertEquals(scan, noScan, 2.0, "at the threshold the alternatives tie");
    }

    @Test
    @DisplayName("The price of a healthy year is swept too, and breaks even between $80,000 and $90,000")
    void healthPriceBreaksEven() {
        Swing price = sweep.swing(DecisionTreeSensitivity.HEALTH_PRICE).orElseThrow();
        assertEquals(Bounds.of(50_000, 200_000), price.range());
        assertEquals(100_000, price.base());
        Flip flip = price.flipsAt(tree.rootIndex()).get(0);
        assertTrue(flip.at() > 80_000 && flip.at() < 90_000, "break-even " + flip.at());
        assertEquals(vertexOf("rda-no-scan"), flip.chosenBelow());
        assertEquals(vertexOf("rda-scan"), flip.chosenAbove());
    }

    @Test
    @DisplayName("Swings are sorted longest first, and a bar reads back to the value that made it")
    void longestFirstAndReadsBack() {
        for (int i = 1; i < sweep.swings().size(); i++) {
            assertTrue(sweep.swings().get(i - 1).width() >= sweep.swings().get(i).width());
        }
        Swing scanCost = sweep.swing(measureUnder("rda-scan", MoneyCost.class)).orElseThrow();
        double root = scanCost.rootAtValue(1_000).orElseThrow();
        assertEquals(3_880 + 300, root, 1.0, "a scan dearer by 300 costs 300 more at the root, up to the flip");
        assertEquals(1_000, scanCost.valueAtRoot(root).orElseThrow(), 1.0);
    }

    static BlockSweep mover() {
        return BlockSweep.open(body, T, ids, 100_000).orElseThrow();
    }
}
