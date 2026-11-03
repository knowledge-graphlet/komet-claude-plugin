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
import network.ike.komet.claude.block.Identities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static network.ike.komet.claude.decision.ScanTreeRollbackTest.T;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A tree of consequences written once and folded in at the outcomes of another tree, {@code tree=…}. */
class FoldInTest {

    /** What a rupture leads to: a chance at the root, no choice in it. */
    static final String CONSEQUENCES = """
            chance: k:RuptureConsequences[Consequences of a rupture] @id=rda-rupture-consequences
              parameter = [0.35,0.35] k:CaseFatality[Case fatality of a rupture] @id=rda-case-fatality basis=estimated
              event: k:Death[death] @id=rda-death
                p = from k:CaseFatality[] @id=rda-case-fatality
                money = [50000,50000] k:Usd[US dollar] @id=rda-usd
                count = [1,1] k:Deaths[deaths] @id=rda-deaths
                outcome: k:Dead[death] @id=rda-dead
              residual: k:Survival[survival] @id=rda-survival
                money = [200000,200000] k:Usd[US dollar] @id=rda-usd
                outcome: k:Survived[survival] @id=rda-survived
            """;

    /** A choice whose untreated course ends in a rupture now and then. */
    static final String CHOICE = """
            decision: k:Treat[Treat?] @id=rda-treat
              alternative: k:DoTreat[treat] @id=rda-do-treat
                money = [10000,10000] k:Usd[US dollar] @id=rda-usd
                outcome: k:Safe[safe] @id=rda-safe
              alternative: k:Wait[wait] @id=rda-wait
                chance: k:Ruptures[Rupture?] @id=rda-ruptures
                  event: k:Rupture[rupture] @id=rda-rupture
                    p = [0.1,0.1]
                    outcome: k:Ruptured[rupture] @id=rda-ruptured tree=rda-rupture-consequences
                  residual: k:Intact[intact] @id=rda-intact
                    outcome: k:Fine[fine] @id=rda-fine
            """;

    private static Rollback rollBack(Identities ids, Map<String, String> bodies) {
        DecisionTree tree = DecisionTreeText.parse(CHOICE, T, ids).tree().orElseThrow();
        return Rollback.of(tree, BlockSweep.coordinate(tree, 100_000), LinkedTrees.of(bodies, T, ids, ExternalQuantities.none()));
    }

    @Test
    @DisplayName("A tree may have a chance at its root, and rolls back to the expected cost of everyone who enters it")
    void chanceAtTheRoot() {
        Identities ids = BlockIdentities.storeFree();
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(CONSEQUENCES, T, ids);
        DecisionTree tree = parsed.tree().orElseThrow();
        assertTrue(tree.check().isEmpty(), () -> "" + tree.check());
        Rollback rollback = Rollback.of(tree, BlockSweep.coordinate(tree, 100_000));
        assertEquals(0.35 * 50_000 + 0.65 * 200_000, rollback.rootValue().orElseThrow().midpoint(), 1e-6);
        assertEquals(Optional.of("rda-rupture-consequences"), LinkedTrees.rootIdentifier(tree, ids));
        Ledger ledger = Ledger.of(tree, rollback).orElseThrow();
        assertEquals(1, ledger.entries().size(), "one row: what everyone who reaches it can expect");
    }

    @Test
    @DisplayName("An outcome that folds in a tree costs what that tree costs from its root, and its counts and leaves come along")
    void foldsIn() {
        Identities ids = BlockIdentities.storeFree();
        Rollback rollback = rollBack(ids, Map.of("rda-rupture-consequences", CONSEQUENCES));
        DecisionTree tree = rollback.tree();
        assertTrue(rollback.findings().isEmpty(), () -> "" + rollback.findings());
        double consequences = 0.35 * 50_000 + 0.65 * 200_000;
        int wait = tree.decision().orElseThrow().alternatives().get(1).vertexIndex();
        assertEquals(0.1 * consequences, rollback.value(wait).orElseThrow().midpoint(), 1e-6);
        assertEquals(1, rollback.foldedAll().size());

        Ledger ledger = Ledger.of(tree, rollback).orElseThrow();
        long deaths = ids.nid("rda-deaths").orElseThrow();
        assertTrue(ledger.units().contains(deaths), "the folded tree's counted unit is a column");
        assertEquals(0.1 * 0.35, ledger.entry(wait).orElseThrow().count(deaths), 1e-9);

        Cohort cohort = Cohort.of(tree, rollback, 1000);
        Cohort.Tally waiting = cohort.tallies().get(1);
        long dead = ids.nid("rda-dead").orElseThrow();
        double reachDeath = waiting.leaves().stream().filter(l -> l.concept().isPresent() && l.concept().getAsLong() == dead)
                .mapToDouble(Cohort.Leaf::count).sum();
        assertEquals(35, reachDeath, 1e-9, "of 1,000 who wait, 100 rupture and 35 die");
    }

