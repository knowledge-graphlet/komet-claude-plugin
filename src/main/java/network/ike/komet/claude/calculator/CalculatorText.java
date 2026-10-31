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
package network.ike.komet.claude.calculator;

import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.BlockLines.Form;
import network.ike.komet.claude.block.BlockLines.Line;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.calculator.CalculatorElement.Band;
import network.ike.komet.claude.calculator.CalculatorElement.Coefficient;
import network.ike.komet.claude.calculator.CalculatorElement.Condition;
import network.ike.komet.claude.calculator.CalculatorElement.Conversion;
import network.ike.komet.claude.calculator.CalculatorElement.Formula;
import network.ike.komet.claude.calculator.CalculatorElement.Input;
import network.ike.komet.claude.calculator.CalculatorElement.MissingValue;
import network.ike.komet.claude.calculator.CalculatorElement.Output;
import network.ike.komet.claude.calculator.CalculatorElement.Recency;
import network.ike.komet.claude.calculator.CalculatorElement.Root;
import network.ike.komet.claude.calculator.CalculatorElement.Stratum;
import network.ike.komet.claude.calculator.CalculatorElement.Term;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.Measure;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.IntIntMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.primitive.ImmutableIntIntMap;
import org.eclipse.collections.api.map.primitive.MutableIntIntMap;

/**
 * The calculator profile in text ({@code dev-calculator-blocks}): the {@code [calculator]} block,
 * the method, sugar for the property-graph block with this profile. One vertex per line, its kind
 * word first, indentation the edge:
 *
 * <pre>
 * [calculator]
 * ----
 * calculator: k:AscvdRisk10[10-year ASCVD risk] @id=… source=… @vertex=…
 *   output = k:AscvdRiskScore[…] @id=… in k:PctPce2013[…] @id=… domain [0,100] scale=100 @vertex=…
 *   input: k:Age[Age] @id=… in k:Year[] @id=… domain [40,79] topic=… required @vertex=…
 *   input: k:Sex[Sex] @id=… values=…;… required @vertex=…
 *   input: k:Hdl[HDL cholesterol] @id=… in k:MgPerDl[] @id=… domain [20,100] when-missing=plausible @vertex=…
 *     missing = [35,70] @vertex=…
 *   term = k:LnAge[ln age] @id=… from NaturalLogarithm of k:Age[] @id=… @vertex=…
 *   stratum: k:WhiteMale[White male] @id=… @vertex=…
 *     when = k:Sex[] @id=… k:Male[] @id=… @vertex=…
 *     coefficient = [12.344,12.344] k:LnAge[] @id=… basis=cited source=… @vertex=…
 *   formula = from CoxSurvival of k:S0[] @id=… k:Mean[] @id=… @vertex=…
 *   band: k:Low[low] @id=… when [0,5) source=… @vertex=…
 * ----
 * </pre>
 *
 * <p>Lossless by identity, deterministic in emission and never refusing on parse, under the rules
 * {@code DecisionTreeText} states; a derivation kind is written as its bare word, which the shared
 * reader also accepts after {@code from}.
 */
public final class CalculatorText {

    /** The block's language tag, and the profile's name under the property-graph block. */
    public static final String TAG = "calculator";

    private static final String INDENT = "  ";
    private static final String DELIMITER = "----";

    private CalculatorText() {
    }

    /** Whether a fence's info string names this profile. */
    public static boolean isTagged(String info) {
        String tag = info == null ? "" : Tokens.words(info).stream().findFirst().orElse("");
        return TAG.equals(tag) || ("property-graph".equals(tag) && info.contains("profile=" + TAG));
    }

    /** What a parse produced: the calculator when there was a root to build from, every finding, and each vertex's line. */
    public record Parsed(Optional<Calculator> calculator, ImmutableList<ProfileFinding> findings, ImmutableIntIntMap lines) {
        public OptionalInt lineOf(int vertexIndex) {
            return lines.containsKey(vertexIndex) ? OptionalInt.of(lines.get(vertexIndex)) : OptionalInt.empty();
        }
    }

    // ── Emit ─────────────────────────────────────────────────────────────────

    public static String toAdoc(Calculator calculator, Identities ids) {
        return "[" + TAG + "]\n" + DELIMITER + "\n" + body(calculator, ids) + DELIMITER + "\n";
    }

