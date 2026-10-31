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
package network.ike.komet.claude.decision;

import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.BlockLines.Form;
import network.ike.komet.claude.block.BlockLines.Line;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionElement.Chance;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.Decision;
import network.ike.komet.claude.decision.DecisionElement.Event;
import network.ike.komet.claude.decision.DecisionElement.HealthCost;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Node;
import network.ike.komet.claude.decision.DecisionElement.Outcome;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import network.ike.komet.claude.decision.DecisionElement.Probability;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.IntIntMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.map.primitive.ImmutableIntIntMap;
import org.eclipse.collections.api.map.primitive.MutableIntIntMap;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.list.primitive.ImmutableLongList;

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

/**
 * The decision-tree profile in text: the {@code [decision-tree]} block, sugar for the
 * property-graph block with this profile ({@code dev-decision-tree-blocks}, settled KEC
 * 2026-10-27). One vertex per line, its kind word first, indentation the edge, measures as value
 * lines under the node that carries them, the model's parameters as value lines under the root:
 *
 * <pre>
 * [decision-tree]
 * ----
 * decision: k:ScanNow[Scan now?] @id=… @vertex=…
 *   parameter = [0.02,0.02] k:Reopening[Late reopening hazard in year 2] @id=… basis=estimated @vertex=…
 *   parameter = [0.9,0.9] k:Sensitivity[MRA sensitivity] @id=… @vertex=…
 *   parameter = k:Positive[Share of scans positive] @id=… from k:BayesMarginal[Bayes marginal] @id=… of k:Reopening[] @id=… k:Sensitivity[] @id=… k:FalsePositive[] @id=… @vertex=…
 *   parameter = [150000,150000] k:RuptureCost[Cost of a rupture] @id=… in k:Usd[US dollar] @id=… @vertex=…
 *   alternative: k:Scan[Scan] @id=… statement=… @vertex=…
 *     money = [700,700] k:Usd[US dollar at 2026 prices] @id=… basis=estimated @vertex=…
 *     chance: k:MraResult[MRA result] @id=… @vertex=…
 *       event: k:Positive[Scan positive] @id=… @vertex=…
 *         probability = from k:Lookup[…] … (or a stated interval)
 *         …
 * ----
 * </pre>
 *
 * <p><b>Lossless by identity.</b> A concept writes as the {@code k:} token of the ANF block,
 * its {@code @id} the identity and its key and label human aids recomputed on every emit; every
 * vertex writes its own identity as {@code @vertex}, so a block parsed and written again holds
 * the same vertices and a revised number is a new version of one of them. A stated measure
 * writes in the ANF interval grammar; a derived one writes {@code from} its kind {@code of} the
 * quantities it reads, and one that takes a parameter's value as it is writes {@code from} that
 * quantity alone; a cost or a parameter with a money or health semantic names it after
 * {@code in}. Nothing computed is written.
 *
 * <p><b>Deterministic emission.</b> Parameters first under the root, then measures, then nodes,
 * each in stored order; one fixed attribute order; integral numbers without a fraction and others
 * exactly as Java prints them. Store to text to store is byte-stable.
 *
 * <p><b>Parsing never refuses.</b> An unknown word, an identifier that does not resolve, a
 * measure with no semantic: each is a finding with its line, and the vertex is written with the
 * gap, as the ANF block keeps a clarify. {@link DecisionTree#check()} says whether the tree is
 * well formed.
 */
public final class DecisionTreeText {

    /** The block's language tag, and the profile's name under the property-graph block. */
    public static final String TAG = "decision-tree";

    /** The default price of a year of healthy life, in the tree's money semantic. */
    public static final double DEFAULT_HEALTH_PRICE = 100_000;

    private static final String HEALTH_PRICE = "health-price";
    private static final String INDENT = "  ";
    private static final String DELIMITER = "----";

    private DecisionTreeText() {
    }

    /**
     * Whether a fence's info string names this profile: tagged {@code decision-tree}, or
     * {@code property-graph} with {@code profile=decision-tree}.
     */
    public static boolean isTagged(String info) {
        String tag = info == null ? "" : Tokens.words(info).stream().findFirst().orElse("");
        return TAG.equals(tag) || ("property-graph".equals(tag) && info.contains("profile=" + TAG));
    }

