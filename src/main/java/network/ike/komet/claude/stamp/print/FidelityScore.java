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

import network.ike.komet.claude.stamp.print.LayoutOracle.Kind;
import network.ike.komet.claude.stamp.print.LayoutOracle.Layout;
import network.ike.komet.claude.stamp.print.LayoutOracle.Line;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How closely the first pass tracks a golden layout, with each mismatch attributed by
 * elimination to the rule that likeliest caused it: the same words but a different width
 * means measurement or kerning; a break inside a word means hyphenation; a different page for
 * a unit whose lines agree means a keep or spacing rule; the same breaks at different
 * baselines means leading. One aggregate number per document — the share of Typst's lines
 * the first pass breaks exactly — and the breakdown behind it.
 */
public final class FidelityScore {

    private FidelityScore() { }

    /** The rule a mismatch is laid at. */
    public enum Rule {
        /** The same words on the line, but the break fell a word earlier or later: glyph widths or kerning. */
        WIDTH,
        /** Typst broke inside a word the first pass could not. */
        HYPHENATION,
        /** A heading or a short unit kept with what follows, or not. */
        KEEP_TOGETHER,
        /** Two lines kept at a page's foot or head, or not. */
        WIDOW_ORPHAN,
        /** The unit begins on another page though its lines agree: the space between units. */
        SPACING,
        /** The same breaks, but the baselines drift: the line pitch. */
        LEADING,
        /** The lines differ beyond a word: paragraph breaking (Typst's optimized breaks). */
        PARAGRAPH_BREAKING,
        /** The unit was not laid out at all by one side. */
        MISSING
    }

    /**
     * One document's score.
     *
     * @param name          the document
     * @param pagesGolden   Typst's page count
     * @param pagesGreedy   the first pass's
     * @param units         units compared
     * @param unitsSamePage units beginning on the same page in both
     * @param paragraphsSame units whose every line breaks as Typst's
     * @param linesGolden   Typst's lines
     * @param linesSame     first-pass lines that break exactly as a Typst line of the same unit
     * @param offByOneWord  first-pass lines that end one word before or after Typst's
     * @param hyphenMissed  Typst's hyphenated lines the first pass did not reproduce
     * @param attribution   mismatching units by the rule laid at
     */
    public record Document(String name, int pagesGolden, int pagesGreedy, int units, int unitsSamePage, int paragraphsSame, int linesGolden,
                           int linesSame, int offByOneWord, int hyphenMissed, Map<Rule, Integer> attribution) {

        /** @return the aggregate: the share of Typst's lines the first pass breaks exactly, in percent */
        public double score() {
            return linesGolden == 0 ? 100 : 100.0 * linesSame / linesGolden;
        }

        /** @return one line of findings */
        public String text() {
            StringBuilder rules = new StringBuilder();
            attribution.entrySet().stream().filter(e -> e.getValue() > 0).sorted(Map.Entry.<Rule, Integer>comparingByValue().reversed())
                    .forEach(e -> rules.append(' ').append(e.getKey().name().toLowerCase(Locale.ROOT)).append('=').append(e.getValue()));
            return String.format(Locale.ROOT, "%-36s %6.1f%%  pages %2d/%2d  units same page %4d/%4d  paragraphs same %4d  lines %4d/%4d  off by a word %3d  hyphens missed %3d %s",
                    name, score(), pagesGreedy, pagesGolden, unitsSamePage, units, paragraphsSame, linesSame, linesGolden, offByOneWord, hyphenMissed, rules);
        }
    }

    /**
     * The corpus's score: line-weighted over its documents.
     *
     * @param documents the documents
     */
    public record Corpus(List<Document> documents) {

        public double score() {
            int golden = documents.stream().mapToInt(Document::linesGolden).sum();
            int same = documents.stream().mapToInt(Document::linesSame).sum();
            return golden == 0 ? 100 : 100.0 * same / golden;
        }

        public int pagesMatching() {
            return (int) documents.stream().filter(d -> d.pagesGolden() == d.pagesGreedy()).count();
        }

        public Map<Rule, Integer> attribution() {
            Map<Rule, Integer> total = new EnumMap<>(Rule.class);
            for (Document d : documents) {
                d.attribution().forEach((rule, n) -> total.merge(rule, n, Integer::sum));
            }
            return total;
        }

