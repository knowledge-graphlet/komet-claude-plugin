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

import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The year-2 scan decision of {@code rda-decision-tree}, rolled back by the profile and checked
 * against the numbers the topic works by hand: scanning costs $3,880 a patient and not scanning
 * $4,188, so the scan saves $308; below about $86,000 a year of healthy life, not scanning is the
 * cheaper course. Hermetic: the terms are synthetic, since nothing in the profile or the rollback
 * reads a store.
 */
class ScanTreeRollbackTest {

    private static final DecisionTerms T = DecisionTerms.synthetic();

    /** The money and health semantics the tree's costs are on: US dollars at 2026 prices, QALYs. */
    private static final long USD = -5_001;
    private static final long QALY = -5_002;

    // Model assumptions and default values (rda-model-assumptions), as the topic states them.
    private static final double REOPENING = 0.02;          // late reopening hazard in year 2
    private static final double SENSITIVITY = 0.90;        // of the MRA
    private static final double SPECIFICITY = 0.80;
    private static final double RUPTURE_RISK_20 = 1 - Math.pow(0.99, 20);   // 1% a year over 20 years
    private static final double RUPTURE_MONEY = 150_000;
    private static final double RUPTURE_QALY = 10;
    private static final double RETREAT_REMOVES = 0.90;

    private static ValuationCoordinate at(double healthPrice) {
        return ValuationCoordinate.pricingHealthAt(Bounds.point(healthPrice), USD, QALY);
    }

    /**
     * Builds the scan tree. Probabilities are the worked values the topic's callout table derives
     * from the assumptions: a positive scan 21.4%, a true reopening behind it 8.41%, a missed
     * reopening behind a negative scan 0.254%. The complements are residual events.
     */
    private static DecisionTree scanTree(Bounds reopening) {
        double positive = REOPENING * SENSITIVITY + (1 - REOPENING) * (1 - SPECIFICITY);
        double ppv = REOPENING * SENSITIVITY / positive;
        double missed = REOPENING * (1 - SENSITIVITY) / (1 - positive);

        DecisionTree.Builder b = DecisionTree.builder(T);
        DecisionTree.Builder.Handle scanNow = b.decision(1);

        DecisionTree.Builder.Handle scan = scanNow.alternative(2)
                .money(Bounds.point(700), USD).health(Bounds.point(0.002), QALY);
        DecisionTree.Builder.Handle mra = scan.chance(3);
        DecisionTree.Builder.Handle positiveScan = mra.event(4).probability(Bounds.point(positive))
                .money(Bounds.point(5_000), USD).health(Bounds.point(0.01), QALY);
        DecisionTree.Builder.Handle angiogram = positiveScan.chance(5);
        DecisionTree.Builder.Handle confirmed = angiogram.event(6).probability(Bounds.point(ppv));
        DecisionTree.Builder.Handle retreatDecision = confirmed.decision(7);
        retreatDecision.alternative(8)
                .money(Bounds.point(35_000), USD).health(Bounds.point(0.15), QALY)
                .outcome(9)
                .money(Bounds.point((1 - RETREAT_REMOVES) * RUPTURE_RISK_20 * RUPTURE_MONEY), USD)
                .health(Bounds.point((1 - RETREAT_REMOVES) * RUPTURE_RISK_20 * RUPTURE_QALY), QALY);
        untreatedReopening(retreatDecision.alternative(10).outcome(11));
        angiogram.residualEvent(12).outcome(13);
        DecisionTree.Builder.Handle negativeScan = mra.residualEvent(14);
        DecisionTree.Builder.Handle missedChance = negativeScan.chance(15);
        untreatedReopening(missedChance.event(16).probability(Bounds.point(missed)).outcome(17));
        missedChance.residualEvent(18).outcome(19);

        DecisionTree.Builder.Handle noScan = scanNow.alternative(20).money(Bounds.point(0), USD);
        DecisionTree.Builder.Handle present = noScan.chance(21);
        untreatedReopening(present.event(22).probability(reopening).outcome(23));
        present.residualEvent(24).outcome(25);
        return b.tree();
    }

    private static void untreatedReopening(DecisionTree.Builder.Handle leaf) {
        leaf.money(Bounds.point(RUPTURE_RISK_20 * RUPTURE_MONEY), USD)
                .health(Bounds.point(RUPTURE_RISK_20 * RUPTURE_QALY), QALY);
    }

    private static DecisionElement.Alternative alternative(DecisionTree tree, int which) {
        return tree.decision().orElseThrow().alternatives().get(which);
    }

    @Test
    @DisplayName("The scan tree is well formed under the profile")
    void scanTreeIsWellFormed() {
        DecisionTree tree = scanTree(Bounds.point(REOPENING));
        assertEquals(0, tree.check().size(), () -> "findings: " + tree.check());
        assertEquals(0, tree.unprofiled().size(), "every vertex has a kind");
    }

