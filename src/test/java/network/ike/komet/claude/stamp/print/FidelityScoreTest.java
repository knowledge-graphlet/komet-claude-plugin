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
package network.ike.komet.claude.stamp.print;

import network.ike.komet.claude.stamp.print.FidelityScore.Rule;
import network.ike.komet.claude.stamp.print.LayoutOracle.Kind;
import network.ike.komet.claude.stamp.print.LayoutOracle.Layout;
import network.ike.komet.claude.stamp.print.LayoutOracle.Line;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fidelity score and its attribution by elimination, on hand-made layouts. */
class FidelityScoreTest {

    private static final Unit HEADING = new Unit(0, 0, Kind.HEADING, "A heading", 18, true, 0, 16, 9, true);
    private static final Unit PROSE = new Unit(1, 1, Kind.PARAGRAPH, "one two three four five six seven", 12, false, 0, 13, 0, false);
    private static final Unit MORE = new Unit(2, 2, Kind.PARAGRAPH, "alpha beta gamma", 12, false, 0, 13, 0, false);

    private static Layout layout(int pages, Line... lines) {
        return new Layout(pages, List.of(lines), true, 0, List.of());
    }

    @Test
    void identicalLayoutsScoreFully() {
        Layout golden = layout(1, new Line(0, 0, 72, 85, 0, 9, false), new Line(1, 0, 72, 110, 0, 17, false), new Line(1, 0, 72, 126, 18, 33, false));

        FidelityScore.Document d = FidelityScore.score("same", golden, golden, List.of(HEADING, PROSE));

        assertEquals(100.0, d.score(), 0.001);
        assertEquals(2, d.paragraphsSame());
        assertEquals(2, d.unitsSamePage());
        assertTrue(d.attribution().values().stream().allMatch(n -> n == 0));
    }

    @Test
    void aBreakOneWordEarlyIsWidthAndInsideAWordIsHyphenation() {
        // "one two three four five six seven": Typst ends the first line after "five", the greedy pass after "four".
        Layout golden = layout(1, new Line(1, 0, 72, 110, 0, 23, false), new Line(1, 0, 72, 126, 24, 33, false));
        Layout greedy = layout(1, new Line(1, 0, 72, 110, 0, 18, false), new Line(1, 0, 72, 126, 19, 33, false));

        FidelityScore.Document d = FidelityScore.score("width", golden, greedy, List.of(PROSE));

        assertEquals(1, d.attribution().get(Rule.WIDTH), "the first line ends a word early: measurement");
        assertEquals(1, d.offByOneWord());
        assertEquals(0.0, d.score(), 0.001);

        Layout hyphenated = layout(1, new Line(1, 0, 72, 110, 0, 21, true), new Line(1, 0, 72, 126, 21, 33, false));
        d = FidelityScore.score("hyphen", hyphenated, greedy, List.of(PROSE));
        assertEquals(1, d.attribution().get(Rule.HYPHENATION));
        assertEquals(1, d.hyphenMissed());
    }

    @Test
    void theSameLinesOnAnotherPageAreAKeepOrSpacingRuleAndDriftingBaselinesAreLeading() {
        Layout golden = layout(2, new Line(0, 1, 72, 85, 0, 9, false), new Line(1, 1, 72, 110, 0, 17, false), new Line(1, 1, 72, 126, 18, 33, false));
        Layout greedy = layout(2, new Line(0, 0, 72, 700, 0, 9, false), new Line(1, 1, 72, 85, 0, 17, false), new Line(1, 1, 72, 101, 18, 33, false));

        FidelityScore.Document d = FidelityScore.score("keep", golden, greedy, List.of(HEADING, PROSE));

        assertEquals(1, d.attribution().get(Rule.KEEP_TOGETHER), "the heading stayed on the page before its paragraph");
        assertEquals(2, d.paragraphsSame());
        assertEquals(100.0, d.score(), 0.001, "every line breaks as Typst's, only the pages differ");

        Layout drifting = layout(1, new Line(1, 0, 72, 110, 0, 17, false), new Line(1, 0, 72, 128, 18, 33, false));
        Layout even = layout(1, new Line(1, 0, 72, 110, 0, 17, false), new Line(1, 0, 72, 126, 18, 33, false));
        d = FidelityScore.score("leading", drifting, even, List.of(PROSE));
        assertEquals(1, d.attribution().get(Rule.LEADING));

        Layout shifted = layout(1, new Line(1, 0, 72, 120, 0, 17, false), new Line(1, 0, 72, 136, 18, 33, false), new Line(2, 0, 72, 160, 0, 16, false));
        Layout base = layout(1, new Line(1, 0, 72, 110, 0, 17, false), new Line(1, 0, 72, 126, 18, 33, false), new Line(2, 0, 72, 150, 0, 16, false));
        d = FidelityScore.score("spacing", shifted, base, List.of(PROSE, MORE));
        assertEquals(0, d.attribution().get(Rule.SPACING), "the first unit's offset is not attributed without a comparable unit before it");
        assertEquals(0, d.attribution().get(Rule.LEADING));
    }

    @Test
    void theCorpusScoreIsLineWeightedAndNamesTheTopRules() {
        FidelityScore.Document a = new FidelityScore.Document("a", 1, 1, 2, 2, 1, 10, 5, 2, 1, java.util.Map.of(Rule.WIDTH, 2, Rule.HYPHENATION, 1));
        FidelityScore.Document b = new FidelityScore.Document("b", 2, 2, 4, 4, 4, 30, 30, 0, 0, java.util.Map.of());

        FidelityScore.Corpus corpus = new FidelityScore.Corpus(List.of(a, b));

        assertEquals(87.5, corpus.score(), 0.001, "35 of 40 lines");
        assertEquals(2, corpus.pagesMatching());
        assertEquals(List.of(Rule.WIDTH, Rule.HYPHENATION), corpus.topRules());
        assertTrue(corpus.text().contains("corpus: 2 document(s), score 87.5%"));
        assertTrue(FidelityScore.oneWordApart("one two three", 3, 7));
        assertTrue(!FidelityScore.oneWordApart("one two three", 3, 13));
    }
}
