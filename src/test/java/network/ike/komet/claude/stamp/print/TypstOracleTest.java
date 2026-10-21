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

import network.ike.komet.claude.stamp.print.LayoutOracle.Geometry;
import network.ike.komet.claude.stamp.print.LayoutOracle.Kind;
import network.ike.komet.claude.stamp.print.LayoutOracle.Layout;
import network.ike.komet.claude.stamp.print.LayoutOracle.Line;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;
import network.ike.komet.claude.stamp.print.TypstOracle.Item;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Typst oracle's reading of typst's answer: parsing, word alignment, lines and hyphenation points, without typst. */
class TypstOracleTest {

    private static final Geometry LETTER = new Geometry(612, 792, 72, 72, 468, 648);
    /** Six points a character, so suffix widths are unambiguous. */
    private static final LayoutOracle.Measure WIDTH = (text, unit, mono, bold) -> text.length() * 6.0;

    @Test
    void parsesEveryKindOfLabelValue() {
        String json = "[{\"v\":0,\"p\":1,\"x\":\"72pt\",\"y\":\"72pt\"},{\"v\":\"say \\\"hi\\\"\",\"p\":1,\"x\":\"72.5pt\",\"y\":\"80.57pt\"},"
                + "{\"v\":false,\"p\":2,\"x\":\"100pt\",\"y\":\"-1.5pt\"},{\"v\":null,\"p\":2,\"x\":\"72pt\",\"y\":\"300pt\"}]";

        List<Item> items = TypstOracle.parse(json);

        assertEquals(4, items.size());
        assertEquals(new Item(0, 0, 72, 72), items.get(0), "pages are 0-based in the oracle");
        assertEquals(new Item("say \"hi\"", 0, 72.5, 80.57), items.get(1));
        assertEquals(new Item(Boolean.FALSE, 1, 100, -1.5), items.get(2));
        assertEquals(new Item(null, 1, 72, 300), items.get(3));
    }

    /** A word's start and end labels on one line. */
    private static void word(List<Item> items, String text, int page, double x, double y, double endX) {
        items.add(new Item(text, page, x, y));
        items.add(new Item(Boolean.FALSE, page, endX, y));
    }

    @Test
    void readsLinesOffTheWordsAndSkipsMarkupOnlyTheUnitHas() {
        // Block 0: a heading; block 1: a paragraph whose drawn text carries *bold* markers typst renders without.
        Unit heading = new Unit(0, 0, Kind.HEADING, "A heading", 18, true, 0, 0, 9, true);
        Unit prose = new Unit(1, 1, Kind.PARAGRAPH, "The *quick* brown fox jumps", 12, false, 0, 13, 0, false);
        List<Item> items = new ArrayList<>();
        items.add(new Item(0, 0, 72, 72));
        word(items, "A", 0, 72, 84, 78);
        word(items, "heading", 0, 89, 84, 157);
        items.add(new Item(1, 0, 72, 93));
        word(items, "The", 0, 72, 101.5, 90);
        word(items, "quick", 0, 97, 101.5, 129);
        word(items, "brown", 0, 133, 101.5, 171);
        word(items, "fox", 0, 540, 117.9, 90);
        word(items, "jumps", 0, 97, 117.9, 132);
        items.add(new Item(null, 0, 72, 130));

        Layout layout = TypstOracle.align(items, List.of(heading, prose), LETTER, WIDTH);

        assertTrue(layout.exact());
        assertEquals(1, layout.pages());
        assertEquals(List.of(new Line(0, 0, 72, 84, 0, 9, false)), layout.ofUnit(0));
        assertEquals(List.of(new Line(1, 0, 72, 101.5, 0, 17, false), new Line(1, 0, 72, 117.9, 18, 27, false)), layout.ofUnit(1),
                "the first line holds 'The *quick* brown' with its markers, the second 'fox jumps'");
        assertEquals("The *quick* brown", layout.ofUnit(1).get(0).text(prose));
        assertEquals("fox jumps", layout.ofUnit(1).get(1).text(prose));
        assertTrue(layout.notes().isEmpty(), layout.notes().toString());
    }

