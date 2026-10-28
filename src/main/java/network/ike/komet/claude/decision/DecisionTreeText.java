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
import network.ike.komet.claude.decision.DecisionElement.Probability;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The decision-tree profile in text: the {@code [decision-tree]} block, sugar for the
 * property-graph block with this profile ({@code dev-decision-tree-blocks}, settled KEC
 * 2026-10-27). One vertex per line, its kind word first, indentation the edge, measures as value
 * lines under the node that carries them:
 *
 * <pre>
 * [decision-tree]
 * ----
 * decision: k:ScanNow[Scan now?] @id=… @vertex=…
 *   alternative: k:Scan[Scan] @id=… statement=… @vertex=…
 *     money = [700,700] k:Usd[US dollar at 2026 prices] @id=… basis=estimated @vertex=…
 *     health = [0.002,0.002] k:Qaly[Quality-adjusted life year] @id=… @vertex=…
 *     chance: k:MraResult[MRA result] @id=… @vertex=…
 *       event: k:Positive[Scan positive] @id=… @vertex=…
 *         probability = [0.214,0.214] @vertex=…
 *         …
 *       residual: k:Negative[Scan negative] @id=… @vertex=…
 *         …
 *   alternative: k:NoScan[No scan] @id=… @vertex=…
 *     …
 * ----
 * </pre>
 *
 * <p><b>Lossless by identity.</b> A concept writes as the {@code k:} token of the ANF block,
 * its {@code @id} the identity and its key and label human aids recomputed on every emit; every
 * vertex writes its own identity as {@code @vertex}, so a block parsed and written again holds
 * the same vertices and a revised number is a new version of one of them. A measure writes in
 * the ANF interval grammar, a probability leaving its semantic implicit, a cost naming its money
 * or health semantic. Nothing computed is written.
 *
 * <p><b>Deterministic emission.</b> Measures before nodes, each in stored order; one fixed
 * attribute order; integral numbers without a fraction and others exactly as Java prints them.
 * Store to text to store is byte-stable, so a parameter edit changes one line in a diff.
 *
 * <p><b>Parsing never refuses.</b> An unknown word, an identifier that does not resolve, a
 * measure with no semantic: each is a finding with its line, and the vertex is written with the
 * gap, as the ANF block keeps a clarify. The caller decides whether the tree is usable, and
 * {@link DecisionTree#check()} says whether it is well formed.
 */
public final class DecisionTreeText {

    /** The block's language tag, and the profile's name under the property-graph block. */
    public static final String TAG = "decision-tree";

    private static final String INDENT = "  ";
    private static final String DELIMITER = "----";

    private DecisionTreeText() {
    }

    /** What a parse produced: the tree, when there was a root to build from, and every finding. */
    public record Parsed(Optional<DecisionTree> tree, ImmutableList<ProfileFinding> findings) {
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
        for (DecisionElement child : tree.children(node.vertexIndex())) {
            if (child instanceof Node) {
                continue;
            }
            measure(sb, tree, ids, child, depth + 1);
        }
        for (Node child : tree.children(node.vertexIndex(), Node.class)) {
            node(sb, tree, ids, child, depth + 1);
        }
    }

    private static void measure(StringBuilder sb, DecisionTree tree, Identities ids, DecisionElement element, int depth) {
        Measure measure = switch (element) {
            case Probability probability -> probability.measure();
            case Cost cost -> cost.measure();
            case Node node -> throw new IllegalStateException("a node is not a measure");
        };
        Bounds bounds = measure.bounds();
        sb.append(INDENT.repeat(depth)).append(word(element)).append(" = ")
                .append(Tokens.interval(finite(bounds.lower()), finite(bounds.upper()),
                        !bounds.lowerOpen(), !bounds.upperOpen()));
        boolean implicit = element instanceof Probability && measure.semanticNid() == tree.terms().shareAtNode();
        if (!implicit && measure.semanticNid() != 0) {
            concept(sb, ids, OptionalLong.of(measure.semanticNid()));
        }
        measure.basisNid().ifPresent(nid -> basisWord(tree, nid).ifPresent(word -> sb.append(" basis=").append(word)));
        tree.conceptProperty(element.vertexIndex(), tree.terms().basisSource())
                .ifPresent(nid -> reference(sb, ids, "source", nid));
        measure.derivedFrom().ifPresent(nid -> reference(sb, ids, "derived", nid));
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

    private static void vertex(StringBuilder sb, DecisionTree tree, int vertexIndex) {
        EntityVertex vertex = tree.vertex(vertexIndex);
        sb.append(" @vertex=").append(new UUID(vertex.mostSignificantBits(), vertex.leastSignificantBits()))
                .append('\n');
    }

    /** A reference attribute, written only when the store can name the component: a nid is local and never written. */
    private static void reference(StringBuilder sb, Identities ids, String name, long nid) {
        ids.identifier(nid).ifPresent(id -> sb.append(' ').append(name).append('=').append(id));
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
        reader.attributes(root, rootHandle, false);
        reader.children(root, rootHandle, "decision");
        return new Parsed(Optional.of(builder.tree()), reader.findings.toImmutable());
    }

    private static ProfileFinding finding(Line line, String message) {
        return ProfileFinding.ofTree("line " + line.number() + ": " + message);
    }

    /** One parse: the terms and identities it reads by, and the findings it gathers. */
    private static final class Reader {
        private final DecisionTerms terms;
        private final Identities ids;
        private final MutableList<ProfileFinding> findings = Lists.mutable.empty();

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
                attributes(child, childHandle, "outcome".equals(child.word()));
                children(child, childHandle, child.word());
            }
        }

        private void measure(Line line, DecisionTree.Builder.Handle node) {
            long kind;
            boolean probability = false;
            switch (line.word().toLowerCase(Locale.ROOT)) {
                case "probability", "p" -> {
                    kind = terms.probabilityMeasure();
                    probability = true;
                }
                case "money" -> kind = terms.moneyCostMeasure();
                case "health" -> kind = terms.healthCostMeasure();
                default -> {
                    findings.add(finding(line, "unknown value of a vertex: " + line.word()));
                    return;
                }
            }
            if (!line.children().isEmpty()) {
                findings.add(finding(line, "a measure has nothing beneath it"));
            }
            Optional<Tokens.IntervalPrefix> prefix = Tokens.intervalPrefix(line.head());
            if (prefix.isEmpty()) {
                findings.add(finding(line, "a measure begins with an interval, [lo,hi]: " + line.head()));
                return;
            }
            Tokens.Interval interval = prefix.get().interval();
            Bounds bounds = new Bounds(
                    interval.lower() == null ? Double.NEGATIVE_INFINITY : interval.lower(),
                    interval.upper() == null ? Double.POSITIVE_INFINITY : interval.upper(),
                    !interval.includeLower(), !interval.includeUpper());
            long semantic;
            String rest = prefix.get().rest();
            if (rest.isEmpty()) {
                semantic = probability ? terms.shareAtNode() : 0;
                if (!probability) {
                    findings.add(finding(line, "a cost names its measure semantic after its interval"));
                }
            } else {
                semantic = resolveConcept(line, rest, "a measure semantic");
            }
            DecisionTree.Builder.Handle measure = node.measure(kind, bounds, semantic, vertexId(line));
            Map<String, String> attributes = line.attributes();
            attributes.forEach((name, value) -> {
                switch (name) {
                    case "basis" -> basis(line, value).ifPresent(measure::basis);
                    case "source" -> resolve(line, value, "a basis source").ifPresent(measure::basisSource);
                    case "derived" -> resolve(line, value, "a derivation").ifPresent(measure::derivedFrom);
                    case "@vertex" -> { }
                    default -> findings.add(finding(line, "unknown attribute of a measure: " + name));
                }
            });
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
            return resolveConcept(line, head, what);
        }

        private long resolveConcept(Line line, String text, String what) {
            Optional<Tokens.Concept> token = Tokens.concept(text);
            if (token.isEmpty()) {
                findings.add(finding(line, what + " is not a k: token: " + text));
                return 0;
            }
            if (token.get().identifier().isEmpty()) {
                findings.add(finding(line, what + " carries no @id: " + text));
                return 0;
            }
            return resolve(line, token.get().identifier().get(), what).orElse(0L);
        }

        private OptionalLong resolve(Line line, String identifier, String what) {
            String id = identifier.strip();
            if (id.isEmpty()) {
                return OptionalLong.empty();
            }
            OptionalLong nid = ids.nid(id);
            if (nid.isEmpty()) {
                findings.add(finding(line, what + " does not resolve: " + id));
            }
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
