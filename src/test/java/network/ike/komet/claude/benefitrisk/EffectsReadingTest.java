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
package network.ike.komet.claude.benefitrisk;

import network.ike.komet.claude.benefitrisk.EffectsReading.Row;
import network.ike.komet.claude.benefitrisk.EffectsReading.Verdict;
import network.ike.komet.document.DecisionFigure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The worked example of ike-lab-documents topic {@code dev-benefit-risk-blocks}: its effects table
 * is the golden, computed there by a separate script. All the data are invented.
 */
class EffectsReadingTest {

    private static final String TREE = """
            goal: k:Choice[Choice of polymer] @id=br-goal
              gate: k:Geno[Genotoxicity battery result] @id=br-geno pass=negative
              domain: k:Fav[Favorable effects] @id=br-fav polarity=favorable
                criterion: k:Strength[Tensile strength] @id=br-strength better=higher
                criterion: k:Capsule[Fibrous capsule thickness] @id=br-capsule better=lower
              domain: k:Unfav[Unfavorable effects] @id=br-unfav polarity=unfavorable
                criterion: k:Viability[Cell viability] @id=br-viability better=higher
                criterion: k:Hemolysis[Hemolysis] @id=br-hemolysis better=lower
                criterion: k:Inflam[Sites with inflammation] @id=br-inflam better=lower
                criterion: k:Systemic[Chronic systemic toxicity score] @id=br-systemic better=lower
            """;

    private static final String TABLE = """
            alternative: k:PolymerN[Polymer N] @id=n
            alternative: k:PolymerE[Polymer E] @id=e
            row: k:Geno[] @id=br-geno
              arm: n result=negative source=study-3
              arm: e result=negative source=study-3
            row: k:Strength[] @id=br-strength
              arm: n mean=86.0 sd=6.0 n=12 source=study-7
              arm: e mean=78.0 sd=7.0 n=12 source=study-7
            row: k:Capsule[] @id=br-capsule
              arm: n mean=120 sd=40 n=10 source=study-9
              arm: e mean=180 sd=55 n=10 source=study-9
            row: k:Viability[] @id=br-viability
              arm: n mean=84.0 sd=5.0 n=6 source=study-2
              arm: e mean=92.0 sd=4.0 n=6 source=study-2
            row: k:Hemolysis[] @id=br-hemolysis
              arm: n mean=2.8 sd=0.6 n=8 source=study-4
              arm: e mean=1.9 sd=0.5 n=8 source=study-4
            row: k:Inflam[] @id=br-inflam
              arm: n count=3 of=24 source=study-14
              arm: e count=8 of=24 source=study-14
            row: k:Systemic[] @id=br-systemic
              arm: n low=0.2 likely=0.6 high=2.0 source=panel-1
              arm: e mean=0.4 sd=0.3 n=10 source=study-11
            """;

    private static EffectsReading reading() {
        return EffectsReading.of(ValueTree.parse(TREE), EffectsTable.parse(TABLE)).orElseThrow();
    }