    @Test
    void aSplitWordBecomesAHyphenatedHeadAndATailOnTheNextLine() {
        Unit prose = new Unit(0, 0, Kind.PARAGRAPH, "see internationalization now", 12, false, 0, 13, 0, false);
        List<Item> items = new ArrayList<>();
        items.add(new Item(0, 0, 72, 93));
        word(items, "see", 0, 72, 101.5, 90);
        // The word begins a line (its start label was left at the end of the line before) and ends 13 characters
        // (78 pt) from the line's left edge, less than its own 20: its tail "tionalization" is on this line.
        items.add(new Item("internationalization", 0, 540, 101.5));
        items.add(new Item(Boolean.FALSE, 0, 72 + 13 * 6.0, 117.9));
        word(items, "now", 0, 155, 117.9, 173);

        Layout layout = TypstOracle.align(items, List.of(prose), LETTER, WIDTH);

        List<Line> lines = layout.ofUnit(0);
        assertEquals(2, lines.size());
        assertEquals(new Line(0, 0, 72, 101.5, 0, 11, true), lines.get(0));
        assertEquals("see interna-", lines.get(0).text(prose));
        assertEquals(new Line(0, 0, 72, 117.9, 11, 28, false), lines.get(1));
        assertEquals("tionalization now", lines.get(1).text(prose));
        assertFalse(layout.notes().stream().anyMatch(n -> n.contains("hyphenation")), "the tail's width named the suffix");
    }

    @Test
    void theHyphenationPointNearestTheTailNamesTheCut() {
        Unit prose = new Unit(0, 0, Kind.PARAGRAPH, "see materialization now `komet-claude` the end", 12, false, 0, 13, 0, false);
        // ma|te|ri|al|iza|tion: suffixes of 13, 11, 9, 7 and 4 characters. A tail of 7.6 characters is nearest the cut
        // after "materia" by any character, but the point after "material" (7 characters, 3.6 pt off) names it.
        TypstOracle.Cut cut = TypstOracle.cutByTail(WIDTH, prose, "materialization", 4, 7.6 * 6);
        assertEquals(8, cut.at());
        assertTrue(cut.point());
        assertEquals(3.6, cut.distance(), 0.001);
        // A word with no point breaks only at a hyphen or slash of its own: "komet-|claude`" in inline raw.
        cut = TypstOracle.cutByTail(WIDTH, prose, "`komet-claude`", prose.text().indexOf('`'), 7 * 6);
        assertEquals(7, cut.at());
        assertFalse(cut.point());
        assertEquals(0, cut.distance(), 0.001);
        // A word with neither cannot be hyphenated, whatever its tail's width says.
        assertEquals(null, TypstOracle.cutByTail(WIDTH, prose, "the", prose.text().indexOf("the"), 6));
        assertArrayEquals(new int[] {5, 15}, TypstOracle.ownCuts("self-contained/x-1"), "after the hyphen and the slash, not before a digit");
        assertArrayEquals(new int[] {8, 15, 19}, TypstOracle.ownCuts("https://github.com/x"), "a link breaks at its pieces");
    }

    @Test
    void aWordWithoutAHyphenationPointStandsWholeOnTheNextLine() {
        Unit prose = new Unit(0, 0, Kind.PARAGRAPH, "see the now", 12, false, 0, 13, 0, false);
        List<Item> items = new ArrayList<>();
        items.add(new Item(0, 0, 72, 93));
        word(items, "see", 0, 72, 101.5, 90);
        // "the" begins the second line and its end label sits a character from the left edge — a width the tail of a
        // hyphenated word would have, but "the" has no hyphenation point: it stands whole.
        items.add(new Item("the", 0, 540, 101.5));
        items.add(new Item(Boolean.FALSE, 0, 72 + 6.0, 117.9));
        word(items, "now", 0, 94, 117.9, 112);

        Layout layout = TypstOracle.align(items, List.of(prose), LETTER, WIDTH);

        List<Line> lines = layout.ofUnit(0);
        assertEquals(2, lines.size());
        assertEquals(new Line(0, 0, 72, 101.5, 0, 3, false), lines.get(0));
        assertEquals("the now", lines.get(1).text(prose));
        assertTrue(lines.stream().noneMatch(Line::hyphenated));
    }

