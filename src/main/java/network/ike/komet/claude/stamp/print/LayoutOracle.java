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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What lays a document out on pages ({@code dev-document-surface-pages}; KEC 2026-10-20: Typst
 * is the layout oracle and JavaFX the renderer, in two passes). An oracle answers, for the
 * {@link Unit}s of a document — its paragraph-like pieces in order, each with the text the page
 * draws for it — where every line of every unit falls: the page, the baseline's distance from
 * the page's top edge, the characters of the unit's text the line holds, and whether the line
 * ends in a hyphen the oracle inserted. A renderer then draws each page from the lines on it,
 * keeping the text's provenance by character offset.
 *
 * <p>Two oracles exist: {@link GreedyFxOracle}, which answers at once from JavaFX's own line
 * breaking, and {@link TypstOracle}, which asks typst and answers when it returns. A view shows
 * the first and settles on the second.
 */
public interface LayoutOracle {

    /** What kind of unit, for the spacing and keep rules a greedy oracle applies. */
    enum Kind { TITLE, HEADING, PARAGRAPH, LIST_ITEM, VERBATIM, TABLE_ROW, OTHER }

    /** The cap height of the Noto fonts as a fraction of the size: Typst's line box starts at the first line's cap height. */
    double CAP_HEIGHT = 0.714;

    /** The pitch between baselines as a fraction of the size: Typst's leading of 0.65em plus the cap height. */
    double PITCH = 1.364;

    /**
     * One paragraph-like piece of the document, as both oracles see it: the text the page draws
     * for it (paragraphs separated by {@code \n}; a verbatim unit's lines are its paragraphs),
     * its font, and how it sits in the flow.
     *
     * @param index        the unit's index in the document
     * @param block        the top-level block of the print model the unit belongs to, as the Typst writer labels them
     * @param kind         the unit's kind
     * @param text         the drawn text
     * @param fontSize     the size the unit is set in, in points
     * @param bold         whether the unit is bold (a heading)
     * @param indent       the left indent of its lines in points (a list item's)
     * @param spaceAbove   the space the unit keeps above its first line's cap height, in points
     * @param spaceBelow   the space it keeps below its last baseline, in points
     * @param keepWithNext whether it stays on the page the next unit starts on
     */
    record Unit(int index, int block, Kind kind, String text, double fontSize, boolean bold, double indent, double spaceAbove,
                double spaceBelow, boolean keepWithNext) {
        public Unit {
            Objects.requireNonNull(kind, "kind is null");
            text = text == null ? "" : text;
        }

        /** @return the paragraph of the text an offset falls in, and the offset within it, as {@code {paragraph, offset}} */
        public int[] paragraphAt(int offset) {
            int paragraph = 0;
            int start = 0;
            for (int i = 0; i < Math.min(offset, text.length()); i++) {
                if (text.charAt(i) == '\n') {
                    paragraph++;
                    start = i + 1;
                }
            }
            return new int[] {paragraph, offset - start};
        }
    }

    /**
     * One laid-out line of a unit.
     *
     * @param unit       the unit's index
     * @param page       the 0-based page
     * @param x          the line's left edge, from the page's left edge, in points
     * @param baseline   the line's baseline, from the page's top edge, in points
     * @param from       the first character of the unit's text on the line
     * @param to         the character after the last
     * @param hyphenated whether the line ends in a hyphen the oracle inserted (the text has none there)
     */
    record Line(int unit, int page, double x, double baseline, int from, int to, boolean hyphenated) {

        /** @return the line's characters of the unit's text, with the hyphen when the oracle broke a word */
        public String text(Unit of) {
            String t = of.text();
            String slice = t.substring(Math.clamp(from, 0, t.length()), Math.clamp(to, 0, t.length()));
            return hyphenated && !slice.endsWith("-") ? slice + "-" : slice;
        }

        /** @return whether the line covers the whole unit (a unit placed as a whole, wrapped by the renderer) */
        public boolean whole(Unit of) {
            return from == 0 && to >= of.text().length();
        }
    }

    /**
     * The page geometry both oracles lay out for, in points.
     *
     * @param pageWidth    the page width
     * @param pageHeight   the page height
     * @param contentLeft  the left edge of the content, from the page's left edge
     * @param contentTop   the top of the content, from the page's top edge
     * @param contentWidth the content width
     * @param bandHeight   the height of the content band
     */
    record Geometry(double pageWidth, double pageHeight, double contentLeft, double contentTop, double contentWidth, double bandHeight) {

        /** @return the bottom of the content band, from the page's top edge */
        public double contentBottom() {
            return contentTop + bandHeight;
        }
    }

    /**
     * The answer: the lines in document order, and the page count.
     *
     * @param pages  the page count, at least 1
     * @param lines  every line of every unit, in document order
     * @param exact  whether the answer is Typst's own (the second pass) rather than a greedy estimate
     * @param millis how long the oracle took
     * @param notes  what the oracle could not do exactly, one per line; empty when nothing
     */
    record Layout(int pages, List<Line> lines, boolean exact, long millis, List<String> notes) {

        public Layout {
            lines = List.copyOf(lines);
            notes = List.copyOf(notes);
        }

        /** @return the lines on a page, in order */
        public List<Line> onPage(int page) {
            return lines.stream().filter(l -> l.page() == page).toList();
        }

        /** @return the lines of a unit, in order */
        public List<Line> ofUnit(int unit) {
            return lines.stream().filter(l -> l.unit() == unit).toList();
        }

        /** @return the page a unit begins on, or -1 when it has no line */
        public int pageOf(int unit) {
            for (Line line : lines) {
                if (line.unit() == unit) {
                    return line.page();
                }
            }
            return -1;
        }

        /** @return the first unit that begins on each page from the second on; -1 when a page starts inside a unit or holds none */
        public List<Integer> breaks() {
            List<Integer> breaks = new ArrayList<>();
            for (int p = 1; p < pages; p++) {
                int first = -1;
                for (Line line : lines) {
                    if (line.page() == p) {
                        first = line.from() == 0 ? line.unit() : -1;
                        break;
                    }
                }
                breaks.add(first);
            }
            return breaks;
        }
    }

    /** Measures text as the renderer draws it, for the Typst oracle's hyphenation points and line edges. */
    @FunctionalInterface
    interface Measure {

        /**
         * @param text the text
         * @param unit the unit it belongs to
         * @param mono whether the text is an inline raw span (set in the mono font at 0.85 of the size)
         * @param bold whether the text is a strong span
         * @return the width in points
         */
        double width(String text, Unit unit, boolean mono, boolean bold);
    }

    /**
     * Lays the units out.
     *
     * @param units    the units in document order
     * @param geometry the page geometry
     * @return the layout
     */
    Layout layout(List<Unit> units, Geometry geometry);
}
