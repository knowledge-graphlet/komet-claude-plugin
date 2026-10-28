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

import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.block.Identities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static network.ike.komet.claude.decision.ScanTreeRollbackTest.QALY;
import static network.ike.komet.claude.decision.ScanTreeRollbackTest.REOPENING;
import static network.ike.komet.claude.decision.ScanTreeRollbackTest.T;
import static network.ike.komet.claude.decision.ScanTreeRollbackTest.USD;
import static network.ike.komet.claude.decision.ScanTreeRollbackTest.assumedScanTree;
import static network.ike.komet.claude.decision.ScanTreeRollbackTest.at;
import static network.ike.komet.claude.decision.ScanTreeRollbackTest.scanTree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scan tree through text and back: the block the emitter writes parses to the same tree,
 * vertex identities included, and writes again byte for byte; a block written by hand in the
 * topic's style parses and rolls back to the worked numbers; and every way a block can be
 * wrong is a finding with its line, never a refusal.
 */
class DecisionTreeTextTest {

    /** Identities backed by a map: the 25 scan-tree concepts and the two cost semantics. */
    private static final Identities IDS = new Identities() {
        private final Map<String, Long> nids = new HashMap<>();
        private final Map<Long, String> identifiers = new HashMap<>();

        {
            for (long nid = 1; nid <= 25; nid++) {
                add("c" + nid, nid);
            }
            for (long nid = 101; nid <= 112; nid++) {
                add("q" + nid, nid);
            }
            add("usd-2026", USD);
            add("qaly", QALY);
            add("kind-complement", T.complement());
            add("kind-product", T.product());
            add("kind-cumulative-hazard", T.cumulativeHazard());
            add("kind-bayes-posterior", T.bayesPosterior());
            add("kind-bayes-marginal", T.bayesMarginal());
        }

        private void add(String identifier, long nid) {
            nids.put(identifier, nid);
            identifiers.put(nid, identifier);
        }

        @Override
        public OptionalLong nid(String identifier) {
            Long nid = nids.get(identifier);
            return nid == null ? OptionalLong.empty() : OptionalLong.of(nid);
        }

        @Override
        public Optional<String> identifier(long nid) {
            return Optional.ofNullable(identifiers.get(nid));
        }

        @Override
        public String label(long nid) {
            if (nid == USD) {
                return "US dollar at 2026 prices";
            }
            if (nid == QALY) {
                return "Quality-adjusted life year";
            }
            if (nid == T.complement()) {
                return "Complement";
            }
            if (nid == T.product()) {
                return "Product";
            }
            if (nid == T.cumulativeHazard()) {
                return "Cumulative hazard";
            }
            if (nid == T.bayesPosterior()) {
                return "Bayes posterior";
            }
            if (nid == T.bayesMarginal()) {
                return "Bayes marginal";
            }
            return (nid > 100 ? "Quantity " : "Concept ") + nid;
        }
    };

    private static UUID vertexId(DecisionTree tree, int index) {
        EntityVertex vertex = tree.vertex(index);
        return new UUID(vertex.mostSignificantBits(), vertex.leastSignificantBits());
    }

    @Test
    @DisplayName("The scan tree writes, reads back without findings, and writes again byte for byte")
    void roundTripIsByteStable() {
        DecisionTree original = scanTree(Bounds.point(REOPENING));
        String first = DecisionTreeText.toAdoc(original, IDS);

        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(first, T, IDS);

        assertTrue(parsed.findings().isEmpty(), () -> "findings: " + parsed.findings());
        DecisionTree read = parsed.tree().orElseThrow();
        assertTrue(read.check().isEmpty(), () -> "check: " + read.check());
        assertEquals(first, DecisionTreeText.toAdoc(read, IDS), "the second emission is the first");
        assertTrue(first.startsWith("[decision-tree]\n----\ndecision: k:Concept1[Concept 1] @id=c1 @vertex="),
                () -> first.lines().findFirst().orElse("") + " / " + first.lines().skip(2).findFirst().orElse(""));
    }