    @Test
    void theLastWordsEndLabelIsIgnored() {
        Unit prose = new Unit(0, 0, Kind.PARAGRAPH, "one two three", 12, false, 0, 13, 0, false);
        List<Item> items = new ArrayList<>();
        items.add(new Item(0, 0, 72, 93));
        word(items, "one", 0, 72, 101.5, 90);
        word(items, "two", 0, 94, 101.5, 112);
        items.add(new Item("three", 0, 116, 101.5));
        items.add(new Item(Boolean.FALSE, 0, 72, 123.7));            // typst leaves the paragraph's closing label at its end

        Layout layout = TypstOracle.align(items, List.of(prose), LETTER, WIDTH);

        assertEquals(List.of(new Line(0, 0, 72, 101.5, 0, 13, false)), layout.ofUnit(0), "one line, the last word on it");

        // The same paragraph with the last word starting a new line: its start label was left at the end of the line before.
        items.set(5, new Item("three", 0, 530, 101.5));
        layout = TypstOracle.align(items, List.of(prose), LETTER, WIDTH);
        assertEquals(List.of(new Line(0, 0, 72, 101.5, 0, 7, false), new Line(0, 0, 72, 101.5 + 12 * LayoutOracle.PITCH, 8, 13, false)),
                layout.ofUnit(0), "the last word did not fit after its start label: a line of its own, a pitch below");
    }

    @Test
    void wordsAtHardBreaksKeepTheirLines() {
        // A verbatim unit of three lines: each line's last word gets its end label at the next line's start, and
        // each line's first word its start label at the line before's end.
        Unit raw = new Unit(0, 0, Kind.VERBATIM, "alpha beta\ngamma\ndelta epsilon", 10, false, 0, 0, 0, false);
        List<Item> items = new ArrayList<>();
        items.add(new Item(0, 0, 80, 100));
        word(items, "alpha", 0, 80, 110, 110);
        items.add(new Item("beta", 0, 113, 110));
        items.add(new Item(Boolean.FALSE, 0, 80, 124));
        items.add(new Item("gamma", 0, 137, 110));                   // the line before's end
        items.add(new Item(Boolean.FALSE, 0, 80, 138));              // the line after's start
        items.add(new Item("delta", 0, 110, 124));
        items.add(new Item(Boolean.FALSE, 0, 110, 138));
        items.add(new Item("epsilon", 0, 113, 138));
        items.add(new Item(Boolean.FALSE, 0, 80, 152));

        Layout layout = TypstOracle.align(items, List.of(raw), LETTER, WIDTH);

        List<Line> lines = layout.ofUnit(0);
        assertEquals(3, lines.size(), lines.toString());
        assertEquals("alpha beta", lines.get(0).text(raw));
        assertEquals(110, lines.get(0).baseline());
        assertEquals("gamma", lines.get(1).text(raw));
        assertEquals(110 + 10 * LayoutOracle.PITCH, lines.get(1).baseline(), 0.001, "a lone word on a line: a pitch below");
        assertEquals("delta epsilon", lines.get(2).text(raw));
        assertEquals(138, lines.get(2).baseline());
        assertTrue(lines.stream().noneMatch(Line::hyphenated));
    }

    @Test
    void aListMarkerAlignsAndItsLinesHangUnderTheText() {
        Unit item = new Unit(0, 0, Kind.LIST_ITEM, "• first item that wraps", 12, false, 12, 0, 0, false);
        List<Item> items = new ArrayList<>();
        items.add(new Item(0, 0, 72, 93));
        word(items, "•", 0, 84, 101.5, 90);
        word(items, "first", 0, 94, 101.5, 120);
        word(items, "item", 0, 124, 101.5, 148);
        word(items, "that", 0, 540, 117.9, 118);
        word(items, "wraps", 0, 122, 117.9, 155);

        Layout layout = TypstOracle.align(items, List.of(item), LETTER, WIDTH);

        List<Line> lines = layout.ofUnit(0);
        assertEquals(2, lines.size());
        assertEquals(84, lines.get(0).x(), "the first line starts at the marker");
        assertEquals(94, lines.get(1).x(), "the second hangs under the text");
        assertEquals("• first item", lines.get(0).text(item));
        assertEquals("that wraps", lines.get(1).text(item));
    }

