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

import javafx.application.Platform;
import javafx.scene.text.Font;
import network.ike.komet.claude.stamp.print.LayoutOracle.Kind;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first pass's line breaker, headless: the pieces a line may begin at, Typst's cost of a
 * line, and the breaks themselves — a word hyphenated where the line would otherwise stretch
 * too far, left whole where its spaces can take the gap, never in a table cell or an inline
 * raw span.
 */
class GreedyFxOracleTest {

    private static GreedyFxOracle oracle;

    @BeforeAll
    static void startJavaFx() {
        try {
            Class.forName("com.sun.glass.ui.monocle.MonoclePlatformFactory");
            System.setProperty("glass.platform", "Monocle");
            System.setProperty("monocle.platform", "Headless");
            System.setProperty("prism.order", "sw");
            System.setProperty("prism.text", "t2k");
            System.setProperty("java.awt.headless", "true");
        } catch (ClassNotFoundException ignored) {
            // the default toolkit
        }
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyRunning) {
            // started by another test
        }
        DocumentFonts.loadForJavaFx();
        oracle = new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO, 1.0);
    }

    private static Unit prose(String text) {
        return new Unit(0, 0, Kind.PARAGRAPH, text, 12, false, 0, 0, 0, false);
    }

    private static List<List<Integer>> plain(List<int[]> lines) {
        return lines.stream().map(l -> List.of(l[0], l[1], l[2])).toList();
    }

    @Test
    void piecesBeginAfterSpacesHyphensAndSlashes() {
        List<int[]> pieces = GreedyFxOracle.pieces("self-contained words/and-more  end -x 1/2");
        assertEquals(List.of(List.of(0, 5, 1), List.of(5, 14, 0), List.of(15, 21, 1), List.of(21, 25, 0), List.of(25, 29, 0),
                List.of(31, 34, 1), List.of(35, 37, 1), List.of(38, 41, 1)), plain(pieces),
                "self-|contained words/|and-|more end -x 1/2: no break after a lone dash, nor before a digit after a slash");
    }

    @Test
    void aLineCostsAsTypstCountsIt() {
        double sp = 3;
        // A perfect line costs one; stretching within the spaces' natural half costs the cube of the ratio a hundredfold.
        assertEquals(1, GreedyFxOracle.cost(100, 100, 4, sp, 12, true, -1, -1, false), 1e-9);
        assertEquals(Math.pow(1 + 100 * Math.pow(0.5, 3), 2), GreedyFxOracle.cost(100, 97, 4, sp, 12, true, -1, -1, false), 1e-9,
                "a gap of 3 over a stretch of 4 spaces × 1.5");
        // Beyond that, the extra per space over half an em.
        double extra = (12 - 6) / 4.0 / 6;
        assertEquals(Math.pow(1 + 100 * Math.pow(1 + extra, 3), 2), GreedyFxOracle.cost(100, 88, 4, sp, 12, true, -1, -1, false), 1e-9);
        // Shrinking is allowed to a third of the spaces when justified, never otherwise.
        assertEquals(Math.pow(1 + 100 * Math.pow(0.5, 3), 2), GreedyFxOracle.cost(100, 102, 4, sp, 12, true, -1, -1, false), 1e-9);
        assertTrue(GreedyFxOracle.cost(100, 105, 4, sp, 12, true, -1, -1, false) > 1e12, "overfull");
        assertTrue(GreedyFxOracle.cost(100, 101, 4, sp, 12, false, -1, -1, false) > 1e12, "a ragged line cannot shrink");
        // A hyphen's penalty, raised by 15% for every letter under five on either side, doubled after a dash.
        assertEquals(Math.pow(1 + GreedyFxOracle.HYPHEN_COST, 2), GreedyFxOracle.cost(100, 100, 4, sp, 12, true, 6, 9, false), 1e-9);
        assertEquals(Math.pow(1 + 1.45 * GreedyFxOracle.HYPHEN_COST, 2), GreedyFxOracle.cost(100, 100, 4, sp, 12, true, 2, 13, false), 1e-9);
        assertEquals(Math.pow(1 + 2 * GreedyFxOracle.HYPHEN_COST, 2), GreedyFxOracle.cost(100, 100, 4, sp, 12, true, 6, 9, true), 1e-9);
    }

    @Test
    void widthsAddUpWordByWord() {
        Font font = oracle.font(prose(""));
        double sp = oracle.width(" ", font);
        double sum = oracle.width("aaaa", font) + sp + oracle.width("bbbb", font) + sp + oracle.width("materi-", font);
        assertEquals(oracle.width("aaaa bbbb materi-", font), sum, 0.02, "no kerning: a line's width is its words' and spaces'");
    }

    @Test
    void hyphenatesTheWordThatDidNotFitWhereTheLineWouldStretchTooFar() {
        Unit unit = prose("aaaa bbbb materialization cccc");
        Font font = oracle.font(unit);
        assertArrayEquals(new int[] {2, 4, 6, 8, 11}, Hyphenator.english().points("materialization"), "ma|te|ri|al|iza|tion");
        double avail = oracle.width("aaaa bbbb materi-", font);

        List<int[]> lines = oracle.breaks(unit, avail);

        assertEquals(List.of(List.of(0, 16, 1), List.of(16, 30, 0)), plain(lines),
                "'aaaa bbbb materi-' with the hyphen, then 'alization cccc' from the cut");
    }

    @Test
    void leavesTheWordWholeWhereTheSpacesCanTakeTheGap() {
        // Nineteen spaces stretch by half a space each: the gap a four-letter head would fill costs less than the hyphen.
        String filler = "aa ".repeat(19) + "aa";
        Unit unit = prose(filler + " materialization zz");
        Font font = oracle.font(unit);
        double avail = oracle.width(filler + " mate-", font);

        List<int[]> lines = oracle.breaks(unit, avail);

        assertEquals(List.of(List.of(0, filler.length(), 0), List.of(filler.length() + 1, unit.text().length(), 0)), plain(lines));
    }

    @Test
    void aTableCellIsNeitherHyphenatedNorShrunk() {
        Unit unit = prose("aaaa bbbb materialization cccc");
        Font font = oracle.font(unit);
        double avail = oracle.width("aaaa bbbb materi-", font);

        assertEquals(List.of(List.of(0, 9, 0), List.of(10, 25, 0), List.of(26, 30, 0)), plain(oracle.breaks(unit, avail, false)));

        Unit three = prose("aaaa bbbb cccc");
        double tight = oracle.width("aaaa bbbb cccc", font) - 0.5;
        assertEquals(List.of(List.of(0, 14, 0)), plain(oracle.breaks(three, tight, true)), "justified: the spaces shrink half a point");
        assertEquals(List.of(List.of(0, 9, 0), List.of(10, 14, 0)), plain(oracle.breaks(three, tight, false)), "ragged: they do not");
        Unit row = new Unit(0, 0, Kind.TABLE_ROW, "aaaa bbbb cccc", 12, false, 0, 0, 0, false);
        assertEquals(List.of(List.of(0, 9, 0), List.of(10, 14, 0)), plain(oracle.breaks(row, tight)), "a row's text is a cell's");
    }

    @Test
    void anInlineRawSpanIsNotHyphenated() {
        Unit unit = prose("aaaa bbbb `materialization` cccc");
        Font font = oracle.font(unit);
        double avail = oracle.width("aaaa bbbb `materi-", font);

        List<int[]> lines = oracle.breaks(unit, avail);

        assertEquals(List.of(0, 9, 0), List.of(lines.getFirst()[0], lines.getFirst()[1], lines.getFirst()[2]));
        assertTrue(lines.stream().noneMatch(l -> l[2] == 1), lines.stream().map(l -> l[0] + "-" + l[1] + "/" + l[2]).toList().toString());
    }

    @Test
    void aWordLongerThanTheLineIsHyphenatedWhereItsHeadFits() {
        Unit unit = prose("materialization");
        Font font = oracle.font(unit);
        double avail = oracle.width("materi-", font) + 1;

        assertEquals(List.of(List.of(0, 6, 1), List.of(6, 11, 1), List.of(11, 15, 0)), plain(oracle.breaks(unit, avail)),
                "materi- / aliza- / tion: the tail is too wide too");
        assertEquals(List.of(List.of(0, 15, 0)), plain(oracle.breaks(unit, avail, false)), "a cell's word overflows whole");
    }

    @Test
    void verbatimAndParagraphsKeepTheirOwnLines() {
        Unit raw = new Unit(0, 0, Kind.VERBATIM, "alpha beta gamma\n\ndelta", 10, false, 0, 0, 0, false);
        assertEquals(List.of(List.of(0, 16, 0), List.of(17, 17, 0), List.of(18, 23, 0)), plain(oracle.breaks(raw, 20)));
        Unit two = prose("aaaa bbbb\ncccc");
        double avail = oracle.width("aaaa bbbb cccc dddd", oracle.font(two));
        assertEquals(List.of(List.of(0, 9, 0), List.of(10, 14, 0)), plain(oracle.breaks(two, avail)), "a paragraph break is a line break");
    }
}