    @Test
    @DisplayName("Vertex identities survive the round trip, so a revised number is a new version of one vertex")
    void vertexIdentitiesPersist() {
        DecisionTree original = scanTree(Bounds.point(REOPENING));
        DecisionTree read = DecisionTreeText.parse(DecisionTreeText.toAdoc(original, IDS), T, IDS).tree().orElseThrow();

        assertEquals(original.source().vertexCount(), read.source().vertexCount(), "every vertex is read back");
        assertEquals(vertexId(original, original.rootIndex()), vertexId(read, read.rootIndex()), "the root keeps its identity");
        for (DecisionElement.Probability probability : original.nodesOfType(DecisionElement.Probability.class)) {
            UUID id = vertexId(original, probability.vertexIndex());
            assertTrue(read.nodesOfType(DecisionElement.Probability.class)
                            .anySatisfy(p -> vertexId(read, p.vertexIndex()).equals(id)),
                    "each probability keeps its identity");
        }
    }

    @Test
    @DisplayName("The tree read back rolls back to the worked numbers")
    void readTreeRollsBack() {
        DecisionTree read = DecisionTreeText.parse(DecisionTreeText.toAdoc(scanTree(Bounds.point(REOPENING)), IDS), T, IDS)
                .tree().orElseThrow();
        Rollback rollback = Rollback.of(read, at(100_000));
        DecisionElement.Decision root = read.decision().orElseThrow();

        assertEquals(3_880, rollback.value(root.alternatives().get(0).vertexIndex()).orElseThrow().lower(), 1.0);
        assertEquals(4_188, rollback.value(root.alternatives().get(1).vertexIndex()).orElseThrow().lower(), 1.0);
    }

    @Test
    @DisplayName("A block written by hand in the topic's style parses, with no vertex identities and the p alias")
    void handWrittenBlockParses() {
        String block = """
                [decision-tree]
                ----
                decision: k:ScanNow[Scan now?] @id=c1
                    alternative: k:Scan[Scan] @id=c2
                        money = [700,700] k:Usd[US dollar] @id=usd-2026 basis=estimated
                        health = [0.002,0.002] k:Qaly[QALY] @id=qaly
                        chance: k:Reopening[Reopening present?] @id=c3
                            event: k:Present[Reopening present] @id=c4
                                p = [0.02,0.02]
                                outcome: k:Rupture[Rupture risk] @id=c5
                                    money = [27314,27314] k:Usd[US dollar] @id=usd-2026 basis=cited source=c6
                            residual: k:Absent[No reopening] @id=c7
                                outcome: k:Clear[Clear] @id=c8
                    alternative: k:NoScan[No scan] @id=c9
                        outcome: k:Nothing[Nothing done] @id=c10
                            money = [0,0] k:Usd[US dollar] @id=usd-2026
                ----
                """;

        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(block, T, IDS);

        assertTrue(parsed.findings().isEmpty(), () -> "findings: " + parsed.findings());
        DecisionTree tree = parsed.tree().orElseThrow();
        assertTrue(tree.check().isEmpty(), () -> "check: " + tree.check());
        Rollback rollback = Rollback.of(tree, at(100_000));
        DecisionElement.Decision root = tree.decision().orElseThrow();
        assertEquals(700 + 200 + 0.02 * 27_314, rollback.value(root.alternatives().get(0).vertexIndex()).orElseThrow().lower(), 0.01);
        assertEquals(0, rollback.value(root.alternatives().get(1).vertexIndex()).orElseThrow().lower(), 0.01);
        DecisionElement.MoneyCost cited = tree.nodesOfType(DecisionElement.MoneyCost.class)
                .detect(cost -> cost.measure().basisNid().orElse(0) == T.citedBasis());
        assertTrue(tree.nodesOfType(DecisionElement.MoneyCost.class)
                .anySatisfy(cost -> cost.measure().basisNid().orElse(0) == T.estimatedBasis()), "the estimated basis is read");
        assertEquals(6, tree.conceptProperty(cited.vertexIndex(), T.basisSource()).orElseThrow(), "the source is read");
        String written = DecisionTreeText.toAdoc(tree, IDS);
        assertTrue(written.contains("basis=cited source=c6 @vertex="), () -> written);
        assertEquals(written, DecisionTreeText.toAdoc(DecisionTreeText.parse(written, T, IDS).tree().orElseThrow(), IDS),
                "once written with identities, the block is stable");
    }