    public static String body(Calculator calculator, Identities ids) {
        StringBuilder sb = new StringBuilder();
        Optional<Root> root = calculator.calculator();
        if (root.isEmpty()) {
            return "";
        }
        Root r = root.get();
        CalculatorTerms terms = calculator.terms();
        sb.append("calculator:");
        concept(sb, ids, r.concept());
        r.source().ifPresent(nid -> reference(sb, ids, "source", nid));
        vertex(sb, calculator, r.vertexIndex());

        r.output().ifPresent(output -> {
            sb.append(INDENT).append("output =");
            concept(sb, ids, output.concept());
            if (output.semantic() != 0) {
                sb.append(" in");
                concept(sb, ids, OptionalLong.of(output.semantic()));
            }
            output.domain().ifPresent(domain -> sb.append(" domain ").append(interval(domain)));
            if (output.scale() != 1.0) {
                sb.append(" scale=").append(Tokens.number(output.scale()));
            }
            vertex(sb, calculator, output.vertexIndex());
        });

        for (Input input : r.inputs()) {
            sb.append(INDENT).append("input:");
            concept(sb, ids, OptionalLong.of(input.quantity()));
            if (input.expectedSemantic() != 0) {
                sb.append(" in");
                concept(sb, ids, OptionalLong.of(input.expectedSemantic()));
            }
            input.domain().ifPresent(domain -> sb.append(" domain ").append(interval(domain)));
            input.topic().ifPresent(nid -> reference(sb, ids, "topic", nid));
            if (input.conceptValued()) {
                List<String> values = new ArrayList<>();
                input.admittedValues().forEach(nid -> ids.identifier(nid).ifPresent(values::add));
                if (!values.isEmpty()) {
                    sb.append(" values=").append(String.join(";", values));
                }
            }
            if (input.required()) {
                sb.append(" required");
            }
            OptionalLong whenMissing = input.whenMissing();
            if (whenMissing.isPresent()) {
                policyWord(terms, whenMissing.getAsLong()).ifPresent(word -> sb.append(" when-missing=").append(word));
            }
            vertex(sb, calculator, input.vertexIndex());
            for (Conversion conversion : input.conversions()) {
                sb.append(INDENT).append(INDENT).append("conversion =");
                conversion.factor().ifPresent(factor -> sb.append(' ').append(interval(factor)));
                concept(sb, ids, OptionalLong.of(conversion.fromSemantic()));
                vertex(sb, calculator, conversion.vertexIndex());
            }
            input.recency().ifPresent(recency -> {
                sb.append(INDENT).append(INDENT).append("recency =");
                recency.window().ifPresent(window -> sb.append(' ').append(interval(window)));
                if (recency.unit() != 0) {
                    concept(sb, ids, OptionalLong.of(recency.unit()));
                }
                vertex(sb, calculator, recency.vertexIndex());
            });
            input.missingValue().ifPresent(missing -> {
                sb.append(INDENT).append(INDENT).append("missing =");
                missing.bounds().ifPresent(bounds -> sb.append(' ').append(interval(bounds)));
                concept(sb, ids, missing.conceptValue());
                vertex(sb, calculator, missing.vertexIndex());
            });
        }

        for (Term term : r.terms()) {
            sb.append(INDENT).append("term =");
            concept(sb, ids, OptionalLong.of(term.quantity()));
            derivation(sb, ids, terms, term.derivedFrom(), term.operands());
            vertex(sb, calculator, term.vertexIndex());
        }

        for (Stratum stratum : r.strata()) {
            sb.append(INDENT).append("stratum:");
            concept(sb, ids, stratum.concept());
            vertex(sb, calculator, stratum.vertexIndex());
            for (Condition condition : stratum.conditions()) {
                sb.append(INDENT).append(INDENT).append("when =");
                concept(sb, ids, OptionalLong.of(condition.input()));
                concept(sb, ids, condition.value());
                vertex(sb, calculator, condition.vertexIndex());
            }
            for (Coefficient coefficient : stratum.coefficients()) {
                sb.append(INDENT).append(INDENT).append("coefficient =");
                coefficient.value().ifPresent(value -> sb.append(' ').append(interval(value)));
                concept(sb, ids, OptionalLong.of(coefficient.quantity()));
                basis(sb, terms, coefficient.measure());
                coefficient.source().ifPresent(nid -> reference(sb, ids, "source", nid));
                vertex(sb, calculator, coefficient.vertexIndex());
            }
        }

        r.formula().ifPresent(formula -> {
            sb.append(INDENT).append("formula =");
            derivation(sb, ids, terms, formula.kind(), formula.operands());
            vertex(sb, calculator, formula.vertexIndex());
        });

        for (Band band : r.bands()) {
            sb.append(INDENT).append("band:");
            concept(sb, ids, band.concept());
            band.interval().ifPresent(bounds -> sb.append(" when ").append(interval(bounds)));
            band.source().ifPresent(nid -> reference(sb, ids, "source", nid));
            vertex(sb, calculator, band.vertexIndex());
        }
        return sb.toString();
    }