    @Test
    @DisplayName("At $100,000 a QALY, scanning costs $3,880, not scanning $4,188, and the scan saves $308")
    void rollsBackToTheWorkedNumbers() {
        DecisionTree tree = scanTree(Bounds.point(REOPENING));
        Rollback rollback = Rollback.of(tree, at(100_000));

        assertTrue(rollback.determinable(), () -> "findings: " + rollback.findings());
        Bounds scan = rollback.value(alternative(tree, 0).vertexIndex()).orElseThrow();
        Bounds noScan = rollback.value(alternative(tree, 1).vertexIndex()).orElseThrow();
        assertTrue(scan.isPoint() && noScan.isPoint(), "point inputs roll back to point values");
        assertEquals(3_880, scan.lower(), 1.0, "scanning, the topic's $3,880");
        assertEquals(4_188, noScan.lower(), 1.0, "not scanning, the topic's $4,188");
        assertEquals(308, noScan.lower() - scan.lower(), 1.0, "the scan's saving, the topic's $308");
        assertEquals(scan, rollback.rootValue().orElseThrow(), "the decision takes the cheaper course");
        assertEquals(alternative(tree, 0).vertexIndex(), rollback.chosen(tree.rootIndex()).orElseThrow(),
                "scanning is chosen");
    }

    @Test
    @DisplayName("The confirmed reopening is retreated: $70,941 against $209,407 left alone")
    void retreatsAConfirmedReopening() {
        DecisionTree tree = scanTree(Bounds.point(REOPENING));
        Rollback rollback = Rollback.of(tree, at(100_000));
        DecisionElement.Decision retreat = tree.nodesOfType(DecisionElement.Decision.class)
                .detect(decision -> decision.vertexIndex() != tree.rootIndex());

        assertEquals(70_941, rollback.value(retreat.alternatives().get(0).vertexIndex()).orElseThrow().lower(), 1.0);
        assertEquals(209_407, rollback.value(retreat.alternatives().get(1).vertexIndex()).orElseThrow().lower(), 1.0);
        assertEquals(retreat.alternatives().get(0).vertexIndex(), rollback.chosen(retreat.vertexIndex()).orElseThrow());
    }

    @Test
    @DisplayName("Below the break-even price of a healthy year, not scanning is chosen; above it, scanning")
    void breakEvenFollowsTheHealthPrice() {
        DecisionTree tree = scanTree(Bounds.point(REOPENING));
        int scan = alternative(tree, 0).vertexIndex();
        int noScan = alternative(tree, 1).vertexIndex();

        assertEquals(noScan, Rollback.of(tree, at(50_000)).chosen(tree.rootIndex()).orElseThrow(), "at $50,000");
        assertEquals(noScan, Rollback.of(tree, at(80_000)).chosen(tree.rootIndex()).orElseThrow(), "at $80,000");
        assertEquals(scan, Rollback.of(tree, at(90_000)).chosen(tree.rootIndex()).orElseThrow(), "at $90,000");
        assertEquals(scan, Rollback.of(tree, at(150_000)).chosen(tree.rootIndex()).orElseThrow(), "at $150,000");
    }

    @Test
    @DisplayName("A bounded reopening hazard rolls back to bounds, and a choice the bounds cannot settle stays open")
    void boundedInputsRollBackToBounds() {
        DecisionTree tree = scanTree(Bounds.of(0.01, 0.04));
        Rollback rollback = Rollback.of(tree, at(100_000));

        Bounds noScan = rollback.value(alternative(tree, 1).vertexIndex()).orElseThrow();
        assertEquals(0.01 * RUPTURE_RISK_20 * (RUPTURE_MONEY + RUPTURE_QALY * 100_000), noScan.lower(), 1.0);
        assertEquals(0.04 * RUPTURE_RISK_20 * (RUPTURE_MONEY + RUPTURE_QALY * 100_000), noScan.upper(), 1.0);
        assertTrue(rollback.rootValue().isPresent(), "the root is still bounded");
        assertTrue(rollback.chosen(tree.rootIndex()).isEmpty(),
                "the scan's $3,880 lies inside the no-scan bounds, so no alternative is surely no greater");
    }

    @Test
    @DisplayName("A chance whose events do not close is not determinable, and the finding names it")
    void unclosedChanceIsNotDeterminable() {
        DecisionTree.Builder b = DecisionTree.builder(T);
        DecisionTree.Builder.Handle root = b.decision(1);
        DecisionTree.Builder.Handle chance = root.alternative(2).chance(3);
        chance.event(4).probability(Bounds.point(0.3)).outcome(5).money(Bounds.point(100), USD);
        chance.event(6).probability(Bounds.point(0.3)).outcome(7).money(Bounds.point(200), USD);
        DecisionTree tree = b.tree();

        assertEquals(1, tree.check().size(), () -> "findings: " + tree.check());
        assertTrue(tree.check().get(0).message().contains("do not close"));
        Rollback rollback = Rollback.of(tree, at(100_000));
        assertFalse(rollback.determinable());
        Optional<ProfileFinding> why = rollback.findings().detectOptional(f -> f.message().contains("do not close"));
        assertTrue(why.isPresent(), () -> "findings: " + rollback.findings());
    }

    @Test
    @DisplayName("A cost on another money semantic is refused by the coordinate, not silently added")
    void foreignSemanticIsNotDeterminable() {
        DecisionTree.Builder b = DecisionTree.builder(T);
        b.decision(1).alternative(2).outcome(3).money(Bounds.point(100), -9_999);
        Rollback rollback = Rollback.of(b.tree(), at(100_000));

        assertFalse(rollback.determinable());
        assertTrue(rollback.findings().anySatisfy(f -> f.message().contains("money semantic")));
    }
}