    @Test
    void aTableRowIsPlacedWholeAndAUnitWithoutWordsIsNoted() {
        Unit row = new Unit(0, 0, Kind.TABLE_ROW, "cell one  │  cell two", 12, false, 0, 0, 0, false);
        Unit image = new Unit(1, 1, Kind.OTHER, "🖼 image::diagram.svg[]", 12, false, 0, 0, 0, false);
        List<Item> items = new ArrayList<>();
        items.add(new Item(0, 0, 72, 93));
        word(items, "cell", 0, 77, 105, 101);
        word(items, "one", 0, 103, 105, 121);
        word(items, "cell", 0, 200, 105, 222);
        word(items, "two", 0, 226, 105, 244);
        items.add(new Item(1, 0, 72, 150));

        Layout layout = TypstOracle.align(items, List.of(row, image), LETTER, WIDTH);

        assertEquals(List.of(new Line(0, 0, 77, 105, 0, row.text().length(), false)), layout.ofUnit(0),
                "a row sits on its first word's baseline, at that word's left");
        assertTrue(layout.ofUnit(1).isEmpty());
        assertEquals(List.of("1 unit(s) matched no word of typst's and are not placed: [1/OTHER '\uD83D\uDDBC image::diagram.svg[]']"), layout.notes());
    }

    @Test
    void aCaptionTypstPutsBeforeATableSkipsNoRow() {
        Unit header = new Unit(0, 0, Kind.TABLE_ROW, "Attribute  │  Value", 12, false, 0, 0, 0, false);
        Unit row = new Unit(1, 0, Kind.TABLE_ROW, "topic  │  Context -- Female", 12, false, 0, 0, 0, false);
        List<Item> items = new ArrayList<>();
        items.add(new Item(0, 0, 72, 93));
        word(items, "Context", 0, 72, 100, 114);                   // the caption, which no unit draws
        word(items, "--", 0, 117, 100, 129);
        word(items, "Female", 0, 132, 100, 168);
        word(items, "Attribute", 0, 77, 120, 131);
        word(items, "Value", 0, 264, 120, 294);
        word(items, "topic", 0, 77, 140, 107);
        word(items, "Context", 0, 264, 140, 306);
        word(items, "--", 0, 309, 140, 321);
        word(items, "Female", 0, 324, 140, 360);

        Layout layout = TypstOracle.align(items, List.of(header, row), LETTER, WIDTH);

        assertEquals(1, layout.ofUnit(0).size(), "the header row is placed, not skipped for the caption");
        assertEquals(120, layout.ofUnit(0).getFirst().baseline());
        assertEquals(140, layout.ofUnit(1).getFirst().baseline());
        assertTrue(layout.notes().isEmpty(), layout.notes().toString());
    }

    @Test
    void theLayoutsBreaksNameTheUnitEachPageStartsWith() {
        Layout layout = new Layout(3, List.of(
                new Line(0, 0, 72, 80, 0, 5, false),
                new Line(1, 1, 72, 80, 0, 5, false),
                new Line(1, 2, 72, 80, 6, 9, false)), true, 0, List.of());

        assertEquals(List.of(1, -1), layout.breaks());
        assertEquals(1, layout.pageOf(1));
        assertEquals(-1, layout.pageOf(7));
        assertEquals(new int[] {1, 2}[0], new Unit(0, 0, Kind.VERBATIM, "ab\ncd\nef", 10, false, 0, 0, 0, false).paragraphAt(5)[0]);
        assertEquals(2, new Unit(0, 0, Kind.VERBATIM, "ab\ncd\nef", 10, false, 0, 0, 0, false).paragraphAt(7)[0]);
        assertEquals(1, new Unit(0, 0, Kind.VERBATIM, "ab\ncd\nef", 10, false, 0, 0, 0, false).paragraphAt(7)[1]);
    }
}