    private static void derivation(StringBuilder sb, Identities ids, CalculatorTerms terms, OptionalLong from,
                                   org.eclipse.collections.api.list.primitive.ImmutableLongList operands) {
        if (from.isEmpty()) {
            return;
        }
        sb.append(" from");
        long kind = from.getAsLong();
        Optional<String> word = kindWord(terms, kind);
        if (word.isPresent()) {
            sb.append(' ').append(word.get());
        } else {
            concept(sb, ids, from);
        }
        if (!operands.isEmpty()) {
            sb.append(" of");
            operands.forEach(nid -> concept(sb, ids, OptionalLong.of(nid)));
        }
    }

    /** The bare word a derivation kind writes as. */
    static Optional<String> kindWord(CalculatorTerms terms, long kind) {
        if (kind == terms.naturalLogarithm()) {
            return Optional.of("NaturalLogarithm");
        }
        if (kind == terms.coxSurvival()) {
            return Optional.of("CoxSurvival");
        }
        if (kind == terms.shared().complement()) {
            return Optional.of("Complement");
        }
        if (kind == terms.shared().product()) {
            return Optional.of("Product");
        }
        if (kind == terms.shared().cumulativeHazard()) {
            return Optional.of("CumulativeHazard");
        }
        if (kind == terms.shared().bayesPosterior()) {
            return Optional.of("BayesPosterior");
        }
        if (kind == terms.shared().bayesMarginal()) {
            return Optional.of("BayesMarginal");
        }
        return Optional.empty();
    }

    /** The written word of a when-missing policy. */
    public static Optional<String> policyWord(CalculatorTerms terms, long policy) {
        if (policy == terms.notDeterminable()) {
            return Optional.of("not-determinable");
        }
        if (policy == terms.plausible()) {
            return Optional.of("plausible");
        }
        if (policy == terms.assumed()) {
            return Optional.of("assumed");
        }
        return Optional.empty();
    }

    private static void basis(StringBuilder sb, CalculatorTerms terms, Measure measure) {
        measure.basisNid().ifPresent(nid -> {
            if (nid == terms.shared().estimatedBasis()) {
                sb.append(" basis=estimated");
            } else if (nid == terms.shared().citedBasis()) {
                sb.append(" basis=cited");
            }
        });
    }

    private static String interval(Bounds bounds) {
        return Tokens.interval(finite(bounds.lower()), finite(bounds.upper()), !bounds.lowerOpen(), !bounds.upperOpen());
    }

    private static void concept(StringBuilder sb, Identities ids, OptionalLong concept) {
        if (concept.isEmpty() || concept.getAsLong() == 0) {
            return;
        }
        long nid = concept.getAsLong();
        ids.identifier(nid).ifPresent(identifier -> sb.append(' ').append(Tokens.concept(ids.label(nid), identifier)));
    }

    private static void reference(StringBuilder sb, Identities ids, String name, long nid) {
        ids.identifier(nid).ifPresent(id -> sb.append(' ').append(name).append('=').append(id));
    }

    private static void vertex(StringBuilder sb, Calculator calculator, int vertexIndex) {
        EntityVertex vertex = calculator.vertex(vertexIndex);
        sb.append(" @vertex=").append(new UUID(vertex.mostSignificantBits(), vertex.leastSignificantBits())).append('\n');
    }

    private static Double finite(double bound) {
        return Double.isInfinite(bound) ? null : bound;
    }

    // ── Parse ────────────────────────────────────────────────────────────────