    @Test
    @DisplayName("Each way a block can be wrong is a finding naming its line, and the tree is still built")
    void problemsAreFindingsNotRefusals() {
        String block = """
                decision: k:ScanNow[Scan now?] @id=c1
                  alternative: k:Scan[Scan] @id=nowhere
                    money = [700,700]
                    health = [1,1] k:Qaly[QALY] @id=qaly basis=guessed
                    weight = [1,1]
                    chance: k:Reopening[Reopening present?] @id=c3
                      residual: k:Absent[No reopening] @id=c7
                        outcome: @vertex=not-a-uuid
                  limb: k:Scan[Scan] @id=c2
                """;

        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(block, T, IDS);

        assertTrue(parsed.tree().isPresent(), "the tree is built around the gaps");
        assertFinding(parsed, "line 2", "does not resolve: nowhere");
        assertFinding(parsed, "line 3", "names its measure semantic");
        assertFinding(parsed, "line 4", "estimated, cited or derived");
        assertFinding(parsed, "line 5", "unknown value of a vertex: weight");
        assertFinding(parsed, "line 8", "an outcome names no concept");
        assertFinding(parsed, "line 8", "not a vertex identity");
        assertFinding(parsed, "line 9", "unknown kind of vertex under decision: limb");
        assertFalse(parsed.tree().orElseThrow().check().isEmpty(), "the gaps show in the profile's checks too");
    }

    @Test
    @DisplayName("A tree stated from its assumptions writes its parameters and derivations and reads back byte for byte")
    void derivedTreeRoundTrips() {
        DecisionTree original = assumedScanTree(Bounds.point(REOPENING));
        String first = DecisionTreeText.toAdoc(original, IDS);

        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(first, T, IDS);

        assertTrue(parsed.findings().isEmpty(), () -> "findings: " + parsed.findings() + "\n" + first);
        DecisionTree read = parsed.tree().orElseThrow();
        assertTrue(read.check().isEmpty(), () -> "check: " + read.check());
        assertEquals(first, DecisionTreeText.toAdoc(read, IDS), "the second emission is the first");
        assertTrue(first.contains("parameter = k:Quantity108[Quantity 108] @id=q108 from k:CumulativeHazard[Cumulative hazard] @id=kind-cumulative-hazard of k:Quantity106[Quantity 106] @id=q106 k:Quantity107[Quantity 107] @id=q107 @vertex="),
                () -> first);
        assertTrue(first.contains("money = from k:Product[Product] @id=kind-product of k:Quantity108[Quantity 108] @id=q108 k:Quantity109[Quantity 109] @id=q109 in k:USDollarAt2026Prices[US dollar at 2026 prices] @id=usd-2026 @vertex="),
                () -> first);
        Rollback rollback = Rollback.of(read, at(100_000));
        assertEquals(3_880, rollback.value(read.decision().orElseThrow().alternatives().get(0).vertexIndex()).orElseThrow().lower(), 1.0);
    }