    @Test
    @DisplayName("An outcome that folds in a tree the document lacks, or that folds itself in, is not determinable")
    void missingAndSelf() {
        Identities ids = BlockIdentities.storeFree();
        Rollback missing = rollBack(ids, Map.of());
        assertTrue(missing.findings().anySatisfy(f -> f.message().equals(Rollback.FOLDS_IN_NOTHING)), () -> "" + missing.findings());

        String self = CHOICE.replace("tree=rda-rupture-consequences", "tree=rda-treat");
        DecisionTree tree = DecisionTreeText.parse(self, T, ids).tree().orElseThrow();
        Rollback cycle = Rollback.of(tree, BlockSweep.coordinate(tree, 100_000),
                LinkedTrees.of(Map.of("rda-treat", self), T, ids, ExternalQuantities.none()));
        assertTrue(cycle.findings().anySatisfy(f -> f.message().equals("folds in a tree that folds this one in")), () -> "" + cycle.findings());
    }

    @Test
    @DisplayName("A tree folded in before its block is read takes the name its root line writes")
    void foldedTreeIsNamedByItsRootLine() {
        Identities ids = BlockIdentities.storeFree();
        // a reply writes the folding tree first, so tree=rda-rupture-consequences is read before the tree's root line
        DecisionTreeText.parse(CHOICE, T, ids);
        long consequences = ids.nid("rda-rupture-consequences").orElseThrow();
        assertEquals("rda-rupture-consequences", ids.label(consequences));
        DecisionTreeText.parse(CONSEQUENCES, T, ids);
        assertEquals("Consequences of a rupture", ids.label(consequences));
        assertEquals("Consequences of a rupture", DecisionTreeText.parse(CHOICE, T, ids).labelled(ids).label(consequences),
                "the folding tree, read again, names the tree it folds in");
    }

    @Test
    @DisplayName("The assistant's two trees in one reply fold together; a tree from an earlier reply is passed over")
    void feedbackFoldsTheReplysTrees() {
        String both = "```decision-tree\n" + CONSEQUENCES + "```\n\n```decision-tree\n" + CHOICE + "```\n";
        assertEquals(Optional.empty(), DecisionTreeFeedback.message(both, T, BlockIdentities.storeFree()));
        String alone = "```decision-tree\n" + CHOICE + "```\n";
        assertEquals(Optional.empty(), DecisionTreeFeedback.message(alone, T, BlockIdentities.storeFree()),
                "the consequences may be in an earlier reply");
    }

    @Test
    @DisplayName("The sweep re-reads the folded tree under its own identities, and swings the folded tree's values too")
    void sweepFoldsIn() {
        Identities ids = BlockIdentities.storeFree();
        Optional<DecisionTreeSensitivity> swept = BlockSweep.sweep(CHOICE, T, ids, 100_000, 4,
                ExternalQuantities.none(), Map.of("rda-rupture-consequences", CONSEQUENCES));
        assertTrue(swept.isPresent());
        DecisionTreeSensitivity sweep = swept.get();
        assertTrue(sweep.base().determinable());
        java.util.List<DecisionTreeSensitivity.Swing> folded = sweep.swings()
                .select(s -> sweep.folded(s.measureVertex()).isPresent()).castToList();
        assertEquals(3, folded.size(), "the case fatality and the two money costs of the folded tree");
        DecisionTreeSensitivity.Swing fatality = folded.stream()
                .filter(s -> network.ike.komet.claude.decision.render.SweptQuantity.name(sweep, ids, s.measureVertex())
                        .equals("Case fatality of a rupture in \u201CConsequences of a rupture\u201D"))
                .findFirst().orElseThrow();
        // more deaths, which cost less than survival here, make waiting cheaper: the root's cost falls as fatality rises
        assertTrue(fatality.rootAtHigh() < fatality.rootAtLow(), () -> fatality.rootAtLow() + " to " + fatality.rootAtHigh());
        assertTrue(fatality.measureVertex() <= DecisionTreeSensitivity.FOLDED);
    }
}