    /** Reads a block; never refuses, every problem a finding naming its line. */
    public static Parsed parse(String text, CalculatorTerms terms, Identities ids) {
        Reader reader = new Reader(terms, ids);
        BlockLines.Parsed lines = BlockLines.parse(text);
        lines.problems().forEach(problem -> reader.findings.add(ProfileFinding.ofTree(problem)));
        if (lines.roots().size() != 1) {
            reader.findings.add(ProfileFinding.ofTree("a calculator has one root line, not " + lines.roots().size()));
            return new Parsed(Optional.empty(), reader.findings.toImmutable(), IntIntMaps.immutable.empty());
        }
        Line root = lines.roots().get(0);
        if (root.form() != Form.VERTEX || !"calculator".equals(root.word())) {
            reader.findings.add(finding(root, "the root is not a calculator"));
            return new Parsed(Optional.empty(), reader.findings.toImmutable(), IntIntMaps.immutable.empty());
        }
        Calculator.Builder builder = Calculator.builder(terms);
        Calculator.Builder.Handle rootHandle = builder.calculator(reader.concept(root, "a calculator"), reader.vertexId(root));
        reader.lines.put(rootHandle.vertexIndex(), root.number());
        root.attributes().forEach((name, value) -> {
            switch (name) {
                case "source" -> reader.resolve(root, value, "a source").ifPresent(rootHandle::source);
                case "@vertex" -> { }
                default -> reader.findings.add(finding(root, "unknown attribute of a calculator: " + name));
            }
        });
        reader.children(root, rootHandle);
        if (!reader.localSymbols.isEmpty()) {
            reader.findings.add(ProfileFinding.ofTree(reader.localSymbols.size()
                    + " identifiers name nothing in the knowledge base and are read as concepts local to the block: "
                    + String.join(", ", reader.localSymbols)));
        }
        return new Parsed(Optional.of(builder.tree()), reader.findings.toImmutable(), reader.lines.toImmutable());
    }

    private static ProfileFinding finding(Line line, String message) {
        return ProfileFinding.ofTree("line " + line.number() + ": " + message);
    }

    /** The parts of a line's head: intervals, concepts, and the keywords that give the next concept its role. */
    private sealed interface Item permits IntervalItem, ConceptItem, Keyword {
    }

    private record IntervalItem(Tokens.Interval interval) implements Item {
    }

    private record ConceptItem(Tokens.Concept concept, String text) implements Item {
    }

    private record Keyword(String word) implements Item {
    }

    private static final Set<String> KEYWORDS = Set.of("from", "of", "in", "domain", "when", "required");

    private static final class Reader {
        private final CalculatorTerms terms;
        private final Identities ids;
        private final MutableList<ProfileFinding> findings = Lists.mutable.empty();
        private final MutableIntIntMap lines = IntIntMaps.mutable.empty();
        private final Set<String> localSymbols = new LinkedHashSet<>();

        private Reader(CalculatorTerms terms, Identities ids) {
            this.terms = terms;
            this.ids = ids;
        }

        private void children(Line root, Calculator.Builder.Handle rootHandle) {
            for (Line child : root.children()) {
                if (child.form() == Form.VALUE) {
                    switch (child.word().toLowerCase(Locale.ROOT)) {
                        case "output" -> output(child, rootHandle);
                        case "term" -> term(child, rootHandle);
                        case "formula" -> formula(child, rootHandle);
                        default -> findings.add(finding(child, "unknown value of a calculator: " + child.word()));
                    }
                    continue;
                }
                switch (child.word().toLowerCase(Locale.ROOT)) {
                    case "input" -> input(child, rootHandle);
                    case "stratum" -> stratum(child, rootHandle);
                    case "band" -> band(child, rootHandle);
                    default -> findings.add(finding(child, "unknown kind of vertex under a calculator: " + child.word()));
                }
            }
        }

