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
package network.ike.komet.claude.benefitrisk;

import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.anf.AnfValue;
import network.ike.komet.claude.anf.Circumstance;
import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.Tokens;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code [findings]} block: the entries of the addition to the Device Extension that
 * ike-lab-documents topic {@code rda-dex-findings} proposes. DeX holds no such findings today, so
 * this block stands in for it, a mock a document must say is one.
 *
 * <p>A finding is two acts. What someone observed is an ANF statement, an {@code [anf]} block of the
 * document, which holds the topic, the group, the result, the method, the period and the year in
 * print. Entering that statement against a hazard and a material is a curator's act, and that is
 * the entry here, filed by hazard and material and not by the device the statement was made about,
 * so it can reach the review of another device. How many a share is of is a statement of its own,
 * because each value is, and the source stays on the entry until an anf block writes associations.
 *
 * @param all      the findings, as entered
 * @param problems what could not be read
 */
public record Findings(List<Finding> all, List<String> problems) {

    /** The block's style. */
    public static final String TAG = "findings";

    /** The measure of a share of a group, a term proposed for the starter set. */
    private static final String SHARE = "population share";
    /** The start of the name of the presence scale. */
    private static final String PRESENCE = "presence";
    /** How an entry names a statement of the document. */
    private static final String REFERENCE = "anf:";
    private static final String NOT_STATED = "Not stated here";
    /** The label an offline read keeps of an identifier it could not resolve. */
    private static final Pattern UNRESOLVED_LABEL = Pattern.compile("\\[(.*)\\]\\s*$");
    /** A store's semantic tag or tags after a concept's name. */
    private static final Pattern SEMANTIC_TAG = Pattern.compile("(\\s*\\([^)]*\\))+\\s*$");

    /**
     * A concept named in a block.
     *
     * @param id   its identifier
     * @param name its name
     */
    public record Named(String id, String name) {
    }

    /**
     * One finding.
     *
     * @param id           its identifier, by which an arm of an effects table cites it
     * @param statement    what was found, in a sentence, as the entry reads
     * @param hazard       the hazard it is entered against, when it shows one
     * @param material     the material it is entered against
     * @param statementName the name of the anf block that states it
     * @param countName    the name of the anf block that states how many a share is of
     * @param topic        what was observed
     * @param group        the group it was observed in, which holds its conditions
     * @param method       how it was observed
     * @param inPrint      the year it was in print
     * @param counted      what was counted, in the words of a table's cell
     * @param period       the time over which it was counted
     * @param source       the source, as a short citation
     * @param result       the word a gate reads
     * @param sharePercent the share counted, in percent
     * @param among        how many the share is of
     * @param unit         what the share counts
     * @param narrative    whether it holds narrative only, having no scale yet
     */
    public record Finding(String id, String statement, Optional<Named> hazard, Optional<Named> material, String statementName,
                          Optional<String> countName, String topic, String group, String method, int inPrint, String counted,
                          String period, String source, Optional<String> result, OptionalDouble sharePercent, OptionalInt among,
                          String unit, boolean narrative) {

        /** What was counted and among how many, in words. */
        public String countedAmong() {
            return among.isPresent() && !counted.startsWith("All ") && !counted.startsWith("None ")
                    ? counted + ", among " + among.getAsInt() + " " + unit
                    : among.isPresent() ? counted + " " + unit : counted;
        }

        /** What a cell of an effects table says: what a gate reads names what was observed, since the row does not. */
        public String cell() {
            if (result.isEmpty()) {
                return counted;
            }
            String count = counted.isEmpty() ? "" : counted.substring(0, 1).toLowerCase(Locale.ROOT) + counted.substring(1);
            return topic + ": " + count + (NOT_STATED.equals(period) ? "" : ", " + period);
        }
    }

    /**
     * Reads a block's entries, each over the statement it names.
     *
     * @param body       the block's content
     * @param statements the statements of the document, by the names their blocks give them
     * @return the findings, and what could not be read
     */
    public static Findings parse(String body, Function<String, Optional<AnfStatement>> statements) {
        BlockLines.Parsed lines = BlockLines.parse(body);
        List<String> problems = new ArrayList<>(lines.problems());
        List<Finding> all = new ArrayList<>();
        for (BlockLines.Line line : lines.roots()) {
            Optional<Tokens.Concept> concept = Tokens.concept(line.head());
            if (!"finding".equals(line.word()) || concept.isEmpty()) {
                problems.add("line " + line.number() + ": not a finding over a concept: " + line.rest());
                continue;
            }
            read(line, concept.get(), statements, problems).ifPresent(all::add);
        }
        return new Findings(all, problems);
    }

