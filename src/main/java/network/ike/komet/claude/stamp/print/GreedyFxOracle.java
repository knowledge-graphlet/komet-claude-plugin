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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The first pass: greedy line breaking over JavaFX's glyph widths, answered at once. Each
 * paragraph of a unit is broken a line at a time at the content width in the unit's font, each
 * line end chosen by Typst's own cost of a line — the stretch or shrink of its spaces, the
 * penalty of a hyphen — among the longest end that fits, the one before it and the hyphenation
 * points of the word that did not fit ({@link Hyphenator}: Typst's patterns and bounds); the
 * lines are placed down the pages as Typst places them — the
 * first baseline a cap height below the content top, baselines a pitch apart, units a paragraph's
 * spacing apart — with a heading kept with the two lines after it and two lines kept at the foot
 * and head of a split unit, as Typst's sticky headings and its orphan and widow costs do; a
 * table breaks between its rows, keeping its caption with its header and first row and setting
 * its header again on every page it reaches, a line of the header's own. What
 * Typst's optimized breaks still do differently — a line broken short so a later one breaks
 * well — the second pass corrects. Must be used on the JavaFX application thread, or on a
 * thread that may lay text out. A line carrying a footnote's mark takes the note's room from the
 * foot of its page, and a line whose note does not fit moves on, as in Typst; a note is kept
 * whole here, where Typst may split one over a page. Words are measured in the fonts their
 * inline spans are drawn in.
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
        Foot foot = new Foot(units, geometry);
        List<Unit> header = List.of();                            // the header rows of the table being set
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            if (unit.kind() == Kind.FOOTNOTE) {
                continue;                                         // placed with the page its mark lands on
            }
            double pitch = UnitSpacing.pitch(unit);
            double cap = unit.fontSize() * CAP_HEIGHT;
            if (unit.kind() == Kind.FIGURE) {
                // Whole on a page, as Typst sets it; its line's baseline is its foot.
                double height = unit.figureHeight() * unit.figureScale(geometry.contentWidth(), geometry.bandHeight());
                double top = baseline < 0 ? geometry.contentTop() : baseline + UnitSpacing.gap(previous, unit);
                if (baseline >= 0 && top + height > foot.bottom() + 0.01) {
                    foot.close(page++, lines);
                    top = geometry.contentTop();
                }
                lines.add(new Line(i, page, geometry.contentLeft(), top + height, 0, unit.text().length(), false));
                baseline = top + height;
                previous = unit;
                continue;
            }
            if (unit.kind() == Kind.TABLE_ROW) {
                // A table breaks between its rows, each kept whole, its header on every page it reaches.
                boolean opens = opensTable(units, i);
                if (opens) {
                    header = headerRows(units, i);
                }
                double height = rowHeight(unit, geometry);
                double first = baseline < 0 ? geometry.contentTop() + cap + UnitSpacing.gap(null, unit) : baseline + UnitSpacing.gap(previous, unit) + cap;
                double kept = opens ? tableHead(units, i, geometry) : height;
                boolean fits = foot.fit(unit, List.of(new int[] {0, unit.text().length()}), 0, first - cap - UnitSpacing.CELL_INSET + kept, 0) > 0;
                if (baseline >= 0 && !fits && (opens || !header.contains(unit)) && height <= geometry.bandHeight()) {
                    foot.close(page++, lines);
                    baseline = opens ? -1 : repeatHeader(header, page, geometry, lines);
                    previous = opens || header.isEmpty() ? null : header.getLast();
                    first = baseline < 0 ? geometry.contentTop() + cap + UnitSpacing.gap(null, unit) : baseline + UnitSpacing.gap(previous, unit) + cap;
                }
                double last = setRow(unit, page, first, geometry, lines);
                foot.take(unit, new int[] {0, unit.text().length()});
                double end = last + UnitSpacing.CELL_INSET;
                while (end > geometry.contentBottom() + 0.01) {
                    // Only a row taller than a page gets here, which Typst breaks: it runs on under the header.
                    double over = end - geometry.contentBottom();
                    foot.close(page++, lines);
                    double above = repeatHeader(header, page, geometry, lines);
                    end = (above < 0 ? geometry.contentTop() : above + UnitSpacing.CELL_INSET) + over;
                }
                baseline = end - UnitSpacing.CELL_INSET;
                previous = unit;
                continue;
            }
            // A list item hangs its lines after the first under its text, past the marker.
            double hang = unit.kind() == Kind.LIST_ITEM ? unit.fontSize() * 0.9 : 0;
            List<int[]> rows = breaks(unit, (geometry.contentWidth() - unit.indent()) * measure);
            double first = baseline < 0 ? geometry.contentTop() + cap + UnitSpacing.gap(null, unit) : baseline + UnitSpacing.gap(previous, unit) + cap;
            double height = (rows.size() - 1) * pitch;
            Unit following = nextInFlow(units, i);
            double companion = unit.keepWithNext() && following != null
                    ? UnitSpacing.gap(unit, following) + following.fontSize() * (CAP_HEIGHT + PITCH) : 0;
            if (following != null && following.kind() == Kind.TABLE_ROW && following.block() == unit.block()) {
                // A caption stays with the rows its table will not leave at a page's foot.
                companion = UnitSpacing.gap(unit, following) - UnitSpacing.CELL_INSET + tableHead(units, following.index(), geometry);
            }
            boolean splittable = unit.kind() == Kind.PARAGRAPH || unit.kind() == Kind.LIST_ITEM;
            boolean whole = !splittable || rows.size() <= 2 || unit.keepWithNext();
            if (baseline >= 0 && first + (whole ? height + companion : pitch) > foot.bottom() + 0.01) {
                foot.close(page++, lines);
                baseline = -1;
                previous = null;
                first = geometry.contentTop() + cap + UnitSpacing.gap(null, unit);
            }
            int row = 0;
            double y = first;
            while (row < rows.size()) {
                int remaining = rows.size() - row;
                int fit = foot.fit(unit, rows, row, y, pitch);
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
                    foot.close(page++, lines);
                    baseline = -1;
                    previous = null;
                    y = geometry.contentTop() + cap + UnitSpacing.gap(null, unit);
                    continue;
                }
                for (int k = 0; k < fit; k++) {
                    int[] range = rows.get(row + k);
                    double x = geometry.contentLeft() + unit.indent() + (row + k > 0 ? hang : 0);
                    lines.add(new Line(i, page, x, y + k * pitch, range[0], range[1], range[2] == 1));
                    foot.take(unit, range);
                }
                baseline = y + (fit - 1) * pitch;
                row += fit;
                if (row < rows.size()) {
                    foot.close(page++, lines);
                    baseline = -1;
                    previous = null;
                    y = geometry.contentTop() + cap + UnitSpacing.gap(null, unit);
                }
            }
            previous = unit;
        }
        foot.close(page, lines);
        lines.sort(Comparator.comparingInt(Line::page).thenComparingInt(Line::unit).thenComparingInt(Line::from));
        return new Layout(page + 1, lines, false, (System.nanoTime() - started) / 1_000_000, List.of());
    }

    /** Whether a row is its table's first: a table's notes stand between its rows. */
    private static boolean opensTable(List<Unit> units, int row) {
        for (int i = row - 1; i >= 0; i--) {
            if (units.get(i).kind() != Kind.FOOTNOTE) {
                return units.get(i).kind() != Kind.TABLE_ROW || units.get(i).block() != units.get(row).block();
            }
        }
        return true;
    }

    /** The header rows of the table a row opens: the bold rows it starts with. */
    private static List<Unit> headerRows(List<Unit> units, int row) {
        List<Unit> out = new ArrayList<>();
        for (int i = row; i < units.size() && units.get(i).block() == units.get(row).block(); i++) {
            Unit unit = units.get(i);
            if (unit.kind() == Kind.FOOTNOTE) {
                continue;
            }
            if (unit.kind() != Kind.TABLE_ROW || !unit.bold()) {
                break;
            }
            out.add(unit);
        }
        return out;
    }

    /** What a table must have room for to start on a page, as Typst keeps it: its header and its first row. */
    private double tableHead(List<Unit> units, int row, Geometry geometry) {
        List<Unit> header = headerRows(units, row);
        double height = header.stream().mapToDouble(h -> rowHeight(h, geometry)).sum();
        for (int i = header.isEmpty() ? row : header.getLast().index() + 1; i < units.size() && units.get(i).block() == units.get(row).block(); i++) {
            if (units.get(i).kind() == Kind.TABLE_ROW) {
                return height + rowHeight(units.get(i), geometry);
            }
        }
        return height;
    }

    /** Sets a table's header rows at a page's top; @return the last's last baseline, -1 for a table with none */
    private double repeatHeader(List<Unit> header, int page, Geometry geometry, List<Line> lines) {
        double baseline = -1;
        Unit previous = null;
        for (Unit row : header) {
            double cap = row.fontSize() * CAP_HEIGHT;
            double first = baseline < 0 ? geometry.contentTop() + cap + UnitSpacing.gap(null, row) : baseline + UnitSpacing.gap(previous, row) + cap;
            baseline = setRow(row, page, first, geometry, lines);
            previous = row;
        }
        return baseline;
    }

    /**
     * Sets a row: its line stands on its first cell's first baseline, a cell being centered in
     * its row as the writer sets tables.
     *
     * @param first the row's first baseline, in its tallest cell
     * @return that cell's last baseline
     */
    private double setRow(Unit row, int page, double first, Geometry geometry, List<Line> lines) {
        int[] cells = rowLines(row, geometry);
        double pitch = UnitSpacing.pitch(row);
        lines.add(new Line(row.index(), page, geometry.contentLeft() + TypstOracle.TABLE_INSET, first + (cells[0] - cells[1]) * pitch / 2, 0, row.text().length(), false));
        return first + (cells[0] - 1) * pitch;
    }

    /** The next unit in the flow after an index, a note being placed elsewhere; {@code null} at the end. */
    private static Unit nextInFlow(List<Unit> units, int index) {
        for (int i = index + 1; i < units.size(); i++) {
            if (units.get(i).kind() != Kind.FOOTNOTE) {
                return units.get(i);
            }
        }
        return null;
    }

    /**
     * The foot of the page being filled: the notes whose marks stand on it, stacked up from the
     * content's bottom edge, the last note's last baseline on that edge, each note's first line
     * indented by an em of its own size, a rule and the clearance above the lot.
     */
    private final class Foot {
        private final Geometry geometry;
        private final Map<Integer, Unit> byNumber = new HashMap<>();
        private final Map<Integer, List<int[]>> rowsOf = new HashMap<>();
        private final List<Unit> here = new ArrayList<>();
        private final Set<Integer> placed = new HashSet<>();
        private double height;

        Foot(List<Unit> units, Geometry geometry) {
            this.geometry = geometry;
            for (Unit unit : units) {
                if (unit.kind() == Kind.FOOTNOTE) {
                    byNumber.put(unit.note(), unit);
                }
            }
        }

        /** @return where the page's text must end */
        double bottom() {
            return geometry.contentBottom() - height;
        }

        private List<int[]> rows(Unit note) {
            return rowsOf.computeIfAbsent(note.note(), n -> breaks(note, geometry.contentWidth()));
        }

        /** What one more note adds to a foot of a height. */
        private double added(Unit note, double foot) {
            double em = note.fontSize() / FOOTNOTE_SCALE;
            double own = note.fontSize() * CAP_HEIGHT + (rows(note).size() - 1) * note.fontSize() * PITCH;
            return own + (foot == 0 ? (FOOTNOTE_CLEARANCE + FOOTNOTE_GAP) * em : FOOTNOTE_GAP * em);
        }

        /** @return the note a mark of a unit's row names, for each mark in it not yet placed */
        private List<Unit> marked(Unit unit, int[] row) {
            List<Unit> out = new ArrayList<>();
            for (InlineText.Mark mark : unit.marks()) {
                Unit note = byNumber.get(mark.note());
                if (note != null && mark.at() >= row[0] && mark.at() < row[1] && !placed.contains(mark.note())) {
                    out.add(note);
                }
            }
            return out;
        }

        /** @return how many of a unit's rows from one on fit on the page with their notes, the first at a baseline */
        int fit(Unit unit, List<int[]> rows, int from, double y, double pitch) {
            double foot = height;
            int fit = 0;
            for (int k = from; k < rows.size(); k++) {
                double grown = foot;
                for (Unit note : marked(unit, rows.get(k))) {
                    grown += added(note, grown);
                }
                if (y + fit * pitch > geometry.contentBottom() - grown + 0.01) {
                    break;
                }
                foot = grown;
                fit++;
            }
            return fit;
        }

        /** Takes a row onto the page, with the notes it marks. */
        void take(Unit unit, int[] row) {
            for (Unit note : marked(unit, row)) {
                height += added(note, height);
                here.add(note);
                placed.add(note.note());
            }
        }

        /** Sets the notes of a finished page, which leaves the next one's foot empty. */
        void close(int page, List<Line> lines) {
            double y = geometry.contentBottom();
            for (int n = here.size() - 1; n >= 0; n--) {
                Unit note = here.get(n);
                List<int[]> rows = rows(note);
                double pitch = note.fontSize() * PITCH;
                for (int k = rows.size() - 1; k >= 0; k--) {
                    int[] range = rows.get(k);
                    lines.add(new Line(note.index(), page, geometry.contentLeft() + (k == 0 ? note.fontSize() : 0), y, range[0], range[1], range[2] == 1));
                    y -= pitch;
                }
                y += pitch - note.fontSize() * CAP_HEIGHT - FOOTNOTE_GAP * note.fontSize() / FOOTNOTE_SCALE;
            }
            here.clear();
            height = 0;
        }
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
                    for (int[] range : wrap(paragraph, start, unit, width, justified)) {
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
     *
     * @param base where the paragraph starts in the unit's text
     */
    private List<int[]> wrap(String text, int base, Unit unit, double avail, boolean justified) {
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
                double wj = w + (j > i && piece[2] == 1 ? sp : 0) + width(text, piece[0], piece[1], unit, base, font);
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
                        if (point > piece[0] && point < piece[1] && !unit.monoAt(base + point)
                                && width(text, piece[0], point, unit, base, font) + hyphen <= avail + 1e-6) {
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
                    if (point <= next[0] || point >= next[1] || unit.monoAt(base + point)) {
                        continue;                                 // Typst never hyphenates inline raw
                    }
                    double head = wLast + (next[2] == 1 ? sp : 0) + width(text, next[0], point, unit, base, font) + hyphen;
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

    /** The width of a range of a paragraph, each stretch of it in the font its style is drawn in. */
    private double width(String text, int from, int to, Unit unit, int base, Font font) {
        if (unit.runs().isEmpty()) {
            return width(text.substring(from, to), font);
        }
        double total = 0;
        int at = from;
        while (at < to) {
            InlineText.Run run = unit.runAt(base + at);
            int end = to;
            for (InlineText.Run r : unit.runs()) {
                if (r.from() - base > at && r.from() - base < end) {
                    end = r.from() - base;
                }
                if (r.to() - base > at && r.to() - base < end) {
                    end = r.to() - base;
                }
            }
            total += width(text.substring(at, end), run == null ? font : font(unit, run.mono(), run.bold(), run.italic()));
            at = end;
        }
        return total;
    }

    /**
     * A table row's height as Typst lays it: its cells share the content width by its table's
     * column widths, each wraps in its share less the inset, and the row is as tall as its
     * tallest cell plus the inset.
     */
    double rowHeight(Unit row, Geometry geometry) {
        return (rowLines(row, geometry)[0] - 1) * UnitSpacing.pitch(row) + row.fontSize() * CAP_HEIGHT + 2 * UnitSpacing.CELL_INSET;
    }

    /** @return the lines of a row's tallest cell, and of its first */
    private int[] rowLines(Unit row, Geometry geometry) {
        String[] cells = row.text().split("\u2502");
        int total = 0;
        for (int c = 0; c < cells.length; c++) {
            total += c < row.columns().size() ? row.columns().get(c) : 1;
        }
        int tallest = 1;
        int first = 1;
        for (int c = 0; c < cells.length; c++) {
            Unit one = new Unit(row.index(), row.block(), Kind.PARAGRAPH, cells[c].strip(), row.fontSize(), row.bold(), 0, 0, 0, false);
            double share = geometry.contentWidth() * (c < row.columns().size() ? row.columns().get(c) : 1) / total;
            int lines = breaks(one, share - 2 * TypstOracle.TABLE_INSET, false).size();
            tallest = Math.max(tallest, lines);
            first = c == 0 ? lines : first;
        }
        return new int[] {tallest, first};
    }

    /** @return the font a unit is set in */
    public Font font(Unit unit) {
        return Font.font(unit.kind() == Kind.VERBATIM ? monoFamily : family, unit.bold() ? FontWeight.BOLD : FontWeight.NORMAL,
                FontPosture.REGULAR, unit.fontSize());
    }

    /** @return the font of a unit's span: inline raw in the mono font at {@link TypstWriter#RAW_SCALE} of the size */
    private Font font(Unit unit, boolean mono, boolean bold, boolean italic) {
        return Font.font(mono ? monoFamily : family, bold || unit.bold() ? FontWeight.BOLD : FontWeight.NORMAL,
                italic ? FontPosture.ITALIC : FontPosture.REGULAR, mono ? unit.fontSize() * TypstWriter.RAW_SCALE : unit.fontSize());
    }

    /**
     * The width of a text in a unit's font, for the Typst oracle's hyphenation points.
     *
     * @param text the text
     * @param unit the unit
     * @return the width in points
     */
    public double width(String text, Unit unit) {
        return width(text, font(unit));
    }

    /** As {@link #width(String, Unit)}, in a span's font: {@link LayoutOracle.Measure}. */
    public double width(String text, Unit unit, boolean mono, boolean bold, boolean italic) {
        return width(text, mono || bold || italic ? font(unit, mono, bold, italic) : font(unit));
    }
}