        private void output(Line line, Calculator.Builder.Handle root) {
            Head head = head(line);
            long topic = head.first().map(c -> resolveConcept(line, c, "an output's topic")).orElse(0L);
            if (head.first().isEmpty()) {
                findings.add(finding(line, "an output names its topic"));
            }
            long semantic = head.in().map(c -> resolveConcept(line, c, "an output's measure semantic")).orElse(0L);
            if (head.in().isEmpty()) {
                findings.add(finding(line, "an output names its measure semantic after in"));
            }
            Calculator.Builder.Handle output = root.output(topic, semantic, vertexId(line));
            head.domain().ifPresent(domain -> output.bounds(bounds(domain)));
            lines.put(output.vertexIndex(), line.number());
            line.attributes().forEach((name, value) -> {
                switch (name) {
                    case "scale" -> {
                        try {
                            output.scale(Double.parseDouble(value));
                        } catch (NumberFormatException e) {
                            findings.add(finding(line, "a scale is a number: " + value));
                        }
                    }
                    case "@vertex" -> { }
                    default -> findings.add(finding(line, "unknown attribute of an output: " + name));
                }
            });
            noChildren(line);
        }

        private void input(Line line, Calculator.Builder.Handle root) {
            Head head = head(line);
            long quantity = head.first().map(c -> resolveConcept(line, c, "an input's quantity")).orElse(0L);
            if (head.first().isEmpty()) {
                findings.add(finding(line, "an input names its quantity"));
            }
            long semantic = head.in().map(c -> resolveConcept(line, c, "an input's measure semantic")).orElse(0L);
            Calculator.Builder.Handle input = root.input(quantity, semantic, vertexId(line));
            lines.put(input.vertexIndex(), line.number());
            head.domain().ifPresent(domain -> input.bounds(bounds(domain)));
            if (head.flag("required")) {
                input.required();
            }
            line.attributes().forEach((name, value) -> {
                switch (name) {
                    case "topic" -> resolve(line, value, "an input's topic").ifPresent(input::topic);
                    case "values" -> {
                        List<Long> values = new ArrayList<>();
                        for (String identifier : value.split(";")) {
                            resolve(line, identifier, "an admitted value").ifPresent(values::add);
                        }
                        input.admittedValues(values.stream().mapToLong(Long::longValue).toArray());
                    }
                    case "required" -> input.required();
                    case "when-missing" -> {
                        OptionalLong policy = terms.policy(value);
                        if (policy.isEmpty()) {
                            findings.add(finding(line, "a when-missing policy is not-determinable, plausible or assumed, not " + value));
                        } else {
                            input.whenMissing(policy.getAsLong());
                        }
                    }
                    case "@vertex" -> { }
                    default -> findings.add(finding(line, "unknown attribute of an input: " + name));
                }
            });
            for (Line child : line.children()) {
                if (child.form() != Form.VALUE) {
                    findings.add(finding(child, "an input carries values, not vertices: " + child.word()));
                    continue;
                }
                Head h = head(child);
                switch (child.word().toLowerCase(Locale.ROOT)) {
                    case "conversion" -> {
                        long from = h.first().map(c -> resolveConcept(child, c, "a semantic converted from")).orElse(0L);
                        if (h.interval().isEmpty()) {
                            findings.add(finding(child, "a conversion states its factor as an interval"));
                        }
                        Calculator.Builder.Handle conversion = input.conversion(from,
                                h.interval().map(Reader::bounds).orElse(Bounds.point(1)), vertexId(child));
                        lines.put(conversion.vertexIndex(), child.number());
                    }
                    case "recency" -> {
                        long unit = h.first().map(c -> resolveConcept(child, c, "a recency's unit")).orElse(0L);
                        if (h.interval().isEmpty()) {
                            findings.add(finding(child, "a recency states its window as an interval"));
                        }
                        Calculator.Builder.Handle recency = input.recency(
                                h.interval().map(Reader::bounds).orElse(Bounds.of(0, 0)), unit, vertexId(child));
                        lines.put(recency.vertexIndex(), child.number());
                    }
                    case "missing" -> {
                        Calculator.Builder.Handle missing;
                        if (h.interval().isPresent()) {
                            missing = input.missingValue(bounds(h.interval().get()), vertexId(child));
                        } else if (h.first().isPresent()) {
                            missing = input.missingConcept(resolveConcept(child, h.first().get(), "an assumed concept"), vertexId(child));
                        } else {
                            findings.add(finding(child, "a missing value states bounds or a concept"));
                            continue;
                        }
                        lines.put(missing.vertexIndex(), child.number());
                    }
                    default -> findings.add(finding(child, "unknown value of an input: " + child.word()));
                }
                unknownAttributes(child, "a value of an input", Set.of());
            }
        }

