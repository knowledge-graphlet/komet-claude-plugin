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
import network.ike.komet.claude.decision.DecisionElement.Chance;
import network.ike.komet.claude.decision.DecisionTreeLayout.Kind;
import network.ike.komet.claude.decision.DecisionTreeLayout.Placed;
import network.ike.komet.claude.decision.DecisionTreeLayout.Run;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The left-to-right drawing of the scan tree: a row per terminal, a column per depth of nodes,
 * every node at the midpoint of what it reaches, every branch an elbow that never runs backwards,
 * and a folded node drawn as a terminal with nothing beneath it.
 */
class DecisionTreeLayoutTest {

    static final DecisionTerms T = DecisionTerms.synthetic();
    static DecisionTree tree;
    static BlockIdentities ids;

    @BeforeAll
    static void readTheScanTree() throws IOException {
        try (InputStream in = DecisionTreeLayoutTest.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ids = BlockIdentities.storeFree();
            tree = DecisionTreeText.parse(text, T, ids).tree().orElseThrow();
        }
    }

    static DecisionTreeLayout.Sizing sizing() {
        return new DecisionTreeLayout.Sizing() {
            public double rowHeight() { return 40; }
            public double glyph() { return 14; }
            public double gap() { return 10; }
            public double margin() { return 6; }
            public double branchLabelWidth(int branchVertex) { return 100; }
            public double nodeLabelWidth(int nodeVertex) { return 50; }
        };
    }

    @Test
    @DisplayName("Seven outcomes take seven rows across five columns, the root drawn first")
    void rowsAndColumns() {
        DecisionTreeLayout layout = DecisionTreeLayout.of(tree, sizing(), v -> false);
        assertEquals(7, layout.rows());
        assertEquals(5, layout.columns());
        assertEquals(tree.rootIndex(), layout.placed().get(0).vertexIndex());
        assertEquals(Kind.DECISION, layout.placed().get(0).kind());
        assertEquals(7, layout.placed().stream().filter(p -> p.kind() == Kind.OUTCOME).count());
        assertEquals(6 + 6 + 7 * 40, layout.height(), 1e-9);
        assertEquals(12, layout.runs().size(), "two alternatives, two retreat alternatives, and four chances of two events");
    }

    @Test
    @DisplayName("A node sits at the midpoint of the rows its branches reach")
    void nodesAtTheMidpoint() {
        DecisionTreeLayout layout = DecisionTreeLayout.of(tree, sizing(), v -> false);
        Placed root = layout.placed(tree.rootIndex()).orElseThrow();
        Alternative scan = tree.nodesOfType(Alternative.class).get(0);
        Alternative noScan = tree.nodesOfType(Alternative.class).detect(a -> "no scan".equals(ids.label(a.concept().getAsLong())));
        Run scanRun = layout.run(scan.vertexIndex()).orElseThrow();
        Run noScanRun = layout.run(noScan.vertexIndex()).orElseThrow();
        assertEquals((scanRun.y() + noScanRun.y()) / 2, root.y(), 1e-9);
        assertEquals(root.y(), scanRun.y0(), 1e-9);
        assertTrue(scanRun.y() < noScanRun.y(), "the first alternative is drawn above the second");
    }

    @Test
    @DisplayName("Every branch runs rightwards: stub, bend, run, to the next glyph's left edge")
    void runsNeverGoBack() {
        DecisionTreeLayout layout = DecisionTreeLayout.of(tree, sizing(), v -> false);
        for (Run run : layout.runs()) {
            assertTrue(run.x0() < run.xBend(), "stub " + run);
            assertTrue(run.xBend() < run.x1(), "run " + run);
            Placed to = layout.placed(run.toVertex()).orElseThrow();
            assertEquals(to.x() - 7, run.x1(), 1e-9, "the run ends at the glyph " + run);
            assertEquals(to.y(), run.y(), 1e-9);
            Placed from = layout.placed(run.fromVertex()).orElseThrow();
            assertEquals(from.column() + 1, to.column());
            assertTrue(run.runLength() >= 100 + 6, "room for the label " + run);
        }
    }

    @Test
    @DisplayName("Folding the MRA result chance leaves three rows and three columns")
    void foldingHidesWhatIsBeneath() {
        Chance mra = tree.nodesOfType(Chance.class).get(0);
        DecisionTreeLayout layout = DecisionTreeLayout.of(tree, sizing(), Set.of(mra.vertexIndex())::contains);
        assertEquals(3, layout.rows());
        assertEquals(3, layout.columns());
        Placed folded = layout.placed(mra.vertexIndex()).orElseThrow();
        assertTrue(folded.collapsed());
        assertEquals(Kind.CHANCE, folded.kind());
        assertEquals(4, layout.runs().size(), "the root's two alternatives and the unscanned chance's two events");
    }
}