    /** The health price a fence's info string sets, {@code health-price=<number>}, when it does. */
    public static Optional<Double> healthPrice(String info) {
        if (info == null) {
            return Optional.empty();
        }
        for (String word : Tokens.words(info)) {
            if (word.startsWith(HEALTH_PRICE + "=")) {
                try {
                    return Optional.of(Double.parseDouble(word.substring(HEALTH_PRICE.length() + 1).replace(",", "")));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * What a parse produced: the tree, when there was a root to build from, every finding, and
     * the line of the text each vertex was read from, so a surface that lets the reader move a
     * number can restate the line that states it.
     */
    public record Parsed(Optional<DecisionTree> tree, ImmutableList<ProfileFinding> findings,
                         ImmutableIntIntMap lines) {

        public Parsed(Optional<DecisionTree> tree, ImmutableList<ProfileFinding> findings) {
            this(tree, findings, IntIntMaps.immutable.empty());
        }

        /** The one-based line a vertex was read from, when the text stated it. */
        public OptionalInt lineOf(int vertexIndex) {
            return lines.containsKey(vertexIndex) ? OptionalInt.of(lines.get(vertexIndex)) : OptionalInt.empty();
        }
    }

    // ── Emit ─────────────────────────────────────────────────────────────────

    /** The whole block, header and delimiters included, ending with a newline. */
    public static String toAdoc(DecisionTree tree, Identities ids) {
        return "[" + TAG + "]\n" + DELIMITER + "\n" + body(tree, ids) + DELIMITER + "\n";
    }

    /** The block's body alone. */
    public static String body(DecisionTree tree, Identities ids) {
        StringBuilder sb = new StringBuilder();
        Optional<DecisionElement> root = tree.root();
        if (root.isPresent() && root.get() instanceof Node node) {
            node(sb, tree, ids, node, 0);
        }
        return sb.toString();
    }

    private static void node(StringBuilder sb, DecisionTree tree, Identities ids, Node node, int depth) {
        sb.append(INDENT.repeat(depth)).append(word(node)).append(':');
        concept(sb, ids, node.concept());
        node.statements().forEach(nid -> reference(sb, ids, "statement", nid));
        node.inputKind().ifPresent(nid -> reference(sb, ids, "input", nid));
        if (node instanceof Outcome outcome) {
            outcome.treeReference().ifPresent(nid -> reference(sb, ids, "tree", nid));
        }
        vertex(sb, tree, node.vertexIndex());
        for (Parameter parameter : tree.children(node.vertexIndex(), Parameter.class)) {
            measure(sb, tree, ids, parameter, depth + 1);
        }
        for (DecisionElement child : tree.children(node.vertexIndex())) {
            if (child instanceof Node || child instanceof Parameter) {
                continue;
            }
            measure(sb, tree, ids, child, depth + 1);
        }
        for (Node child : tree.children(node.vertexIndex(), Node.class)) {
            node(sb, tree, ids, child, depth + 1);
        }
    }

    private static void measure(StringBuilder sb, DecisionTree tree, Identities ids, DecisionElement element, int depth) {
        Measure measure = tree.measure(element.vertexIndex());
        sb.append(INDENT.repeat(depth)).append(word(element)).append(" =");
        if (element instanceof Parameter parameter && measure.derived()) {
            concept(sb, ids, parameter.quantity());
        }
        if (measure.stated()) {
            Bounds bounds = measure.bounds();
            sb.append(' ').append(Tokens.interval(finite(bounds.lower()), finite(bounds.upper()),
                    !bounds.lowerOpen(), !bounds.upperOpen()));
            if (element instanceof Parameter parameter) {
                concept(sb, ids, parameter.quantity());
            }
        }
        if (measure.derived()) {
            sb.append(" from");
            concept(sb, ids, measure.derivedFrom());
            if (!measure.operands().isEmpty()) {
                sb.append(" of");
                measure.operands().forEach(nid -> concept(sb, ids, OptionalLong.of(nid)));
            }
        }
        boolean share = measure.semanticNid() == tree.terms().shareAtNode();
        if (!share && measure.semanticNid() != 0) {
            if (element instanceof Parameter || measure.derived()) {
                sb.append(" in");
            }
            concept(sb, ids, OptionalLong.of(measure.semanticNid()));
        }
        measure.basisNid().ifPresent(nid -> basisWord(tree, nid)
                .filter(word -> !(measure.derived() && "derived".equals(word)))
                .ifPresent(word -> sb.append(" basis=").append(word)));
        tree.conceptProperty(element.vertexIndex(), tree.terms().basisSource())
                .ifPresent(nid -> reference(sb, ids, "source", nid));
        vertex(sb, tree, element.vertexIndex());
    }

    private static void concept(StringBuilder sb, Identities ids, OptionalLong concept) {
        if (concept.isEmpty()) {
            return;
        }
        long nid = concept.getAsLong();
        Optional<String> identifier = ids.identifier(nid);
        if (identifier.isPresent()) {
            sb.append(' ').append(Tokens.concept(ids.label(nid), identifier.get()));
        }
    }

    /** A reference attribute, written only when the store can name the component: a nid is local and never written. */
    private static void reference(StringBuilder sb, Identities ids, String name, long nid) {
        ids.identifier(nid).ifPresent(id -> sb.append(' ').append(name).append('=').append(id));
    }

    private static void vertex(StringBuilder sb, DecisionTree tree, int vertexIndex) {
        EntityVertex vertex = tree.vertex(vertexIndex);
        sb.append(" @vertex=").append(new UUID(vertex.mostSignificantBits(), vertex.leastSignificantBits()))
                .append('\n');
    }

    private static Double finite(double bound) {
        return Double.isInfinite(bound) ? null : bound;
    }

    private static String word(DecisionElement element) {
        return switch (element) {
            case Decision d -> "decision";
            case Alternative a -> "alternative";
            case Chance c -> "chance";
            case Event e -> e.residual() ? "residual" : "event";
            case Outcome o -> "outcome";
            case Probability p -> "probability";
            case MoneyCost m -> "money";
            case HealthCost h -> "health";
            case Parameter p -> "parameter";
        };
    }

    private static Optional<String> basisWord(DecisionTree tree, long basisNid) {
        DecisionTerms terms = tree.terms();
        if (basisNid == terms.estimatedBasis()) {
            return Optional.of("estimated");
        }
        if (basisNid == terms.citedBasis()) {
            return Optional.of("cited");
        }
        if (basisNid == terms.derivedBasis()) {
            return Optional.of("derived");
        }
        return Optional.empty();
    }

    // ── Parse ────────────────────────────────────────────────────────────────

    /**
     * Reads a block, or its body, into a decision tree.
     *
     * @param text  the block text, header and delimiters optional
     * @param terms the profile's concepts
     * @param ids   what resolves written identifiers
     * @return the tree when the text has a root decision, and every finding
     */
    public static Parsed parse(String text, DecisionTerms terms, Identities ids) {
        return parse(text, terms, ids, ExternalQuantities.none());
    }

    /**
     * Reads a block, adding as parameters the quantities it reads with {@code from} alone that it
     * states no parameter for and another block computes ({@code dev-calculator-blocks}): a
     * calculator's risk becomes a stated parameter of the tree in memory, on the semantic the
     * external gives it, never in the text. Such a parameter has no line, so the surface offers no
     * what-if on it.
     */
    public static Parsed parse(String text, DecisionTerms terms, Identities ids, ExternalQuantities externals) {
        Reader reader = new Reader(terms, ids);
        BlockLines.Parsed lines = BlockLines.parse(text);
        lines.problems().forEach(problem -> reader.findings.add(ProfileFinding.ofTree(problem)));
        if (lines.roots().size() != 1) {
            reader.findings.add(ProfileFinding.ofTree("a decision tree has one root line, not " + lines.roots().size()));
            return new Parsed(Optional.empty(), reader.findings.toImmutable());
        }
        Line root = lines.roots().get(0);
        if (root.form() != Form.VERTEX || !"decision".equals(root.word())) {
            reader.findings.add(finding(root, "the root is not a decision"));
            return new Parsed(Optional.empty(), reader.findings.toImmutable());
        }
        DecisionTree.Builder builder = DecisionTree.builder(terms);
        DecisionTree.Builder.Handle rootHandle = builder.decision(reader.concept(root, "a decision"), reader.vertexId(root));
        reader.lines.put(rootHandle.vertexIndex(), root.number());
        reader.attributes(root, rootHandle, false);
        reader.children(root, rootHandle, "decision");
        for (long quantity : reader.readQuantities) {
            if (reader.parameterQuantities.contains(quantity)) {
                continue;
            }
            // stated as the other block computed it, held to four significant figures since it is a computed
            // number, with no basis of its own: its working is the other block's
            externals.value(quantity).ifPresent(value -> rootHandle
                    .parameter(quantity, significant(value.bounds(), 4), value.semanticNid(),
                            UUID.nameUUIDFromBytes(("external parameter " + quantity).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
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

    /** Bounds held to a number of significant figures, the lower rounded down and the upper up, so they still contain the value. */
    static Bounds significant(Bounds bounds, int digits) {
        return new Bounds(significant(bounds.lower(), digits, false), significant(bounds.upper(), digits, true),
                bounds.lowerOpen(), bounds.upperOpen());
    }

    private static double significant(double value, int digits, boolean up) {
        if (value == 0 || Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        double magnitude = Math.pow(10, digits - 1 - Math.floor(Math.log10(Math.abs(value))));
        double scaled = value * magnitude;
        return (up ? Math.ceil(scaled - 1e-9) : Math.floor(scaled + 1e-9)) / magnitude;
    }

    /** One item of a value line's head: an interval, a concept token, or one of the words from, of and in. */
    private sealed interface Item permits IntervalItem, ConceptItem, Keyword {
    }

    private record IntervalItem(Tokens.Interval interval) implements Item {
    }

    private record ConceptItem(Tokens.Concept concept, String text) implements Item {
    }

    private record Keyword(String word) implements Item {
    }

    /** One parse: the terms and identities it reads by, and the findings it gathers. */
    private static final class Reader {
        private final DecisionTerms terms;
        private final Identities ids;
        private final MutableList<ProfileFinding> findings = Lists.mutable.empty();
        /** The line each vertex was read from. */
        private final MutableIntIntMap lines = IntIntMaps.mutable.empty();
        /** The identifiers the store lacked that the identities made local, in reading order. */
        private final Set<String> localSymbols = new LinkedHashSet<>();

        /** The quantities read with from alone, and the quantities the block's parameters name, for the externals. */
        private final Set<Long> readQuantities = new LinkedHashSet<>();
        private final Set<Long> parameterQuantities = new LinkedHashSet<>();

        private Reader(DecisionTerms terms, Identities ids) {
            this.terms = terms;
            this.ids = ids;
        }

        private void children(Line line, DecisionTree.Builder.Handle handle, String parentWord) {
            for (Line child : line.children()) {
                if (child.form() == Form.VALUE) {
                    measure(child, handle);
                    continue;
                }
                DecisionTree.Builder.Handle childHandle = switch (child.word()) {
                    case "alternative" -> handle.alternative(concept(child, "an alternative"), vertexId(child));
                    case "chance" -> handle.chance(concept(child, "a chance"), vertexId(child));
                    case "event" -> handle.event(concept(child, "an event"), vertexId(child));
                    case "residual" -> handle.residualEvent(concept(child, "a residual event"), vertexId(child));
                    case "decision" -> handle.decision(concept(child, "a decision"), vertexId(child));
                    case "outcome" -> handle.outcome(concept(child, "an outcome"), vertexId(child));
                    default -> null;
                };
                if (childHandle == null) {
                    findings.add(finding(child, "unknown kind of vertex under " + parentWord + ": " + child.word()));
                    continue;
                }
                lines.put(childHandle.vertexIndex(), child.number());
                attributes(child, childHandle, "outcome".equals(child.word()));
                children(child, childHandle, child.word());
            }
        }

        private void measure(Line line, DecisionTree.Builder.Handle node) {
            long kind;
            boolean share;
            boolean parameter = false;
            switch (line.word().toLowerCase(Locale.ROOT)) {
                case "probability", "p" -> {
                    kind = terms.probabilityMeasure();
                    share = true;
                }
                case "money" -> {
                    kind = terms.moneyCostMeasure();
                    share = false;
                }
                case "health" -> {
                    kind = terms.healthCostMeasure();
                    share = false;
                }
                case "parameter" -> {
                    kind = terms.parameterMeasure();
                    share = true;
                    parameter = true;
                }
                default -> {
                    findings.add(finding(line, "unknown value of a vertex: " + line.word()));
                    return;
                }
            }
            if (!line.children().isEmpty()) {
                findings.add(finding(line, "a measure has nothing beneath it"));
            }
            List<Item> items = items(line, line.head());
            Tokens.Interval interval = null;
            Tokens.Concept quantity = null;
            Tokens.Concept derivation = null;
            List<Tokens.Concept> operands = new ArrayList<>();
            Tokens.Concept unit = null;
            String mode = "";
            for (Item item : items) {
                switch (item) {
                    case IntervalItem i -> {
                        if (interval != null) {
                            findings.add(finding(line, "a measure has one interval"));
                        }
                        interval = i.interval();
                    }
                    case Keyword k -> mode = k.word();
                    case ConceptItem c -> {
                        switch (mode) {
                            case "from" -> derivation = c.concept();
                            case "of" -> operands.add(c.concept());
                            case "in" -> unit = c.concept();
                            default -> {
                                if (parameter && quantity == null) {
                                    quantity = c.concept();
                                } else if (!parameter && unit == null && interval != null) {
                                    unit = c.concept();
                                } else {
                                    findings.add(finding(line, "an unexpected token: " + c.text()));
                                }
                            }
                        }
                    }
                }
            }
            if (interval == null && derivation == null) {
                findings.add(finding(line, "a measure begins with an interval, [lo,hi], or is derived from a kind: " + line.head()));
                return;
            }
            if (interval != null && derivation != null) {
                findings.add(finding(line, "a measure is stated or derived, not both"));
            }
            long semantic;
            if (unit != null) {
                semantic = resolveConcept(line, unit, "a measure semantic");
            } else if (share) {
                semantic = terms.shareAtNode();
            } else {
                semantic = 0;
                findings.add(finding(line, "a cost names its measure semantic" + (derivation != null ? " after in" : " after its interval")));
            }
            UUID vertexId = vertexId(line);
            DecisionTree.Builder.Handle measure;
            if (derivation != null) {
                // from a quantity alone, with nothing after of, reads that parameter as it is
                boolean reads = operands.isEmpty() && !namesDerivationKind(derivation);
                long kindNid = reads ? resolveConcept(line, derivation, "a quantity a measure reads")
                        : derivationKind(line, derivation);
                if (reads && kindNid != 0) {
                    readQuantities.add(kindNid);
                }
                if (operands.isEmpty() && !reads) {
                    findings.add(finding(line, "a derivation reads its operands after of"));
                }
                if (parameter && quantity == null && !operands.isEmpty()) {
                    // the quantity written after the operands instead of before from: unambiguous when
                    // the kind reads a fixed number of operands and one token is left over
                    OptionalInt arity = terms.arity(kindNid);
                    if (arity.isPresent() && operands.size() == arity.getAsInt() + 1) {
                        quantity = operands.remove(operands.size() - 1);
                        findings.add(finding(line, "a derived parameter names its quantity before from; read "
                                + Tokens.concept(quantity.label(), quantity.identifier().orElse(null)) + " as the quantity"));
                    }
                }
                long[] operandNids = operands.stream()
                        .mapToLong(operand -> resolveConcept(line, operand, "an operand")).toArray();
                if (parameter) {
                    long quantityNid = quantity == null ? 0 : resolveConcept(line, quantity, "a parameter's quantity");
                    if (quantity == null) {
                        findings.add(finding(line, "a parameter names its quantity"));
                    }
                    parameterQuantities.add(quantityNid);
                    measure = node.derivedParameter(quantityNid, kindNid, operandNids, semantic, vertexId);
                } else {
                    measure = node.derived(kind, kindNid, operandNids, semantic, vertexId);
                }
            } else {
                Bounds bounds = new Bounds(
                        interval.lower() == null ? Double.NEGATIVE_INFINITY : interval.lower(),
                        interval.upper() == null ? Double.POSITIVE_INFINITY : interval.upper(),
                        !interval.includeLower(), !interval.includeUpper());
                if (parameter) {
                    long quantityNid = quantity == null ? 0 : resolveConcept(line, quantity, "a parameter's quantity");
                    if (quantity == null) {
                        findings.add(finding(line, "a parameter names its quantity"));
                    }
                    parameterQuantities.add(quantityNid);
                    measure = node.parameter(quantityNid, bounds, semantic, vertexId);
                } else {
                    measure = node.measure(kind, bounds, semantic, vertexId);
                }
            }
            lines.put(measure.vertexIndex(), line.number());
            Map<String, String> attributes = line.attributes();
            attributes.forEach((name, value) -> {
                switch (name) {
                    case "basis" -> basis(line, value).ifPresent(measure::basis);
                    case "source" -> resolve(line, value, "a basis source").ifPresent(measure::basisSource);
                    case "@vertex" -> { }
                    default -> findings.add(finding(line, "unknown attribute of a measure: " + name));
                }
            });
        }

        /** The head of a value line as items: intervals, concept tokens with their @id, and the three keywords. */
        private List<Item> items(Line line, String head) {
            List<Item> items = new ArrayList<>();
            List<String> words = Tokens.words(head);
            String keyword = "";
            for (int i = 0; i < words.size(); i++) {
                String word = words.get(i);
                if ("from".equals(keyword) && !word.startsWith("k:") && terms.derivationKind(word).isPresent()) {
                    // a derivation kind written bare after from, as it reads naturally: from CumulativeHazard of ...
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
                } else if (List.of("from", "of", "in").contains(word.toLowerCase(Locale.ROOT))) {
                    keyword = word.toLowerCase(Locale.ROOT);
                    items.add(new Keyword(keyword));
                } else {
                    findings.add(finding(line, "an unexpected word: " + word));
                }
            }
            return items;
        }

        private void attributes(Line line, DecisionTree.Builder.Handle handle, boolean outcome) {
            line.attributes().forEach((name, value) -> {
                switch (name) {
                    case "statement" -> {
                        for (String identifier : value.split(";")) {
                            resolve(line, identifier, "a statement").ifPresent(handle::statement);
                        }
                    }
                    case "input" -> resolve(line, value, "an input kind").ifPresent(handle::inputKind);
                    case "tree" -> {
                        if (outcome) {
                            resolve(line, value, "a tree reference").ifPresent(handle::treeReference);
                        } else {
                            findings.add(finding(line, "only an outcome folds in another tree"));
                        }
                    }
                    case "@vertex" -> { }
                    default -> findings.add(finding(line, "unknown attribute of a vertex: " + name));
                }
            });
        }

        /** The concept a vertex line names in its head, or zero with a finding. */
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

        /**
         * The derivation kind a token names: by its identity when the store has it, else by its
         * name, as the documentation build reads {@code from k:Complement[] of ...} with no
         * identity at all; a kind known neither way resolves as any concept would.
         */
        /** Whether a token after from names a derivation kind, as against a quantity read as it is. */
        private boolean namesDerivationKind(Tokens.Concept token) {
            if (token.identifier().isPresent()) {
                OptionalLong stored = ids.nid(token.identifier().get().strip());
                if (stored.isPresent() && terms.isDerivationKind(stored.getAsLong())) {
                    return true;
                }
            }
            return terms.derivationKind(token.label()).isPresent() || terms.derivationKind(token.key()).isPresent();
        }

        private long derivationKind(Line line, Tokens.Concept token) {
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
            return resolveConcept(line, token, "a derivation kind");
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

        /**
         * The nid an identifier names: in the store, else local to the block when the identities
         * admit one (recorded once per identifier, reported together at the end), else a finding.
         */
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
                case "estimated" -> OptionalLong.of(terms.estimatedBasis());
                case "cited" -> OptionalLong.of(terms.citedBasis());
                case "derived" -> OptionalLong.of(terms.derivedBasis());
                default -> {
                    findings.add(finding(line, "a basis is estimated, cited or derived, not " + word));
                    yield OptionalLong.empty();
                }
            };
        }
    }

    /** The words a vertex line may begin with, for a renderer deciding whether a block is this profile's. */
    public static List<String> vertexWords() {
        return List.of("decision", "alternative", "chance", "event", "residual", "outcome");
    }
}
