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
package network.ike.komet.claude.anf.element;

import network.ike.komet.claude.stamp.Span;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.anf.Circumstance;

/**
 * A lift scored in two parts against the elements a fixture expects ({@code dev-anf-element-isolation},
 * Testing). The first part scores the isolation on its own: each expected element is matched to at
 * most one isolated element by the overlap of their spans, so recall and precision count elements,
 * and the overlap of each matched pair scores its spans. The second part scores each expected
 * element's statement: whether the element was missed, matched but not lifted, lifted to the
 * expected statement, or lifted to another, and if another, which fields differ. A wrong statement
 * then says whether the fact was missed or mis-encoded.
 *
 * <p>Two hard gates of the ANF plan's phase 2 are counted beside the scores: codes left unresolved
 * in any lifted statement, and polarity flips, a matched statement asserting presence where the
 * expected one asserts absence, or the reverse.
 */
public final class LiftScore {

    private LiftScore() {
    }

    // ── What a fixture expects ───────────────────────────────────────────────

    /**
     * An element a fixture expects.
     *
     * @param id        the fixture's name for it, its expected statement's block id
     * @param sentence  its element sentence
     * @param spans     the narrative's words it rests on
     * @param statement the statement its lift should produce, when the fixture gives one
     */
    public record Expected(String id, String sentence, List<Span> spans, Optional<AnfStatement> statement) {

        public Expected {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(sentence, "sentence");
            if (spans == null || spans.isEmpty()) {
                throw new IllegalArgumentException("an expected element rests on at least one span: " + id);
            }
            spans = spans.stream().sorted().distinct().toList();
            statement = statement == null ? Optional.empty() : statement;
        }
    }

    /**
     * A fixture's expectation of one narrative.
     *
     * @param narrative the narrative the lift reads
     * @param elements  the elements it should isolate
     * @param notScored spans an isolated element may rest on without counting for or against it:
     *                  words the source's statements leave unencoded
     */
    public record Expectation(String narrative, List<Expected> elements, List<Span> notScored) {

        public Expectation {
            Objects.requireNonNull(narrative, "narrative");
            elements = elements == null ? List.of() : List.copyOf(elements);
            notScored = notScored == null ? List.of() : notScored.stream().sorted().distinct().toList();
        }
    }

    // ── The score ────────────────────────────────────────────────────────────

    /** What became of an expected element's statement. */
    public enum Verdict {
        /** No isolated element matched it. */
        MISSED,
        /** An element matched it but has no statement. */
        NOT_LIFTED,
        /** The matched element's statement is the expected one. */
        CORRECT,
        /** The matched element's statement differs from the expected one. */
        MIS_ENCODED,
        /** The fixture gives no statement for it, so only its isolation is scored. */
        UNSCORED
    }

    /**
     * One field the expected and lifted statements disagree on.
     *
     * @param field    the field's name in the {@code [anf]} grammar, or {@code type}
     * @param expected the expected values, empty when the expected statement lacks the field
     * @param lifted   the lifted values, empty when the lifted statement lacks the field
     */
    public record Difference(String field, List<String> expected, List<String> lifted) {
    }

    /**
     * An expected element and what the lift made of it.
     *
     * @param expected    the expected element
     * @param found       the isolated element matched to it, if any
     * @param overlap     the matched spans' overlap, characters in both over characters in either; zero when missed
     * @param exact       whether the matched spans cover exactly the expected characters
     * @param verdict     what became of its statement
     * @param differences the fields a mis-encoded statement gets wrong
     * @param polarityFlip whether the lifted statement asserts presence where absence is expected, or the reverse
     */
    public record Match(Expected expected, Optional<ClinicalElement> found, double overlap, boolean exact,
                        Verdict verdict, List<Difference> differences, boolean polarityFlip) {
    }

