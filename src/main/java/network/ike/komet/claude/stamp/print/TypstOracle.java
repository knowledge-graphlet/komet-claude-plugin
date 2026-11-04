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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The second pass: Typst's own layout, read back from the compiled document. The Typst writer
 * labels every word with its text ({@link #WORD_RULE}) and every top-level block with its index;
 * one {@code typst eval} answers each label's page and position — about 50 ms for a topic, no
 * native code, no PDF parsing — and the oracle aligns the words with the units' drawn text
 * character by character, so every Typst line becomes a range of a unit's text at Typst's
 * baseline on Typst's page. A word whose end label sits on a later line than its start was
 * hyphenated: the tail's width, the end label's distance from the line's left edge, picks among
 * the word's hyphenation points ({@link Hyphenator}, Typst's own patterns) the suffix Typst
 * carried over, measured in the same font. What stays inexact: a word without a hyphenation
 * point near the tail's width (inline raw broken at a hyphen or slash of its own, text the
 * alignment maps loosely) falls back to the suffix whose width matches best, table cells are
 * placed as whole rows, and the labels themselves perturb
 * Typst's justification by a fraction of a point — never its line breaks, which the fidelity
 * measurement checks by comparing block positions with and without them. A listing is read
 * differently, from the labels alone ({@link #rowsOf}): Typst never hyphenates it, and its rows
 * break where its text lets them, not where a width says.
 */
public final class TypstOracle implements LayoutOracle {

    /** The label on the metadata before every word, carrying the word's text. */
    public static final String WORD_LABEL = "w";
    /** The label on the metadata after every word, carrying {@code false}. */
    public static final String WORD_END_LABEL = "e";

    /** How many words {@link #RUN_RULE} hands {@link #WORD_LABEL_RULE} at a time: under Typst's limit, in whole words. */
    static final int RUN_WORDS = 256;

    /**
     * The rule that labels words: a label carrying the word's text before it and one after it.
     * Only the end label's position is trusted — it follows the word's glyphs on the word's own
     * line (or the line its hyphenated tail landed on); a start label before a line's first word
     * is left at the end of the line before, and one at a paragraph's start reports the
     * paragraph's top rather than a baseline.
     */
    static final String WORD_LABEL_RULE = "#show regex(\"\\\\S+\"): it => [#metadata(it.text)<" + WORD_LABEL
            + ">#it#metadata(false)<" + WORD_END_LABEL + ">]\n";

    /**
     * The rule that keeps {@link #WORD_LABEL_RULE} under Typst's limit. Typst applies a regex rule
     * to a run of text one match at a time and gives up at 512 matches ("maximum grouping depth
     * exceeded"), which costs the whole document its answer. A run is every word up to the next
     * change of style, however many source lines or hard breaks it crosses, so 512 is a
     * listing's words as much as a long paragraph's. Labels do not end a run: {@code metadata}
     * realizes to nothing. So this rule takes {@link #RUN_WORDS} words at a time and ends each
     * batch with an empty span of changed style, which draws nothing, breaks no line, and sends
     * the batch's words through the labelling rule on their own count. Declared after that rule
     * so it wins where both match, it can spend its own 512 matches on batches, so a run holds
     * 131,072 words. A batch spanning hard breaks arrives as one text, and Typst breaks its lines
     * at the same places. No line moves, and no label that is read: of 132,524 in the fidelity
     * corpus one did, the closing label of a description followed by a nested list, which goes
     * to its word's true end.
     */
    static final String RUN_RULE = "#show regex(\"\\\\S+(?:\\\\s+\\\\S+){0," + (RUN_WORDS - 1)
            + "}\"): it => [#it#text(size: 1em)[]]\n";

    /** The show rules that label words, in order: see {@link #WORD_LABEL_RULE} and {@link #RUN_RULE}. */
    public static final String WORD_RULE = WORD_LABEL_RULE + RUN_RULE;

    /** The expression that lists every label with its value, page and position, in document order. */
    public static final String QUERY = "query(selector(<" + WORD_LABEL + ">).or(<" + WORD_END_LABEL + ">).or(<" + TypstWriter.BLOCK_LABEL
            + ">).or(<" + TypstWriter.END_LABEL + ">)).map(it => (v: it.value, p: it.location().page(), x: it.location().position().x, y: it.location().position().y))";

    /** How close, in points, the best suffix's width must come to the tail's for a hyphenation point to be taken. */
    static final double HYPHEN_TOLERANCE = 1.5;
    /**
     * How much wider, as a share of a word, JavaFX may measure it than Typst set it: it sets no kerning, and a
     * short word can lose a tenth to one pair ("r." in Noto Serif).
     */
    static final double KERNING_SHARE = 0.15;
    /** The inset of a table cell, as the Typst writer sets it. */
    public static final double TABLE_INSET = 5;
    /** The inset of a listing's block, as the Typst writer sets it. */
    public static final double RAW_INSET = 8;

    /**
     * One labelled element: a block's index, a word's text, {@code Boolean.FALSE} for a word's
     * end, or {@code null} for the end of the document.
     *
     * @param value the metadata value
     * @param page  the 0-based page
     * @param x     the position from the page's left edge, in points
     * @param y     the position from the page's top edge, in points: the baseline for an inline label
     */
    public record Item(Object value, int page, double x, double y) { }

    private final Path root;
    private final Path source;
    private final Path fontPath;
    private final Measure width;

    /**
     * @param root     typst's root folder
     * @param source   the source written with {@link TypstWriter.Labels#WORDS}
     * @param fontPath the bundled fonts' folder, or {@code null}
     * @param width    the width of a text in a unit's font, in points, for hyphenation points
     */
    public TypstOracle(Path root, Path source, Path fontPath, Measure width) {
        this.root = root;
        this.source = source;
        this.fontPath = fontPath;
        this.width = width;
    }

    /**
     * Runs typst and aligns its answer; may be called on any thread. A typst failure answers a
     * layout of no lines whose notes carry typst's output.
     */
    @Override
    public Layout layout(List<Unit> units, Geometry geometry) {
        long started = System.nanoTime();
        TypstCompiler.Result result = TypstCompiler.evaluate(root, source, QUERY, fontPath);
        if (!result.ok() || !result.output().stripLeading().startsWith("[")) {
            return new Layout(1, List.of(), false, (System.nanoTime() - started) / 1_000_000, List.of("typst did not answer: " + result.output().strip()));
        }
        Layout aligned = align(parse(result.output()), units, geometry, width);
        return new Layout(aligned.pages(), aligned.lines(), aligned.exact(), (System.nanoTime() - started) / 1_000_000, aligned.notes());
    }

    private static final Pattern ITEM = Pattern.compile(
            "\\{\"v\":(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+|true|false|null),\"p\":(\\d+),\"x\":\"(-?[\\d.]+)pt\",\"y\":\"(-?[\\d.]+)pt\"\\}");

    /**
     * Parses typst's answer to {@link #QUERY}.
     *
     * @param json the JSON array
     * @return the items in document order
     */
    public static List<Item> parse(String json) {
        List<Item> items = new ArrayList<>();
        Matcher m = ITEM.matcher(json);
        while (m.find()) {
            String raw = m.group(1);
            Object value;
            if (raw.startsWith("\"")) {
                value = unescape(raw.substring(1, raw.length() - 1));
            } else if (raw.equals("null")) {
                value = null;
            } else if (raw.equals("true") || raw.equals("false")) {
                value = Boolean.valueOf(raw);
            } else {
                value = Integer.valueOf(raw);
            }
            items.add(new Item(value, Integer.parseInt(m.group(2)) - 1, Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
        }
        return items;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> out.append(n);
            }
        }
        return out.toString();
    }

    /**
     * A word as typst placed it: its start label's position (untrusted, see {@link #WORD_RULE}) and
     * its end label's; {@code endless} when typst dropped the end label, which it does for a word
     * hyphenated across a page break.
     */
    record Word(String text, int page, double x, double y, int endPage, double endX, double endY, boolean endless) {

        Word(String text, int page, double x, double y, int endPage, double endX, double endY) {
            this(text, page, x, y, endPage, endX, endY, false);
        }

        Word withoutEnd() {
            return new Word(text, page, x, y, endPage, endX, endY, true);
        }
    }

    /**
     * Aligns typst's labelled words with the units' drawn text and reads the lines off them. Pure,
     * for tests: no typst, no JavaFX beyond the width function given.
     *
     * @param items    the parsed labels in document order
     * @param units    the units
     * @param geometry the page geometry
     * @param width    the width of a text in a unit's font, in points
     * @return the layout; its notes name what could not be placed exactly
     */
    public static Layout align(List<Item> items, List<Unit> units, Geometry geometry, Measure width) {
        Map<Integer, List<Word>> wordsByBlock = new LinkedHashMap<>();
        int block = -1;
        int pages = 0;
        Word open = null;
        for (Item item : items) {
            pages = Math.max(pages, item.page() + 1);
            if (open != null && !(item.value() instanceof Boolean)) {
                List<Word> own = wordsByBlock.get(block);
                own.set(own.size() - 1, own.getLast().withoutEnd());   // no end label came: typst dropped it
                open = null;
            }
            switch (item.value()) {
                case Integer index -> block = index;
                case String text -> {
                    open = new Word(text, item.page(), item.x(), item.y(), item.page(), item.x(), item.y());
                    wordsByBlock.computeIfAbsent(block, b -> new ArrayList<>()).add(open);
                }
                case Boolean ignored -> {
                    if (open != null) {
                        List<Word> own = wordsByBlock.get(block);
                        if (item.y() < geometry.contentTop() - 1 || item.y() > geometry.contentBottom() + 1) {
                            own.removeLast();                     // a running head's or folio's word, queried in the block it interrupts
                        } else {
                            own.set(own.size() - 1, new Word(open.text(), open.page(), open.x(), open.y(), item.page(), item.x(), item.y()));
                        }
                        open = null;
                    }
                }
                case null, default -> { }
            }
        }
        List<String> notes = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        Map<Integer, List<Unit>> unitsByBlock = new LinkedHashMap<>();
        for (Unit unit : units) {
            unitsByBlock.computeIfAbsent(unit.block(), b -> new ArrayList<>()).add(unit);
        }
        int unplaced = 0;
        List<String> unplacedNames = new ArrayList<>();
        int guessedHyphens = 0;
        for (Map.Entry<Integer, List<Unit>> e : unitsByBlock.entrySet()) {
            List<Word> words = wordsByBlock.getOrDefault(e.getKey(), List.of());
            Map<Unit, List<Placed>> placed = place(words, e.getValue());
            for (Unit unit : e.getValue()) {
                List<Placed> own = placed.getOrDefault(unit, List.of());
                if (own.isEmpty()) {
                    unplaced++;
                    if (unplacedNames.size() < 6) {
                        String text = unit.text().strip();
                        unplacedNames.add(unit.index() + "/" + unit.kind() + " '" + text.substring(0, Math.min(24, text.length())) + "'");
                    }
                    continue;
                }
                if (unit.kind() == Kind.FIGURE) {
                    // Its labels stand before its box, at the box's head: its line's baseline is its foot.
                    Word w = own.getFirst().word();
                    double height = unit.figureHeight() * unit.figureScale(geometry.contentWidth(), geometry.bandHeight());
                    lines.add(new Line(unit.index(), w.endPage(), geometry.contentLeft(), w.endY() + height, 0, unit.text().length(), false));
                    continue;
                }
                if (unit.kind() == Kind.TABLE_ROW || unit.kind() == Kind.OTHER) {
                    // A row's first word begins a cell: its end label's baseline is the row's; its x is the cell's left
                    // edge when the word is the cell's last (typst leaves a paragraph's closing label there), else
                    // the word's width before it.
                    Word w = own.getFirst().word();
                    double x = unit.kind() == Kind.TABLE_ROW ? geometry.contentLeft() + TABLE_INSET : geometry.contentLeft() + unit.indent();
                    lines.add(new Line(unit.index(), w.endPage(), x, w.endY(), 0, unit.text().length(), false));
                    continue;
                }
                if (unit.kind() == Kind.VERBATIM) {
                    rowsOf(unit, own, geometry, width, lines);
                    continue;
                }
                guessedHyphens += linesOf(unit, own, geometry, width, lines);
            }
        }
        if (unplaced > 0) {
            notes.add(unplaced + " unit(s) matched no word of typst's and are not placed: " + unplacedNames);
        }
        if (guessedHyphens > 0) {
            notes.add(guessedHyphens + " hyphenation point(s) named loosely: by a tail's width no suffix matches within the tolerance, or by the fitting width");
        }
        lines.sort((a, b) -> a.page() != b.page() ? Integer.compare(a.page(), b.page())
                : a.unit() != b.unit() ? Integer.compare(a.unit(), b.unit()) : Integer.compare(a.from(), b.from()));
        // A line's left edge comes from its first word's end less that word's width as JavaFX measures it, which
        // runs a point or two wide of Typst's for a kerned or raw-set word: nothing starts left of the measure.
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.x() < geometry.contentLeft()) {
                lines.set(i, new Line(line.unit(), line.page(), geometry.contentLeft(), line.baseline(), line.from(), line.to(), line.hyphenated()));
            }
        }
        return new Layout(Math.max(1, pages), lines, true, 0, notes);
    }

    /** A word mapped into a unit's text. */
    record Placed(Word word, int from, int to) { }

    /**
     * Maps the words of a block to the units' text by aligning the non-blank characters of both,
     * in order, skipping what only one side has — inline markup on the unit's side, a list marker
     * or a smart quote on typst's — by matching three characters ahead.
     */
    static Map<Unit, List<Placed>> place(List<Word> words, List<Unit> units) {
        List<int[]> unitChars = new ArrayList<>();                 // {unit, offset}
        StringBuilder u = new StringBuilder();
        for (int k = 0; k < units.size(); k++) {
            String text = units.get(k).text();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!Character.isWhitespace(c)) {
                    unitChars.add(new int[] {k, i});
                    u.append(normal(c));
                }
            }
        }
        List<int[]> wordChars = new ArrayList<>();                 // {word, offset}
        StringBuilder t = new StringBuilder();
        for (int w = 0; w < words.size(); w++) {
            String text = words.get(w).text();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!Character.isWhitespace(c) && c != '\u200b') {
                    wordChars.add(new int[] {w, i});
                    t.append(normal(c));
                }
            }
        }
        int[] mapped = new int[wordChars.size()];
        java.util.Arrays.fill(mapped, -1);
        int i = 0;
        int j = 0;
        while (i < t.length() && j < u.length()) {
            if (t.charAt(i) == u.charAt(j)) {
                mapped[i] = j;
                i++;
                j++;
                continue;
            }
            // What only one side has — markup on the unit's, a caption or marker on typst's — is skipped;
            // where both could be skipped to meet again, the shorter skip is the likelier.
            int skipUnit = ahead(t, i, u, j, 240);
            int skipWord = ahead(u, j, t, i, 240);
            if (skipUnit >= 0 && (skipWord < 0 || skipUnit <= skipWord)) {
                j += skipUnit;
                continue;
            }
            if (skipWord >= 0) {
                i += skipWord;
                continue;
            }
            i++;
            j++;
        }
        Map<Unit, List<Placed>> out = new HashMap<>();
        for (int w = 0; w < words.size(); w++) {
            int first = -1;
            int last = -1;
            for (int k = 0; k < wordChars.size(); k++) {
                if (wordChars.get(k)[0] == w && mapped[k] >= 0) {
                    if (first < 0) {
                        first = mapped[k];
                    }
                    last = mapped[k];
                }
            }
            if (first < 0) {
                continue;
            }
            int[] start = unitChars.get(first);
            int[] end = unitChars.get(last);
            if (end[0] != start[0]) {
                end = start;
            }
            out.computeIfAbsent(units.get(start[0]), x -> new ArrayList<>()).add(new Placed(words.get(w), start[1], end[1] + 1));
        }
        return out;
    }

    /** The smallest skip d within the limit such that four characters of {@code a} at {@code i} recur in {@code b} at {@code j + d}; -1 when none. */
    private static int ahead(CharSequence a, int i, CharSequence b, int j, int limit) {
        int n = Math.min(4, a.length() - i);
        if (n == 0) {
            return -1;
        }
        for (int d = 1; d <= limit && j + d + n <= b.length(); d++) {
            boolean same = true;
            for (int k = 0; k < n; k++) {
                if (a.charAt(i + k) != b.charAt(j + d + k)) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return d;
            }
        }
        return -1;
    }

    /** Typography both sides agree on: straight quotes and plain hyphens for typst's smart ones. */
    private static char normal(char c) {
        return switch (c) {
            case '\u2018', '\u2019', '\u201a', '\u2032' -> '\'';
            case '\u201c', '\u201d', '\u201e', '\u2033' -> '"';
            case '\u2013', '\u2014', '\u2010', '\u2011', '\u2212' -> '-';
            case '\u2023', '\u25e6', '\u25aa' -> '\u2022';            // list markers of every depth read as one bullet
            case '\u00a0' -> ' ';
            default -> c;
        };
    }

    /**
     * The lines of a unit from its placed words: one per distinct (page, baseline) the words'
     * end labels sit on — except the unit's last word, whose end label Typst leaves at the
     * paragraph's end: it sits on its start label's line when it fits after it there, else on
     * the line after. A line's left edge is its first word's end less that word's width. A word
     * that begins a line but whose end lies closer to the unit's left edge than its own width
     * was hyphenated: its tail is on this line, its head — the prefix left when the suffix whose
     * width matches the tail's is taken away — ends the line before, with a hyphen.
     *
     * @return how many hyphenation points had to be guessed from the fitting width
     */
    private static int linesOf(Unit unit, List<Placed> placed, Geometry geometry, Measure width, List<Line> out) {
        int guessed = 0;
        double cap = unit.fontSize() * CAP_HEIGHT;
        double pitch = unit.fontSize() * PITCH;
        double right = geometry.contentLeft() + geometry.contentWidth();
        // Where each word sits: {page, baseline}, from its end label — except a word followed by a hard
        // break or the paragraph's end, whose end label Typst leaves at the next line's start: it sits on
        // its start label's line when it fits after it there (and the word before it did not end a line
        // too), else on the line after.
        double[][] at = new double[placed.size()][];
        boolean[] bogus = new boolean[placed.size()];
        boolean[] overPage = new boolean[placed.size()];
        for (int k = 0; k < placed.size(); k++) {
            Word w = placed.get(k).word();
            boolean endBogus = k == placed.size() - 1 || breakAfter(unit, placed, k);
            bogus[k] = endBogus;
            if (w.endless() && k > 0 && k + 1 < placed.size()) {
                // Hyphenated across a page break: typst drops the end label, and the tail is unlabelled on the next
                // page's first line. The word keeps the line before's; the tail follows the next word's line.
                overPage[k] = true;
                bogus[k] = true;
                at[k] = at[k - 1];
                continue;
            }
            if (!endBogus) {
                at[k] = new double[] {w.endPage(), w.endY()};
            } else if (k == 0) {
                at[k] = new double[] {w.page(), w.y() + cap};          // a first word: its start label is at the paragraph's top
            } else {
                String text = unit.text().substring(placed.get(k).from(), placed.get(k).to());
                // A justified line runs to the right edge, and a word that began the next one left its start label
                // there: with no room at all it stays off, however its width is reckoned.
                double wide = measure(width, unit, text, placed.get(k).from()) * (1 - KERNING_SHARE);
                boolean inline = !breakAfter(unit, placed, k - 1) && w.page() == (int) at[k - 1][0] && Math.abs(w.y() - at[k - 1][1]) < 0.5
                        && w.x() + wide <= right + 0.5;
                if (inline) {
                    at[k] = at[k - 1];
                } else if (at[k - 1][1] + pitch <= geometry.contentBottom() + 0.5) {
                    at[k] = new double[] {at[k - 1][0], at[k - 1][1] + pitch};
                } else {
                    at[k] = new double[] {at[k - 1][0] + 1, geometry.contentTop() + cap};
                }
            }
        }
        record Key(int page, long y) { }
        Map<Key, List<Integer>> byLine = new LinkedHashMap<>();
        for (int k = 0; k < placed.size(); k++) {
            byLine.computeIfAbsent(new Key((int) at[k][0], Math.round(at[k][1] * 10)), x -> new ArrayList<>()).add(k);
        }
        // The unit's left edge: the lines' provisional left edges mostly agree; a hyphenated tail's first word,
        // measured whole, puts its line's edge too far left, a word after an unlabelled tail too far right, a
        // list marker too far left — so the most frequent value wins, the largest among ties.
        Map<Long, Integer> votes = new HashMap<>();
        List<List<Integer>> groups = new ArrayList<>(byLine.values());
        for (int g = 0; g < groups.size(); g++) {
            int k = groups.get(g).getFirst();
            if (unit.kind() == Kind.LIST_ITEM && g == 0 && groups.size() > 1) {
                continue;                                         // the marker's line sits left of the hang
            }
            if (k == placed.size() - 1 && k > 0) {
                continue;                                         // a last word beginning a line: its start label is elsewhere
            }
            votes.merge(Math.round(provisionalLeft(unit, placed.get(k), k == placed.size() - 1, width) * 2), 1, Integer::sum);
        }
        double left = geometry.contentLeft() + unit.indent();
        int most = 0;
        for (Map.Entry<Long, Integer> e : votes.entrySet()) {
            if (e.getValue() > most || (e.getValue() == most && e.getKey() / 2.0 > left)) {
                most = e.getValue();
                left = e.getKey() / 2.0;
            }
        }
        Map<Key, int[]> ranges = new LinkedHashMap<>();           // {from, to, hyphenated}
        Map<Key, double[]> baselines = new HashMap<>();           // {page, baseline, x}
        int previous = -1;
        for (List<Integer> group : groups) {
            for (int g = 0; g < group.size(); g++) {
                int k = group.get(g);
                Placed p = placed.get(k);
                Word w = p.word();
                Key line = new Key((int) at[k][0], Math.round(at[k][1] * 10));
                String text = unit.text().substring(p.from(), p.to());
                double whole = measure(width, unit, text, p.from());
                boolean last = k == placed.size() - 1;
                if (overPage[k]) {
                    // The tail's width: from the next line's left edge to the next word's start, less the space between.
                    Word next = placed.get(k + 1).word();
                    double tailWidth = next.x() - left - width.width(" ", unit, false, false);
                    Cut byTail = cutByTail(width, unit, text, p.from(), tailWidth);
                    int cut;
                    if (byTail == null || byTail.distance() > HYPHEN_TOLERANCE * 4) {
                        // No suffix comes near the tail's width: the longest head that fits the room before the page break.
                        int[] candidates = hyphenPoints(unit, text, p.from());
                        if (candidates.length == 0) {
                            candidates = ownCuts(text);
                        }
                        if (candidates.length == 0) {
                            // Typst dropped the end label of a word it cannot break: it stands whole on the line before.
                            extend(ranges, baselines, line, (int) at[k][0], at[k][1], Double.MAX_VALUE, p.from(), p.to(), false);
                            previous = k;
                            continue;
                        }
                        guessed++;
                        double room = right - w.x();
                        cut = candidates[0];
                        for (int c : candidates) {
                            if (measure(width, unit, text.substring(0, c) + "-", p.from()) <= room) {
                                cut = c;
                            }
                        }
                    } else {
                        cut = byTail.at();
                        if (byTail.distance() > HYPHEN_TOLERANCE) {
                            guessed++;
                        }
                    }
                    extend(ranges, baselines, line, (int) at[k][0], at[k][1], Double.MAX_VALUE, p.from(), p.from() + cut, true);
                    double[] below = at[k + 1];
                    Key tailLine = new Key((int) below[0], Math.round(below[1] * 10));
                    extend(ranges, baselines, tailLine, (int) below[0], below[1], left, p.from() + cut, p.to(), false);
                    previous = k;
                    continue;
                }
                double tail = w.endX() - left;
                // A tail is at least a couple of characters short of the whole word; JavaFX measures a long word a
                // point or two wider than Typst set it, so the margin grows with the word.
                double slack = Math.max(HYPHEN_TOLERANCE * 2, 0.08 * whole);
                boolean hyphenated = g == 0 && previous >= 0 && !bogus[k] && tail >= 0 && tail < whole - slack && text.length() >= 4;
                if (!hyphenated) {
                    double x = g == 0 ? (last ? left : w.endX() - whole) : Double.MAX_VALUE;
                    extend(ranges, baselines, line, (int) at[k][0], at[k][1], x, p.from(), p.to(), false);
                    previous = k;
                    continue;
                }
                Cut byTail = cutByTail(width, unit, text, p.from(), tail);
                if (byTail == null || byTail.distance() > HYPHEN_TOLERANCE * 4) {
                    // No suffix comes near the tail's width: the word was not hyphenated after all; it stands whole on this line.
                    double x = w.endX() - whole;
                    extend(ranges, baselines, line, (int) at[k][0], at[k][1], x, p.from(), p.to(), false);
                    previous = k;
                    continue;
                }
                int cut = byTail.at();
                if (byTail.distance() > HYPHEN_TOLERANCE) {
                    guessed++;                                    // taken, but the tail's width named it loosely
                }
                Key head = new Key((int) at[previous][0], Math.round(at[previous][1] * 10));
                extend(ranges, baselines, head, (int) at[previous][0], at[previous][1], Double.MAX_VALUE, p.from(), p.from() + cut, true);
                extend(ranges, baselines, line, (int) at[k][0], at[k][1], left, p.from() + cut, p.to(), false);
                previous = k;
            }
        }
        for (Map.Entry<Key, int[]> e : ranges.entrySet()) {
            int[] r = e.getValue();
            double[] b = baselines.get(e.getKey());
            out.add(new Line(unit.index(), (int) b[0], b[2] == Double.MAX_VALUE ? left : b[2], b[1], r[0], r[1], r[2] == 1));
        }
        return guessed;
    }

    /** A row of a listing: its page and its baseline in tenths of a point. */
    private record Row(int page, long y) { }

    /**
     * The rows of a listing, one for each row Typst set, read from its labels alone: a vote on
     * left edges, as a paragraph takes, would be won by the most frequent indent. What the
     * labels give instead, unlike a paragraph's: every
     * word's end label stands right after its glyphs on its row, a source line's last word's
     * too; only the listing's closing label is elsewhere, at the block's left edge on the last
     * row. So the left edge is that label, a character's width is the widest any word implies
     * from its source column (a word on a wrapped row implies less), and a word ending closer to
     * the left edge than its own length was broken: what lies past the edge is its tail. Typst
     * never hyphenates raw text, so a broken word carries no added hyphen; it breaks only where
     * its text lets it, and a tail's length in characters names the cut whatever the character.
     * The rows before a tail run up from it by the pitch.
     */
    private static void rowsOf(Unit unit, List<Placed> placed, Geometry geometry, Measure width, List<Line> out) {
        String text = unit.text();
        int last = placed.size() - 1;
        Word closing = placed.getLast().word();
        double left = closing.endless() ? placed.stream().mapToDouble(p -> p.word().x()).min().orElseThrow() : closing.endX();
        double character = 0;
        double pitch = Double.MAX_VALUE;
        for (int k = 0; k < last; k++) {
            Placed p = placed.get(k);
            Word w = p.word();
            Word next = placed.get(k + 1).word();
            if (w.endless() || next.endless()) {
                continue;
            }
            character = Math.max(character, (w.endX() - left) / (p.to() - lineStart(text, p.from())));
            if (w.endPage() == next.endPage() && next.endY() - w.endY() > 0.5) {
                pitch = Math.min(pitch, next.endY() - w.endY());
            }
        }
        character = character > 0 ? character : width.width(" ", unit, false, false);
        pitch = pitch < Double.MAX_VALUE ? pitch : unit.fontSize() * PITCH;
        double cap = unit.fontSize() * CAP_HEIGHT;
        int columns = Math.max(1, (int) ((geometry.contentLeft() + geometry.contentWidth() - RAW_INSET - left) / character + 0.01));

        Map<Row, int[]> ranges = new LinkedHashMap<>();
        Map<Row, double[]> baselines = new HashMap<>();
        double[] row = null;                                      // {page, baseline} the word before ended on
        for (int k = 0; k <= last; k++) {
            Placed p = placed.get(k);
            Word w = p.word();
            int length = p.to() - p.from();
            int start = lineStart(text, p.from());
            boolean begins = k == 0 || placed.get(k - 1).to() <= start;
            Word before = k == 0 ? null : placed.get(k - 1).word();
            int column = begins || before.endless() ? p.from() - start
                    : (int) Math.round((before.endX() - left) / character) + p.from() - placed.get(k - 1).to();
            double x = begins ? left + (p.from() - start) * character : left;

            // The closing label says nothing of a tail, and a word that crosses a page, broken or whole, keeps no end label.
            boolean told = k < last && !w.endless();
            int tail = told ? (int) Math.round((w.endX() - left) / character) : 0;
            String word = text.substring(p.from(), p.to());
            boolean broken = told ? tail < length
                    : ownCuts(word).length > 0 && column + length > columns && (k > 0 || w.endless() || w.endY() > w.y() + cap + pitch / 2);
            double[] end;
            if (!w.endless()) {
                end = new double[] {w.endPage(), w.endY()};
            } else if (column + length <= columns) {
                end = new double[] {w.page(), w.y()};
            } else if (k < last && placed.get(k + 1).from() > p.to() && lineStart(text, placed.get(k + 1).from()) == start) {
                end = new double[] {placed.get(k + 1).word().endPage(), placed.get(k + 1).word().endY()};
            } else {
                end = new double[] {w.page() + 1, geometry.contentTop() + cap};
            }
            if (!broken) {
                addToRow(ranges, baselines, (int) end[0], end[1], x, p.from(), p.to());
                row = end;
                continue;
            }
            int[] cuts = rowBreaks(word, column, columns, tail, begins);
            for (int c = 0; c <= cuts.length; c++) {
                int from = c == 0 ? 0 : cuts[c - 1];
                int to = c == cuts.length ? length : cuts[c];
                if (from == to) {
                    continue;                                     // the word's head did not fit after the word before
                }
                boolean follows = c == 0 && !begins && row != null;
                int page = follows ? (int) row[0] : (int) end[0];
                double y = follows ? row[1] : end[1] - (cuts.length - c) * pitch;
                if (y < geometry.contentTop() && row != null) {   // over the foot of a page: down from the row before
                    page = (int) row[0];
                    y = row[1] + (begins ? c + 1 : c) * pitch;
                }
                addToRow(ranges, baselines, page, y, c == 0 ? x : left, p.from() + from, p.from() + to);
            }
            row = end;
        }
        for (Map.Entry<Row, int[]> e : ranges.entrySet()) {
            int[] r = e.getValue();
            double[] b = baselines.get(e.getKey());
            out.add(new Line(unit.index(), (int) b[0], b[2], b[1], r[0], r[1], false));
        }
    }

    /** The offset a source line starts at. */
    private static int lineStart(String text, int offset) {
        return text.lastIndexOf('\n', offset - 1) + 1;
    }

    /** Adds a span to a listing's row; the row's left edge is the first span's. */
    private static void addToRow(Map<Row, int[]> ranges, Map<Row, double[]> baselines, int page, double y, double x, int from, int to) {
        Row row = new Row(page, Math.round(y * 10));
        extend(ranges, baselines, row, page, y, ranges.containsKey(row) ? Double.MAX_VALUE : x, from, to, false);
    }

    /**
     * Where a word of a listing broke, as Typst breaks raw text: each row takes the word up to
     * its last break opportunity that fits, and a row that has none takes what fits. The tail,
     * when its length is known, decides the last cut.
     *
     * @param word    the word
     * @param column  the column the word starts at
     * @param columns the characters a row holds
     * @param tail    the characters Typst carried to the word's last row, or 0 when unknown
     * @param begins  whether the word begins its source line, so has nowhere further down to start
     * @return the ascending offsets each later row begins at; a first offset of 0 when the word's
     *         head did not fit on the row it would have started on
     */
    static int[] rowBreaks(String word, int column, int columns, int tail, boolean begins) {
        int[] own = ownCuts(word);
        int end = word.length() - tail;
        List<Integer> cuts = new ArrayList<>();
        int at = 0;
        int room = columns - column;
        boolean fresh = begins;
        while (end - at > room) {
            int cut = at;
            for (int c : own) {
                if (c > at && c <= at + room) {
                    cut = c;
                }
            }
            if (cut == at && fresh) {
                cut = at + room;
            }
            cuts.add(cut);
            at = cut;
            room = columns;
            fresh = true;
        }
        if (tail > 0 && end > at) {
            cuts.add(end);
        }
        return cuts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** A cut of a hyphenated word: the offset, how far the suffix's width lies from the tail's, and whether it is a hyphenation point. */
    record Cut(int at, double distance, boolean point) { }

    /**
     * The cut of a word whose tail Typst carried to the next line: the hyphenation point whose
     * suffix width comes nearest the tail's, when one comes within four times the tolerance;
     * else the nearest of the word's own break opportunities — after a hyphen or slash it
     * carries, which Typst breaks at in inline raw too, or the pieces of a link — unless a
     * point comes nearer still. A word with neither was not hyphenated, whatever its tail's
     * width says: the widths of inline markup and of text the alignment maps loosely are not
     * Typst's, and a word Typst cannot break stands whole on the next line.
     *
     * @return the cut, or {@code null} for a word Typst cannot break
     */
    static Cut cutByTail(Measure width, Unit unit, String text, int offset, double tail) {
        Cut byPoint = nearest(width, unit, text, offset, tail, hyphenPoints(unit, text, offset), true);
        if (byPoint != null && byPoint.distance() <= HYPHEN_TOLERANCE * 4) {
            return byPoint;
        }
        Cut byOwn = nearest(width, unit, text, offset, tail, ownCuts(text), false);
        if (byOwn == null) {
            return byPoint;
        }
        return byPoint != null && byPoint.distance() <= byOwn.distance() ? byPoint : byOwn;
    }

    private static Cut nearest(Measure width, Unit unit, String text, int offset, double tail, int[] candidates, boolean point) {
        Cut best = null;
        for (int c : candidates) {
            if (c < 1 || c >= text.length()) {
                continue;
            }
            double d = Math.abs(measure(width, unit, text.substring(c), offset + c) - tail);
            if (best == null || d < best.distance()) {
                best = new Cut(c, d, point);
            }
        }
        return best;
    }

    /** The hyphenation points of a word of a unit's text, as Typst had them: none inside an inline raw span. */
    static int[] hyphenPoints(Unit unit, String text, int offset) {
        int[] points = Hyphenator.english().pointsIn(text, 0, text.length());
        if (offset < 0) {
            return points;
        }
        return java.util.Arrays.stream(points).filter(p -> !inRaw(unit.text(), offset + p)).toArray();
    }

    /**
     * A word's own line-break opportunities, ascending: after a hyphen or dash or a slash
     * followed by a letter, and after a vertical bar (UAX #14, which Typst applies inside raw as well);
     * in a link, after any character that is not a letter, digit or opening bracket, as Typst
     * breaks links.
     */
    static int[] ownCuts(String text) {
        boolean link = text.contains("://") || text.startsWith("www.");
        List<Integer> cuts = new ArrayList<>();
        for (int c = 1; c < text.length(); c++) {
            char before = text.charAt(c - 1);
            char at = text.charAt(c);
            boolean dash = before == '-' || before == '\u2010' || before == '\u2011' || before == '\u2013' || before == '\u2014' || before == '/';
            if (dash && Character.isLetter(at) || before == '|') {
                cuts.add(c);                                      // UAX #14: no break between a hyphen or slash and a digit
            } else if (link && !Character.isLetterOrDigit(before) && before != '(' && before != '[' && Character.isLetterOrDigit(at)) {
                cuts.add(c);
            }
        }
        return cuts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Whether an offset of a text lies in an inline raw span: between backticks. */
    static boolean inRaw(String text, int offset) {
        int count = 0;
        for (int i = 0; i < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '`') {
                count++;
            }
        }
        return count % 2 == 1;
    }

    /**
     * The width of a span of a unit's text as the page draws it: in the mono font when the span
     * lies between backticks (an inline raw, set at {@link TypstWriter#RAW_SCALE} of the size), bold between
     * asterisks.
     *
     * @param offset the span's offset in the unit's text, or -1 when unknown
     */
    private static double measure(Measure width, Unit unit, String text, int offset) {
        boolean mono = false;
        boolean bold = false;
        if (offset >= 0) {
            String before = unit.text().substring(0, Math.min(offset, unit.text().length()));
            mono = inRaw(unit.text(), offset);
            bold = before.chars().filter(c -> c == '*').count() % 2 == 1;
        }
        return width.width(text, unit, mono, bold);
    }

    /** Whether a hard break — a source line's end, or AsciiDoc's trailing {@code +} — follows a word in the unit's text. */
    private static boolean breakAfter(Unit unit, List<Placed> placed, int k) {
        if (k + 1 >= placed.size()) {
            return false;
        }
        String gap = unit.text().substring(Math.min(placed.get(k).to(), unit.text().length()), Math.min(placed.get(k + 1).from(), unit.text().length()));
        return gap.indexOf('\n') >= 0 || gap.strip().equals("+");
    }

    /** A line's left edge as its first word implies it: the end label less the word's width, or the start label of a last word. */
    private static double provisionalLeft(Unit unit, Placed first, boolean last, Measure width) {
        Word w = first.word();
        if (last) {
            return w.x();
        }
        return w.endX() - measure(width, unit, unit.text().substring(first.from(), first.to()), first.from());
    }

    private static <K> void extend(Map<K, int[]> ranges, Map<K, double[]> baselines, K key, int page, double y, double x, int from, int to, boolean hyphenated) {
        int[] r = ranges.get(key);
        if (r == null) {
            ranges.put(key, new int[] {from, to, hyphenated ? 1 : 0});
            baselines.put(key, new double[] {page, y, x});
        } else {
            r[0] = Math.min(r[0], from);
            r[1] = Math.max(r[1], to);
            r[2] = hyphenated ? 1 : r[2];
            double[] at = baselines.get(key);
            at[2] = Math.min(at[2], x);
        }
        if (hyphenated) {
            ranges.get(key)[2] = 1;
        }
    }
}
