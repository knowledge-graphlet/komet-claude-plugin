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
package network.ike.komet.claude.complexconcept.model;

import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpression;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfStatement;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Definition;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Expansion;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Member;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Prerequisite;
import network.ike.komet.claude.complexconcept.model.ComplexConceptSemantic.Cardinality;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Builds and edits a complex concept specification's tree, refusing with a reason any edit that
 * would break the tree's rules (IKE-Network/ike-issues#1312; ike-lab-documents topics
 * {@code anf-complex-concept-architecture}, {@code anf-expansion-safety} and
 * {@code dev-ditree-editor-skin-family}). The analogue of {@code AnfExpressionBuilder} and of
 * {@code LogicalExpressionBuilder}.
 *
 * <p>The rules: a specification has one definition, an EL++ expression held by value, and one
 * expansion, uniform or focused, never both. A uniform expansion's conjunction is AND or OR; only
 * an OR conjunction carries an unsafe distribution policy, since a non-absent value is the one
 * unsafe cell of the behavioral space, and the policy is one of refuse expansion, flag as
 * indeterminate positive, or retain the compound code; a phenomenon is a member at most once. A
 * focused expansion's prerequisites each hold a well-formed ANF statement by value, checked by the
 * ANF adaptor layer, and relate to the focus by a precondition association.
 *
 * <p>Edits change the builder's tree in place; {@link #asDiTree()} returns the built tree.
 * {@link #completeness()} lists what a specification still lacks without refusing it, and
 * {@link #wellFormedness(DiTreeEntity)} checks a whole tree this builder did not build.
 */
public final class ComplexConceptBuilder {

    private static final List<ConceptFacade> CONJUNCTIONS = List.of(IkeConcept.AND_CONJUNCTION, IkeConcept.OR_CONJUNCTION);

    private static final List<ConceptFacade> UNSAFE_DISTRIBUTION_POLICIES = List.of(
            IkeConcept.REFUSE_EXPANSION, IkeConcept.FLAG_AS_INDETERMINATE_POSITIVE, IkeConcept.RETAIN_COMPOUND_CODE);

    private static final List<ConceptFacade> PREREQUISITE_ASSOCIATIONS = List.of(IkeConcept.PRECONDITION_ASSOCIATION);

    private DiTreeEntity.Builder builder;
    private ComplexConceptExpression expression;
    private final Supplier<UUID> vertexUuids;
    private boolean removed;

    /** Starts a specification: a root with nothing beneath it yet. */
    public ComplexConceptBuilder() {
        this(UUID::randomUUID);
    }

    /**
     * Starts a specification whose new vertices take their identities from a supplier.
     *
     * @param vertexUuids the identities of new vertices, in order of creation
     */
    public ComplexConceptBuilder(Supplier<UUID> vertexUuids) {
        this.vertexUuids = Objects.requireNonNull(vertexUuids, "vertexUuids");
        this.builder = DiTreeEntity.builder();
        this.expression = new ComplexConceptExpression(builder);
        EntityVertex root = EntityVertex.make(vertexUuids.get(), ComplexConceptSemantic.SPECIFICATION.nid);
        builder.addVertex(root);
        builder.setRoot(root);
        new ComplexConceptAdaptor.RootAdaptor(expression, root.vertexIndex());
    }

    /**
     * Edits an existing specification tree; the tree itself is left as it was.
     *
     * @param tree the specification tree
     * @throws IllegalStateException if a vertex's meaning is not a specification meaning
     */
    public ComplexConceptBuilder(DiTreeEntity tree) {
        this.vertexUuids = UUID::randomUUID;
        this.builder = DiTreeEntity.builder(tree);
        this.expression = new ComplexConceptExpression(builder);
    }

    /** @return the root, the specification */
    public ComplexConcept.Root root() {
        return expression.root();
    }

    /** @return the adaptor view over the tree as it stands */
    public ComplexConceptExpression expression() {
        return expression;
    }

    /** @return the built tree's adaptor view */
    public ComplexConceptExpression build() {
        return new ComplexConceptExpression(asDiTree());
    }

    /** @return the built tree */
    public DiTreeEntity asDiTree() {
        if (removed) {
            builder.compress();
            expression = new ComplexConceptExpression(builder);
            removed = false;
        }
        return builder.build();
    }

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Adds the description logic definition.
     *
     * @param expressionTree the EL++ expression, held by value
     * @return the definition
     * @throws ComplexConceptRefusal if the specification has one, or the tree is not a logical expression
     */
    public Definition definition(DiTreeEntity expressionTree) {
        checkLogicalExpression(expressionTree);
        admit(root(), ComplexConceptSemantic.DEFINITION);
        EntityVertex vertex = newVertex(ComplexConceptSemantic.DEFINITION);
        vertex.putUncommittedProperty(IkeConcept.DEFINITION_EXPRESSION.nid(), expressionTree);
        vertex.commitProperties();
        attach(vertex, root());
        return new ComplexConceptAdaptor.DefinitionAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a uniform expansion.
     *
     * @param conjunction       AND conjunction or OR conjunction
     * @param measurementDomain the scale every member is measured on; may be null while the
     *                          specification is under construction
     * @return the expansion
     * @throws ComplexConceptRefusal if the specification has an expansion, or the conjunction is not one
     */
    public Expansion.Uniform uniformExpansion(ConceptFacade conjunction, ConceptFacade measurementDomain) {
        checkConjunction(conjunction);
        checkNoExpansion();
        admit(root(), ComplexConceptSemantic.UNIFORM_EXPANSION);
        EntityVertex vertex = newVertex(ComplexConceptSemantic.UNIFORM_EXPANSION);
        vertex.putUncommittedProperty(IkeConcept.EXPANSION_CONJUNCTION.nid(), EntityProxy.Concept.make(conjunction));
        if (measurementDomain != null) {
            vertex.putUncommittedProperty(IkeConcept.MEASUREMENT_DOMAIN.nid(), EntityProxy.Concept.make(measurementDomain));
        }
        vertex.commitProperties();
        attach(vertex, root());
        return new ComplexConceptAdaptor.UniformExpansionAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a focused expansion.
     *
     * @param focus the phenomenon the provided value applies to
     * @return the expansion
     * @throws ComplexConceptRefusal if the specification has an expansion
     */
    public Expansion.Focused focusedExpansion(ConceptFacade focus) {
        Objects.requireNonNull(focus, "focus");
        checkNoExpansion();
        admit(root(), ComplexConceptSemantic.FOCUSED_EXPANSION);
        EntityVertex vertex = newVertex(ComplexConceptSemantic.FOCUSED_EXPANSION);
        vertex.putUncommittedProperty(IkeConcept.FOCUS_PHENOMENON.nid(), EntityProxy.Concept.make(focus));
        vertex.commitProperties();
        attach(vertex, root());
        return new ComplexConceptAdaptor.FocusedExpansionAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a member to a uniform expansion.
     *
     * @param uniform    the expansion
     * @param phenomenon the phenomenon one expanded statement is about
     * @return the member
     * @throws ComplexConceptRefusal if the phenomenon is already a member
     */
    public Member member(Expansion.Uniform uniform, ConceptFacade phenomenon) {
        Objects.requireNonNull(phenomenon, "phenomenon");
        admit(uniform, ComplexConceptSemantic.MEMBER);
        checkNotAMember(uniform, phenomenon, null);
        EntityVertex vertex = newVertex(ComplexConceptSemantic.MEMBER);
        vertex.putUncommittedProperty(KernelConcept.CONCEPT_REFERENCE.nid(), EntityProxy.Concept.make(phenomenon));
        vertex.commitProperties();
        attach(vertex, uniform);
        return new ComplexConceptAdaptor.MemberAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a prerequisite to a focused expansion.
     *
     * @param focused       the expansion
     * @param statementTree the prerequisite as an ANF statement, held by value: its topic is the
     *                      condition and its result the fixed definitional value
     * @param association   how it relates to the focus statement: a precondition association
     * @return the prerequisite
     * @throws ComplexConceptRefusal if the statement is not a well-formed ANF statement, or the
     *                               association is not a prerequisite association
     */
    public Prerequisite prerequisite(Expansion.Focused focused, DiTreeEntity statementTree, ConceptFacade association) {
        checkStatement(statementTree);
        checkAssociation(association);
        admit(focused, ComplexConceptSemantic.PREREQUISITE);
        EntityVertex vertex = newVertex(ComplexConceptSemantic.PREREQUISITE);
        vertex.putUncommittedProperty(IkeConcept.PREREQUISITE_STATEMENT.nid(), statementTree);
        vertex.putUncommittedProperty(IkeConcept.PREREQUISITE_ASSOCIATION.nid(), EntityProxy.Concept.make(association));
        vertex.commitProperties();
        attach(vertex, focused);
        return new ComplexConceptAdaptor.PrerequisiteAdaptor(expression, vertex.vertexIndex());
    }

    // ── Edits ─────────────────────────────────────────────────────────────────

    /**
     * Replaces the definition's expression, as when an edit inside the embedded axiom control is
     * spliced back: an update of this one vertex.
     *
     * @param definition     the definition
     * @param expressionTree the new expression
     * @throws ComplexConceptRefusal if the tree is not a logical expression
     */
    public void spliceDefinition(Definition definition, DiTreeEntity expressionTree) {
        checkLogicalExpression(expressionTree);
        EntityVertex vertex = own(definition);
        vertex.putUncommittedProperty(IkeConcept.DEFINITION_EXPRESSION.nid(), expressionTree);
        vertex.commitProperties();
    }

    /**
     * Changes a uniform expansion's conjunction.
     *
     * @param uniform     the expansion
     * @param conjunction AND conjunction or OR conjunction
     * @throws ComplexConceptRefusal if it is not one, or an AND conjunction would keep an unsafe
     *                               distribution policy
     */
    public void setConjunction(Expansion.Uniform uniform, ConceptFacade conjunction) {
        checkConjunction(conjunction);
        if (conjunction.nid() == IkeConcept.AND_CONJUNCTION.nid() && uniform.unsafeDistributionPolicy().isPresent()) {
            throw new ComplexConceptRefusal("An AND conjunction distributes every value safely and has no unsafe"
                    + " distribution policy; remove the policy before changing the conjunction to AND");
        }
        EntityVertex vertex = own(uniform);
        vertex.putUncommittedProperty(IkeConcept.EXPANSION_CONJUNCTION.nid(), EntityProxy.Concept.make(conjunction));
        vertex.commitProperties();
    }

    /**
     * Sets a uniform expansion's measurement domain.
     *
     * @param uniform           the expansion
     * @param measurementDomain the scale every member is measured on
     */
    public void setMeasurementDomain(Expansion.Uniform uniform, ConceptFacade measurementDomain) {
        Objects.requireNonNull(measurementDomain, "measurementDomain");
        EntityVertex vertex = own(uniform);
        vertex.putUncommittedProperty(IkeConcept.MEASUREMENT_DOMAIN.nid(), EntityProxy.Concept.make(measurementDomain));
        vertex.commitProperties();
    }

    /**
     * Sets how an OR-conjunction uniform expansion handles a non-absent value.
     *
     * @param uniform the expansion
     * @param policy  refuse expansion, flag as indeterminate positive, or retain compound code
     * @throws ComplexConceptRefusal if the conjunction is not OR, or the policy is not one of those
     */
    public void setUnsafeDistributionPolicy(Expansion.Uniform uniform, ConceptFacade policy) {
        checkPolicy(uniform.conjunction().orElse(null), policy);
        EntityVertex vertex = own(uniform);
        vertex.putUncommittedProperty(IkeConcept.UNSAFE_DISTRIBUTION_POLICY.nid(), EntityProxy.Concept.make(policy));
        vertex.commitProperties();
    }

    /**
     * Sets a focused expansion's focus.
     *
     * @param focused the expansion
     * @param focus   the phenomenon the provided value applies to
     */
    public void setFocus(Expansion.Focused focused, ConceptFacade focus) {
        Objects.requireNonNull(focus, "focus");
        EntityVertex vertex = own(focused);
        vertex.putUncommittedProperty(IkeConcept.FOCUS_PHENOMENON.nid(), EntityProxy.Concept.make(focus));
        vertex.commitProperties();
    }

    /**
     * Re-points a member to another phenomenon.
     *
     * @param member     the member
     * @param phenomenon the new phenomenon
     * @throws ComplexConceptRefusal if the phenomenon is already another member
     */
    public void updateMember(Member member, ConceptFacade phenomenon) {
        Objects.requireNonNull(phenomenon, "phenomenon");
        EntityVertex vertex = own(member);
        Expansion.Uniform uniform = (Expansion.Uniform) parentOf(member);
        checkNotAMember(uniform, phenomenon, member);
        vertex.putUncommittedProperty(KernelConcept.CONCEPT_REFERENCE.nid(), EntityProxy.Concept.make(phenomenon));
        vertex.commitProperties();
    }

    /**
     * Replaces a prerequisite's statement, as when an edit inside the embedded ANF statement control
     * is spliced back: an update of this one vertex.
     *
     * @param prerequisite  the prerequisite
     * @param statementTree the new statement
     * @throws ComplexConceptRefusal if it is not a well-formed ANF statement
     */
    public void spliceStatement(Prerequisite prerequisite, DiTreeEntity statementTree) {
        checkStatement(statementTree);
        EntityVertex vertex = own(prerequisite);
        vertex.putUncommittedProperty(IkeConcept.PREREQUISITE_STATEMENT.nid(), statementTree);
        vertex.commitProperties();
    }

    /**
     * Changes how a prerequisite relates to the focus statement.
     *
     * @param prerequisite the prerequisite
     * @param association  a prerequisite association
     * @throws ComplexConceptRefusal if it is not one
     */
    public void updateAssociation(Prerequisite prerequisite, ConceptFacade association) {
        checkAssociation(association);
        EntityVertex vertex = own(prerequisite);
        vertex.putUncommittedProperty(IkeConcept.PREREQUISITE_ASSOCIATION.nid(), EntityProxy.Concept.make(association));
        vertex.commitProperties();
    }

    /**
     * Removes a vertex and everything beneath it.
     *
     * @param vertex the vertex
     * @return the root, after the removal
     * @throws ComplexConceptRefusal if the vertex is the root
     */
    public ComplexConcept.Root remove(ComplexConcept vertex) {
        own(vertex);
        if (vertex.vertexIndex() == root().vertexIndex()) {
            throw new ComplexConceptRefusal("The specification itself cannot be removed from its own tree");
        }
        DiTreeEntity tree = builder.build();
        builder = tree.removeVertex(vertex.vertexIndex());
        expression = new ComplexConceptExpression(builder);
        removed = true;
        return root();
    }

    // ── What a specification lacks, and whether a tree is one ─────────────────

    /**
     * What a complete specification still lacks. A specification under construction is well
     * formed without these; this is the editor's list of missing rows, not a refusal.
     *
     * @return the reasons, empty for a complete specification
     */
    public List<String> completeness() {
        List<String> missing = new ArrayList<>();
        ComplexConcept.Root root = root();
        if (root.definition().isEmpty()) {
            missing.add("a definition");
        }
        Optional<Expansion> expansion = root.expansion();
        if (expansion.isEmpty()) {
            missing.add("an expansion, uniform or focused");
            return missing;
        }
        switch (expansion.get()) {
            case Expansion.Uniform uniform -> {
                if (uniform.measurementDomain().isEmpty()) {
                    missing.add("the uniform expansion's measurement domain");
                }
                if (uniform.members().size() < 2) {
                    missing.add("at least two members");
                }
                boolean or = uniform.conjunction().map(c -> c.nid() == IkeConcept.OR_CONJUNCTION.nid()).orElse(false);
                if (or && uniform.unsafeDistributionPolicy().isEmpty()) {
                    missing.add("how the OR conjunction handles a non-absent value");
                }
            }
            case Expansion.Focused focused -> {
                if (focused.prerequisites().isEmpty()) {
                    missing.add("at least one prerequisite");
                }
                for (Prerequisite prerequisite : focused.prerequisites()) {
                    prerequisite.statement().ifPresent(statement -> {
                        AnfStatement.Root statementRoot = statement.root();
                        if (statementRoot.context().flatMap(AnfStatement.Context::topic).isEmpty()) {
                            missing.add("a prerequisite's topic");
                        }
                        if (statementRoot.circumstance().isEmpty()) {
                            missing.add("a prerequisite's fixed value");
                        }
                    });
                }
            }
        }
        return missing;
    }

    /**
     * The rules a whole specification tree breaks, for a tree this builder did not build: the
     * rules the builder keeps one edit at a time, checked over every vertex at once. Where
     * {@link #completeness} lists what a specification still lacks, a tree that breaks any rule
     * here is not a specification at all.
     *
     * @param tree the specification tree
     * @return the rules broken, each a sentence for a person or the model; empty for a
     *         well-formed tree
     */
    public static List<String> wellFormedness(DiTreeEntity tree) {
        List<String> broken = new ArrayList<>();
        if (tree == null || tree.root() == null) {
            broken.add("A specification tree has a root");
            return broken;
        }
        if (tree.root().getMeaningNid() != ComplexConceptSemantic.SPECIFICATION.nid) {
            broken.add("A specification tree's root is a complex concept specification");
            return broken;
        }
        for (EntityVertex vertex : tree.vertexMap()) {
            if (!ComplexConceptSemantic.covers(vertex.getMeaningNid())) {
                broken.add("Every vertex of a specification tree carries a complex concept specification meaning;"
                        + " vertex " + vertex.asUuid() + " does not");
            }
        }
        if (!broken.isEmpty()) {
            return broken;
        }
        checkVertex(ComplexConceptExpression.from(tree).root(), broken);
        return broken;
    }

    /** One vertex's own rules and its children's admission, then each child's in turn. */
    private static void checkVertex(ComplexConcept vertex, List<String> broken) {
        ComplexConceptSemantic semantic = vertex.semantic();
        Map<ComplexConceptSemantic, Integer> seen = new EnumMap<>(ComplexConceptSemantic.class);
        for (ComplexConcept child : vertex.children()) {
            Cardinality cardinality = semantic.admits(child.semantic());
            int times = seen.merge(child.semantic(), 1, Integer::sum);
            if (cardinality == Cardinality.NONE && times == 1) {
                broken.add("A " + child.semantic().describe() + " does not sit under a " + semantic.describe());
            } else if (cardinality == Cardinality.ONE && times == 2) {
                broken.add("A " + semantic.describe() + " has at most one " + child.semantic().describe());
            }
        }
        switch (semantic.shape) {
            case ROOT -> {
                long expansions = vertex.children().stream().filter(child -> child.semantic().isExpansion()).count();
                if (expansions > 1) {
                    broken.add("A specification has one expansion, uniform or focused; this one has " + expansions);
                }
            }
            case EXPRESSION -> {
                Optional<DiTreeEntity> nested = ((Definition) vertex).expressionTree();
                if (nested.isEmpty()) {
                    broken.add("A definition holds its EL++ expression");
                } else {
                    keep(broken, () -> checkLogicalExpression(nested.get()));
                }
            }
            case EXPANSION -> {
                if (vertex instanceof Expansion.Uniform uniform) {
                    keep(broken, () -> checkConjunction(uniform.conjunction().orElse(null)));
                    uniform.unsafeDistributionPolicy().ifPresent(policy ->
                            keep(broken, () -> checkPolicy(uniform.conjunction().orElse(null), policy)));
                    Set<Long> phenomena = new HashSet<>();
                    for (Member member : uniform.members()) {
                        member.phenomenon().ifPresent(phenomenon -> {
                            if (!phenomena.add(phenomenon.nid())) {
                                broken.add(phenomenon.description() + " is a member at most once");
                            }
                        });
                    }
                } else if (vertex instanceof Expansion.Focused focused && focused.focus().isEmpty()) {
                    broken.add("A focused expansion names its focus phenomenon");
                }
            }
            case CONCEPT_REFERENCE -> {
                if (((Member) vertex).phenomenon().isEmpty()) {
                    broken.add("A member names its phenomenon");
                }
            }
            case STATEMENT -> {
                Prerequisite prerequisite = (Prerequisite) vertex;
                Optional<DiTreeEntity> statement = prerequisite.statementTree();
                if (statement.isEmpty()) {
                    broken.add("A prerequisite holds its ANF statement");
                } else {
                    keep(broken, () -> checkStatement(statement.get()));
                }
                keep(broken, () -> checkAssociation(prerequisite.association().orElse(null)));
            }
        }
        for (ComplexConcept child : vertex.children()) {
            checkVertex(child, broken);
        }
    }

    /** Runs one of the builder's rules, recording its refusal rather than throwing it. */
    private static void keep(List<String> broken, Runnable rule) {
        try {
            rule.run();
        } catch (ComplexConceptRefusal refusal) {
            broken.add(refusal.getMessage());
        } catch (RuntimeException unreadable) {
            broken.add("A vertex could not be read as its meaning says: " + unreadable.getMessage());
        }
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    /** The vertex of an adaptor of this builder's tree; an adaptor of another tree is refused. */
    private EntityVertex own(ComplexConcept adaptor) {
        if (!(adaptor instanceof ComplexConceptAdaptor own) || own.adaptedExpression != expression) {
            throw new ComplexConceptRefusal("The " + adaptor.semantic().describe()
                    + " belongs to another tree, or to a view this builder has since replaced");
        }
        return builder.vertex(adaptor.vertexIndex());
    }

    /** The parent of a vertex of this builder's tree. */
    private ComplexConcept parentOf(ComplexConcept child) {
        for (ComplexConcept candidate : expression.nodesOfType(ComplexConcept.class)) {
            for (ComplexConcept grandchild : candidate.children()) {
                if (grandchild.vertexIndex() == child.vertexIndex()) {
                    return candidate;
                }
            }
        }
        throw new ComplexConceptRefusal("The " + child.semantic().describe() + " has no parent in this tree");
    }

    /** The admission table, and the cardinality it gives. */
    private void admit(ComplexConcept parent, ComplexConceptSemantic child) {
        own(parent);
        ComplexConceptSemantic parentSemantic = parent.semantic();
        Cardinality cardinality = parentSemantic.admits(child);
        if (cardinality == Cardinality.NONE) {
            throw new ComplexConceptRefusal("A " + child.describe() + " does not sit under a " + parentSemantic.describe());
        }
        if (cardinality == Cardinality.ONE) {
            for (ComplexConcept existing : parent.children()) {
                if (existing.semantic() == child) {
                    throw new ComplexConceptRefusal("A " + parentSemantic.describe() + " has at most one " + child.describe());
                }
            }
        }
    }

    private void checkNoExpansion() {
        root().expansion().ifPresent(existing -> {
            throw new ComplexConceptRefusal("A specification has one expansion, uniform or focused; this one"
                    + " already has a " + existing.semantic().describe());
        });
    }

    private static void checkNotAMember(Expansion.Uniform uniform, ConceptFacade phenomenon, Member except) {
        for (Member member : uniform.members()) {
            if (except != null && member.vertexIndex() == except.vertexIndex()) {
                continue;
            }
            if (member.phenomenon().map(existing -> existing.nid() == phenomenon.nid()).orElse(false)) {
                throw new ComplexConceptRefusal(phenomenon.description() + " is a member at most once");
            }
        }
    }

    private static void checkConjunction(ConceptFacade conjunction) {
        if (conjunction == null || !isOneOf(conjunction, CONJUNCTIONS)) {
            throw new ComplexConceptRefusal("A uniform expansion's conjunction is AND conjunction or OR conjunction");
        }
    }

    private static void checkPolicy(ConceptFacade conjunction, ConceptFacade policy) {
        if (conjunction == null || conjunction.nid() != IkeConcept.OR_CONJUNCTION.nid()) {
            throw new ComplexConceptRefusal("Only an OR conjunction has an unsafe distribution policy: an AND"
                    + " conjunction distributes every value safely");
        }
        if (policy == null || !isOneOf(policy, UNSAFE_DISTRIBUTION_POLICIES)) {
            throw new ComplexConceptRefusal("An unsafe distribution policy is refuse expansion, flag as"
                    + " indeterminate positive, or retain compound code");
        }
    }

    private static void checkAssociation(ConceptFacade association) {
        if (association == null || !isOneOf(association, PREREQUISITE_ASSOCIATIONS)) {
            throw new ComplexConceptRefusal("A prerequisite relates to the focus by a precondition association");
        }
    }

    private static void checkStatement(DiTreeEntity statementTree) {
        List<String> broken = AnfExpressionBuilder.wellFormedness(statementTree);
        if (!broken.isEmpty()) {
            throw new ComplexConceptRefusal("A prerequisite's statement is a well-formed ANF statement: "
                    + String.join("; ", broken));
        }
    }

    private static void checkLogicalExpression(DiTreeEntity expressionTree) {
        if (expressionTree == null || expressionTree.root() == null) {
            throw new ComplexConceptRefusal("A definition needs an expression tree with a root");
        }
        if (expressionTree.root().getMeaningNid() != KernelConcept.DEFINITION_ROOT.nid()) {
            throw new ComplexConceptRefusal("A definition is a logical expression, whose root is a definition root");
        }
        try {
            new LogicalExpression(expressionTree);
        } catch (RuntimeException notLogical) {
            throw new ComplexConceptRefusal("A definition is a logical expression over the kernel's axiom meanings: "
                    + notLogical.getMessage());
        }
    }

    private static boolean isOneOf(ConceptFacade concept, List<ConceptFacade> allowed) {
        for (ConceptFacade candidate : allowed) {
            if (candidate.nid() == concept.nid()) {
                return true;
            }
        }
        return false;
    }

    private EntityVertex newVertex(ComplexConceptSemantic semantic) {
        EntityVertex vertex = EntityVertex.make(vertexUuids.get(), semantic.nid);
        builder.addVertex(vertex);
        return vertex;
    }

    private void attach(EntityVertex child, ComplexConcept parent) {
        builder.addEdge(child.vertexIndex(), parent.vertexIndex());
    }
}