    private static Row row(String id) {
        return reading().rows().stream().filter(r -> r.leaf().part().id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void effectsMatchTheWorkedExample() {
        assertEquals("+1.18 (+0.32, +2.05)", row("br-strength").effect().orElseThrow().words());
        assertEquals("+1.19 (+0.24, +2.15)", row("br-capsule").effect().orElseThrow().words());
        assertEquals("−1.63 (−2.94, −0.32)", row("br-viability").effect().orElseThrow().words());
        assertEquals("−1.54 (−2.66, −0.42)", row("br-hemolysis").effect().orElseThrow().words());
        assertEquals("+0.69 (−0.12, +1.51)", row("br-inflam").effect().orElseThrow().words());
    }

    @Test
    void whichAlternativeIsFavoredIsComputed() {
        assertEquals(Verdict.FIRST, row("br-capsule").verdict());
        assertEquals(Verdict.SECOND, row("br-hemolysis").verdict());
        assertEquals(Verdict.NOT_SEPARATED, row("br-inflam").verdict());
        assertEquals(Verdict.NOT_DETERMINABLE, row("br-systemic").verdict());
        assertEquals(Verdict.GATE_PASSED, row("br-geno").verdict());
        assertEquals("2 favor Polymer N, 2 favor Polymer E, 1 not separated, 1 with nothing to compute from.", reading().summary());
        assertEquals(List.of(), reading().findings());
    }

    @Test
    void aLeafWithNoRowStillAppears() {
        String tree = TREE + "    criterion: k:Fatigue[Fatigue life] @id=br-fatigue better=higher\n";
        EffectsReading read = EffectsReading.of(ValueTree.parse(tree), EffectsTable.parse(TABLE)).orElseThrow();
        assertTrue(read.rows().stream().anyMatch(r -> r.leaf().part().id().equals("br-fatigue") && r.verdict() == Verdict.NOT_DETERMINABLE));
        assertTrue(read.findings().contains("No evidence for Fatigue life."));
    }

    @Test
    void aRowWithNoLeafAndAnUnsourcedValueAreFindings() {
        String table = TABLE + "row: k:Stray[] @id=br-stray\n  arm: n mean=1 sd=1 n=4\n";
        List<String> findings = EffectsReading.of(ValueTree.parse(TREE), EffectsTable.parse(table)).orElseThrow().findings();
        assertTrue(findings.contains("The row br-stray has no leaf in the value tree."));
    }

    @Test
    void theForestDrawsEveryEffectAndNoGate() {
        DecisionFigure figure = ForestFigure.build(reading(), 640, 10.5);
        assertEquals(4, figure.marks().stream().filter(m -> m.role().equals("forest-point")).count());
        assertEquals(1, figure.marks().stream().filter(m -> m.role().equals("forest-point-open")).count());
        assertEquals(1, figure.marks().stream().filter(m -> m.role().equals("forest-none")).count());
        assertEquals(6, figure.marks().stream().filter(m -> m.role().equals("forest-name")
                && !((DecisionFigure.Text) m).text().isBlank()).count());
    }

    @Test
    void theValueTreeDrawsAsATreeOnThePage() {
        ValueTree.Part goal = ValueTree.parse(TREE).goal().orElseThrow();
        DecisionFigure figure = ValueTreeFigure.build(goal, 460, 8.5);
        assertEquals(10, figure.marks().stream().filter(m -> m.role().equals("value-tree-part")).count());
        // one line to each part beneath its parent, never a row of a table
        assertEquals(9, figure.marks().stream().filter(m -> m.role().equals("value-tree-edge")).count());
        assertEquals(7, figure.marks().stream().filter(m -> m.role().equals("value-tree-reading")).count());
        double goalLeft = figure.marks().stream().filter(DecisionFigure.Rect.class::isInstance).map(DecisionFigure.Rect.class::cast)
                .mapToDouble(DecisionFigure.Rect::x).min().orElseThrow();
        double furthest = figure.marks().stream().filter(DecisionFigure.Rect.class::isInstance).map(DecisionFigure.Rect.class::cast)
                .mapToDouble(r -> r.x() + r.w()).max().orElseThrow();
        assertEquals(0, goalLeft, 1e-9);
        assertEquals(460, furthest, 1e-6);
        assertTrue(figure.marks().stream().filter(DecisionFigure.Rect.class::isInstance).map(DecisionFigure.Rect.class::cast)
                .allMatch(r -> r.y() >= 0 && r.y() + r.h() <= figure.height()));
    }

    @Test
    void aTallParentDoesNotReachIntoTheBoxBeforeIt() {
        ValueTree.Part goal = ValueTree.parse("""
                goal: k:G[A goal] @id=g
                  gate: k:Gate[A gate] @id=gate pass=intact
                  domain: k:D[A domain whose long name wraps across several lines of its narrow column in the figure] @id=d
                    criterion: k:C[A criterion] @id=c better=higher
                """).goal().orElseThrow();
        List<DecisionFigure.Rect> boxes = ValueTreeFigure.build(goal, 460, 8.5).marks().stream()
                .filter(DecisionFigure.Rect.class::isInstance).map(DecisionFigure.Rect.class::cast).toList();
        for (DecisionFigure.Rect a : boxes) {
            for (DecisionFigure.Rect b : boxes) {
                boolean sameColumn = a != b && Math.abs(a.x() - b.x()) < 1e-9;
                assertTrue(!sameColumn || a.y() + a.h() <= b.y() + 1e-9 || b.y() + b.h() <= a.y() + 1e-9);
            }
        }
    }
}