    /**
     * A lift's two-part score.
     *
     * @param matches         each expected element, in the fixture's order, with what the lift made of it
     * @param spurious        isolated elements matching no expected element and resting on scored words
     * @param merged          isolated elements over two or more expected elements, one of them left unmatched,
     *                        with the expected elements' ids
     * @param split           expected elements under two or more isolated elements, one of them left unmatched,
     *                        with the isolated elements' ids
     * @param isolatedCount   the elements the isolation produced
     * @param unresolvedCodes values left to clarify across every lifted statement
     */
    public record Report(List<Match> matches, List<ClinicalElement> spurious, Map<String, List<String>> merged,
                         Map<String, List<String>> split, int isolatedCount, int unresolvedCodes) {

        /** Expected elements an isolated element matched. */
        public int matched() {
            return (int) matches.stream().filter(m -> m.found().isPresent()).count();
        }

        /** Matched elements whose spans are exactly the expected ones. */
        public int exact() {
            return (int) matches.stream().filter(Match::exact).count();
        }

        /** Element recall: the share of expected elements the isolation found. */
        public double recall() {
            return ratio(matched(), matches.size());
        }

        /** Element precision: the share of scored isolated elements that match an expected one. */
        public double precision() {
            return ratio(matched(), matched() + spurious.size());
        }

        public double f1() {
            double p = precision();
            double r = recall();
            return p + r == 0 ? 0 : 2 * p * r / (p + r);
        }

        /** The mean overlap of the matched elements' spans. */
        public double spanOverlap() {
            return matches.stream().filter(m -> m.found().isPresent()).mapToDouble(Match::overlap).average().orElse(0);
        }

        /** Expected elements with a statement to score. */
        public int statementsExpected() {
            return (int) matches.stream().filter(m -> m.verdict() != Verdict.UNSCORED).count();
        }

        public long count(Verdict verdict) {
            return matches.stream().filter(m -> m.verdict() == verdict).count();
        }

        /** Statement accuracy end to end: correct statements over every statement expected, a missed element counting wrong. */
        public double statementAccuracy() {
            return ratio((int) count(Verdict.CORRECT), statementsExpected());
        }

        /** Statement accuracy of the elements isolated: correct statements over the statements expected of matched elements. */
        public double statementAccuracyOfMatched() {
            return ratio((int) count(Verdict.CORRECT), statementsExpected() - (int) count(Verdict.MISSED));
        }

        public int polarityFlips() {
            return (int) matches.stream().filter(Match::polarityFlip).count();
        }

        /** Whether the plan's hard gates hold: no code left unresolved, no polarity flipped. */
        public boolean gatesHold() {
            return unresolvedCodes == 0 && polarityFlips() == 0;
        }

        /** The report as plain text, one line per expected element after the totals. */
        public String summary() {
            StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.ROOT,
                    "elements: recall %.2f (%d of %d), precision %.2f (%d spurious of %d isolated), F1 %.2f, "
                            + "span overlap %.2f, %d exact%n",
                    recall(), matched(), matches.size(), precision(), spurious.size(), isolatedCount, f1(),
                    spanOverlap(), exact()));
            s.append(String.format(Locale.ROOT,
                    "statements: %d of %d correct (%.2f; %.2f of those isolated), %d mis-encoded, %d not lifted, %d missed%n",
                    count(Verdict.CORRECT), statementsExpected(), statementAccuracy(), statementAccuracyOfMatched(),
                    count(Verdict.MIS_ENCODED), count(Verdict.NOT_LIFTED), count(Verdict.MISSED)));
            s.append(String.format(Locale.ROOT, "gates: %d unresolved codes, %d polarity flips%n",
                    unresolvedCodes, polarityFlips()));
            for (Match m : matches) {
                s.append("  ").append(m.expected().id()).append(": ").append(m.verdict().name().toLowerCase(Locale.ROOT));
                m.found().ifPresent(f -> s.append(String.format(Locale.ROOT, " (%s, overlap %.2f)", f.id(), m.overlap())));
                if (m.polarityFlip()) {
                    s.append(", polarity flipped");
                }
                for (Difference d : m.differences()) {
                    s.append("\n    ").append(d.field()).append(": expected ").append(d.expected())
                            .append(", lifted ").append(d.lifted());
                }
                s.append('\n');
            }
            spurious.forEach(e -> s.append("  spurious ").append(e.id()).append(": ").append(e.sentence()).append('\n'));
            merged.forEach((id, ids) -> s.append("  merged ").append(id).append(" over ").append(ids).append('\n'));
            split.forEach((id, ids) -> s.append("  split ").append(id).append(" into ").append(ids).append('\n'));
            return s.toString();
        }

        private static double ratio(int numerator, int denominator) {
            return denominator == 0 ? 0 : (double) numerator / denominator;
        }
    }

    // ── Scoring ──────────────────────────────────────────────────────────────

    /** Scores an isolation, and the lift of its elements, against what a fixture expects of the same narrative. */
    public static Report score(Expectation expectation, ElementSet isolated) {
        if (!expectation.narrative().equals(isolated.narrative())) {
            throw new IllegalArgumentException("the isolation read another narrative than the fixture's");
        }
        List<Expected> expected = expectation.elements();
        List<ClinicalElement> found = isolated.elements();

        // every overlapping pair, best first: by span overlap, then by how alike their sentences are
        record Pair(int e, int f, double overlap, double sentences) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (int e = 0; e < expected.size(); e++) {
            for (int f = 0; f < found.size(); f++) {
                double overlap = overlap(expected.get(e).spans(), found.get(f).spans());
                if (overlap > 0) {
                    pairs.add(new Pair(e, f, overlap, wordOverlap(expected.get(e).sentence(), found.get(f).sentence())));
                }
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::overlap).thenComparingDouble(Pair::sentences).reversed());
        Map<Integer, Pair> byExpected = new LinkedHashMap<>();
        Set<Integer> taken = new HashSet<>();
        for (Pair pair : pairs) {
            if (!byExpected.containsKey(pair.e()) && taken.add(pair.f())) {
                byExpected.put(pair.e(), pair);
            }
        }

        List<Match> matches = new ArrayList<>();
        for (int e = 0; e < expected.size(); e++) {
            Expected element = expected.get(e);
            Pair pair = byExpected.get(e);
            if (pair == null) {
                matches.add(new Match(element, Optional.empty(), 0, false,
                        element.statement().isPresent() ? Verdict.MISSED : Verdict.UNSCORED, List.of(), false));
                continue;
            }
            ClinicalElement match = found.get(pair.f());
            boolean exact = covered(element.spans()).equals(covered(match.spans()));
            matches.add(statementMatch(element, match, pair.overlap(), exact));
        }

        List<ClinicalElement> spurious = new ArrayList<>();
        for (int f = 0; f < found.size(); f++) {
            if (!taken.contains(f) && !within(found.get(f).spans(), expectation.notScored())) {
                spurious.add(found.get(f));
            }
        }

        // elements may share words, as an age and a sex share "62-year-old man"; a merge is one that cost a
        // fact, an element over an expected one left unmatched, and a split one that left a piece unmatched
        Map<String, List<String>> merged = new LinkedHashMap<>();
        for (int f = 0; f < found.size(); f++) {
            ClinicalElement element = found.get(f);
            List<Integer> over = new ArrayList<>();
            for (int e = 0; e < expected.size(); e++) {
                if (overlap(expected.get(e).spans(), element.spans()) > 0) {
                    over.add(e);
                }
            }
            if (over.size() > 1 && over.stream().anyMatch(e -> !byExpected.containsKey(e))) {
                merged.put(element.id(), over.stream().map(e -> expected.get(e).id()).toList());
            }
        }
        Map<String, List<String>> split = new LinkedHashMap<>();
        for (Expected element : expected) {
            List<Integer> into = new ArrayList<>();
            for (int f = 0; f < found.size(); f++) {
                if (overlap(element.spans(), found.get(f).spans()) > 0) {
                    into.add(f);
                }
            }
            if (into.size() > 1 && into.stream().anyMatch(f -> !taken.contains(f))) {
                split.put(element.id(), into.stream().map(f -> found.get(f).id()).toList());
            }
        }

        int unresolved = found.stream().flatMap(x -> x.statement().stream())
                .mapToInt(LiftScore::unresolvedCodes).sum();
        return new Report(matches, spurious, merged, split, found.size(), unresolved);
    }

    private static Match statementMatch(Expected element, ClinicalElement match, double overlap, boolean exact) {
        if (element.statement().isEmpty()) {
            return new Match(element, Optional.of(match), overlap, exact, Verdict.UNSCORED, List.of(), false);
        }
        if (match.statement().isEmpty()) {
            return new Match(element, Optional.of(match), overlap, exact, Verdict.NOT_LIFTED, List.of(), false);
        }
        AnfStatement want = element.statement().get();
        AnfStatement got = match.statement().get();
        List<Difference> differences = differences(want, got);
        return new Match(element, Optional.of(match), overlap, exact,
                differences.isEmpty() ? Verdict.CORRECT : Verdict.MIS_ENCODED, differences, polarityFlip(want, got));
    }

    // ── Spans ────────────────────────────────────────────────────────────────

    /** Characters both span lists cover over characters either covers. */
    static double overlap(List<Span> a, List<Span> b) {
        BitSet left = covered(a);
        BitSet right = covered(b);
        BitSet both = (BitSet) left.clone();
        both.and(right);
        BitSet either = (BitSet) left.clone();
        either.or(right);
        return either.isEmpty() ? 0 : (double) both.cardinality() / either.cardinality();
    }

    private static BitSet covered(List<Span> spans) {
        BitSet bits = new BitSet();
        spans.forEach(s -> bits.set(s.start(), s.end()));
        return bits;
    }

    private static boolean within(List<Span> spans, List<Span> outer) {
        if (outer.isEmpty()) {
            return false;
        }
        BitSet inner = covered(spans);
        inner.andNot(covered(outer));
        return inner.isEmpty();
    }

    private static double wordOverlap(String a, String b) {
        Set<String> left = words(a);
        Set<String> right = words(b);
        Set<String> both = new HashSet<>(left);
        both.retainAll(right);
        Set<String> either = new HashSet<>(left);
        either.addAll(right);
        return either.isEmpty() ? 0 : (double) both.size() / either.size();
    }

    private static Set<String> words(String sentence) {
        Set<String> words = new HashSet<>();
        for (String word : sentence.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    // ── Statements ───────────────────────────────────────────────────────────

    private static final Pattern GROUNDED = Pattern.compile("k:[^\\[\\s]*\\[[^\\]]*\\]\\s*(@id=\\S+)");
    private static final Pattern CANDIDATE = Pattern.compile("candidate\\[(.*?)\\]\\s*disp=\\S+.*$");

    /**
     * The fields two statements disagree on. A grounded value compares by its concept, never its
     * label; a candidate by its label, case aside, never its disposition or nearest matches.
     */
    static List<Difference> differences(AnfStatement expected, AnfStatement lifted) {
        Map<String, List<String>> want = fields(expected);
        Map<String, List<String>> got = fields(lifted);
        Set<String> names = new java.util.LinkedHashSet<>(want.keySet());
        names.addAll(got.keySet());
        List<Difference> differences = new ArrayList<>();
        for (String name : names) {
            List<String> w = want.getOrDefault(name, List.of());
            List<String> g = got.getOrDefault(name, List.of());
            if (!w.equals(g)) {
                differences.add(new Difference(name, w, g));
            }
        }
        return differences;
    }

    /** A statement's fields by name, each field's values normalized and in order. */
    static Map<String, List<String>> fields(AnfStatement statement) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put("type", List.of(statement.statementType().name().toLowerCase(Locale.ROOT)));
        String adoc = AnfAdoc.toAdoc(statement);
        for (String line : adoc.split("\n")) {
            int sep = line.indexOf(":: ");
            if (sep <= 0) {
                continue;
            }
            fields.computeIfAbsent(line.substring(0, sep), k -> new ArrayList<>())
                    .add(normalized(line.substring(sep + 3).strip()));
        }
        fields.replaceAll((name, values) -> values.stream().sorted().toList());
        return fields;
    }

    private static String normalized(String value) {
        Matcher grounded = GROUNDED.matcher(value);
        String v = grounded.replaceAll("$1");
        Matcher candidate = CANDIDATE.matcher(v);
        if (candidate.find()) {
            v = v.substring(0, candidate.start()) + "candidate[" + candidate.group(1).strip().toLowerCase(Locale.ROOT) + "]";
        }
        return v;
    }

    private static int unresolvedCodes(AnfStatement statement) {
        int n = 0;
        for (String line : AnfAdoc.toAdoc(statement).split("\n")) {
            if (line.contains(":: clarify[")) {
                n++;
            }
        }
        return n;
    }

    // ── Polarity ─────────────────────────────────────────────────────────────

    private enum Polarity { PRESENT, ABSENT, OPEN }

    /** Whether one statement asserts presence where the other asserts absence. */
    static boolean polarityFlip(AnfStatement expected, AnfStatement lifted) {
        Polarity want = polarity(expected);
        Polarity got = polarity(lifted);
        return want != Polarity.OPEN && got != Polarity.OPEN && want != got;
    }

    /** Absence is the point zero; presence is a result that excludes zero from below. */
    private static Polarity polarity(AnfStatement statement) {
        if (!(statement.circumstance() instanceof Circumstance.Performance p) || p.result() == null) {
            return Polarity.OPEN;
        }
        AnfStatement.Result r = p.result();
        Double lower = r.lowerBound();
        Double upper = r.upperBound();
        if (lower != null && upper != null && lower == 0 && upper == 0) {
            return Polarity.ABSENT;
        }
        if (lower != null && (lower > 0 || (lower == 0 && !r.includeLowerBound()))) {
            return Polarity.PRESENT;
        }
        return Polarity.OPEN;
    }
}
