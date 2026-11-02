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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The regulatory scan tree counted out for a thousand patients, and what its choice trades. */
class CohortTradeoffTest {

    static final DecisionTerms T = DecisionTerms.synthetic();
    static BlockIdentities ids;
    static DecisionTree tree;
    static Rollback rollback;

    @BeforeAll
    static void readTheScanTree() throws IOException {
        String body;
        try (InputStream in = CohortTradeoffTest.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            body = text.substring(text.indexOf("----\n") + 5, text.lastIndexOf("----"));
        }
        ids = BlockIdentities.storeFree();
        rollback = BlockSweep.sweep(body, T, ids, 100_000, BlockSweep.DEFAULT_STEPS).orElseThrow().base();
        tree = rollback.tree();
    }

    static int vertexOf(String identifier) {
        long nid = ids.nid(identifier).orElseThrow();
        return tree.nodes().stream().filter(n -> n.concept().orElse(0) == nid).findFirst().orElseThrow().vertexIndex();
    }

    @Test
    @DisplayName("Of 1,000 scanned, 214 test positive, 18 of those have a reopening, and 2 of the 786 negatives are missed")
    void countsFollowTheShares() {
        Cohort cohort = Cohort.of(tree, rollback, 1_000);
        assertEquals(1_000, cohort.count(vertexOf("rda-scan")).orElseThrow(), 1e-9, "every one of the cohort takes each alternative");
        assertEquals(1_000, cohort.count(vertexOf("rda-no-scan")).orElseThrow(), 1e-9);
        assertEquals(214, cohort.count(vertexOf("rda-mra-positive")).orElseThrow(), 1.0);
        assertEquals(786, cohort.count(vertexOf("rda-mra-negative")).orElseThrow(), 1.0, "the residual takes the rest");
        assertEquals(18, cohort.count(vertexOf("rda-reopening-confirmed")).orElseThrow(), 1.0);
        assertEquals(18, cohort.count(vertexOf("rda-retreat-yes")).orElseThrow(), 1.0, "a nested decision passes its count to each alternative");
        assertEquals(2, cohort.count(vertexOf("rda-missed-reopening")).orElseThrow(), 0.5);
        assertEquals(20, cohort.count(vertexOf("rda-reopening-yes")).orElseThrow(), 1e-6, "2% of 1,000 not scanned");
        assertEquals("214 of 1,000", network.ike.komet.claude.decision.render.DecisionFigureText.ofCohort(214.2, 1_000));
        assertEquals("all 1,000", network.ike.komet.claude.decision.render.DecisionFigureText.ofCohort(1_000, 1_000));
        assertEquals("2", network.ike.komet.claude.decision.render.DecisionFigureText.count(2.04));
    }

    @Test
    @DisplayName("The tallies follow the retreat decision's choice: of 1,000 scanned, 18 are retreated, 2 keep a rupture risk, the rest are clear")
    void talliesFollowTheChoice() {
        Cohort cohort = Cohort.of(tree, rollback, 1_000);
        assertEquals(2, cohort.tallies().size(), "one tally per alternative at the root");
        Cohort.Tally scan = cohort.tallies().get(0);
        assertEquals(vertexOf("rda-scan"), scan.alternativeVertex());
        assertEquals(1_000, scan.leaves().stream().mapToDouble(Cohort.Leaf::count).sum(), 1e-6, "every one ends somewhere");
        assertEquals(4, scan.leaves().size(), "two clears with ids of their own, risk cut to a tenth, rupture risk");
        Cohort.Leaf clear = scan.leaves().get(0);
        assertEquals(ids.nid("rda-clear-after-negative").orElseThrow(), clear.concept().orElseThrow(), "the largest first: the clear after a negative");
        assertEquals(784, clear.count(), 1.0);
        assertTrue(scan.leaves().stream().anyMatch(l -> Math.abs(l.count() - 18) < 1.0), "the 18 retreated");
        Cohort.Tally noScan = cohort.tallies().get(1);
        assertEquals(2, noScan.leaves().size());
        assertEquals(20, noScan.leaves().get(1).count(), 1e-6, "the 20 who keep a rupture risk");
    }

    @Test
    @DisplayName("Scanning buys health: $308 priced, it costs more money and keeps more healthy life, a healthy year at the sweep's break-even")
    void scanningBuysHealth() {
        Tradeoff tradeoff = Tradeoff.of(tree, rollback).orElseThrow();
        assertEquals(vertexOf("rda-scan"), tradeoff.chosen());
        assertEquals(vertexOf("rda-no-scan"), tradeoff.nextBest());
        assertEquals(Tradeoff.Kind.BUYS_HEALTH, tradeoff.kind());
        assertTrue(tradeoff.money() > 0, "scanning costs more money per patient: " + tradeoff.money());
        assertTrue(tradeoff.health() > 0, "and keeps more healthy life: " + tradeoff.health());
        assertEquals(86_400, tradeoff.pricePerHealthyYear(), 100.0, "the price at which the alternatives tie, where the tornado's price bar flips");
        assertEquals(1_958, tradeoff.money(), 5.0, "scanning costs $1,958 more per patient along the course chosen: the scan, the angiograms, the retreatments");
        assertEquals(0.0227, tradeoff.health(), 0.0005, "and keeps 0.0227 QALY more per patient");
        assertEquals(308, tradeoff.health() * 100_000 - tradeoff.money(), 1.0, "priced at $100,000 the advantage is the $308 net value");
        assertEquals(100_000, tradeoff.priced(), 1e-9);
        // at half the price not scanning is chosen, and the same trade reads the other way round
        Rollback half = Rollback.of(tree, rollback.coordinate().withHealthPrice(Bounds.point(50_000)));
        Tradeoff atHalf = Tradeoff.of(tree, half).orElseThrow();
        assertEquals(vertexOf("rda-no-scan"), atHalf.chosen());
        assertEquals(Tradeoff.Kind.SAVES_MONEY, atHalf.kind());
        assertTrue(atHalf.pricePerHealthyYear() > 80_000 && atHalf.pricePerHealthyYear() < 100_000,
                "read from the other side the saving per healthy year given up is near the tie, " + atHalf.pricePerHealthyYear()
                + ", though the retreat decision chooses otherwise at half the price");
    }
}
