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
package network.ike.komet.claude.complexconcept;

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfSemantic;
import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.BlockLines.Form;
import network.ike.komet.claude.block.BlockLines.Line;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Expansion;
import network.ike.komet.claude.complexconcept.model.ComplexConceptBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The written form of a complex concept's specification: a {@code complex-concept} fenced block,
 * read into a store-free {@link Spec} and built into a specification tree through
 * {@link ComplexConceptBuilder} (ike-lab-documents topics {@code dev-decision-point-knowledge}
 * and {@code anf-complex-concept-architecture}; IKE-Network/ike-issues#812, #1312).
 *
 * <p>One line per vertex, two spaces of indentation per level, the concept tokens of the
 * decision-tree block:
 * <pre>
 * concept: k:SurveillanceDecision[Surveillance imaging decision after aneurysm seal] @id=rda-surveillance-decision
 *   parent: k:ClinicalDecision[Clinical decision] @id=…
 *   expansion: focused k:SurveillanceDecision[] @id=rda-surveillance-decision
 *     prerequisite: k:AneurysmSealed[Intracranial aneurysm sealed by endovascular treatment] @id=… association=precondition
 *       value = [1,1] k:Presence[presence] @id=…
 *     prerequisite: k:TimeSinceSeal[Time since the seal] @id=…
 *       value = [2,2] k:Year[year] @id=…
 * </pre>
 * or, for a uniform expansion,
 * <pre>
 * concept: k:Perrla[PERRLA panel] @id=…
 *   parent: k:AssessmentPanel[Clinical assessment panel] @id=…
 *   expansion: uniform and k:Presence[presence] @id=…
 *     member: k:PupilEquality[Pupil equality assessment] @id=…
 * </pre>
 * The description logic definition is derived, as the architecture prescribes: a focused
 * complex concept is a primitive subtype of its parent, which defaults to its focus; a uniform
 * one a primitive subtype of its grouper parent. A prerequisite's {@code value} is its fixed,
 * definitional value on the unit it names: presence is {@code [1,1]}, absence {@code [0,0]}.
 * An OR conjunction carries {@code unsafe=refuse|flag|retain}, how a non-absent value is handled.
 *
 * <p><b>Parsing never refuses.</b> A line that cannot be read is a finding; an identifier the
 * store lacks is a concept local to the block, numbered through the identities so a
 * decision-tree block in the same document names the same concept; what the specification
 * still lacks is listed by {@link #completeness}, as the builder lists it.
 */
public final class ComplexConceptText {

    /** The fenced-block language tag. */
    public static final String TAG = "complex-concept";

    /** The namespace of the public id a local concept is proposed under. */
    static final UUID LOCAL_NAMESPACE = UUID.fromString("3b1c8f2e-6d5a-4c1b-9e7f-2a0d4c6e8b10");

    private ComplexConceptText() {
    }

    /** Whether a fence's info string names this block. */
    public static boolean isTagged(String info) {
        if (info == null) {
            return false;
        }
        String first = info.strip();
        int end = first.indexOf(' ');
        if (end > 0) {
            first = first.substring(0, end);
        }
        return first.equals(TAG) || first.startsWith(TAG + ",");
    }

    // ── The spec ─────────────────────────────────────────────────────────────

    /** A concept the block names: as written, and the nid it resolves to, local to the block or not. */
    public record Ref(Tokens.Concept token, long nid, boolean local) {
        /** The label the block gives it, else its key, else its identifier. */
        public String label() {
            if (token.label() != null && !token.label().isBlank()) {
                return token.label();
            }
            if (token.key() != null && !token.key().isBlank()) {
                return token.key();
            }
            return token.identifier().orElse("");
        }
    }

    /** A prerequisite of a focused expansion: the condition, its fixed value and how it relates to the focus. */
    public record Prerequisite(Ref topic, Optional<Tokens.Interval> value, Optional<Ref> unit, String association, int line) {
    }

    /** The expansion, one of two shapes. */
    public sealed interface Shape permits Focused, Uniform {
    }

    /** A focused expansion: the phenomenon the provided value applies to, and its prerequisites. */
    public record Focused(Ref focus, List<Prerequisite> prerequisites) implements Shape {
    }

    /** A uniform expansion: its conjunction, the domain every member shares, the members, and the OR policy. */
    public record Uniform(String conjunction, Optional<Ref> domain, List<Ref> members, Optional<String> unsafe) implements Shape {
    }

    /** A parsed block: the complex concept, its parent when stated, and its expansion when it has one. */
    public record Spec(Ref concept, Optional<Ref> parent, Optional<Shape> shape) {
        /** The parent the definition states: the one written, else a focused expansion's focus. */
        public Optional<Ref> definitionParent() {
            if (parent.isPresent()) {
                return parent;
            }
            return shape.filter(Focused.class::isInstance).map(s -> ((Focused) s).focus());
        }

        /** Every concept the block names, the complex concept first. */
        public List<Ref> refs() {
            List<Ref> refs = new ArrayList<>();
            refs.add(concept);
            parent.ifPresent(refs::add);
            shape.ifPresent(s -> {
                switch (s) {
                    case Focused focused -> {
                        refs.add(focused.focus());
                        for (Prerequisite prerequisite : focused.prerequisites()) {
                            refs.add(prerequisite.topic());
                            prerequisite.unit().ifPresent(refs::add);
                        }
                    }
                    case Uniform uniform -> {
                        uniform.domain().ifPresent(refs::add);
                        refs.addAll(uniform.members());
                    }
                }
            });
            return refs;
        }
    }

    /** What a block read as: the spec when its root is a complex concept, every finding, and the symbols made local. */
    public record Parsed(Optional<Spec> spec, List<String> findings, List<String> localSymbols) {
    }

    // ── Parse ────────────────────────────────────────────────────────────────

    /**
     * Reads a block's body.
     *
     * @param literal the block's text
     * @param ids     what resolves written identifiers, admitting ones local to the block
     * @return the spec, the findings, and the local symbols
     */
    public static Parsed parse(String literal, Identities ids) {
        Reader reader = new Reader(ids);
        BlockLines.Parsed lines = BlockLines.parse(literal == null ? "" : literal);
        reader.findings.addAll(lines.problems());
        if (lines.roots().size() != 1) {
            reader.findings.add(lines.roots().isEmpty() ? "the block is empty" : "the block has more than one root");
            return reader.done(Optional.empty());
        }
        Line root = lines.roots().get(0);
        if (root.form() != Form.VERTEX || !"concept".equals(root.word())) {
            reader.findings.add("line " + root.number() + ": the root is not a concept");
            return reader.done(Optional.empty());
        }
        Optional<Ref> concept = reader.concept(root, "the complex concept");
        if (concept.isEmpty()) {
            return reader.done(Optional.empty());
        }
        reader.conceptNid = concept.get().nid();
        Optional<Ref> parent = Optional.empty();
        Optional<Shape> shape = Optional.empty();
        for (Line child : root.children()) {
            if (child.form() != Form.VERTEX) {
                reader.findings.add("line " + child.number() + ": a value line where a vertex was expected: " + child.word());
                continue;
            }
            switch (child.word()) {
                case "parent" -> {
                    if (parent.isPresent()) {
                        reader.findings.add("line " + child.number() + ": a second parent");
                    } else {
                        parent = reader.concept(child, "the parent");
                    }
                    reader.noChildren(child);
                }
                case "expansion" -> {
                    if (shape.isPresent()) {
                        reader.findings.add("line " + child.number() + ": a second expansion; a specification has one");
                    } else {
                        shape = reader.expansion(child);
                    }
                }
                default -> reader.findings.add("line " + child.number() + ": unknown word " + child.word()
                        + "; a complex concept has parent and expansion lines");
            }
        }
        return reader.done(Optional.of(new Spec(concept.get(), parent, shape)));
    }

    /** What a spec still lacks to be a complete specification, as the builder would list it; empty when nothing. */
    public static List<String> completeness(Spec spec) {
        List<String> missing = new ArrayList<>();
        if (spec.definitionParent().isEmpty()) {
            missing.add("a definition: a parent line");
        }
        if (spec.shape().isEmpty()) {
            missing.add("an expansion, uniform or focused");
            return missing;
        }
        switch (spec.shape().get()) {
            case Uniform uniform -> {
                if (uniform.domain().isEmpty()) {
                    missing.add("the uniform expansion's measurement domain");
                }
                if (uniform.members().size() < 2) {
                    missing.add("at least two members");
                }
                if ("or".equals(uniform.conjunction()) && uniform.unsafe().isEmpty()) {
                    missing.add("how the OR conjunction handles a non-absent value: unsafe=refuse, flag or retain");
                }
            }
            case Focused focused -> {
                if (focused.prerequisites().isEmpty()) {
                    missing.add("at least one prerequisite");
                }
                for (Prerequisite prerequisite : focused.prerequisites()) {
                    if (prerequisite.value().isEmpty()) {
                        missing.add("a fixed value for " + prerequisite.topic().label());
                    }
                }
            }
        }
        return missing;
    }

    /** One sentence saying what the complex concept is, for a tooltip beside a chip that names it. */
    public static String describe(Spec spec) {
        StringBuilder sb = new StringBuilder("Complex concept");
        spec.definitionParent().ifPresent(parent -> sb.append(", a kind of ").append(parent.label()));
        if (spec.shape().isEmpty()) {
            return sb.append('.').toString();
        }
        switch (spec.shape().get()) {
            case Focused focused -> {
                sb.append("; focused on ").append(focused.focus().label());
                if (!focused.prerequisites().isEmpty()) {
                    sb.append(", given ");
                    List<String> clauses = new ArrayList<>();
                    for (Prerequisite prerequisite : focused.prerequisites()) {
                        clauses.add(prerequisite.topic().label() + " " + valueWords(prerequisite));
                    }
                    sb.append(String.join(", ", clauses));
                }
            }
            case Uniform uniform -> {
                sb.append("; ").append("and".equals(uniform.conjunction()) ? "every member" : "any member");
                uniform.domain().ifPresent(domain -> sb.append(" on ").append(domain.label()));
                if (!uniform.members().isEmpty()) {
                    sb.append(": ");
                    List<String> names = new ArrayList<>();
                    for (Ref member : uniform.members()) {
                        names.add(member.label());
                    }
                    sb.append(String.join(", ", names));
                }
            }
        }
        return sb.append('.').toString();
    }

    /** A prerequisite's value in words: present, absent, a number with its unit, or "any". */
    public static String valueWords(Prerequisite prerequisite) {
        if (prerequisite.value().isEmpty()) {
            return "(no value)";
        }
        Tokens.Interval value = prerequisite.value().get();
        String unit = prerequisite.unit().map(Ref::label).orElse("");
        boolean presence = unit.toLowerCase(Locale.ROOT).startsWith("presence");
        Double lower = value.lower();
        Double upper = value.upper();
        if (presence && lower != null && upper != null && lower == 0 && upper == 0) {
            return "absent";
        }
        if (presence && lower != null && lower == 1 && (upper == null || upper == 1)) {
            return "present";
        }
        // a count is its number; a lower-case unit word is plural beside any number but one
        String suffix = unit.isEmpty() || unit.equalsIgnoreCase("count") ? "" : " " + unit;
        boolean one = lower != null && upper != null && lower.equals(upper) && lower == 1;
        if (!suffix.isEmpty() && !one && unit.matches("[a-z]+") && !unit.endsWith("s")) {
            suffix = suffix + "s";
        }
        if (lower != null && upper != null && lower.equals(upper)) {
            return Tokens.number(lower) + suffix;
        }
        if (lower != null && upper == null) {
            return (value.includeLower() ? "at least " : "more than ") + Tokens.number(lower) + suffix;
        }
        if (lower == null && upper != null) {
            return (value.includeUpper() ? "at most " : "less than ") + Tokens.number(upper) + suffix;
        }
        if (lower == null) {
            return "any" + suffix;
        }
        return Tokens.number(lower) + " to " + Tokens.number(upper) + suffix;
    }

    // ── Build ────────────────────────────────────────────────────────────────

    /**
     * The facade a block's concept builds with: the store's concept when it holds one, else a
     * proxy under a public id derived from the symbol, the id the concept is proposed under.
     */
    public static ConceptFacade facade(Ref ref) {
        if (!ref.local()) {
            return ConceptFacade.make(ref.nid());
        }
        return EntityProxy.Concept.make(ref.label(), PublicIds.of(localPublicId(ref)));
    }

    /** The public id a local concept is proposed under: one per symbol, wherever it is written. */
    public static UUID localPublicId(Ref ref) {
        return UuidT5Generator.get(LOCAL_NAMESPACE, "complex-concept block: " + ref.token().identifier().orElse(ref.label()));
    }

    /**
     * Builds the specification tree a spec states, through the builder that keeps the rules.
     *
     * @param spec   the spec
     * @param facade what each concept builds as
     * @return the builder holding the tree
     * @throws network.ike.komet.claude.complexconcept.model.ComplexConceptRefusal if the spec breaks a rule
     */
    public static ComplexConceptBuilder builder(Spec spec, Function<Ref, ConceptFacade> facade) {
        ComplexConceptBuilder builder = new ComplexConceptBuilder();
        spec.definitionParent().ifPresent(parent ->
                builder.definition(AnfExpressionBuilder.singleConceptExpression(facade.apply(parent))));
        if (spec.shape().isEmpty()) {
            return builder;
        }
        switch (spec.shape().get()) {
            case Focused focused -> {
                Expansion.Focused expansion = builder.focusedExpansion(facade.apply(focused.focus()));
                for (Prerequisite prerequisite : focused.prerequisites()) {
                    builder.prerequisite(expansion, statement(prerequisite, facade), association(prerequisite.association()));
                }
            }
            case Uniform uniform -> {
                ConceptFacade conjunction = "or".equals(uniform.conjunction()) ? IkeConcept.OR_CONJUNCTION : IkeConcept.AND_CONJUNCTION;
                Expansion.Uniform expansion = builder.uniformExpansion(conjunction,
                        uniform.domain().map(facade).orElse(null));
                for (Ref member : uniform.members()) {
                    builder.member(expansion, facade.apply(member));
                }
                uniform.unsafe().ifPresent(policy -> builder.setUnsafeDistributionPolicy(expansion, policy(policy)));
            }
        }
        return builder;
    }

    /** A prerequisite as an ANF statement: the condition as its topic, its fixed value as the performance's result. */
    static DiTreeEntity statement(Prerequisite prerequisite, Function<Ref, ConceptFacade> facade) {
        AnfExpressionBuilder statement = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        statement.expression(statement.context(), AnfSemantic.TOPIC, facade.apply(prerequisite.topic()));
        if (prerequisite.value().isPresent()) {
            Tokens.Interval value = prerequisite.value().get();
            statement.measure(statement.circumstance(), AnfSemantic.RESULT,
                    value.lower() == null ? null : BigDecimal.valueOf(value.lower()),
                    value.upper() == null ? null : BigDecimal.valueOf(value.upper()),
                    value.includeLower(), value.includeUpper(), null,
                    prerequisite.unit().map(facade).orElse(null));
        }
        return statement.asDiTree();
    }

    static ConceptFacade association(String word) {
        return IkeConcept.PRECONDITION_ASSOCIATION;
    }

    static ConceptFacade policy(String word) {
        return switch (word) {
            case "flag" -> IkeConcept.FLAG_AS_INDETERMINATE_POSITIVE;
            case "retain" -> IkeConcept.RETAIN_COMPOUND_CODE;
            default -> IkeConcept.REFUSE_EXPANSION;
        };
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /** The block's text a spec states: the canonical form, one indentation unit, fixed line order. */
    public static String write(Spec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("concept: ").append(token(spec.concept())).append('\n');
        spec.parent().ifPresent(parent -> sb.append("  parent: ").append(token(parent)).append('\n'));
        spec.shape().ifPresent(shape -> {
            switch (shape) {
                case Focused focused -> {
                    sb.append("  expansion: focused ").append(token(focused.focus())).append('\n');
                    for (Prerequisite prerequisite : focused.prerequisites()) {
                        sb.append("    prerequisite: ").append(token(prerequisite.topic())).append('\n');
                        prerequisite.value().ifPresent(value -> {
                            sb.append("      value = ").append(Tokens.interval(value.lower(), value.upper(),
                                    value.includeLower(), value.includeUpper()));
                            prerequisite.unit().ifPresent(unit -> sb.append(' ').append(token(unit)));
                            sb.append('\n');
                        });
                    }
                }
                case Uniform uniform -> {
                    sb.append("  expansion: uniform ").append(uniform.conjunction());
                    uniform.domain().ifPresent(domain -> sb.append(' ').append(token(domain)));
                    uniform.unsafe().ifPresent(policy -> sb.append(" unsafe=").append(policy));
                    sb.append('\n');
                    for (Ref member : uniform.members()) {
                        sb.append("    member: ").append(token(member)).append('\n');
                    }
                }
            }
        });
        return sb.toString();
    }

    private static String token(Ref ref) {
        return Tokens.concept(ref.label(), ref.token().identifier().orElse(null));
    }

    // ── Read back from a tree ────────────────────────────────────────────────

    /**
     * The facades a spec builds with, remembering which proxy stands for which local symbol, so a
     * tree an editor hands back reads to the same symbols.
     */
    public static final class Proxies implements Function<Ref, ConceptFacade> {
        private final java.util.Map<Long, Ref> byNid = new java.util.HashMap<>();

        @Override
        public ConceptFacade apply(Ref ref) {
            ConceptFacade facade = facade(ref);
            byNid.putIfAbsent(facade.nid(), ref);
            return facade;
        }

        /** The ref a nid stood for when the tree was built, local or grounded; empty for a concept the editor introduced. */
        public Optional<Ref> ref(long nid) {
            return Optional.ofNullable(byNid.get(nid));
        }
    }

    /**
     * The spec a specification tree states, after an editor has changed it: the concept stays the
     * block's own, and every other concept reads back through the proxies it was built with, or
     * through the identities when the editor introduced it from the store.
     *
     * @param tree     the edited tree
     * @param original the spec the tree was built from
     * @param ids      the identities the block resolves through
     * @param proxies  the facades the tree was built with
     * @return the spec
     */
    public static Spec spec(DiTreeEntity tree, Spec original, Identities ids, Proxies proxies) {
        network.ike.komet.claude.complexconcept.model.ComplexConcept.Root root =
                new network.ike.komet.claude.complexconcept.model.ComplexConceptExpression(tree).root();
        Function<ConceptFacade, Ref> ref = facade -> proxies.ref(facade.nid()).orElseGet(() -> grounded(facade.nid(), ids));
        Optional<Ref> definitionParent = root.definition()
                .flatMap(network.ike.komet.claude.complexconcept.model.ComplexConcept.Definition::expression)
                .flatMap(expression -> singleConcept(expression))
                .map(ref);
        Optional<Shape> shape = root.expansion().map(expansion -> switch (expansion) {
            case Expansion.Focused focused -> {
                Ref focus = focused.focus().map(ref).orElse(original.shape()
                        .filter(Focused.class::isInstance).map(f -> ((Focused) f).focus()).orElse(original.concept()));
                List<Prerequisite> prerequisites = new ArrayList<>();
                for (network.ike.komet.claude.complexconcept.model.ComplexConcept.Prerequisite prerequisite
                        : focused.prerequisites()) {
                    prerequisite.statement().ifPresent(statement -> {
                        var statementRoot = statement.root();
                        Optional<Ref> topic = statementRoot.context()
                                .flatMap(network.ike.komet.claude.anf.model.AnfStatement.Context::topic)
                                .flatMap(network.ike.komet.claude.anf.model.AnfStatement.Attribute.Expression::singleConcept)
                                .map(ref);
                        if (topic.isEmpty()) {
                            return;
                        }
                        Optional<network.ike.komet.claude.anf.model.AnfStatement.Attribute.Measure> result = statementRoot.circumstance()
                                .filter(network.ike.komet.claude.anf.model.AnfStatement.Circumstance.Performance.class::isInstance)
                                .flatMap(c -> ((network.ike.komet.claude.anf.model.AnfStatement.Circumstance.Performance) c).result());
                        Optional<Tokens.Interval> value = result.map(measure -> new Tokens.Interval(
                                measure.lowerBound().map(BigDecimal::doubleValue).orElse(null),
                                measure.upperBound().map(BigDecimal::doubleValue).orElse(null),
                                measure.includeLowerBound(), measure.includeUpperBound()));
                        Optional<Ref> unit = result.flatMap(network.ike.komet.claude.anf.model.AnfStatement.Attribute.Measure::unit).map(ref);
                        prerequisites.add(new Prerequisite(topic.get(), value, unit, "precondition", 0));
                    });
                }
                yield new Focused(focus, List.copyOf(prerequisites));
            }
            case Expansion.Uniform uniform -> {
                String conjunction = uniform.conjunction().map(c -> c.nid() == IkeConcept.OR_CONJUNCTION.nid() ? "or" : "and").orElse("and");
                Optional<Ref> domain = uniform.measurementDomain().map(ref);
                List<Ref> members = new ArrayList<>();
                for (network.ike.komet.claude.complexconcept.model.ComplexConcept.Member member : uniform.members()) {
                    member.phenomenon().map(ref).ifPresent(members::add);
                }
                Optional<String> unsafe = uniform.unsafeDistributionPolicy().map(policy ->
                        policy.nid() == IkeConcept.FLAG_AS_INDETERMINATE_POSITIVE.nid() ? "flag"
                                : policy.nid() == IkeConcept.RETAIN_COMPOUND_CODE.nid() ? "retain" : "refuse");
                yield new Uniform(conjunction, domain, List.copyOf(members), unsafe);
            }
        });
        // a parent equal to a focused expansion's focus was derived, not written, unless the block wrote one
        Optional<Ref> parent = definitionParent;
        if (original.parent().isEmpty() && shape.isPresent() && shape.get() instanceof Focused focused
                && definitionParent.isPresent() && definitionParent.get().nid() == focused.focus().nid()) {
            parent = Optional.empty();
        }
        return new Spec(original.concept(), parent, shape);
    }

    private static Optional<ConceptFacade> singleConcept(
            dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpression expression) {
        for (var set : expression.definitionRoot().sets()) {
            for (var connective : set.elements()) {
                for (var atom : connective.elements()) {
                    if (atom instanceof dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalAxiom.Atom.ConceptAxiom conceptAxiom) {
                        return Optional.of(conceptAxiom.concept());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** A ref for a concept the editor introduced from the store, written by its identifier and label. */
    static Ref grounded(long nid, Identities ids) {
        String label = ids.label(nid);
        Optional<String> identifier = ids.identifier(nid);
        return new Ref(new Tokens.Concept(Tokens.keyOf(label), label, identifier), nid, ids.isLocal(nid));
    }

    // ── The reader ───────────────────────────────────────────────────────────

    private static final class Reader {
        private final Identities ids;
        private final List<String> findings = new ArrayList<>();
        private final Set<String> localSymbols = new LinkedHashSet<>();
        /** The complex concept's nid once the root has resolved, so a focus naming it is noticed. */
        private long conceptNid;

        private Reader(Identities ids) {
            this.ids = ids;
        }

        private Parsed done(Optional<Spec> spec) {
            if (!localSymbols.isEmpty()) {
                findings.add(localSymbols.size() + " identifiers name nothing in the knowledge base and are read as"
                        + " concepts local to the block: " + String.join(", ", localSymbols));
            }
            return new Parsed(spec, List.copyOf(findings), List.copyOf(localSymbols));
        }

        private void noChildren(Line line) {
            for (Line child : line.children()) {
                findings.add("line " + child.number() + ": nothing nests beneath " + line.word());
            }
        }

        private Optional<Ref> concept(Line line, String what) {
            return concept(line, line.head(), what);
        }

        private Optional<Ref> concept(Line line, String text, String what) {
            String head = text == null ? "" : text.strip();
            if (head.isEmpty()) {
                findings.add("line " + line.number() + ": " + what + " names no concept");
                return Optional.empty();
            }
            Optional<Tokens.Concept> token = Tokens.concept(head);
            if (token.isEmpty()) {
                findings.add("line " + line.number() + ": " + what + " is not a k: token: " + head);
                return Optional.empty();
            }
            return resolve(line, token.get(), what);
        }

        private Optional<Ref> resolve(Line line, Tokens.Concept token, String what) {
            if (Tokens.isBadgeForm(token)) {
                findings.add("line " + line.number() + ": " + what + " is written in the Koncept Badge form, " + Tokens.badgeKey(token)
                        + "; inside the block a concept is k:Key[label] @id=<identifier>, the identifier after @id");
                token = Tokens.fromBadgeForm(token);
            }
            if (token.identifier().isEmpty()) {
                findings.add("line " + line.number() + ": " + what + " carries no @id: " + Tokens.concept(token.label(), null));
                return Optional.empty();
            }
            String id = token.identifier().get().strip();
            String label = token.label() != null && !token.label().isBlank() ? token.label()
                    : token.key() != null && !token.key().isBlank() ? token.key() : id;
            OptionalLong nid = ids.nid(id);
            if (nid.isPresent()) {
                return Optional.of(new Ref(labelled(token, ids.label(nid.getAsLong())), nid.getAsLong(), ids.isLocal(nid.getAsLong())));
            }
            OptionalLong local = ids.local(id, label);
            if (local.isPresent()) {
                localSymbols.add(id);
                return Optional.of(new Ref(labelled(token, ids.label(local.getAsLong())), local.getAsLong(), true));
            }
            findings.add("line " + line.number() + ": " + what + " does not resolve: " + id);
            return Optional.empty();
        }

        /** The token with the label its identity already has, when it was written with none: k:Key[] names what the first mention named. */
        private static Tokens.Concept labelled(Tokens.Concept token, String known) {
            if (token.label() != null && !token.label().isBlank()) {
                return token;
            }
            return known == null || known.isBlank() ? token : new Tokens.Concept(token.key(), known, token.identifier());
        }

        private Optional<Shape> expansion(Line line) {
            List<String> words = line.words();
            if (words.isEmpty()) {
                findings.add("line " + line.number() + ": an expansion is focused or uniform");
                return Optional.empty();
            }
            String kind = words.get(0).toLowerCase(Locale.ROOT);
            switch (kind) {
                case "focused" -> {
                    String rest = line.head().substring(words.get(0).length()).strip();
                    Optional<Ref> focus = concept(line, rest, "the focus");
                    if (focus.isEmpty()) {
                        return Optional.empty();
                    }
                    if (conceptNid == focus.get().nid()) {
                        findings.add("line " + line.number() + ": the focus is the complex concept itself; the focus is the"
                                + " phenomenon the provided value applies to, which the complex concept specializes");
                    }
                    List<Prerequisite> prerequisites = new ArrayList<>();
                    for (Line child : line.children()) {
                        if (child.form() == Form.VERTEX && "prerequisite".equals(child.word())) {
                            prerequisite(child).ifPresent(prerequisites::add);
                        } else {
                            findings.add("line " + child.number() + ": a focused expansion holds prerequisite lines, not " + child.word());
                        }
                    }
                    return Optional.of(new Focused(focus.get(), List.copyOf(prerequisites)));
                }
                case "uniform" -> {
                    String conjunction = words.size() > 1 ? words.get(1).toLowerCase(Locale.ROOT) : "";
                    if (!conjunction.equals("and") && !conjunction.equals("or")) {
                        findings.add("line " + line.number() + ": a uniform expansion says and or or after uniform");
                        return Optional.empty();
                    }
                    String rest = line.head().substring(words.get(0).length()).strip();
                    rest = rest.substring(words.get(1).length()).strip();
                    Optional<Ref> domain = rest.isEmpty() ? Optional.empty() : concept(line, rest, "the measurement domain");
                    Optional<String> unsafe = line.attribute("unsafe").map(s -> s.toLowerCase(Locale.ROOT));
                    if (unsafe.isPresent() && !List.of("refuse", "flag", "retain").contains(unsafe.get())) {
                        findings.add("line " + line.number() + ": unsafe is refuse, flag or retain, not " + unsafe.get());
                        unsafe = Optional.empty();
                    }
                    if (unsafe.isPresent() && conjunction.equals("and")) {
                        findings.add("line " + line.number() + ": only an OR conjunction carries an unsafe policy");
                        unsafe = Optional.empty();
                    }
                    List<Ref> members = new ArrayList<>();
                    for (Line child : line.children()) {
                        if (child.form() == Form.VERTEX && "member".equals(child.word())) {
                            Optional<Ref> member = concept(child, "a member");
                            if (member.isPresent()) {
                                if (members.stream().anyMatch(m -> m.nid() == member.get().nid())) {
                                    findings.add("line " + child.number() + ": " + member.get().label() + " is a member already");
                                } else {
                                    members.add(member.get());
                                }
                            }
                            noChildren(child);
                        } else {
                            findings.add("line " + child.number() + ": a uniform expansion holds member lines, not " + child.word());
                        }
                    }
                    return Optional.of(new Uniform(conjunction, domain, List.copyOf(members), unsafe));
                }
                default -> {
                    findings.add("line " + line.number() + ": an expansion is focused or uniform, not " + words.get(0));
                    return Optional.empty();
                }
            }
        }

        private Optional<Prerequisite> prerequisite(Line line) {
            Optional<Ref> topic = concept(line, "a prerequisite");
            if (topic.isEmpty()) {
                return Optional.empty();
            }
            String association = line.attribute("association").map(s -> s.toLowerCase(Locale.ROOT)).orElse("precondition");
            if (!"precondition".equals(association)) {
                findings.add("line " + line.number() + ": the one association a prerequisite has is precondition, not " + association);
                association = "precondition";
            }
            Optional<Tokens.Interval> value = Optional.empty();
            Optional<Ref> unit = Optional.empty();
            for (Line child : line.children()) {
                if (child.form() == Form.VALUE && "value".equals(child.word())) {
                    Optional<Tokens.IntervalPrefix> prefix = Tokens.intervalPrefix(child.rest());
                    if (prefix.isEmpty()) {
                        findings.add("line " + child.number() + ": a value starts with an interval, [lo,hi]");
                        continue;
                    }
                    value = Optional.of(prefix.get().interval());
                    String rest = prefix.get().rest().strip();
                    if (!rest.isEmpty()) {
                        unit = concept(child, new Line(child.number(), child.indent(), child.form(), child.word(), rest, List.of()).head(),
                                "the value's unit");
                    }
                } else {
                    findings.add("line " + child.number() + ": a prerequisite holds one value line, not " + child.word());
                }
            }
            return Optional.of(new Prerequisite(topic.get(), value, unit, association, line.number()));
        }
    }
}