    private static Optional<Finding> read(BlockLines.Line line, Tokens.Concept concept, Function<String, Optional<AnfStatement>> statements,
                                          List<String> problems) {
        String sentence = concept.label();
        Map<String, String> values = new java.util.HashMap<>();
        Optional<Named> hazard = Optional.empty();
        Optional<Named> material = Optional.empty();
        Optional<String> named = Optional.empty();
        Optional<String> countName = Optional.empty();
        for (BlockLines.Line child : line.children()) {
            if ("statement".equals(child.word())) {
                named = reference(child.head());
                countName = child.attribute("among").flatMap(Findings::reference);
            } else if (child.form() == BlockLines.Form.VALUE) {
                values.put(child.word(), Tokens.unquote(child.rest()));
            } else if ("hazard".equals(child.word())) {
                hazard = named(child);
            } else if ("material".equals(child.word())) {
                material = named(child);
            }
        }
        if (named.isEmpty()) {
            problems.add(sentence + " names no statement.");
            return Optional.empty();
        }
        Optional<AnfStatement> stated = statements.apply(named.get());
        if (stated.isEmpty()) {
            problems.add(sentence + " names anf:" + named.get() + ", which no anf block of this document states.");
            return Optional.empty();
        }
        AnfStatement statement = stated.get();
        if (statement.circumstance() instanceof Circumstance.Request) {
            problems.add(sentence + " names a request, and a finding is what someone observed.");
            return Optional.empty();
        }
        if (statement.statementTime() == null || statement.statementTime().lowerBound() == null) {
            problems.add(sentence + " does not say when it was in print: its statement has no statement time.");
            return Optional.empty();
        }
        String group = label(statement.subjectOfInformation());
        if (group.isEmpty()) {
            problems.add(sentence + " says no group: its statement has no subject of information.");
        }
        if (!values.containsKey("source")) {
            problems.add(sentence + " has no source.");
        }
        OptionalInt among = OptionalInt.empty();
        String unit = "";
        if (countName.isPresent()) {
            Optional<AnfStatement> count = statements.apply(countName.get());
            Optional<AnfStatement.Result> counting = count.flatMap(Findings::resultOf).filter(r -> r.lowerBound() != null);
            if (counting.isEmpty()) {
                problems.add(sentence + " names anf:" + countName.get() + " for its count, which no anf block of this document states.");
            } else {
                among = OptionalInt.of((int) Math.round(counting.get().lowerBound()));
                unit = label(counting.get().measureSemantic()).toLowerCase(Locale.ROOT);
                if (!label(count.get().subjectOfInformation()).equals(group)) {
                    problems.add(sentence + " counts another group: anf:" + countName.get() + " has a different subject of information.");
                }
            }
        }
        Optional<AnfStatement.Result> result = resultOf(statement);
        OptionalDouble share = result.filter(Findings::isShare).filter(r -> r.lowerBound() != null && r.lowerBound().equals(r.upperBound()))
                .map(r -> OptionalDouble.of(percent(r.lowerBound()))).orElse(OptionalDouble.empty());
        boolean narrative = statement.circumstance() instanceof Circumstance.Narrative;
        OptionalInt size = among;
        String counted = statement.circumstance() instanceof Circumstance.Narrative words ? words.text()
                : result.map(r -> counted(r, size)).orElse(NOT_STATED);
        String method = statement.circumstance() instanceof Circumstance.Performance performance ? label(performance.method()) : "";
        return Optional.of(new Finding(concept.identifier().orElse(concept.key()), sentence, hazard, material, named.get(), countName,
                label(statement.topic()), group, method.isEmpty() ? NOT_STATED : method, (int) Math.round(statement.statementTime().lowerBound()),
                counted, period(statement.circumstance().timing()), values.getOrDefault("source", ""), Optional.ofNullable(values.get("gate")),
                share, among, unit, narrative));
    }

    private static Optional<String> reference(String text) {
        String name = text.strip();
        return name.startsWith(REFERENCE) && name.length() > REFERENCE.length() ? Optional.of(name.substring(REFERENCE.length())) : Optional.empty();
    }

