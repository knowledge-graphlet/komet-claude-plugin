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
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalAxiom;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpression;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.foundation.ike.bindings.IkeConcept;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.list.primitive.ImmutableIntList;
import org.eclipse.collections.api.set.ImmutableSet;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Base for every ANF statement vertex, mirroring {@code LogicalAxiomAdaptor} and the complex
 * clause plugin's {@code ClauseAdaptor}: it wraps a vertex index within an enclosing
 * {@link AnfExpression}, registers itself into the expression's adaptor list on construction, and
 * exposes the child and property helpers the shape adaptors read from. One adaptor per vertex;
 * one class per {@link AnfSemantic.Shape}.
 */
public abstract sealed class AnfAdaptor implements AnfStatement
        permits AnfAdaptor.RootAdaptor, AnfAdaptor.ContextAdaptor, AnfAdaptor.PerformanceAdaptor,
        AnfAdaptor.RequestAdaptor, AnfAdaptor.NarrativeAdaptor, AnfAdaptor.ConceptReferenceAdaptor,
        AnfAdaptor.MeasureAdaptor, AnfAdaptor.ExpressionAdaptor, AnfAdaptor.ParticipantAdaptor,
        AnfAdaptor.AuthorAdaptor, AnfAdaptor.NarrativeTextAdaptor, AnfAdaptor.RepetitionAdaptor,
        AnfAdaptor.AssociatedStatementAdaptor {

    final AnfExpression adaptedExpression;
    final int vertexIndex;

    AnfAdaptor(AnfExpression adaptedExpression, int vertexIndex) {
        this.adaptedExpression = adaptedExpression;
        this.vertexIndex = vertexIndex;
        MutableList<AnfAdaptor> adaptors = adaptedExpression.mutableAdaptors();
        if (vertexIndex > -1 && vertexIndex < adaptors.size()) {
            adaptors.set(vertexIndex, this);
        } else if (vertexIndex == adaptors.size()) {
            adaptors.add(this);
        } else {
            while (vertexIndex > adaptors.size()) {
                adaptors.add(null);
            }
            adaptors.add(this);
        }
    }

    @Override
    public int vertexIndex() {
        return vertexIndex;
    }

    @Override
    public UUID vertexUUID() {
        return vertex().asUuid();
    }

    @Override
    public AnfSemantic semantic() {
        return AnfSemantic.get(vertex().getMeaningNid());
    }

    /** @return the vertex this adaptor adapts. */
    EntityVertex vertex() {
        return adaptedExpression.sourceGraph().vertex(vertexIndex);
    }

    @Override
    public ImmutableList<AnfStatement> children() {
        // Through the successor map, not successors(int): a tree builder's successors(int) has no
        // answer for a vertex nothing has been attached to yet, and a statement under construction
        // is full of those.
        ImmutableIntList successors = adaptedExpression.sourceGraph().successorMap().get(vertexIndex);
        if (successors == null) {
            return Lists.immutable.empty();
        }
        MutableList<AnfStatement> result = Lists.mutable.ofInitialCapacity(successors.size());
        for (int childIndex : successors.toArray()) {
            result.add(adaptedExpression.adaptor(childIndex));
        }
        return result.toImmutable();
    }

    /** The children of one meaning, in edge order. */
    protected <A extends AnfStatement> ImmutableList<A> childrenOf(AnfSemantic semantic, Class<A> type) {
        MutableList<A> result = Lists.mutable.empty();
        for (AnfStatement child : children()) {
            if (child.semantic() == semantic && type.isInstance(child)) {
                result.add(type.cast(child));
            }
        }
        return result.toImmutable();
    }

    /** The one child of a meaning, or empty; a builder-made tree never has two. */
    protected <A extends AnfStatement> Optional<A> childOf(AnfSemantic semantic, Class<A> type) {
        ImmutableList<A> children = childrenOf(semantic, type);
        return children.isEmpty() ? Optional.empty() : Optional.of(children.get(0));
    }

    /**
     * Reads a typed vertex property by key.
     *
     * @param key the property-key concept
     * @param <O> the stored value type
     * @return the stored value, or {@code null} if absent
     */
    protected <O> O property(ConceptFacade key) {
        return vertex().propertyFast(key);
    }

    /** Reads a typed vertex property by key as an optional. */
    protected <O> Optional<O> optionalProperty(ConceptFacade key) {
        return Optional.ofNullable(property(key));
    }

    /** The concept a property holds, or empty when absent or not a concept. */
    protected Optional<ConceptFacade> conceptProperty(ConceptFacade key) {
        Object value = property(key);
        return value instanceof ConceptFacade concept ? Optional.of(concept) : Optional.empty();
    }

    /** The tree a property holds, or empty when absent or not a tree. */
    protected Optional<DiTreeEntity> treeProperty(ConceptFacade key) {
        Object value = property(key);
        return value instanceof DiTreeEntity tree ? Optional.of(tree) : Optional.empty();
    }

    /** The decimal a property holds, or empty when absent; a number of another type is widened. */
    protected Optional<BigDecimal> decimalProperty(ConceptFacade key) {
        Object value = property(key);
        return switch (value) {
            case null -> Optional.empty();
            case BigDecimal decimal -> Optional.of(decimal);
            case Number number -> Optional.of(new BigDecimal(number.toString()));
            default -> Optional.empty();
        };
    }

    /** The text a property holds, or empty when absent. */
    protected Optional<String> textProperty(ConceptFacade key) {
        Object value = property(key);
        return value instanceof String text ? Optional.of(text) : Optional.empty();
    }

    /** What an attribute vertex carries in place of a concept: a candidate or a clarification. */
    protected Optional<AnfProvisional> provisionalProperties() {
        Optional<String> label = textProperty(IkeConcept.ANF_PROVISIONAL_LABEL);
        if (label.isPresent()) {
            String nearest = textProperty(IkeConcept.ANF_PROVISIONAL_NEAREST_MATCHES).orElse("");
            List<String> nearestMatches = nearest.isBlank() ? List.of()
                    : Arrays.stream(nearest.split(";")).filter(id -> !id.isBlank()).toList();
            return Optional.of(new AnfProvisional.Candidate(label.get(),
                    textProperty(IkeConcept.ANF_PROVISIONAL_TEXT).orElse(label.get()),
                    textProperty(IkeConcept.ANF_PROVISIONAL_DISPOSITION).orElse("PENDING"),
                    nearestMatches));
        }
        Optional<String> field = textProperty(IkeConcept.ANF_CLARIFICATION_FIELD);
        if (field.isPresent()) {
            return Optional.of(new AnfProvisional.Clarification(field.get(),
                    textProperty(IkeConcept.ANF_CLARIFICATION_QUESTION).orElse("")));
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + semantic() + "]";
    }

    // ── The statement ─────────────────────────────────────────────────────────

    /** The root. */
    public static final class RootAdaptor extends AnfAdaptor implements AnfStatement.Root {
        public RootAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public ConceptFacade kind() {
            return conceptProperty(IkeConcept.ANF_STATEMENT_KIND)
                    .orElseThrow(() -> new IllegalStateException("The statement root carries no statement kind"));
        }

        @Override
        public AnfSemantic kindSemantic() {
            return AnfSemantic.circumstanceKind(kind());
        }

        @Override
        public Optional<Context> context() {
            return childOf(AnfSemantic.CONTEXT, Context.class);
        }

        @Override
        public Optional<Circumstance> circumstance() {
            for (AnfStatement child : children()) {
                if (child instanceof Circumstance circumstance) {
                    return Optional.of(circumstance);
                }
            }
            return Optional.empty();
        }

        @Override
        public ImmutableList<Attribute.AssociatedStatement> associatedStatements() {
            return childrenOf(AnfSemantic.ASSOCIATED_STATEMENT, Attribute.AssociatedStatement.class);
        }
    }

    /** The context clause. */
    public static final class ContextAdaptor extends AnfAdaptor implements AnfStatement.Context {
        public ContextAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<Attribute.Participant> subjectOfRecord() {
            return childOf(AnfSemantic.SUBJECT_OF_RECORD, Attribute.Participant.class);
        }

        @Override
        public Optional<Attribute.Expression> subjectOfInformation() {
            return childOf(AnfSemantic.SUBJECT_OF_INFORMATION, Attribute.Expression.class);
        }

        @Override
        public Optional<Attribute.Expression> topic() {
            return childOf(AnfSemantic.TOPIC, Attribute.Expression.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> statementType() {
            return childOf(AnfSemantic.STATEMENT_TYPE, Attribute.ConceptReference.class);
        }

        @Override
        public ImmutableList<Attribute.Author> authors() {
            return childrenOf(AnfSemantic.AUTHOR, Attribute.Author.class);
        }

        @Override
        public Optional<Attribute.Measure> statementTime() {
            return childOf(AnfSemantic.STATEMENT_TIME, Attribute.Measure.class);
        }
    }

    // ── The circumstances ─────────────────────────────────────────────────────

    /** The performance circumstance. */
    public static final class PerformanceAdaptor extends AnfAdaptor implements AnfStatement.Circumstance.Performance {
        public PerformanceAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<Attribute.Measure> timing() {
            return childOf(AnfSemantic.TIMING, Attribute.Measure.class);
        }

        @Override
        public ImmutableList<Attribute.Expression> purposes() {
            return childrenOf(AnfSemantic.PURPOSE, Attribute.Expression.class);
        }

        @Override
        public Optional<Attribute.Measure> result() {
            return childOf(AnfSemantic.RESULT, Attribute.Measure.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> disposition() {
            return childOf(AnfSemantic.DISPOSITION, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> healthRisk() {
            return childOf(AnfSemantic.HEALTH_RISK, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.Measure> normalRange() {
            return childOf(AnfSemantic.NORMAL_RANGE, Attribute.Measure.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> method() {
            return childOf(AnfSemantic.METHOD, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> device() {
            return childOf(AnfSemantic.DEVICE, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> bodySite() {
            return childOf(AnfSemantic.BODY_SITE, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> laterality() {
            return childOf(AnfSemantic.LATERALITY, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> approach() {
            return childOf(AnfSemantic.APPROACH, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> specimenType() {
            return childOf(AnfSemantic.SPECIMEN_TYPE, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> specimenSourceSite() {
            return childOf(AnfSemantic.SPECIMEN_SOURCE_SITE, Attribute.ConceptReference.class);
        }

        @Override
        public ImmutableList<Attribute.Participant> participants() {
            return childrenOf(AnfSemantic.PARTICIPANT, Attribute.Participant.class);
        }
    }

    /** The request circumstance. */
    public static final class RequestAdaptor extends AnfAdaptor implements AnfStatement.Circumstance.Request {
        public RequestAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<Attribute.Measure> timing() {
            return childOf(AnfSemantic.TIMING, Attribute.Measure.class);
        }

        @Override
        public ImmutableList<Attribute.Expression> purposes() {
            return childrenOf(AnfSemantic.PURPOSE, Attribute.Expression.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> priority() {
            return childOf(AnfSemantic.PRIORITY, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.Measure> requestedResult() {
            return childOf(AnfSemantic.REQUESTED_RESULT, Attribute.Measure.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> disposition() {
            return childOf(AnfSemantic.DISPOSITION, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.Repetition> repetition() {
            return childOf(AnfSemantic.REPETITION, Attribute.Repetition.class);
        }

        @Override
        public ImmutableList<Attribute.ConceptReference> conditionalTriggers() {
            return childrenOf(AnfSemantic.CONDITIONAL_TRIGGER, Attribute.ConceptReference.class);
        }

        @Override
        public ImmutableList<Attribute.Participant> participants() {
            return childrenOf(AnfSemantic.PARTICIPANT, Attribute.Participant.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> device() {
            return childOf(AnfSemantic.DEVICE, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> method() {
            return childOf(AnfSemantic.METHOD, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> location() {
            return childOf(AnfSemantic.LOCATION, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> specimenType() {
            return childOf(AnfSemantic.SPECIMEN_TYPE, Attribute.ConceptReference.class);
        }

        @Override
        public Optional<Attribute.ConceptReference> specimenSourceSite() {
            return childOf(AnfSemantic.SPECIMEN_SOURCE_SITE, Attribute.ConceptReference.class);
        }
    }

    /** The narrative circumstance. */
    public static final class NarrativeAdaptor extends AnfAdaptor implements AnfStatement.Circumstance.Narrative {
        public NarrativeAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<Attribute.Measure> timing() {
            return childOf(AnfSemantic.TIMING, Attribute.Measure.class);
        }

        @Override
        public ImmutableList<Attribute.Expression> purposes() {
            return childrenOf(AnfSemantic.PURPOSE, Attribute.Expression.class);
        }

        @Override
        public Optional<Attribute.NarrativeText> text() {
            return childOf(AnfSemantic.NARRATIVE_TEXT, Attribute.NarrativeText.class);
        }
    }

    // ── The attributes ────────────────────────────────────────────────────────

    /** A concept reference chip. */
    public static final class ConceptReferenceAdaptor extends AnfAdaptor implements AnfStatement.Attribute.ConceptReference {
        public ConceptReferenceAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<ConceptFacade> concept() {
            return conceptProperty(KernelConcept.CONCEPT_REFERENCE);
        }

        @Override
        public Optional<AnfProvisional> provisional() {
            return provisionalProperties();
        }
    }

    /** A measure under the kernel's interval keys. */
    public static final class MeasureAdaptor extends AnfAdaptor implements AnfStatement.Attribute.Measure {
        public MeasureAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<BigDecimal> lowerBound() {
            return decimalProperty(KernelConcept.INTERVAL_LOWER_BOUND);
        }

        @Override
        public Optional<BigDecimal> upperBound() {
            return decimalProperty(KernelConcept.INTERVAL_UPPER_BOUND);
        }

        @Override
        public boolean includeLowerBound() {
            return !Boolean.TRUE.equals(property(KernelConcept.LOWER_BOUND_OPEN));
        }

        @Override
        public boolean includeUpperBound() {
            return !Boolean.TRUE.equals(property(KernelConcept.UPPER_BOUND_OPEN));
        }

        @Override
        public Optional<BigDecimal> resolution() {
            return decimalProperty(IkeConcept.ANF_MEASURE_RESOLUTION);
        }

        @Override
        public Optional<ConceptFacade> unit() {
            return conceptProperty(KernelConcept.UNIT_OF_MEASURE);
        }

        @Override
        public Optional<AnfProvisional> provisional() {
            return provisionalProperties();
        }
    }

    /** A nested logical expression held by value. */
    public static final class ExpressionAdaptor extends AnfAdaptor implements AnfStatement.Attribute.Expression {
        public ExpressionAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<DiTreeEntity> expressionTree() {
            return treeProperty(IkeConcept.ANF_LOGICAL_EXPRESSION);
        }

        @Override
        public Optional<LogicalExpression> expression() {
            return expressionTree().map(LogicalExpression::new);
        }

        @Override
        public Optional<ConceptFacade> singleConcept() {
            return expression().flatMap(AnfAdaptor::singleConceptOf);
        }

        @Override
        public Optional<AnfProvisional> provisional() {
            return provisionalProperties();
        }
    }

    /**
     * The one concept a logical expression reduces to, when it is the degenerate case: a
     * definition root over one necessary set over one conjunction over one concept reference.
     */
    static Optional<ConceptFacade> singleConceptOf(LogicalExpression expression) {
        ImmutableSet<LogicalAxiom.LogicalSet> sets = expression.definitionRoot().sets();
        if (sets.size() != 1 || !(sets.getOnly() instanceof LogicalAxiom.LogicalSet.NecessarySet necessary)) {
            return Optional.empty();
        }
        ImmutableSet<LogicalAxiom.Atom.Connective> connectives = necessary.elements();
        if (connectives.size() != 1 || !(connectives.getOnly() instanceof LogicalAxiom.Atom.Connective.And and)) {
            return Optional.empty();
        }
        ImmutableSet<LogicalAxiom.Atom> atoms = and.elements();
        if (atoms.size() != 1 || !(atoms.getOnly() instanceof LogicalAxiom.Atom.ConceptAxiom conceptAxiom)) {
            return Optional.empty();
        }
        return Optional.ofNullable(conceptAxiom.concept());
    }

    /** A participant reference with its role as a concept. */
    public static final class ParticipantAdaptor extends AnfAdaptor implements AnfStatement.Attribute.Participant {
        public ParticipantAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<ConceptFacade> participant() {
            return conceptProperty(KernelConcept.CONCEPT_REFERENCE);
        }

        @Override
        public Optional<ConceptFacade> role() {
            return conceptProperty(IkeConcept.ANF_PARTICIPANT_ROLE);
        }

        @Override
        public Optional<AnfProvisional> provisional() {
            return provisionalProperties();
        }
    }

    /** An author reference with its role as a nested expression. */
    public static final class AuthorAdaptor extends AnfAdaptor implements AnfStatement.Attribute.Author {
        public AuthorAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<ConceptFacade> participant() {
            return conceptProperty(KernelConcept.CONCEPT_REFERENCE);
        }

        @Override
        public Optional<DiTreeEntity> roleTree() {
            return treeProperty(IkeConcept.ANF_AUTHOR_ROLE_EXPRESSION);
        }

        @Override
        public Optional<LogicalExpression> role() {
            return roleTree().map(LogicalExpression::new);
        }

        @Override
        public Optional<AnfProvisional> provisional() {
            return provisionalProperties();
        }
    }

    /** A text literal. */
    public static final class NarrativeTextAdaptor extends AnfAdaptor implements AnfStatement.Attribute.NarrativeText {
        public NarrativeTextAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public String text() {
            return textProperty(KernelConcept.LITERAL_VALUE).orElse("");
        }

        @Override
        public Optional<AnfProvisional> provisional() {
            return Optional.empty();
        }
    }

    /** A request's repetition clause. */
    public static final class RepetitionAdaptor extends AnfAdaptor implements AnfStatement.Attribute.Repetition {
        public RepetitionAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<Measure> periodStart() {
            return childOf(AnfSemantic.PERIOD_START, Measure.class);
        }

        @Override
        public Optional<Measure> periodDuration() {
            return childOf(AnfSemantic.PERIOD_DURATION, Measure.class);
        }

        @Override
        public Optional<Measure> eventSeparation() {
            return childOf(AnfSemantic.EVENT_SEPARATION, Measure.class);
        }

        @Override
        public Optional<Measure> eventDuration() {
            return childOf(AnfSemantic.EVENT_DURATION, Measure.class);
        }

        @Override
        public Optional<Measure> eventFrequency() {
            return childOf(AnfSemantic.EVENT_FREQUENCY, Measure.class);
        }

        @Override
        public Optional<AnfProvisional> provisional() {
            return Optional.empty();
        }
    }

    /** A reference to another statement. */
    public static final class AssociatedStatementAdaptor extends AnfAdaptor
            implements AnfStatement.Attribute.AssociatedStatement {
        public AssociatedStatementAdaptor(AnfExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<EntityFacade> statement() {
            Object value = property(IkeConcept.ANF_ASSOCIATED_STATEMENT_REFERENCE);
            return value instanceof EntityFacade facade ? Optional.of(facade) : Optional.empty();
        }

        @Override
        public Optional<ConceptFacade> associationType() {
            return conceptProperty(IkeConcept.ANF_ASSOCIATION_TYPE);
        }

        @Override
        public Optional<AnfProvisional> provisional() {
            return Optional.empty();
        }
    }
}