    @Test
    @DisplayName("A hand-written block states its assumptions as parameters and derives from them")
    void handWrittenDerivations() {
        String block = """
                decision: k:Repair[Repair now?] @id=c1
                  parameter = [0.01,0.01] k:Hazard[Yearly failure hazard] @id=q106 basis=estimated
                  parameter = [20,20] k:Years[Years at risk] @id=q107
                  parameter = k:Risk[Twenty-year failure risk] @id=q108 from k:CumulativeHazard[] @id=kind-cumulative-hazard of k:Hazard[] @id=q106 k:Years[] @id=q107
                  parameter = [150000,150000] k:FailureCost[Cost of a failure] @id=q109 in k:Usd[US dollar] @id=usd-2026
                  alternative: k:Wait[wait] @id=c2
                    outcome: k:Exposed[exposed] @id=c3
                      money = from k:Product[] @id=kind-product of k:Risk[] @id=q108 k:FailureCost[] @id=q109 in k:Usd[US dollar] @id=usd-2026
                  alternative: k:Fix[repair] @id=c4
                    money = [20000,20000] k:Usd[US dollar] @id=usd-2026
                    outcome: k:Safe[safe] @id=c5
                """;

        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(block, T, IDS);

        assertTrue(parsed.findings().isEmpty(), () -> "findings: " + parsed.findings());
        DecisionTree tree = parsed.tree().orElseThrow();
        assertTrue(tree.check().isEmpty(), () -> "check: " + tree.check());
        Rollback rollback = Rollback.of(tree, at(100_000));
        DecisionElement.Decision root = tree.decision().orElseThrow();
        assertEquals((1 - Math.pow(0.99, 20)) * 150_000, rollback.value(root.alternatives().get(0).vertexIndex()).orElseThrow().lower(), 0.01);
        assertEquals(20_000, rollback.value(root.alternatives().get(1).vertexIndex()).orElseThrow().lower(), 0.01);
        assertEquals(root.alternatives().get(1).vertexIndex(), rollback.chosen(root.vertexIndex()).orElseThrow(), "repair is cheaper");
    }

    @Test
    @DisplayName("The regulatory topic's block, written for the documentation build with no store, reads live and rolls back to its numbers")
    void readsTheTopicsBlockWithLocalIdentities() throws Exception {
        String block;
        try (InputStream in = DecisionTreeTextTest.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            block = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        BlockIdentities ids = BlockIdentities.storeFree();

        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(block, T, ids);

        DecisionTree tree = parsed.tree().orElseThrow();
        assertFalse(parsed.findings().anySatisfy((ProfileFinding f) -> f.message().contains("does not resolve")),
                () -> "a symbol the store lacks is local, not unresolved: " + parsed.findings());
        assertFalse(parsed.findings().anySatisfy((ProfileFinding f) -> f.message().contains("carries no @id")),
                () -> "a derivation kind is known by its name: " + parsed.findings());
        assertTrue(parsed.findings().anySatisfy((ProfileFinding f) -> f.message().contains("local to the block")
                        && f.message().contains("rda-scan-now")),
                () -> "the local symbols are reported once, together: " + parsed.findings());
        assertTrue(tree.check().isEmpty(), () -> "check: " + tree.check());
        assertEquals("Scan now?", ids.label(tree.decision().orElseThrow().concept().orElseThrow()));

        long usd = tree.nodesOfType(DecisionElement.MoneyCost.class).get(0).measure().semanticNid();
        long qaly = tree.nodesOfType(DecisionElement.HealthCost.class).get(0).measure().semanticNid();
        assertTrue(ids.isLocal(usd) && ids.isLocal(qaly), "the cost semantics are the block's own");
        Rollback rollback = Rollback.of(tree, ValuationCoordinate.pricingHealthAt(Bounds.point(100_000), usd, qaly));
        DecisionElement.Decision root = tree.decision().orElseThrow();
        assertEquals(3_880, rollback.value(root.alternatives().get(0).vertexIndex()).orElseThrow().lower(), 1.0);
        assertEquals(4_188, rollback.value(root.alternatives().get(1).vertexIndex()).orElseThrow().lower(), 1.0);
        assertEquals(root.alternatives().get(0).vertexIndex(), rollback.chosen(root.vertexIndex()).orElseThrow(), "scan is chosen");

        String written = DecisionTreeText.toAdoc(tree, ids);
        assertTrue(written.contains("k:ScanNow[Scan now?] @id=rda-scan-now"), () -> "the symbols write back: " + written);
    }

    private static void assertFinding(DecisionTreeText.Parsed parsed, String line, String text) {
        assertTrue(parsed.findings().anySatisfy((ProfileFinding f) ->
                        f.message().startsWith(line + ":") && f.message().contains(text)),
                () -> "no finding at " + line + " saying \"" + text + "\" among " + parsed.findings());
    }
}