        private void term(Line line, Calculator.Builder.Handle root) {
            Head head = head(line);
            long quantity = head.first().map(c -> resolveConcept(line, c, "a term's quantity")).orElse(0L);
            if (head.first().isEmpty()) {
                findings.add(finding(line, "a term names its quantity before from"));
            }
            if (head.from().isEmpty()) {
                findings.add(finding(line, "a term is derived from a kind or reads a quantity after from"));
                return;
            }
            long kind = derivationOrQuantity(line, head.from().get(), head.of().isEmpty());
            long[] operands = head.of().stream().mapToLong(c -> resolveConcept(line, c, "an operand")).toArray();
            Calculator.Builder.Handle term = root.term(quantity, kind, operands, vertexId(line));
            lines.put(term.vertexIndex(), line.number());
            unknownAttributes(line, "a term", Set.of());
            noChildren(line);
        }

        private void formula(Line line, Calculator.Builder.Handle root) {
            Head head = head(line);
            if (head.from().isEmpty()) {
                findings.add(finding(line, "a formula names its kind after from"));
                return;
            }
            long kind = derivationOrQuantity(line, head.from().get(), false);
            long[] operands = head.of().stream().mapToLong(c -> resolveConcept(line, c, "a stratum constant")).toArray();
            Calculator.Builder.Handle formula = root.formula(kind, operands, vertexId(line));
            lines.put(formula.vertexIndex(), line.number());
            unknownAttributes(line, "a formula", Set.of());
            noChildren(line);
        }

        private void stratum(Line line, Calculator.Builder.Handle root) {
            Calculator.Builder.Handle stratum = root.stratum(concept(line, "a stratum"), vertexId(line));
            lines.put(stratum.vertexIndex(), line.number());
            unknownAttributes(line, "a stratum", Set.of());
            for (Line child : line.children()) {
                if (child.form() != Form.VALUE) {
                    findings.add(finding(child, "a stratum carries values, not vertices: " + child.word()));
                    continue;
                }
                Head h = head(child);
                switch (child.word().toLowerCase(Locale.ROOT)) {
                    case "when" -> {
                        if (h.concepts().size() != 2) {
                            findings.add(finding(child, "a condition names the input then the value it requires"));
                            continue;
                        }
                        long input = resolveConcept(child, h.concepts().get(0), "a condition's input");
                        long value = resolveConcept(child, h.concepts().get(1), "a condition's value");
                        Calculator.Builder.Handle condition = stratum.condition(input, value, vertexId(child));
                        lines.put(condition.vertexIndex(), child.number());
                        unknownAttributes(child, "a condition", Set.of());
                    }
                    case "coefficient" -> {
                        if (h.interval().isEmpty()) {
                            findings.add(finding(child, "a coefficient begins with its value, [lo,hi]"));
                            continue;
                        }
                        long quantity = h.first().map(c -> resolveConcept(child, c, "a coefficient's term")).orElse(0L);
                        if (h.first().isEmpty()) {
                            findings.add(finding(child, "a coefficient names the term or constant it belongs to"));
                        }
                        Calculator.Builder.Handle coefficient = stratum.coefficient(quantity, bounds(h.interval().get()), vertexId(child));
                        lines.put(coefficient.vertexIndex(), child.number());
                        child.attributes().forEach((name, value) -> {
                            switch (name) {
                                case "basis" -> basis(child, value).ifPresent(coefficient::basis);
                                case "source" -> resolve(child, value, "a basis source").ifPresent(coefficient::source);
                                case "@vertex" -> { }
                                default -> findings.add(finding(child, "unknown attribute of a coefficient: " + name));
                            }
                        });
                    }
                    default -> findings.add(finding(child, "unknown value of a stratum: " + child.word()));
                }
            }
        }

        private void band(Line line, Calculator.Builder.Handle root) {
            Head head = head(line);
            long concept = head.first().map(c -> resolveConcept(line, c, "a band")).orElse(0L);
            if (head.when().isEmpty()) {
                findings.add(finding(line, "a band states its interval after when"));
            }
            Calculator.Builder.Handle band = root.band(concept, head.when().map(Reader::bounds).orElse(Bounds.of(0, 0)), vertexId(line));
            lines.put(band.vertexIndex(), line.number());
            line.attributes().forEach((name, value) -> {
                switch (name) {
                    case "source" -> resolve(line, value, "a band's source").ifPresent(band::source);
                    case "@vertex" -> { }
                    default -> findings.add(finding(line, "unknown attribute of a band: " + name));
                }
            });
            noChildren(line);
        }

