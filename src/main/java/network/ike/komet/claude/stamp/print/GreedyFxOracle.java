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

import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The first pass: greedy line breaking over JavaFX's glyph widths, answered at once. Each
 * paragraph of a unit is broken a line at a time at the content width in the unit's font, each
 * line end chosen by Typst's own cost of a line — the stretch or shrink of its spaces, the
 * penalty of a hyphen — among the longest end that fits, the one before it and the hyphenation
 * points of the word that did not fit ({@link Hyphenator}: Typst's patterns and bounds); the
 * lines are placed down the pages as Typst places them — the
 * first baseline a cap height below the content top, baselines a pitch apart, units a paragraph's
 * spacing apart — with a heading kept with the two lines after it and two lines kept at the foot
 * and head of a split unit, as Typst's sticky headings and its orphan and widow costs do. What
 * Typst's optimized breaks still do differently — a line broken short so a later one breaks
 * well — the second pass corrects. Must be used on the JavaFX application thread, or on a
 * thread that may lay text out.
 */
public final class GreedyFxOracle implements LayoutOracle {

    /**
     * The measure a line is broken at, as a fraction of the content width: a calibration of
     * JavaFX's glyph widths (laid without kerning) against Typst's. While the breaker was
     * JavaFX's own, which never shrinks a space, a measure 2.5% wider than the content made up
     * for the shrink Typst allows a justified line (47.7% of Typst's lines on the fidelity
     * corpus, 2026-10-20); with the spaces' shrink in the breaker's own fit rule the peak sits at
     * the content width itself (54.1% at 1.0, 52.2% at 0.995, 51.7% at 1.004).
     */
    public static final double MEASURE = 1.0;

    private final String family;
    private final String monoFamily;
    private final double measure;
    private final boolean hyphenation;

    /**
     * @param family     the body font family
     * @param monoFamily the verbatim font family
     */
    public GreedyFxOracle(String family, String monoFamily) {
        this(family, monoFamily, MEASURE);
    }

    /**
     * @param family     the body font family
     * @param monoFamily the verbatim font family
     * @param measure    the measure lines are broken at, as a fraction of the content width
     */
    public GreedyFxOracle(String family, String monoFamily, double measure) {
        this(family, monoFamily, measure, true);
    }

    /**
     * @param family      the body font family
     * @param monoFamily  the verbatim font family
     * @param measure     the measure lines are broken at, as a fraction of the content width
     * @param hyphenation whether words are hyphenated; off only to score the breaker without it
     */
    public GreedyFxOracle(String family, String monoFamily, double measure, boolean hyphenation) {
        this.family = family;
        this.monoFamily = monoFamily;
        this.measure = measure;
        this.hyphenation = hyphenation;
    }

    @Override
    public Layout layout(List<Unit> units, Geometry geometry) {
        long started = System.nanoTime();
        List<Line> lines = new ArrayList<>();
        int page = 0;
        double baseline = -1;                                     // the last baseline on the page; -1 at a page's top
        Unit previous = null;                                     // the unit before, on the same page
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            double pitch = UnitSpacing.pitch(unit);
            double cap = unit.fontSize() * CAP_HEIGHT;
            if (unit.kind() == Kind.TABLE_ROW && (i == 0 || units.get(i - 1).block() != unit.block())) {
                // A table is a figure, which Typst never splits: the whole of it moves to the next page when it does not fit.
                double table = 0;
                for (int j = i; j < units.size() && units.get(j).kind() == Kind.TABLE_ROW && units.get(j).block() == unit.block(); j++) {
                    table += rowHeight(units.get(j), geometry);
                }
                double first = baseline < 0 ? geometry.contentTop() + cap : baseline + UnitSpacing.gap(previous, unit) + cap;
                if (baseline >= 0 && first + table - cap > geometry.contentBottom() + 0.01 && table <= geometry.bandHeight()) {
                    page++;
                    baseline = -1;
                    previous = null;
                }
            }
            if (unit.kind() == Kind.TABLE_ROW) {
                double first = baseline < 0 ? geometry.contentTop() + cap : baseline + UnitSpacing.gap(previous, unit) + cap;
                double height = rowHeight(unit, geometry);
                lines.add(new Line(i, page, geometry.contentLeft() + TypstOracle.TABLE_INSET, first, 0, unit.text().length(), false));
                baseline = first + height - cap - 2 * UnitSpacing.CELL_INSET;
                previous = unit;
                continue;
            }
            // A list item hangs its lines after the first under its text, past the marker.
            double hang = unit.kind() == Kind.LIST_ITEM ? unit.fontSize() * 0.9 : 0;
            List<int[]> rows = breaks(unit, (geometry.contentWidth() - unit.indent()) * measure);
            double first = baseline < 0 ? geometry.contentTop() + cap + UnitSpacing.gap(null, unit) : baseline + UnitSpacing.gap(previous, unit) + cap;
            double height = (rows.size() - 1) * pitch;
            double companion = unit.keepWithNext() && i + 1 < units.size()
                    ? UnitSpacing.gap(unit, units.get(i + 1)) + units.get(i + 1).fontSize() * (CAP_HEIGHT + PITCH) : 0;
            boolean splittable = unit.kind() == Kind.PARAGRAPH || unit.kind() == Kind.LIST_ITEM;
            boolean whole = !splittable || rows.size() <= 2 || unit.keepWithNext();
            if (baseline >= 0 && first + (whole ? height + companion : pitch) > geometry.contentBottom() + 0.01) {
                page++;
                baseline = -1;
                previous = null;
                first = geometry.contentTop() + cap + UnitSpacing.gap(null, unit);
            }
            int row = 0;
            double y = first;
            while (row < rows.size()) {
                int remaining = rows.size() - row;
                int fit = (int) Math.floor((geometry.contentBottom() - y + 0.01) / pitch) + 1;
                if (fit >= remaining) {
                    fit = remaining;
                } else if (!splittable) {
                    fit = remaining;                              // a unit taller than a page overflows rather than splits
                } else if (fit < 2 && baseline >= 0) {
                    fit = 0;                                      // no orphan: the whole unit moves down
                } else if (remaining - fit < 2 && fit > 2) {
                    fit = remaining - 2;                          // no widow: a line more moves down
                } else if (fit < 1) {
                    fit = 1;
                }
                if (fit == 0) {
                    page++;
                    baseline = -1;
                    previous = null;
                    y = geometry.contentTop() + cap + UnitSpacing.gap(null, unit);
                    continue;
                }
                for (int k = 0; k < fit; k++) {
                    int[] range = rows.get(row + k);
                    double x = geometry.contentLeft() + unit.indent() + (row + k > 0 ? hang : 0);
                    lines.add(new Line(i, page, x, y + k * pitch, range[0], range[1], range[2] == 1));
                }
                baseline = y + (fit - 1) * pitch;
                row += fit;
                if (row < rows.size()) {
                    page++;
                    baseline = -1;
                    previous = null;
                    y = geometry.contentTop() + cap + UnitSpacing.gap(null, unit);
                }
            }
            previous = unit;
        }
        return new Layout(page + 1, lines, false, (System.nanoTime() - started) / 1_000_000, List.of());
    }

    /**
     * The character ranges of the lines of a unit's text at a width, broken as Typst breaks
     * them line by line: each paragraph of the text (separated by {@code \n}) is broken at the
     * end that costs least by Typst's own measure of a line — its spaces stretched or shrunk,
     * a hyphen's penalty — among the longest end that fits, the one before it, and the
     * hyphenation points of the word that did not fit; verbatim text at its own line ends only.
     *
     * @param unit  the unit
     * @param width the available width in points
     * @return the lines as {@code {from, to, hyphenated}} over the unit's text, at least one;
     *         {@code hyphenated} is 1 when the line ends in a hyphen the breaker inserted
     */
    public List<int[]> breaks(Unit unit, double width) {
        return breaks(unit, width, unit.kind() != Kind.TABLE_ROW);
    }

    /**
     * As {@link #breaks(Unit, double)}, set as prose or as a table cell: Typst justifies prose,
     * shrinking its spaces when a line is a little too long, and hyphenates it; a table cell
     * is ragged, never shrunk, never hyphenated.
     *
     * @param justified whether the text is justified and hyphenated
     */
    public List<int[]> breaks(Unit unit, double width, boolean justified) {
        List<int[]> out = new ArrayList<>();
        String text = unit.text();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                String paragraph = text.substring(start, i);
                if (unit.kind() == Kind.VERBATIM || paragraph.isEmpty()) {
                    out.add(new int[] {start, i, 0});
                } else {
                    for (int[] range : wrap(paragraph, unit, width, justified)) {
                        out.add(new int[] {start + range[0], start + range[1], range[2]});
                    }
                }
                start = i + 1;
            }
        }
        return out;
    }

    /** Typst's default hyphenation cost ({@code DEFAULT_HYPH_COST}), raised by 15% for every character under five on either side. */
    static final double HYPHEN_COST = 135;
    /** A space stretches by half its width before stretching costs beyond its natural ratio. */
    static final double SPACE_STRETCH = 0.5;
    /** A space shrinks by a third of its width at most. */
    static final double SPACE_SHRINK = 1 / 3.0;

    /**
     * A piece of a paragraph a line may begin at: a word, or what follows a hyphen or a slash
     * inside one. {@code spaced} says whether a space precedes it (a piece after a hyphen has
     * none).
     */
    private record Piece(int from, int to, boolean spaced) { }

    /** The pieces of a paragraph, in order. */
    static List<int[]> pieces(String text) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            boolean spaced = i == 0 || Character.isWhitespace(text.charAt(i - 1));
            int from = i;
            while (i < n && !Character.isWhitespace(text.charAt(i))) {
                i++;
                // UAX #14: a line may break after a hyphen or a dash before a letter, and after a slash before anything but a digit.
                if (i < n && !Character.isWhitespace(text.charAt(i)) && i > from + 1 && breakAfter(text.charAt(i - 1), text.charAt(i))) {
                    break;
                }
            }
            out.add(new int[] {from, i, spaced ? 1 : 0});
            spaced = false;
        }
        return out;
    }

    private static boolean breakAfter(char before, char at) {
        return switch (before) {
            case '-', '\u2010', '\u2013' -> Character.isLetter(at);
            case '/' -> !Character.isDigit(at);
            case '\u2014' -> true;
            default -> false;
        };
    }

    /**
     * Breaks a paragraph as Typst does, a line at a time: the line's pieces are added while
     * they fit (shrinking the spaces a third at most, when justified); then the end that
     * costs least is chosen among that longest line, the line a piece shorter, and the
     * hyphenation points of the piece that did not fit — the head with its hyphen, when
     * Typst hyphenates the text — by Typst's cost: the cube of the stretch ratio a hundredfold,
     * the hyphen's penalty, squared with one.
     */
    private List<int[]> wrap(String text, Unit unit, double avail, boolean justified) {
        List<int[]> out = new ArrayList<>();
        Font font = font(unit);
        double sp = width(" ", font);
        double hyphen = width("-", font);
        List<int[]> pieces = pieces(text);
        if (pieces.isEmpty()) {
            out.add(new int[] {0, text.length(), 0});
            return out;
        }
        int i = 0;
        boolean previousDash = false;
        while (i < pieces.size()) {
            int lineFrom = pieces.get(i)[0];
            // Extend the line while it fits.
            double w = 0;
            int spaces = 0;
            int last = -1;
            double wLast = 0;
            int spacesLast = 0;
            double wBefore = 0;                                   // the line a piece shorter than the longest
            int spacesBefore = 0;
            for (int j = i; j < pieces.size(); j++) {
                int[] piece = pieces.get(j);
                double wj = w + (j > i && piece[2] == 1 ? sp : 0) + width(text.substring(piece[0], piece[1]), font);
                int sj = spaces + (j > i && piece[2] == 1 ? 1 : 0);
                double shrink = justified ? sj * sp * SPACE_SHRINK : 0;
                if (wj > avail + shrink + 1e-6) {
                    break;
                }
                wBefore = wLast;
                spacesBefore = spacesLast;
                last = j;
                wLast = wj;
                spacesLast = sj;
                w = wj;
                spaces = sj;
            }
            if (last < 0) {
                // The first piece alone is too long: hyphenate it where its head fits, else it overflows alone.
                int[] piece = pieces.get(i);
                int cut = -1;
                if (justified && hyphenation) {
                    for (int point : Hyphenator.english().pointsIn(text, piece[0], wordEnd(text, piece[0]))) {
                        if (point > piece[0] && point < piece[1] && !TypstOracle.inRaw(text, point)
                                && width(text.substring(piece[0], point), font) + hyphen <= avail + 1e-6) {
                            cut = point;
                        }
                    }
                }
                if (cut > 0) {
                    out.add(new int[] {piece[0], cut, 1});
                    pieces.set(i, new int[] {cut, piece[1], 0});
                    previousDash = true;
                } else {
                    out.add(new int[] {piece[0], piece[1], 0});
                    previousDash = endsWithDash(text, piece[1]);
                    i++;
                }
                continue;
            }
            if (last == pieces.size() - 1) {
                out.add(new int[] {lineFrom, pieces.get(last)[1], 0});   // the paragraph's last line
                break;
            }
            // The longest line that fits, the line a piece shorter, or a hyphenated head of the piece that did not fit.
            int end = last;
            double best = cost(avail, wLast, spacesLast, sp, unit.fontSize(), justified, -1, -1, previousDash && endsWithDash(text, pieces.get(last)[1]));
            if (last > i) {
                double shorter = cost(avail, wBefore, spacesBefore, sp, unit.fontSize(), justified, -1, -1, previousDash && endsWithDash(text, pieces.get(last - 1)[1]));
                if (shorter < best) {
                    best = shorter;
                    end = last - 1;
                }
            }
            int cut = -1;
            int[] next = pieces.get(last + 1);
            if (justified && hyphenation) {
                int wordEnd = wordEnd(text, next[0]);
                for (int point : Hyphenator.english().pointsIn(text, next[0], wordEnd)) {
                    if (point <= next[0] || point >= next[1] || TypstOracle.inRaw(text, point)) {
                        continue;                                 // Typst never hyphenates inline raw
                    }
                    double head = wLast + (next[2] == 1 ? sp : 0) + width(text.substring(next[0], point), font) + hyphen;
                    int s = spacesLast + (next[2] == 1 ? 1 : 0);
                    if (head > avail + s * sp * SPACE_SHRINK + 1e-6) {
                        break;
                    }
                    int[] lr = syllables(text, point);
                    double c = cost(avail, head, s, sp, unit.fontSize(), true, lr[0], lr[1], previousDash);
                    if (c < best) {
                        best = c;
                        cut = point;
                    }
                }
            }
            if (cut > 0) {
                out.add(new int[] {lineFrom, cut, 1});
                pieces.set(last + 1, new int[] {cut, next[1], 0});
                i = last + 1;
                previousDash = true;
            } else {
                out.add(new int[] {lineFrom, pieces.get(end)[1], 0});
                i = end + 1;
                previousDash = endsWithDash(text, pieces.get(end)[1]);
            }
        }
        return out;
    }

    /** The end of the whitespace-delimited word a piece begins. */
    private static int wordEnd(String text, int from) {
        int end = from;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return end;
    }

    private static boolean endsWithDash(String text, int to) {
        if (to <= 0) {
            return false;
        }
        char c = text.charAt(to - 1);
        return c == '-' || c == '\u2010' || c == '\u2013' || c == '\u2014';
    }

    /** The letters before and after a hyphenation point within its word, Typst's {@code l} and {@code r}. */
    private static int[] syllables(String text, int point) {
        int l = 0;
        for (int k = point - 1; k >= 0 && Character.isAlphabetic(text.charAt(k)); k--) {
            l++;
        }
        int r = 0;
        for (int k = point; k < text.length() && Character.isAlphabetic(text.charAt(k)); k++) {
            r++;
        }
        return new int[] {l, r};
    }

    /**
     * Typst's cost of a line that is not its paragraph's last: the stretch ratio of its spaces
     * — the gap over their natural stretch, beyond one the extra per space over half an em —
     * cubed a hundredfold; an overfull line a million; a hyphen's penalty, raised for short
     * syllables and doubled after a line ending in a dash; the sum with one, squared.
     *
     * @param l the letters before a hyphen, or -1 for a line without one
     * @param r the letters after it
     */
    static double cost(double avail, double width, int spaces, double sp, double fontSize, boolean justified, int l, int r, boolean consecutiveDash) {
        double delta = avail - width;
        if (Math.abs(delta) < 1e-6) {
            delta = 0;
        }
        double stretch = justified ? spaces * sp * SPACE_STRETCH : spaces * sp * SPACE_STRETCH;
        double shrink = justified ? spaces * sp * SPACE_SHRINK : 0;
        double ratio;
        if (delta >= 0) {
            ratio = stretch > 0 ? delta / stretch : delta == 0 ? 0 : Double.POSITIVE_INFINITY;
            if (ratio > 1) {
                ratio = 1 + (delta - stretch) / Math.max(1, spaces) / (fontSize / 2);
            }
        } else {
            ratio = shrink > 0 ? delta / shrink : Double.NEGATIVE_INFINITY;
        }
        ratio = Math.clamp(ratio, -2, 10);
        double minRatio = justified ? -1 : 0;
        double badness = ratio < minRatio ? 1_000_000 : 100 * Math.pow(Math.abs(ratio), 3);
        double penalty = 0;
        if (l >= 0) {
            int steps = Math.max(0, 5 - l) + Math.max(0, 5 - r);
            penalty += (1 + 0.15 * steps) * HYPHEN_COST;
        }
        if (consecutiveDash) {
            penalty += HYPHEN_COST;
        }
        return Math.pow(1 + badness + penalty, 2);
    }

    private final Map<Font, Map<String, Double>> widths = new HashMap<>();

    /** The width of a text in a font, measured once: JavaFX lays Latin text without kerning, so a line's width is its words' and spaces'. */
    double width(String text, Font font) {
        Map<String, Double> own = widths.computeIfAbsent(font, f -> new HashMap<>());
        Double cached = own.get(text);
        if (cached != null) {
            return cached;
        }
        Text node = new Text(text);
        node.setFont(font);
        double w = node.getLayoutBounds().getWidth();
        if (own.size() > 200_000) {
            own.clear();
        }
        own.put(text, w);
        return w;
    }

    /**
     * A table row's height as Typst lays it: its cells share the content width, each wraps in
     * its share less the inset, and the row is as tall as its tallest cell plus the inset.
     */
    double rowHeight(Unit row, Geometry geometry) {
        String[] cells = row.text().split("\u2502");
        double share = geometry.contentWidth() / Math.max(1, cells.length) - 2 * TypstOracle.TABLE_INSET;
        int lines = 1;
        for (String cell : cells) {
            Unit one = new Unit(row.index(), row.block(), Kind.PARAGRAPH, cell.strip(), row.fontSize(), row.bold(), 0, 0, 0, false);
            lines = Math.max(lines, breaks(one, share, false).size());
        }
        return lines * row.fontSize() * PITCH + 2 * UnitSpacing.CELL_INSET;
    }

    /** @return the font a unit is set in */
    public Font font(Unit unit) {
        return Font.font(unit.kind() == Kind.VERBATIM ? monoFamily : family, unit.bold() ? FontWeight.BOLD : FontWeight.NORMAL,
                FontPosture.REGULAR, unit.fontSize());
    }

    /**
     * The width of a text in a unit's font, for the Typst oracle's hyphenation points.
     *
     * @param text the text
     * @param unit the unit
     * @return the width in points
     */
    public double width(String text, Unit unit) {
        return width(text, unit, false, false);
    }

    /** As {@link #width(String, Unit)}, in the mono font at 0.85 of the size for a raw span, bold for a strong one. */
    public double width(String text, Unit unit, boolean mono, boolean bold) {
        Text node = new Text(text);
        node.setFont(mono ? Font.font(monoFamily, bold ? FontWeight.BOLD : FontWeight.NORMAL, FontPosture.REGULAR, unit.fontSize() * 0.85)
                : bold && !unit.bold() ? Font.font(family, FontWeight.BOLD, FontPosture.REGULAR, unit.fontSize()) : font(unit));
        return node.getLayoutBounds().getWidth();
    }
}
