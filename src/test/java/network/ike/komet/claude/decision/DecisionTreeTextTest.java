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
import network.ike.komet.claude.block.Identities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static network.ike.komet.claude.decision.ScanTreeRollbackTest.QALY;
import static network.ike.komet.claude.decision.ScanTreeRollbackTest.REOPENING;
import static network.ike.komet.claude.decision.ScanTreeRollbackTest.T;
import static network.ike.komet.claude.decision.ScanTreeRollbackTest.USD;
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
            add("usd-2026", USD);
            add("qaly", QALY);
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
            return nid == USD ? "US dollar at 2026 prices" : nid == QALY ? "Quality-adjusted life year"
                    : "Concept " + nid;
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

    private static void assertFinding(DecisionTreeText.Parsed parsed, String line, String text) {
        assertTrue(parsed.findings().anySatisfy((ProfileFinding f) ->
                        f.message().startsWith(line + ":") && f.message().contains(text)),
                () -> "no finding at " + line + " saying \"" + text + "\" among " + parsed.findings());
    }
}