        /** @return the rules most laid at, most first */
        public List<Rule> topRules() {
            return attribution().entrySet().stream().filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.<Rule, Integer>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Document d : documents) {
                sb.append(d.text()).append('\n');
            }
            sb.append(String.format(Locale.ROOT, "corpus: %d document(s), score %.1f%%, pages matching %d, units same page %d of %d, lines %d of %d, attribution %s%n",
                    documents.size(), score(), pagesMatching(), documents.stream().mapToInt(Document::unitsSamePage).sum(),
                    documents.stream().mapToInt(Document::units).sum(), documents.stream().mapToInt(Document::linesSame).sum(),
                    documents.stream().mapToInt(Document::linesGolden).sum(), attribution()));
            return sb.toString();
        }
    }

    /**
     * Scores the first pass against the golden.
     *
     * @param name   the document
     * @param golden Typst's layout
     * @param greedy the first pass's
     * @param units  the units both laid out
     * @return the score
     */
    public static Document score(String name, Layout golden, Layout greedy, List<Unit> units) {
        int samePage = 0;
        int paragraphsSame = 0;
        int linesSame = 0;
        int offByOne = 0;
        int hyphenMissed = 0;
        Map<Rule, Integer> attribution = new EnumMap<>(Rule.class);
        for (Rule rule : Rule.values()) {
            attribution.put(rule, 0);
        }
        int previousAgreed = 1;                                   // whether the unit before began on the same page in both
        double carried = Double.NaN;                              // the baseline offset the unit before carried; NaN when it was not comparable
        for (Unit unit : units) {
            List<Line> g = sorted(golden.ofUnit(unit.index()));
            List<Line> p = sorted(greedy.ofUnit(unit.index()));
            if (g.isEmpty() || p.isEmpty()) {
                if (!g.isEmpty() || !p.isEmpty()) {
                    attribution.merge(Rule.MISSING, 1, Integer::sum);
                }
                continue;
            }
            boolean pageSame = g.getFirst().page() == p.getFirst().page();
            if (pageSame) {
                samePage++;
            }
            boolean breaksSame = g.size() == p.size();
            int same = 0;
            for (Line line : p) {
                if (g.stream().anyMatch(o -> o.from() == line.from() && o.to() == line.to())) {
                    same++;
                }
            }
            linesSame += same;
            for (Line line : g) {
                if (line.hyphenated() && p.stream().noneMatch(o -> o.from() == line.from() && o.to() == line.to())) {
                    hyphenMissed++;
                }
            }
            if (breaksSame) {
                for (int k = 0; k < g.size(); k++) {
                    if (g.get(k).from() != p.get(k).from() || g.get(k).to() != p.get(k).to()) {
                        breaksSame = false;
                        break;
                    }
                }
            }
            if (breaksSame) {
                paragraphsSame++;
                if (!pageSame) {
                    // The lines agree, the page does not: a keep or spacing rule moved the unit.
                    if (previousAgreed == 0) {
                        // the unit before already moved: this one follows it
                    } else if (unit.keepWithNext() || unit.kind() == Kind.HEADING) {
                        attribution.merge(Rule.KEEP_TOGETHER, 1, Integer::sum);
                    } else if (g.size() <= 2 || p.getFirst().page() != p.getLast().page() || g.getFirst().page() != g.getLast().page()) {
                        attribution.merge(Rule.WIDOW_ORPHAN, 1, Integer::sum);
                    } else {
                        attribution.merge(Rule.SPACING, 1, Integer::sum);
                    }
                } else if (drifts(g, p)) {
                    // The same lines, elsewhere on the page: an even offset is the space before the unit, an uneven one the
                    // pitch — unless the unit merely carries the offset an earlier unit on the page already had.
                    double offset = g.getFirst().baseline() - p.getFirst().baseline();
                    if (!evenOffset(g, p)) {
                        attribution.merge(Rule.LEADING, 1, Integer::sum);
                    } else if (!Double.isNaN(carried) && Math.abs(offset - carried) > 0.75) {
                        attribution.merge(Rule.SPACING, 1, Integer::sum);
                    }
                    carried = offset;
                } else {
                    carried = pageSame ? g.getFirst().baseline() - p.getFirst().baseline() : Double.NaN;
                }
            } else {
                // The first line that differs tells the story.
                int k = 0;
                while (k < Math.min(g.size(), p.size()) && g.get(k).from() == p.get(k).from() && g.get(k).to() == p.get(k).to()) {
                    k++;
                }
                Line gl = k < g.size() ? g.get(k) : g.getLast();
                Line pl = k < p.size() ? p.get(k) : p.getLast();
                boolean sameStart = gl.from() == pl.from();
                if (gl.hyphenated() && sameStart) {
                    attribution.merge(Rule.HYPHENATION, 1, Integer::sum);
                } else if (sameStart && oneWordApart(unit.text(), gl.to(), pl.to())) {
                    attribution.merge(Rule.WIDTH, 1, Integer::sum);
                } else {
                    attribution.merge(Rule.PARAGRAPH_BREAKING, 1, Integer::sum);
                }
                for (int i = 0; i < Math.min(g.size(), p.size()); i++) {
                    if (g.get(i).from() == p.get(i).from() && g.get(i).to() != p.get(i).to() && oneWordApart(unit.text(), g.get(i).to(), p.get(i).to())) {
                        offByOne++;
                    }
                }
            }
            previousAgreed = pageSame ? 1 : 0;
            if (!breaksSame || !pageSame) {
                carried = Double.NaN;                             // what follows is offset by this unit's difference, not by a rule
            }
        }
        return new Document(name, golden.pages(), greedy.pages(), units.size(), samePage, paragraphsSame, golden.lines().size(), linesSame,
                offByOne, hyphenMissed, attribution);
    }

    private static List<Line> sorted(List<Line> lines) {
        List<Line> out = new ArrayList<>(lines);
        out.sort(Comparator.comparingInt(Line::from));
        return out;
    }

    /** Whether two line ends differ by exactly one word of the text (one break opportunity between them). */
    static boolean oneWordApart(String text, int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        if (lo == hi || lo < 0 || hi > text.length()) {
            return false;
        }
        String between = text.substring(lo, hi).strip();
        return !between.isEmpty() && !between.contains(" ") && !between.contains("\n");
    }

    /** Whether every line's baseline is off by the same amount: the unit as a whole sits higher or lower. */
    private static boolean evenOffset(List<Line> g, List<Line> p) {
        double first = g.getFirst().baseline() - p.getFirst().baseline();
        for (int k = 1; k < g.size(); k++) {
            if (g.get(k).page() != p.get(k).page() || Math.abs(g.get(k).baseline() - p.get(k).baseline() - first) > 0.75) {
                return false;
            }
        }
        return true;
    }

    /** Whether lines with the same breaks sit at baselines that drift apart on one page. */
    private static boolean drifts(List<Line> g, List<Line> p) {
        for (int k = 0; k < g.size(); k++) {
            if (g.get(k).page() == p.get(k).page() && Math.abs(g.get(k).baseline() - p.get(k).baseline()) > 1.0) {
                return true;
            }
        }
        return false;
    }
}
