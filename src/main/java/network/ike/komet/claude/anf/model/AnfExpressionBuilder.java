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
package network.ike.komet.claude.anf.model;

import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpression;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpressionBuilder;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.model.AnfSemantic.Cardinality;
import network.ike.komet.claude.anf.model.AnfSemantic.Shape;
import network.ike.komet.claude.anf.model.AnfStatement.Attribute;
import network.ike.komet.claude.anf.model.AnfStatement.Circumstance;
import network.ike.komet.claude.anf.model.AnfStatement.Context;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Builds and edits an ANF statement tree, mirroring {@code LogicalExpressionBuilder} and the
 * complex clause plugin's {@code ClauseExpressionBuilder} (IKE-Network/ike-issues#1312): each
 * factory makes an {@code EntityVertex} of one meaning, sets its properties, wires it under its
 * parent and returns the typed adaptor. Start from a kind for a new statement, or from a tree to
 * edit one; then {@link #build()} for the typed view or {@link #asDiTree()} for the tree to write
 * into the ANF statement pattern's field.
 *
 * <p><b>Every rule is refused with a reason, never silently.</b> The admission table of
 * {@link AnfSemantic#admits} says which child sits under which parent and how many; beyond it
 * the builder holds that a statement has exactly one circumstance and that it is of the
 * statement's kind, that a statement type chip names that kind, that a measure's lower bound is
 * at most its upper, that a nested expression is a logical expression, that a narrative's text is
 * not blank, and that an association is recorded in its canonical direction. A refusal is an
 * {@link AnfRefusal}, and the tree is unchanged by a refused edit.
 *
 * <p><b>Nesting by value.</b> A topic, a subject of information, a purpose or an author's role is
 * a {@code DiTreeEntity} held as a vertex property. An edit inside it runs through the logical
 * expression builder on the extracted tree, and {@link #splice} puts the rebuilt tree back as
 * the property value, so to this tree it is an update of one vertex.
 *
 * <p>Adaptors returned before a {@link #remove} are stale afterwards, as the logical builder's
 * are after a removal: re-read them from {@link #root()}.
 */
public final class AnfExpressionBuilder {

    /** The association types recorded as they stand, source to target. */
    private static final List<ConceptFacade> CANONICAL_ASSOCIATIONS = List.of(
            IkeConcept.STATEMENT_COMPOSES, IkeConcept.STATEMENT_FOLLOWS_IN_TIME, IkeConcept.STATEMENT_COMPARES_AGAINST,
            IkeConcept.STATEMENT_SUPPORTS, IkeConcept.STATEMENT_CONTRADICTS, IkeConcept.STATEMENT_REPLICATES,
            IkeConcept.STATEMENT_OVERRULES, IkeConcept.STATEMENT_AMENDS);

    /** The inverse association types, each refused in favour of its canonical form on the other statement. */
    private static final Map<ConceptFacade, ConceptFacade> INVERSE_ASSOCIATIONS = Map.of(
            IkeConcept.STATEMENT_COMPOSED_OF, IkeConcept.STATEMENT_COMPOSES,
            IkeConcept.STATEMENT_PRECEDES_IN_TIME, IkeConcept.STATEMENT_FOLLOWS_IN_TIME);

    private DiTreeEntity.Builder builder;
    private AnfExpression expression;
    private final Supplier<UUID> vertexUuids;
    private boolean removed;

    /**
     * A new statement of a kind, with random vertex identities.
     *
     * @param kind the statement kind: Performance, Request or Narrative circumstance
     * @throws AnfRefusal if the concept is not a circumstance kind
     */
    public AnfExpressionBuilder(ConceptFacade kind) {
        this(kind, UUID::randomUUID);
    }

    /**
     * A new statement of a kind, minting vertex identities from a supplier.
     *
     * @param kind        the statement kind
     * @param vertexUuids the identities for new vertices
     * @throws AnfRefusal if the concept is not a circumstance kind
     */
    public AnfExpressionBuilder(ConceptFacade kind, Supplier<UUID> vertexUuids) {
        AnfSemantic.circumstanceKind(kind);
        this.vertexUuids = Objects.requireNonNull(vertexUuids, "vertexUuids");
        this.builder = DiTreeEntity.builder();
        this.expression = new AnfExpression(builder);
        EntityVertex root = EntityVertex.make(vertexUuids.get(), AnfSemantic.STATEMENT.nid);
        root.putUncommittedProperty(IkeConcept.ANF_STATEMENT_KIND.nid(), EntityProxy.Concept.make(kind));
        root.commitProperties();
        builder.addVertex(root);
        builder.setRoot(root);
        new AnfAdaptor.RootAdaptor(expression, root.vertexIndex());
    }

    /**
     * Edits an existing statement tree; the tree itself is left as it was.
     *
     * @param tree the statement tree
     * @throws IllegalStateException if a vertex's meaning is not an ANF statement meaning
     */
    public AnfExpressionBuilder(DiTreeEntity tree) {
        this.vertexUuids = UUID::randomUUID;
        this.builder = DiTreeEntity.builder(tree);
        this.expression = new AnfExpression(builder);
    }

    /**
     * Edits the tree beneath an expression; the tree itself is left as it was.
     *
     * @param expression the statement
     */
    public AnfExpressionBuilder(AnfExpression expression) {
        this.vertexUuids = UUID::randomUUID;
        this.builder = DiTreeEntity.builder(expression.sourceGraph());
        this.expression = new AnfExpression(builder);
    }

    /** @return the root of the tree under construction. */
    public AnfStatement.Root root() {
        return expression.root();
    }

    /** @return the live typed view over the tree under construction. */
    public AnfExpression expression() {
        return expression;
    }

    /** @return the typed view over the built tree. */
    public AnfExpression build() {
        return new AnfExpression(asDiTree());
    }

    /** @return the built tree, for writing into the ANF statement pattern's field. */
    public DiTreeEntity asDiTree() {
        if (removed) {
            builder.compress();
            expression = new AnfExpression(builder);
            removed = false;
        }
        return builder.build();
    }

    // ── Structure ─────────────────────────────────────────────────────────────

    /**
     * Adds the context under the root.
     *
     * @return the context
     * @throws AnfRefusal if the statement already has one
     */
    public Context context() {
        AnfStatement.Root root = root();
        admit(root, AnfSemantic.CONTEXT);
        EntityVertex vertex = newVertex(AnfSemantic.CONTEXT);
        attach(vertex, root);
        return new AnfAdaptor.ContextAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds the statement's one circumstance, of the statement's kind.
     *
     * @return the circumstance
     * @throws AnfRefusal if the statement already has a circumstance
     */
    public Circumstance circumstance() {
        return circumstance(root().kind());
    }

    /**
     * Adds the statement's one circumstance.
     *
     * @param kind the circumstance kind, which must be the statement's kind
     * @return the circumstance
     * @throws AnfRefusal if the statement already has a circumstance, or the kind is not the
     *                    statement's
     */
    public Circumstance circumstance(ConceptFacade kind) {
        AnfStatement.Root root = root();
        AnfSemantic circumstanceKind = AnfSemantic.circumstanceKind(kind);
        root.circumstance().ifPresent(existing -> {
            throw new AnfRefusal("A statement has exactly one circumstance; this one already has a "
                    + existing.semantic().describe());
        });
        AnfSemantic statementKind = root.kindSemantic();
        if (circumstanceKind != statementKind) {
            throw new AnfRefusal("The circumstance must be of the statement's kind: this statement is a "
                    + statementKind.describe() + ", not a " + circumstanceKind.describe());
        }
        admit(root, circumstanceKind);
        EntityVertex vertex = newVertex(circumstanceKind);
        attach(vertex, root);
        return switch (circumstanceKind) {
            case PERFORMANCE -> new AnfAdaptor.PerformanceAdaptor(expression, vertex.vertexIndex());
            case REQUEST -> new AnfAdaptor.RequestAdaptor(expression, vertex.vertexIndex());
            case NARRATIVE -> new AnfAdaptor.NarrativeAdaptor(expression, vertex.vertexIndex());
            default -> throw new IllegalStateException("Not a circumstance kind: " + circumstanceKind);
        };
    }

    /**
     * Adds a concept reference chip.
     *
     * @param parent    the context or circumstance it sits under
     * @param attribute its meaning, one of the concept reference attributes
     * @param concept   the concept it references
     * @return the chip
     * @throws AnfRefusal if the parent does not admit it, or a statement type names a kind other
     *                    than the statement's
     */
    public Attribute.ConceptReference conceptReference(AnfStatement parent, AnfSemantic attribute, ConceptFacade concept) {
        shape(attribute, Shape.CONCEPT_REFERENCE);
        Objects.requireNonNull(concept, "concept");
        if (attribute == AnfSemantic.STATEMENT_TYPE) {
            checkStatementType(concept);
        }
        admit(parent, attribute);
        EntityVertex vertex = newVertex(attribute);
        vertex.putUncommittedProperty(KernelConcept.CONCEPT_REFERENCE.nid(), EntityProxy.Concept.make(concept));
        vertex.commitProperties();
        attach(vertex, parent);
        return new AnfAdaptor.ConceptReferenceAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a concept reference chip that has no concept yet.
     *
     * @param parent      the context or circumstance it sits under
     * @param attribute   its meaning, one of the concept reference attributes
     * @param provisional the candidate or clarification it carries instead
     * @return the chip
     * @throws AnfRefusal if the parent does not admit it
     */
    public Attribute.ConceptReference provisionalConceptReference(AnfStatement parent, AnfSemantic attribute,
                                                                  AnfProvisional provisional) {
        shape(attribute, Shape.CONCEPT_REFERENCE);
        admit(parent, attribute);
        EntityVertex vertex = newVertex(attribute);
        putProvisional(vertex, provisional);
        vertex.commitProperties();
        attach(vertex, parent);
        return new AnfAdaptor.ConceptReferenceAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a measure.
     *
     * @param parent            the context, circumstance or repetition it sits under
     * @param attribute         its meaning, one of the measure attributes
     * @param lowerBound        the lower bound, or null when unbounded below
     * @param upperBound        the upper bound, or null when unbounded above
     * @param includeLowerBound whether the lower bound is included
     * @param includeUpperBound whether the upper bound is included
     * @param resolution        the resolution, or null when not stated
     * @param unit              the measure semantic: the unit or scale; may be null while the
     *                          measure is under construction
     * @return the measure
     * @throws AnfRefusal if the parent does not admit it, or the lower bound exceeds the upper
     */
    public Attribute.Measure measure(AnfStatement parent, AnfSemantic attribute, BigDecimal lowerBound,
                                     BigDecimal upperBound, boolean includeLowerBound, boolean includeUpperBound,
                                     BigDecimal resolution, ConceptFacade unit) {
        shape(attribute, Shape.MEASURE);
        checkBounds(lowerBound, upperBound, resolution);
        admit(parent, attribute);
        EntityVertex vertex = newVertex(attribute);
        putBounds(vertex, lowerBound, upperBound, includeLowerBound, includeUpperBound, resolution);
        if (unit != null) {
            vertex.putUncommittedProperty(KernelConcept.UNIT_OF_MEASURE.nid(), EntityProxy.Concept.make(unit));
        }
        vertex.commitProperties();
        attach(vertex, parent);
        return new AnfAdaptor.MeasureAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a measure whose unit has no concept yet.
     *
     * @param parent            the context, circumstance or repetition it sits under
     * @param attribute         its meaning, one of the measure attributes
     * @param lowerBound        the lower bound, or null when unbounded below
     * @param upperBound        the upper bound, or null when unbounded above
     * @param includeLowerBound whether the lower bound is included
     * @param includeUpperBound whether the upper bound is included
     * @param resolution        the resolution, or null when not stated
     * @param provisionalUnit   the candidate or clarification the unit carries instead
     * @return the measure
     * @throws AnfRefusal if the parent does not admit it, or the lower bound exceeds the upper
     */
    public Attribute.Measure provisionalMeasure(AnfStatement parent, AnfSemantic attribute, BigDecimal lowerBound,
                                                BigDecimal upperBound, boolean includeLowerBound,
                                                boolean includeUpperBound, BigDecimal resolution,
                                                AnfProvisional provisionalUnit) {
        shape(attribute, Shape.MEASURE);
        checkBounds(lowerBound, upperBound, resolution);
        admit(parent, attribute);
        EntityVertex vertex = newVertex(attribute);
        putBounds(vertex, lowerBound, upperBound, includeLowerBound, includeUpperBound, resolution);
        putProvisional(vertex, provisionalUnit);
        vertex.commitProperties();
        attach(vertex, parent);
        return new AnfAdaptor.MeasureAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a nested logical expression held by value.
     *
     * @param parent         the context or circumstance it sits under
     * @param attribute      its meaning: topic, subject of information or purpose
     * @param expressionTree the logical expression's tree
     * @return the attribute
     * @throws AnfRefusal if the parent does not admit it, or the tree is not a logical expression
     */
    public Attribute.Expression expression(AnfStatement parent, AnfSemantic attribute, DiTreeEntity expressionTree) {
        shape(attribute, Shape.EXPRESSION);
        checkLogicalExpression(expressionTree);
        admit(parent, attribute);
        EntityVertex vertex = newVertex(attribute);
        vertex.putUncommittedProperty(IkeConcept.ANF_LOGICAL_EXPRESSION.nid(), expressionTree);
        vertex.commitProperties();
        attach(vertex, parent);
        return new AnfAdaptor.ExpressionAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a nested logical expression in its degenerate form, one concept reference: a
     * definition root over one necessary set over one conjunction over the concept.
     *
     * @param parent    the context or circumstance it sits under
     * @param attribute its meaning: topic, subject of information or purpose
     * @param concept   the one concept
     * @return the attribute
     * @throws AnfRefusal if the parent does not admit it
     */
    public Attribute.Expression expression(AnfStatement parent, AnfSemantic attribute, ConceptFacade concept) {
        Objects.requireNonNull(concept, "concept");
        return expression(parent, attribute, singleConceptExpression(concept));
    }

    /**
     * Adds a nested expression attribute that has no expression yet.
     *
     * @param parent      the context or circumstance it sits under
     * @param attribute   its meaning: topic, subject of information or purpose
     * @param provisional the candidate or clarification it carries instead
     * @return the attribute
     * @throws AnfRefusal if the parent does not admit it
     */
    public Attribute.Expression provisionalExpression(AnfStatement parent, AnfSemantic attribute,
                                                      AnfProvisional provisional) {
        shape(attribute, Shape.EXPRESSION);
        admit(parent, attribute);
        EntityVertex vertex = newVertex(attribute);
        putProvisional(vertex, provisional);
        vertex.commitProperties();
        attach(vertex, parent);
        return new AnfAdaptor.ExpressionAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a participant reference with its role as a concept.
     *
     * @param parent      the context or circumstance it sits under
     * @param attribute   its meaning: subject of record or participant
     * @param participant the participant concept
     * @param role        the participant's role, or null when not stated
     * @return the attribute
     * @throws AnfRefusal if the parent does not admit it
     */
    public Attribute.Participant participant(AnfStatement parent, AnfSemantic attribute, ConceptFacade participant,
                                             ConceptFacade role) {
        shape(attribute, Shape.PARTICIPANT);
        Objects.requireNonNull(participant, "participant");
        admit(parent, attribute);
        EntityVertex vertex = newVertex(attribute);
        vertex.putUncommittedProperty(KernelConcept.CONCEPT_REFERENCE.nid(), EntityProxy.Concept.make(participant));
        if (role != null) {
            vertex.putUncommittedProperty(IkeConcept.ANF_PARTICIPANT_ROLE.nid(), EntityProxy.Concept.make(role));
        }
        vertex.commitProperties();
        attach(vertex, parent);
        return new AnfAdaptor.ParticipantAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds an author: a participant reference with its role as a nested expression.
     *
     * @param context     the context it sits under
     * @param participant the author concept
     * @param roleTree    the role as a logical expression tree, or null when not stated
     * @return the attribute
     * @throws AnfRefusal if the role tree is not a logical expression
     */
    public Attribute.Author author(Context context, ConceptFacade participant, DiTreeEntity roleTree) {
        Objects.requireNonNull(participant, "participant");
        if (roleTree != null) {
            checkLogicalExpression(roleTree);
        }
        admit(context, AnfSemantic.AUTHOR);
        EntityVertex vertex = newVertex(AnfSemantic.AUTHOR);
        vertex.putUncommittedProperty(KernelConcept.CONCEPT_REFERENCE.nid(), EntityProxy.Concept.make(participant));
        if (roleTree != null) {
            vertex.putUncommittedProperty(IkeConcept.ANF_AUTHOR_ROLE_EXPRESSION.nid(), roleTree);
        }
        vertex.commitProperties();
        attach(vertex, context);
        return new AnfAdaptor.AuthorAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a narrative's text.
     *
     * @param narrative the narrative circumstance
     * @param text      the text
     * @return the attribute
     * @throws AnfRefusal if the text is blank, or the narrative already has text
     */
    public Attribute.NarrativeText narrativeText(Circumstance.Narrative narrative, String text) {
        checkText(text);
        admit(narrative, AnfSemantic.NARRATIVE_TEXT);
        EntityVertex vertex = newVertex(AnfSemantic.NARRATIVE_TEXT);
        vertex.putUncommittedProperty(KernelConcept.LITERAL_VALUE.nid(), text);
        vertex.commitProperties();
        attach(vertex, narrative);
        return new AnfAdaptor.NarrativeTextAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a request's repetition clause; its measures are added under it.
     *
     * @param request the request circumstance
     * @return the clause
     * @throws AnfRefusal if the request already has one
     */
    public Attribute.Repetition repetition(Circumstance.Request request) {
        admit(request, AnfSemantic.REPETITION);
        EntityVertex vertex = newVertex(AnfSemantic.REPETITION);
        attach(vertex, request);
        return new AnfAdaptor.RepetitionAdaptor(expression, vertex.vertexIndex());
    }

    /**
     * Adds a reference to another statement under the root, in the association's canonical
     * direction.
     *
     * @param statement       the other statement's semantic
     * @param associationType a statement association concept
     * @return the attribute
     * @throws AnfRefusal if the type is not a statement association, or is an inverse: the
     *                    refusal names the canonical type to record on the other statement
     */
    public Attribute.AssociatedStatement associatedStatement(EntityFacade statement, ConceptFacade associationType) {
        Objects.requireNonNull(statement, "statement");
        checkAssociation(associationType);
        AnfStatement.Root root = root();
        admit(root, AnfSemantic.ASSOCIATED_STATEMENT);
        EntityVertex vertex = newVertex(AnfSemantic.ASSOCIATED_STATEMENT);
        vertex.putUncommittedProperty(IkeConcept.ANF_ASSOCIATED_STATEMENT_REFERENCE.nid(), statement);
        vertex.putUncommittedProperty(IkeConcept.ANF_ASSOCIATION_TYPE.nid(), EntityProxy.Concept.make(associationType));
        vertex.commitProperties();
        attach(vertex, root);
        return new AnfAdaptor.AssociatedStatementAdaptor(expression, vertex.vertexIndex());
    }

    // ── Edits ─────────────────────────────────────────────────────────────────

    /**
     * Re-points a concept reference chip, settling a provisional one.
     *
     * @param chip    the chip
     * @param concept the new concept
     * @throws AnfRefusal if a statement type would name a kind other than the statement's
     */
    public void updateConcept(Attribute.ConceptReference chip, ConceptFacade concept) {
        Objects.requireNonNull(concept, "concept");
        EntityVertex vertex = own(chip);
        if (chip.semantic() == AnfSemantic.STATEMENT_TYPE) {
            checkStatementType(concept);
        }
        clearProvisional(vertex);
        vertex.putUncommittedProperty(KernelConcept.CONCEPT_REFERENCE.nid(), EntityProxy.Concept.make(concept));
        vertex.commitProperties();
    }

    /**
     * Changes a measure's bounds, resolution and unit.
     *
     * @param measure           the measure
     * @param lowerBound        the lower bound, or null when unbounded below
     * @param upperBound        the upper bound, or null when unbounded above
     * @param includeLowerBound whether the lower bound is included
     * @param includeUpperBound whether the upper bound is included
     * @param resolution        the resolution, or null when not stated
     * @param unit              the unit, or null to leave the unit as it is
     * @throws AnfRefusal if the lower bound exceeds the upper
     */
    public void updateMeasure(Attribute.Measure measure, BigDecimal lowerBound, BigDecimal upperBound,
                              boolean includeLowerBound, boolean includeUpperBound, BigDecimal resolution,
                              ConceptFacade unit) {
        EntityVertex vertex = own(measure);
        checkBounds(lowerBound, upperBound, resolution);
        EntityVertex replacement = vertex.copyWithNewIndex(vertex.vertexIndex());
        replacement.setProperties(org.eclipse.collections.api.factory.primitive.LongObjectMaps.mutable.empty());
        measure.unit().ifPresent(existing ->
                replacement.putUncommittedProperty(KernelConcept.UNIT_OF_MEASURE.nid(), existing));
        copyProvisional(vertex, replacement);
        putBounds(replacement, lowerBound, upperBound, includeLowerBound, includeUpperBound, resolution);
        if (unit != null) {
            clearProvisional(replacement);
            replacement.putUncommittedProperty(KernelConcept.UNIT_OF_MEASURE.nid(), EntityProxy.Concept.make(unit));
        }
        replacement.commitProperties();
        builder.replaceVertex(replacement);
    }

    /**
     * Splices a rebuilt nested expression back as the attribute's value: the edit inside it ran
     * through the logical expression builder, and to this tree it is an update of one vertex.
     *
     * @param attribute      the nested expression attribute
     * @param expressionTree the rebuilt logical expression tree
     * @throws AnfRefusal if the tree is not a logical expression
     */
    public void splice(Attribute.Expression attribute, DiTreeEntity expressionTree) {
        checkLogicalExpression(expressionTree);
        EntityVertex vertex = own(attribute);
        clearProvisional(vertex);
        vertex.putUncommittedProperty(IkeConcept.ANF_LOGICAL_EXPRESSION.nid(), expressionTree);
        vertex.commitProperties();
    }

    /**
     * Splices a rebuilt role expression back into an author.
     *
     * @param author   the author
     * @param roleTree the rebuilt logical expression tree
     * @throws AnfRefusal if the tree is not a logical expression
     */
    public void spliceRole(Attribute.Author author, DiTreeEntity roleTree) {
        checkLogicalExpression(roleTree);
        EntityVertex vertex = own(author);
        vertex.putUncommittedProperty(IkeConcept.ANF_AUTHOR_ROLE_EXPRESSION.nid(), roleTree);
        vertex.commitProperties();
    }

    /**
     * Changes a narrative's text.
     *
     * @param narrativeText the text attribute
     * @param text          the new text
     * @throws AnfRefusal if the text is blank
     */
    public void updateText(Attribute.NarrativeText narrativeText, String text) {
        checkText(text);
        EntityVertex vertex = own(narrativeText);
        vertex.putUncommittedProperty(KernelConcept.LITERAL_VALUE.nid(), text);
        vertex.commitProperties();
    }

    /**
     * Changes the statement's kind: Performance, Request or Narrative. A kind is not a value to
     * convert between, so the change is refused while the circumstance carries attributes or the
     * context names a statement type; an empty circumstance is replaced by one of the new kind.
     *
     * @param kind the new circumstance kind
     * @throws AnfRefusal if the concept is not a circumstance kind, the circumstance carries
     *                    attributes, or the context names a statement type
     */
    public void changeKind(ConceptFacade kind) {
        AnfSemantic newKind = AnfSemantic.circumstanceKind(kind);
        AnfStatement.Root root = root();
        if (root.kindSemantic() == newKind) {
            return;
        }
        Optional<Circumstance> circumstance = root.circumstance();
        if (circumstance.isPresent() && !circumstance.get().children().isEmpty()) {
            throw new AnfRefusal("Remove the " + circumstance.get().semantic().describe()
                    + "'s attributes before changing the statement's kind");
        }
        if (root.context().flatMap(Context::statementType).isPresent()) {
            throw new AnfRefusal("Remove the statement type before changing the statement's kind");
        }
        if (circumstance.isPresent()) {
            root = remove(circumstance.get());
        }
        EntityVertex rootVertex = builder.vertex(root.vertexIndex());
        rootVertex.putUncommittedProperty(IkeConcept.ANF_STATEMENT_KIND.nid(), EntityProxy.Concept.make(kind));
        rootVertex.commitProperties();
        if (circumstance.isPresent()) {
            circumstance(kind);
        }
    }

    /**
     * Re-points a participant reference, or its role, settling a provisional one.
     *
     * @param participant the participant attribute
     * @param concept     the new participant, or null to keep the current one
     * @param role        the new role, or null to keep the current one
     */
    public void updateParticipant(Attribute.Participant participant, ConceptFacade concept, ConceptFacade role) {
        EntityVertex vertex = own(participant);
        clearProvisional(vertex);
        if (concept != null) {
            vertex.putUncommittedProperty(KernelConcept.CONCEPT_REFERENCE.nid(), EntityProxy.Concept.make(concept));
        }
        if (role != null) {
            vertex.putUncommittedProperty(IkeConcept.ANF_PARTICIPANT_ROLE.nid(), EntityProxy.Concept.make(role));
        }
        vertex.commitProperties();
    }

    /**
     * Re-points an author's participant reference.
     *
     * @param author  the author
     * @param concept the new author concept
     */
    public void updateAuthor(Attribute.Author author, ConceptFacade concept) {
        Objects.requireNonNull(concept, "concept");
        EntityVertex vertex = own(author);
        vertex.putUncommittedProperty(KernelConcept.CONCEPT_REFERENCE.nid(), EntityProxy.Concept.make(concept));
        vertex.commitProperties();
    }

    /**
     * Changes an associated statement's association type, in its canonical direction.
     *
     * @param association     the associated statement attribute
     * @param associationType a statement association concept
     * @throws AnfRefusal if the type is not a statement association, or is an inverse
     */
    public void updateAssociationType(Attribute.AssociatedStatement association, ConceptFacade associationType) {
        checkAssociation(associationType);
        EntityVertex vertex = own(association);
        vertex.putUncommittedProperty(IkeConcept.ANF_ASSOCIATION_TYPE.nid(), EntityProxy.Concept.make(associationType));
        vertex.commitProperties();
    }

    /**
     * Removes a vertex and everything beneath it. Every adaptor handed out before is stale
     * afterwards; re-read from the returned root.
     *
     * @param vertex the vertex to remove
     * @return the root of the tree without it
     * @throws AnfRefusal if the vertex is the root
     */
    public AnfStatement.Root remove(AnfStatement vertex) {
        own(vertex);
        if (vertex.vertexIndex() == root().vertexIndex()) {
            throw new AnfRefusal("The statement itself cannot be removed from its own tree");
        }
        DiTreeEntity tree = builder.build();
        builder = tree.removeVertex(vertex.vertexIndex());
        expression = new AnfExpression(builder);
        removed = true;
        return root();
    }

    // ── Completeness ──────────────────────────────────────────────────────────

    /**
     * What a complete statement still lacks: the required attributes the structural definition
     * names that the tree does not yet carry. A tree under construction is well formed without
     * them; this is the editor's list of missing rows, not a refusal.
     *
     * @return the reasons, empty for a complete statement
     */
    public List<String> completeness() {
        List<String> missing = new ArrayList<>();
        AnfStatement.Root root = root();
        if (root.context().isEmpty()) {
            missing.add("a context");
        } else {
            Context context = root.context().get();
            if (context.subjectOfRecord().isEmpty()) {
                missing.add("a subject of record");
            }
            if (context.statementTime().isEmpty()) {
                missing.add("a statement time");
            }
            if (root.kindSemantic() != AnfSemantic.NARRATIVE && context.topic().isEmpty()) {
                missing.add("a topic");
            }
        }
        if (root.circumstance().isEmpty()) {
            missing.add("a " + root.kindSemantic().describe());
        } else if (root.circumstance().get() instanceof Circumstance.Narrative narrative && narrative.text().isEmpty()) {
            missing.add("the narrative's text");
        }
        return missing;
    }

    /**
     * The rules a whole statement tree breaks, for a tree this builder did not build: one an
     * action is handed, or one read back from a store. These are the rules the builder keeps one
     * edit at a time, checked over every vertex at once: the root is a statement; every vertex
     * carries an ANF meaning; every child is one its parent admits, no more often than the
     * admission table allows; the circumstance and the statement type name the root's kind; and
     * each attribute keeps its own rules. Where {@link #completeness} lists what a statement still
     * lacks, a tree that breaks any rule here is not a statement at all.
     *
     * @param tree the statement tree
     * @return the rules broken, each a sentence for a person or the model; empty for a
     *         well-formed tree
     */
    public static List<String> wellFormedness(DiTreeEntity tree) {
        List<String> broken = new ArrayList<>();
        if (tree == null || tree.root() == null) {
            broken.add("A statement tree has a root");
            return broken;
        }
        if (tree.root().getMeaningNid() != AnfSemantic.STATEMENT.nid) {
            broken.add("A statement tree's root is an ANF statement");
            return broken;
        }
        for (EntityVertex vertex : tree.vertexMap()) {
            if (!AnfSemantic.covers(vertex.getMeaningNid())) {
                broken.add("Every vertex of a statement tree carries an ANF statement meaning; vertex "
                        + vertex.asUuid() + " does not");
            }
        }
        if (!broken.isEmpty()) {
            return broken;
        }
        AnfStatement.Root root;
        AnfSemantic kind;
        try {
            root = AnfExpression.from(tree).root();
            kind = root.kindSemantic();
        } catch (RuntimeException unreadable) {
            broken.add("A statement tree's root names the statement's kind: " + unreadable.getMessage());
            return broken;
        }
        checkVertex(root, kind, broken);
        return broken;
    }

    /** One vertex's own rules and its children's admission, then each child's in turn. */
    private static void checkVertex(AnfStatement vertex, AnfSemantic kind, List<String> broken) {
        AnfSemantic semantic = vertex.semantic();
        Map<AnfSemantic, Integer> seen = new java.util.EnumMap<>(AnfSemantic.class);
        for (AnfStatement child : vertex.children()) {
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
                long circumstances = vertex.children().stream().filter(child -> child.semantic().isCircumstance()).count();
                if (circumstances > 1) {
                    broken.add("A statement has one circumstance, of one kind; this one has " + circumstances);
                }
            }
            case CLAUSE -> { }
            case CIRCUMSTANCE -> {
                if (semantic != kind) {
                    broken.add("The circumstance is a " + semantic.describe() + ", but the statement is a "
                            + kind.describe());
                }
            }
            case CONCEPT_REFERENCE -> {
                Attribute.ConceptReference chip = (Attribute.ConceptReference) vertex;
                if (semantic == AnfSemantic.STATEMENT_TYPE && chip.concept().isPresent()) {
                    keep(broken, () -> {
                        AnfSemantic named = AnfSemantic.circumstanceKind(chip.concept().get());
                        if (named != kind) {
                            throw new AnfRefusal("The statement type must name the statement's kind: this"
                                    + " statement is a " + kind.describe() + ", not a " + named.describe());
                        }
                    });
                }
            }
            case MEASURE -> {
                Attribute.Measure measure = (Attribute.Measure) vertex;
                keep(broken, () -> checkBounds(measure.lowerBound().orElse(null), measure.upperBound().orElse(null),
                        measure.resolution().orElse(null)));
            }
            case EXPRESSION -> ((Attribute.Expression) vertex).expressionTree()
                    .ifPresent(nested -> keep(broken, () -> checkLogicalExpression(nested)));
            case AUTHOR -> ((Attribute.Author) vertex).roleTree()
                    .ifPresent(nested -> keep(broken, () -> checkLogicalExpression(nested)));
            case PARTICIPANT -> { }
            case TEXT -> keep(broken, () -> checkText(((Attribute.NarrativeText) vertex).text()));
            case ASSOCIATION -> {
                Attribute.AssociatedStatement association = (Attribute.AssociatedStatement) vertex;
                if (association.statement().isEmpty()) {
                    broken.add("An associated statement names the statement it refers to");
                }
                keep(broken, () -> checkAssociation(association.associationType().orElse(null)));
            }
        }
        for (AnfStatement child : vertex.children()) {
            checkVertex(child, kind, broken);
        }
    }

    /** Runs one of the builder's rules, recording its refusal rather than throwing it. */
    private static void keep(List<String> broken, Runnable rule) {
        try {
            rule.run();
        } catch (AnfRefusal refusal) {
            broken.add(refusal.getMessage());
        } catch (RuntimeException unreadable) {
            broken.add("A vertex could not be read as its meaning says: " + unreadable.getMessage());
        }
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    /** The vertex of an adaptor of this builder's tree; an adaptor of another tree is refused. */
    private EntityVertex own(AnfStatement adaptor) {
        if (!(adaptor instanceof AnfAdaptor anfAdaptor) || anfAdaptor.adaptedExpression != expression) {
            throw new AnfRefusal("The " + adaptor.semantic().describe()
                    + " belongs to another tree, or to a view this builder has since replaced");
        }
        return builder.vertex(adaptor.vertexIndex());
    }

    /** The admission table, and the cardinality it gives. */
    private void admit(AnfStatement parent, AnfSemantic child) {
        own(parent);
        AnfSemantic parentSemantic = parent.semantic();
        Cardinality cardinality = parentSemantic.admits(child);
        if (cardinality == Cardinality.NONE) {
            throw new AnfRefusal("A " + child.describe() + " does not sit under a " + parentSemantic.describe());
        }
        if (cardinality == Cardinality.ONE) {
            for (AnfStatement existing : parent.children()) {
                if (existing.semantic() == child) {
                    throw new AnfRefusal("A " + parentSemantic.describe() + " has at most one " + child.describe());
                }
            }
        }
    }

    private static void shape(AnfSemantic attribute, Shape shape) {
        if (attribute.shape != shape) {
            throw new AnfRefusal("A " + attribute.describe() + " is not a "
                    + shape.name().toLowerCase().replace('_', ' ') + " attribute");
        }
    }

    private void checkStatementType(ConceptFacade concept) {
        AnfSemantic named = AnfSemantic.circumstanceKind(concept);
        AnfSemantic statementKind = root().kindSemantic();
        if (named != statementKind) {
            throw new AnfRefusal("The statement type must name the statement's kind: this statement is a "
                    + statementKind.describe() + ", not a " + named.describe());
        }
    }

    private static void checkBounds(BigDecimal lowerBound, BigDecimal upperBound, BigDecimal resolution) {
        if (lowerBound != null && upperBound != null && lowerBound.compareTo(upperBound) > 0) {
            throw new AnfRefusal("A measure's lower bound is at most its upper bound: "
                    + lowerBound.toPlainString() + " exceeds " + upperBound.toPlainString());
        }
        if (resolution != null && resolution.signum() < 0) {
            throw new AnfRefusal("A measure's resolution is not negative: " + resolution.toPlainString());
        }
    }

    private static void checkLogicalExpression(DiTreeEntity expressionTree) {
        if (expressionTree == null || expressionTree.root() == null) {
            throw new AnfRefusal("A nested expression needs a tree with a root");
        }
        if (expressionTree.root().getMeaningNid() != KernelConcept.DEFINITION_ROOT.nid()) {
            throw new AnfRefusal("A nested expression is a logical expression, whose root is a definition root");
        }
        try {
            new LogicalExpression(expressionTree);
        } catch (RuntimeException notLogical) {
            throw new AnfRefusal("A nested expression is a logical expression over the kernel's axiom meanings: "
                    + notLogical.getMessage());
        }
    }

    private static void checkText(String text) {
        if (text == null || text.isBlank()) {
            throw new AnfRefusal("A narrative's text is not blank");
        }
    }

    private static void checkAssociation(ConceptFacade associationType) {
        if (associationType == null) {
            throw new AnfRefusal("An associated statement carries its association type");
        }
        for (Map.Entry<ConceptFacade, ConceptFacade> inverse : INVERSE_ASSOCIATIONS.entrySet()) {
            if (inverse.getKey().nid() == associationType.nid()) {
                throw new AnfRefusal("An association is recorded in its canonical direction: not "
                        + inverse.getKey().description() + " here, but " + inverse.getValue().description()
                        + " on the other statement");
            }
        }
        for (ConceptFacade canonical : CANONICAL_ASSOCIATIONS) {
            if (canonical.nid() == associationType.nid()) {
                return;
            }
        }
        throw new AnfRefusal("Not a statement association: " + associationType.description());
    }

    // ── Vertices ──────────────────────────────────────────────────────────────

    private EntityVertex newVertex(AnfSemantic semantic) {
        EntityVertex vertex = EntityVertex.make(vertexUuids.get(), semantic.nid);
        builder.addVertex(vertex);
        return vertex;
    }

    private void attach(EntityVertex child, AnfStatement parent) {
        builder.addEdge(child.vertexIndex(), parent.vertexIndex());
    }

    private static void putBounds(EntityVertex vertex, BigDecimal lowerBound, BigDecimal upperBound,
                                  boolean includeLowerBound, boolean includeUpperBound, BigDecimal resolution) {
        if (lowerBound != null) {
            vertex.putUncommittedProperty(KernelConcept.INTERVAL_LOWER_BOUND.nid(), lowerBound);
        }
        if (upperBound != null) {
            vertex.putUncommittedProperty(KernelConcept.INTERVAL_UPPER_BOUND.nid(), upperBound);
        }
        vertex.putUncommittedProperty(KernelConcept.LOWER_BOUND_OPEN.nid(), !includeLowerBound);
        vertex.putUncommittedProperty(KernelConcept.UPPER_BOUND_OPEN.nid(), !includeUpperBound);
        if (resolution != null) {
            vertex.putUncommittedProperty(IkeConcept.ANF_MEASURE_RESOLUTION.nid(), resolution);
        }
    }

    private static void putProvisional(EntityVertex vertex, AnfProvisional provisional) {
        Objects.requireNonNull(provisional, "provisional");
        switch (provisional) {
            case AnfProvisional.Candidate candidate -> {
                vertex.putUncommittedProperty(IkeConcept.ANF_PROVISIONAL_LABEL.nid(), candidate.label());
                vertex.putUncommittedProperty(IkeConcept.ANF_PROVISIONAL_TEXT.nid(), candidate.text());
                vertex.putUncommittedProperty(IkeConcept.ANF_PROVISIONAL_DISPOSITION.nid(), candidate.disposition());
                if (!candidate.nearestMatches().isEmpty()) {
                    vertex.putUncommittedProperty(IkeConcept.ANF_PROVISIONAL_NEAREST_MATCHES.nid(),
                            String.join(";", candidate.nearestMatches()));
                }
            }
            case AnfProvisional.Clarification clarification -> {
                vertex.putUncommittedProperty(IkeConcept.ANF_CLARIFICATION_FIELD.nid(), clarification.field());
                vertex.putUncommittedProperty(IkeConcept.ANF_CLARIFICATION_QUESTION.nid(), clarification.question());
            }
        }
    }

    private static final List<ConceptFacade> PROVISIONAL_KEYS = List.of(
            IkeConcept.ANF_PROVISIONAL_LABEL, IkeConcept.ANF_PROVISIONAL_TEXT, IkeConcept.ANF_PROVISIONAL_DISPOSITION,
            IkeConcept.ANF_PROVISIONAL_NEAREST_MATCHES, IkeConcept.ANF_CLARIFICATION_FIELD,
            IkeConcept.ANF_CLARIFICATION_QUESTION);

    /** Drops the provisional properties from a vertex, in place: the vertex is being settled. */
    private void clearProvisional(EntityVertex vertex) {
        boolean any = false;
        for (ConceptFacade key : PROVISIONAL_KEYS) {
            if (vertex.properties().containsKey(key.nid())) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        var kept = org.eclipse.collections.api.factory.primitive.LongObjectMaps.mutable.<Object>empty();
        vertex.properties().forEachKeyValue((nid, value) -> {
            boolean provisional = false;
            for (ConceptFacade key : PROVISIONAL_KEYS) {
                if (key.nid() == nid) {
                    provisional = true;
                    break;
                }
            }
            if (!provisional) {
                kept.put(nid, value);
            }
        });
        vertex.setProperties(kept);
    }

    private static void copyProvisional(EntityVertex from, EntityVertex to) {
        for (ConceptFacade key : PROVISIONAL_KEYS) {
            Object value = from.properties().get(key.nid());
            if (value != null) {
                to.putUncommittedProperty(key.nid(), value);
            }
        }
    }

    /**
     * The degenerate logical expression for one concept: a definition root over one necessary
     * set over one conjunction over the concept reference.
     *
     * @param concept the concept
     * @return its tree
     */
    public static DiTreeEntity singleConceptExpression(ConceptFacade concept) {
        LogicalExpressionBuilder logical = new LogicalExpressionBuilder();
        logical.NecessarySet(logical.And(logical.ConceptAxiom(concept)));
        return (DiTreeEntity) logical.build().sourceGraph();
    }
}