    private static Optional<AnfStatement.Result> resultOf(AnfStatement statement) {
        return statement.circumstance() instanceof Circumstance.Performance performance ? Optional.ofNullable(performance.result()) : Optional.empty();
    }

    private static boolean isShare(AnfStatement.Result result) {
        return label(result.measureSemantic()).equalsIgnoreCase(SHARE);
    }

    private static double percent(double share) {
        return Math.round(share * 1000) / 10.0;
    }

    /** A result in the words of a table's cell. */
    private static String counted(AnfStatement.Result result, OptionalInt among) {
        Double low = result.lowerBound();
        Double high = result.upperBound();
        if (isShare(result) && low != null && high != null) {
            if (!low.equals(high)) {
                return Tokens.number(percent(low)) + " to " + Tokens.number(percent(high)) + " of 100";
            }
            if (among.isPresent() && low == 1) {
                return "All " + among.getAsInt();
            }
            if (among.isPresent() && low == 0) {
                return "None of " + among.getAsInt();
            }
            return Tokens.number(percent(low)) + " of 100";
        }
        String measure = label(result.measureSemantic());
        if (measure.toLowerCase(Locale.ROOT).startsWith(PRESENCE)) {
            return low != null && low > 0 ? "Present; no count is cited here" : "Absent";
        }
        if (low != null && high == null) {
            return measure + ": more than " + Tokens.number(low);
        }
        if (low != null && low.equals(high)) {
            return measure + ": " + Tokens.number(low);
        }
        return measure + ": " + Tokens.interval(low, high, result.includeLowerBound(), result.includeUpperBound());
    }

    /** A time measure as a period: {@code [7,7]} is at seven, {@code [0,30]} is by thirty, and anything else a span. */
    private static String period(AnfStatement.Result timing) {
        if (timing == null || timing.lowerBound() == null || timing.upperBound() == null) {
            return NOT_STATED;
        }
        double low = timing.lowerBound();
        double high = timing.upperBound();
        String unit = label(timing.measureSemantic()).toLowerCase(Locale.ROOT);
        String units = high == 1 || unit.endsWith("s") ? unit : unit + "s";
        if (low == high) {
            return "at " + Tokens.number(high) + " " + units;
        }
        return low == 0 ? "by " + Tokens.number(high) + " " + units : Tokens.number(low) + " to " + Tokens.number(high) + " " + units;
    }

    /**
     * The name of a value: a resolved concept's, without its semantic tags, a candidate's, or what
     * an offline read kept of an identifier it could not resolve.
     */
    private static String label(AnfValue value) {
        return switch (value) {
            case null -> "";
            case AnfValue.Grounded grounded -> SEMANTIC_TAG.matcher(grounded.label()).replaceFirst("");
            case AnfValue.Candidate candidate -> candidate.provisionalLabel();
            case AnfValue.Clarify clarify -> {
                Matcher unresolved = UNRESOLVED_LABEL.matcher(clarify.question());
                yield unresolved.find() ? unresolved.group(1) : clarify.question();
            }
        };
    }

    private static Optional<Named> named(BlockLines.Line line) {
        return Tokens.concept(line.head()).map(c -> new Named(c.identifier().orElse(c.key()), c.label()));
    }

    /** The findings in print by a year. */
    public List<Finding> inPrintBy(int year) {
        return all.stream().filter(f -> f.inPrint() <= year).toList();
    }

    /** How many findings hold narrative only, having no scale yet: a gap in what they can be read for. */
    public long narrativeOnly() {
        return all.stream().filter(Finding::narrative).count();
    }

    /** A finding by its identifier. */
    public Optional<Finding> byId(String id) {
        return all.stream().filter(f -> f.id().equals(id)).findFirst();
    }

    /**
     * A finding's share with its 95% Wilson score interval, from the share and how many it is of.
     *
     * @param percent the share, in percent
     * @param n       how many it is of
     * @return the share and its limits, in percent
     */
    public static double[] wilson(double percent, int n) {
        double z = 1.959964;
        double p = percent / 100;
        double scale = 1 + z * z / n;
        double center = (p + z * z / (2.0 * n)) / scale;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / scale;
        return new double[]{percent, 100 * (center - half), 100 * (center + half)};
    }
}
