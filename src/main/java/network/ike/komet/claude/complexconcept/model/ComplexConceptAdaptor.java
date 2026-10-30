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
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.model.AnfExpression;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.list.primitive.ImmutableIntList;

import java.util.Optional;
import java.util.UUID;

/**
 * Base for every vertex of a complex concept specification's tree, mirroring {@code AnfAdaptor}:
 * it wraps a vertex index within an enclosing {@link ComplexConceptExpression}, registers itself
 * into the expression's adaptor list on construction, and reads its accessors from the vertex's
 * properties, which are the only persistent state (IKE-Network/ike-issues#1312).
 */
public abstract sealed class ComplexConceptAdaptor implements ComplexConcept
        permits ComplexConceptAdaptor.RootAdaptor, ComplexConceptAdaptor.DefinitionAdaptor,
        ComplexConceptAdaptor.UniformExpansionAdaptor, ComplexConceptAdaptor.FocusedExpansionAdaptor,
        ComplexConceptAdaptor.MemberAdaptor, ComplexConceptAdaptor.PrerequisiteAdaptor {

    final ComplexConceptExpression adaptedExpression;
    final int vertexIndex;

    ComplexConceptAdaptor(ComplexConceptExpression adaptedExpression, int vertexIndex) {
        this.adaptedExpression = adaptedExpression;
        this.vertexIndex = vertexIndex;
        MutableList<ComplexConceptAdaptor> adaptors = adaptedExpression.mutableAdaptors();
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
    public ComplexConceptSemantic semantic() {
        return ComplexConceptSemantic.get(vertex().getMeaningNid());
    }

    EntityVertex vertex() {
        return adaptedExpression.sourceGraph().vertex(vertexIndex);
    }

    @Override
    public ImmutableList<ComplexConcept> children() {
        // Through the successor map, as AnfAdaptor does: a tree builder's successors(int) has no
        // answer for a vertex nothing has been attached to yet.
        ImmutableIntList successors = adaptedExpression.sourceGraph().successorMap().get(vertexIndex);
        if (successors == null) {
            return Lists.immutable.empty();
        }
        MutableList<ComplexConcept> result = Lists.mutable.ofInitialCapacity(successors.size());
        for (int childIndex : successors.toArray()) {
            result.add(adaptedExpression.adaptor(childIndex));
        }
        return result.toImmutable();
    }

    /** The children of one meaning that are of one adaptor type, in order. */
    protected <A extends ComplexConcept> ImmutableList<A> childrenOf(ComplexConceptSemantic semantic, Class<A> type) {
        MutableList<A> result = Lists.mutable.empty();
        for (ComplexConcept child : children()) {
            if (child.semantic() == semantic && type.isInstance(child)) {
                result.add(type.cast(child));
            }
        }
        return result.toImmutable();
    }

    protected Optional<ConceptFacade> conceptProperty(ConceptFacade key) {
        Object value = vertex().propertyFast(key);
        return value instanceof ConceptFacade concept ? Optional.of(concept) : Optional.empty();
    }

    protected Optional<DiTreeEntity> treeProperty(ConceptFacade key) {
        Object value = vertex().propertyFast(key);
        return value instanceof DiTreeEntity tree ? Optional.of(tree) : Optional.empty();
    }

    /** The specification. */
    public static final class RootAdaptor extends ComplexConceptAdaptor implements ComplexConcept.Root {
        public RootAdaptor(ComplexConceptExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<Definition> definition() {
            ImmutableList<Definition> definitions = childrenOf(ComplexConceptSemantic.DEFINITION, Definition.class);
            return definitions.isEmpty() ? Optional.empty() : Optional.of(definitions.get(0));
        }

        @Override
        public Optional<Expansion> expansion() {
            for (ComplexConcept child : children()) {
                if (child instanceof Expansion expansion) {
                    return Optional.of(expansion);
                }
            }
            return Optional.empty();
        }
    }

    /** The definition, an EL++ expression held by value. */
    public static final class DefinitionAdaptor extends ComplexConceptAdaptor implements ComplexConcept.Definition {
        public DefinitionAdaptor(ComplexConceptExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<DiTreeEntity> expressionTree() {
            return treeProperty(IkeConcept.DEFINITION_EXPRESSION);
        }

        @Override
        public Optional<LogicalExpression> expression() {
            return expressionTree().map(LogicalExpression::new);
        }
    }

    /** A uniform expansion. */
    public static final class UniformExpansionAdaptor extends ComplexConceptAdaptor
            implements ComplexConcept.Expansion.Uniform {
        public UniformExpansionAdaptor(ComplexConceptExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<ConceptFacade> conjunction() {
            return conceptProperty(IkeConcept.EXPANSION_CONJUNCTION);
        }

        @Override
        public Optional<ConceptFacade> measurementDomain() {
            return conceptProperty(IkeConcept.MEASUREMENT_DOMAIN);
        }

        @Override
        public Optional<ConceptFacade> unsafeDistributionPolicy() {
            return conceptProperty(IkeConcept.UNSAFE_DISTRIBUTION_POLICY);
        }

        @Override
        public ImmutableList<Member> members() {
            return childrenOf(ComplexConceptSemantic.MEMBER, Member.class);
        }
    }

    /** A focused expansion. */
    public static final class FocusedExpansionAdaptor extends ComplexConceptAdaptor
            implements ComplexConcept.Expansion.Focused {
        public FocusedExpansionAdaptor(ComplexConceptExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<ConceptFacade> focus() {
            return conceptProperty(IkeConcept.FOCUS_PHENOMENON);
        }

        @Override
        public ImmutableList<Prerequisite> prerequisites() {
            return childrenOf(ComplexConceptSemantic.PREREQUISITE, Prerequisite.class);
        }
    }

    /** A member of a uniform expansion. */
    public static final class MemberAdaptor extends ComplexConceptAdaptor implements ComplexConcept.Member {
        public MemberAdaptor(ComplexConceptExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<ConceptFacade> phenomenon() {
            return conceptProperty(KernelConcept.CONCEPT_REFERENCE);
        }
    }

    /** A prerequisite of a focused expansion. */
    public static final class PrerequisiteAdaptor extends ComplexConceptAdaptor implements ComplexConcept.Prerequisite {
        public PrerequisiteAdaptor(ComplexConceptExpression expression, int vertexIndex) {
            super(expression, vertexIndex);
        }

        @Override
        public Optional<DiTreeEntity> statementTree() {
            return treeProperty(IkeConcept.PREREQUISITE_STATEMENT);
        }

        @Override
        public Optional<AnfExpression> statement() {
            return statementTree().map(AnfExpression::from);
        }

        @Override
        public Optional<ConceptFacade> association() {
            return conceptProperty(IkeConcept.PREREQUISITE_ASSOCIATION);
        }
    }
}
