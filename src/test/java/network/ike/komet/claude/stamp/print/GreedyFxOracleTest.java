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

import java.util.ArrayList;
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

    @Test
    void aJustifiedLineStaysInsideTheMeasureWithItsSlack() {
        assertEquals(1, LayoutOracle.lineScale(400, 9, 468, true), "room to spare");
        assertEquals(468 / 469.0, LayoutOracle.lineScale(460, 9, 468, true), 1e-9, "the text fits but the laid width does not");
        assertEquals(1, LayoutOracle.lineScale(460, 9, 468, false), "a ragged line's slack paints nothing");
        assertEquals(468 / 480.0, LayoutOracle.lineScale(480, 9, 468, false), 1e-9, "wider than the measure");
    }

    @Test
    void aFigureThatDoesNotFitMovesToTheNextPageWhole() {
        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        Unit first = new Unit(0, 0, Kind.FIGURE, LayoutOracle.FIGURE_WORD, 12, false, 0, 0, 0, false, 468, 500);
        Unit second = new Unit(1, 1, Kind.FIGURE, LayoutOracle.FIGURE_WORD, 12, false, 0, 0, 0, false, 936, 400);
        var layout = oracle.layout(List.of(first, second), geometry);
        assertEquals(2, layout.pages());
        assertEquals(0, layout.ofUnit(0).getFirst().page());
        assertEquals(72 + 500, layout.ofUnit(0).getFirst().baseline(), 1e-6, "a figure's baseline is its foot");
        assertEquals(1, layout.ofUnit(1).getFirst().page());
        assertEquals(72 + 200, layout.ofUnit(1).getFirst().baseline(), 1e-6, "scaled to the measure: half its height");
    }

    private static final LayoutOracle.Geometry LETTER = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);

    /** A table of one-line rows in a block of its own, its header bold, its first row at a unit index. */
    private static List<Unit> table(int first, int block, int rows) {
        List<Unit> units = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            units.add(new Unit(first + r, block, Kind.TABLE_ROW, "cell " + r + "  \u2502  other", 12, r == 0, 0,
                    r == 0 ? 13.2 : 2 * UnitSpacing.CELL_INSET, 0, false));
        }
        return units;
    }

    /** Filler that leaves the page's room for a given number of one-line rows. */
    private static Unit filler(double height) {
        return new Unit(0, 0, Kind.FIGURE, LayoutOracle.FIGURE_WORD, 12, false, 0, 0, 0, false, 100, height);
    }

    @Test
    void aTableBreaksBetweenItsRowsWithItsHeaderOnEveryPage() {
        List<Unit> units = table(0, 0, 90);

        var layout = oracle.layout(units, LETTER);

        double row = 12 * LayoutOracle.CAP_HEIGHT + 2 * UnitSpacing.CELL_INSET;
        int perPage = (int) (LETTER.bandHeight() / row);
        assertEquals(3, layout.pages(), "a page holds " + perPage + " rows");
        assertEquals(List.of(0, 1, 2), layout.ofUnit(0).stream().map(LayoutOracle.Line::page).toList(), "a header on every page");
        assertEquals(0, layout.ofUnit(perPage - 1).getFirst().page());
        assertEquals(1, layout.ofUnit(perPage).getFirst().page());
        assertEquals(layout.ofUnit(0).get(1).baseline(), layout.ofUnit(0).getFirst().baseline(), 1e-6, "at the page's top");
        assertEquals(layout.ofUnit(0).get(1).baseline() + row, layout.ofUnit(perPage).getFirst().baseline(), 1e-6, "the rows below it");
        for (int u = 1; u < units.size(); u++) {
            assertEquals(1, layout.ofUnit(u).size(), "one line for row " + u);
        }
        assertEquals(List.of(perPage, 2 * perPage - 1), layout.breaks(), "a repeat begins no page");
    }

    @Test
    void aTableStartsWhereItsHeaderAndFirstRowFit() {
        double row = 12 * LayoutOracle.CAP_HEIGHT + 2 * UnitSpacing.CELL_INSET;
        double gap = 13.2;
        List<Unit> fits = new ArrayList<>(List.of(filler(LETTER.bandHeight() - gap - 2 * row)));
        fits.addAll(table(1, 1, 3));
        List<Unit> short1 = new ArrayList<>(List.of(filler(LETTER.bandHeight() - gap - 2 * row + 1)));
        short1.addAll(table(1, 1, 3));

        assertEquals(0, oracle.layout(fits, LETTER).ofUnit(2).getFirst().page(), "both rows fit");
        assertEquals(1, oracle.layout(fits, LETTER).ofUnit(3).getFirst().page(), "the third goes over");
        var moved = oracle.layout(short1, LETTER);
        assertEquals(1, moved.ofUnit(1).getFirst().page(), "a header is not left at the foot without a row");
        assertEquals(1, moved.ofUnit(1).size(), "and has nothing to repeat");
    }

    @Test
    void aCaptionStaysWithItsTable() {
        List<Unit> units = new ArrayList<>(List.of(filler(LETTER.bandHeight() - 60)));
        units.add(new Unit(1, 1, Kind.OTHER, "A caption", 12, true, 0, 12, 12 * 1.1 + UnitSpacing.CELL_INSET - 0.4, false));
        units.addAll(table(2, 1, 3));

        var layout = oracle.layout(units, LETTER);

        assertEquals(1, layout.ofUnit(1).getFirst().page());
        assertEquals(1, layout.ofUnit(2).getFirst().page());
    }

    @Test
    void aRowCentersItsShorterCells() {
        String wide = "many words of a cell that runs to a second line in half the measure, ".repeat(2).strip();
        Unit row = new Unit(0, 0, Kind.TABLE_ROW, "one  \u2502  " + wide, 12, false, 0, 0, 0, false);
        int lines = oracle.breaks(new Unit(0, 0, Kind.PARAGRAPH, wide, 12, false, 0, 0, 0, false), LETTER.contentWidth() / 2 - 10, false).size();
        assertTrue(lines > 1, "a tall cell beside the short one: " + lines);
        double pitch = 12 * LayoutOracle.PITCH;

        var layout = oracle.layout(List.of(row), LETTER);

        double top = LETTER.contentTop() + UnitSpacing.CELL_INSET + 12 * LayoutOracle.CAP_HEIGHT;
        assertEquals(top + (lines - 1) * pitch / 2, layout.ofUnit(0).getFirst().baseline(), 1e-6);
        assertEquals((lines - 1) * pitch + 12 * LayoutOracle.CAP_HEIGHT + 2 * UnitSpacing.CELL_INSET, oracle.rowHeight(row, LETTER), 1e-6);
    }

    @Test
    void aRowTallerThanAPageRunsOnUnderTheHeader() {
        Unit header = table(0, 0, 1).getFirst();
        String tall = "words ".repeat(1400).strip();
        Unit row = new Unit(1, 0, Kind.TABLE_ROW, tall, 12, false, 0, 2 * UnitSpacing.CELL_INSET, 0, false);
        Unit after = new Unit(2, 0, Kind.TABLE_ROW, "after  \u2502  it", 12, false, 0, 2 * UnitSpacing.CELL_INSET, 0, false);
        assertTrue(oracle.rowHeight(row, LETTER) > LETTER.bandHeight());

        var layout = oracle.layout(List.of(header, row, after), LETTER);

        assertEquals(0, layout.ofUnit(1).getFirst().page(), "it starts where it stands");
        assertTrue(layout.ofUnit(2).getFirst().page() > 0, "what follows it follows its end");
        assertEquals(layout.ofUnit(2).getFirst().page() + 1, layout.ofUnit(0).size(), "a header on every page it reaches");
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
        Unit unit = prose("aaaa bbbb materialization cccc")
                .withInline(List.of(new InlineText.Run(10, 25, true, false, false, null)), List.of(), 0);
        Font font = oracle.font(unit);
        double avail = oracle.width("aaaa bbbb materi-", font);

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