        // ── Heads ────────────────────────────────────────────────────────────

        /** A line's head, read into its roles: the leading concepts, and what follows each keyword. */
        private record Head(List<Tokens.Concept> concepts, Optional<Tokens.Concept> in, Optional<Tokens.Interval> interval,
                            Optional<Tokens.Interval> domain, Optional<Tokens.Interval> when,
                            Optional<Tokens.Concept> from, List<Tokens.Concept> of, Set<String> flags) {
            Optional<Tokens.Concept> first() {
                return concepts.isEmpty() ? Optional.empty() : Optional.of(concepts.get(0));
            }

            boolean flag(String word) {
                return flags.contains(word);
            }
        }

        private Head head(Line line) {
            List<Tokens.Concept> concepts = new ArrayList<>();
            Optional<Tokens.Concept> in = Optional.empty();
            Optional<Tokens.Interval> interval = Optional.empty();
            Optional<Tokens.Interval> domain = Optional.empty();
            Optional<Tokens.Interval> when = Optional.empty();
            Optional<Tokens.Concept> from = Optional.empty();
            List<Tokens.Concept> of = new ArrayList<>();
            Set<String> flags = new LinkedHashSet<>();
            String mode = "";
            for (Item item : items(line, line.head())) {
                switch (item) {
                    case IntervalItem i -> {
                        switch (mode) {
                            case "domain" -> domain = Optional.of(i.interval());
                            case "when" -> when = Optional.of(i.interval());
                            default -> {
                                if (interval.isPresent()) {
                                    findings.add(finding(line, "a line has one interval"));
                                }
                                interval = Optional.of(i.interval());
                            }
                        }
                        mode = "";
                    }
                    case Keyword k -> {
                        if ("required".equals(k.word())) {
                            flags.add("required");
                        } else {
                            mode = k.word();
                        }
                    }
                    case ConceptItem c -> {
                        switch (mode) {
                            case "from" -> from = Optional.of(c.concept());
                            case "of" -> of.add(c.concept());
                            case "in" -> {
                                in = Optional.of(c.concept());
                                mode = "";
                            }
                            default -> concepts.add(c.concept());
                        }
                    }
                }
            }
            return new Head(concepts, in, interval, domain, when, from, of, flags);
        }

        private List<Item> items(Line line, String head) {
            List<Item> items = new ArrayList<>();
            List<String> words = Tokens.words(head);
            String keyword = "";
            for (int i = 0; i < words.size(); i++) {
                String word = words.get(i);
                if ("from".equals(keyword) && !word.startsWith("k:") && terms.derivationKind(word).isPresent()) {
                    items.add(new ConceptItem(new Tokens.Concept(word, "", Optional.empty()), word));
                    keyword = "";
                    continue;
                }
                keyword = "";
                if (word.startsWith("k:")) {
                    String text = word;
                    if (i + 1 < words.size() && words.get(i + 1).startsWith("@id=")) {
                        text = word + " " + words.get(++i);
                    }
                    Optional<Tokens.Concept> concept = Tokens.concept(text);
                    if (concept.isEmpty()) {
                        findings.add(finding(line, "not a k: token: " + text));
                    } else {
                        items.add(new ConceptItem(concept.get(), text));
                    }
                } else if (word.startsWith("[") || word.startsWith("(")) {
                    Optional<Tokens.IntervalPrefix> prefix = Tokens.intervalPrefix(word);
                    if (prefix.isEmpty()) {
                        findings.add(finding(line, "not an interval: " + word));
                    } else {
                        items.add(new IntervalItem(prefix.get().interval()));
                    }
                } else if (KEYWORDS.contains(word.toLowerCase(Locale.ROOT))) {
                    keyword = word.toLowerCase(Locale.ROOT);
                    items.add(new Keyword(keyword));
                } else {
                    findings.add(finding(line, "an unexpected word: " + word));
                }
            }
            return items;
        }

        // ── Resolution ───────────────────────────────────────────────────────

        private long derivationOrQuantity(Line line, Tokens.Concept token, boolean noOperands) {
            if (token.identifier().isPresent()) {
                OptionalLong stored = ids.nid(token.identifier().get().strip());
                if (stored.isPresent()) {
                    return stored.getAsLong();
                }
            }
            OptionalLong byLabel = terms.derivationKind(token.label());
            if (byLabel.isPresent()) {
                return byLabel.getAsLong();
            }
            OptionalLong byKey = terms.derivationKind(token.key());
            if (byKey.isPresent()) {
                return byKey.getAsLong();
            }
            return resolveConcept(line, token, noOperands ? "a quantity a term reads" : "a derivation kind");
        }

        private long concept(Line line, String what) {
            String head = line.head();
            if (head.isEmpty()) {
                findings.add(finding(line, what + " names no concept"));
                return 0;
            }
            Optional<Tokens.Concept> token = Tokens.concept(head);
            if (token.isEmpty()) {
                findings.add(finding(line, what + " is not a k: token: " + head));
                return 0;
            }
            return resolveConcept(line, token.get(), what);
        }

        private long resolveConcept(Line line, Tokens.Concept token, String what) {
            if (token.identifier().isEmpty()) {
                findings.add(finding(line, what + " carries no @id: " + Tokens.concept(token.label(), null)));
                return 0;
            }
            String label = token.label() != null && !token.label().isBlank() ? token.label()
                    : token.key() != null && !token.key().isBlank() ? token.key() : token.identifier().get();
            return resolve(line, token.identifier().get(), label, what).orElse(0L);
        }

        private OptionalLong resolve(Line line, String identifier, String what) {
            return resolve(line, identifier, identifier, what);
        }

        private OptionalLong resolve(Line line, String identifier, String label, String what) {
            String id = identifier.strip();
            if (id.isEmpty()) {
                return OptionalLong.empty();
            }
            OptionalLong nid = ids.nid(id);
            if (nid.isPresent()) {
                return nid;
            }
            OptionalLong local = ids.local(id, label);
            if (local.isPresent()) {
                localSymbols.add(id);
                return local;
            }
            findings.add(finding(line, what + " does not resolve: " + id));
            return nid;
        }

        private UUID vertexId(Line line) {
            Optional<String> written = line.attribute("@vertex");
            if (written.isEmpty()) {
                return UUID.randomUUID();
            }
            try {
                return UUID.fromString(written.get());
            } catch (IllegalArgumentException e) {
                findings.add(finding(line, "not a vertex identity: " + written.get()));
                return UUID.randomUUID();
            }
        }

        private OptionalLong basis(Line line, String word) {
            return switch (word.toLowerCase(Locale.ROOT)) {
                case "estimated" -> OptionalLong.of(terms.shared().estimatedBasis());
                case "cited" -> OptionalLong.of(terms.shared().citedBasis());
                default -> {
                    findings.add(finding(line, "a coefficient's basis is estimated or cited, not " + word));
                    yield OptionalLong.empty();
                }
            };
        }

        private void unknownAttributes(Line line, String what, Set<String> known) {
            line.attributes().forEach((name, value) -> {
                if (!"@vertex".equals(name) && !known.contains(name)) {
                    findings.add(finding(line, "unknown attribute of " + what + ": " + name));
                }
            });
        }

        private void noChildren(Line line) {
            if (!line.children().isEmpty()) {
                findings.add(finding(line, "a " + line.word() + " has nothing beneath it"));
            }
        }

        private static Bounds bounds(Tokens.Interval interval) {
            return new Bounds(
                    interval.lower() == null ? Double.NEGATIVE_INFINITY : interval.lower(),
                    interval.upper() == null ? Double.POSITIVE_INFINITY : interval.upper(),
                    !interval.includeLower(), !interval.includeUpper());
        }
    }

    /** The vertex words of the block, for a renderer that decides whether a fence is one of ours. */
    public static List<String> vertexWords() {
        return List.of("calculator", "input", "stratum", "band");
    }

    /** The map a renderer uses to carry a parse's line numbers. */
    static Map<Integer, Integer> linesOf(Parsed parsed) {
        Map<Integer, Integer> map = new java.util.HashMap<>();
        parsed.lines().forEachKeyValue(map::put);
        return map;
    }
}
