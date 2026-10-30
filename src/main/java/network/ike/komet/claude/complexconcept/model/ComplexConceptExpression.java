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

import dev.ikm.tinkar.component.graph.DiTree;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;

/**
 * A complex concept specification's tree seen through its adaptor layer: one
 * {@link ComplexConceptAdaptor} per vertex, typed by the vertex's meaning
 * (IKE-Network/ike-issues#1312). The analogue of {@code AnfExpression} and of
 * {@code LogicalExpression}.
 */
public final class ComplexConceptExpression {

    private final DiTree<EntityVertex> sourceGraph;
    private final MutableList<ComplexConceptAdaptor> adaptors;

    /**
     * Builds the adaptor view over a specification's source graph, a built {@link DiTreeEntity}
     * or a {@link DiTreeEntity.Builder}.
     *
     * @param sourceGraph the specification tree
     * @throws IllegalStateException if a vertex's meaning is not a complex concept specification
     *                               meaning, or the root is not the specification
     */
    public ComplexConceptExpression(DiTree<EntityVertex> sourceGraph) {
        this.sourceGraph = sourceGraph;
        int vertexCount = sourceGraph.vertexMap().size();
        this.adaptors = Lists.mutable.ofInitialCapacity(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            EntityVertex vertex = sourceGraph.vertex(i);
            if (vertex == null) {
                adaptors.add(null);
                continue;
            }
            switch (ComplexConceptSemantic.get(vertex.getMeaningNid())) {
                case SPECIFICATION -> new ComplexConceptAdaptor.RootAdaptor(this, i);
                case DEFINITION -> new ComplexConceptAdaptor.DefinitionAdaptor(this, i);
                case UNIFORM_EXPANSION -> new ComplexConceptAdaptor.UniformExpansionAdaptor(this, i);
                case FOCUSED_EXPANSION -> new ComplexConceptAdaptor.FocusedExpansionAdaptor(this, i);
                case MEMBER -> new ComplexConceptAdaptor.MemberAdaptor(this, i);
                case PREREQUISITE -> new ComplexConceptAdaptor.PrerequisiteAdaptor(this, i);
            }
        }
        EntityVertex root = sourceGraph.root();
        if (root != null && root.getMeaningNid() != ComplexConceptSemantic.SPECIFICATION.nid) {
            throw new IllegalStateException("A complex concept specification tree's root is the specification, not a "
                    + ComplexConceptSemantic.get(root.getMeaningNid()).describe());
        }
    }

    /**
     * Wraps a built specification tree as an expression.
     *
     * @param tree the built specification tree
     * @return the adaptor view
     */
    public static ComplexConceptExpression from(DiTreeEntity tree) {
        return new ComplexConceptExpression(tree);
    }

    /** Package-private: the mutable adaptor list adaptors register themselves into. */
    MutableList<ComplexConceptAdaptor> mutableAdaptors() {
        return adaptors;
    }

    /** Package-private: the adaptor at a vertex index. */
    ComplexConcept adaptor(int vertexIndex) {
        return adaptors.get(vertexIndex);
    }

    /** @return the tree this expression adapts */
    public DiTree<EntityVertex> sourceGraph() {
        return sourceGraph;
    }

    /** @return the root, the specification */
    public ComplexConcept.Root root() {
        return (ComplexConcept.Root) adaptor(sourceGraph.root().vertexIndex());
    }

    /**
     * The vertex with an identity, which persists across versions and across a builder's edits.
     *
     * @param vertexUuid the vertex's identity
     * @return its adaptor, or null when the tree has no such vertex
     */
    public ComplexConcept vertex(java.util.UUID vertexUuid) {
        for (ComplexConceptAdaptor adaptor : adaptors) {
            if (adaptor != null && adaptor.vertexUUID().equals(vertexUuid)) {
                return adaptor;
            }
        }
        return null;
    }

    /**
     * @param type an adaptor type
     * @param <A>  the type
     * @return every vertex of that type, in vertex order
     */
    public <A extends ComplexConcept> ImmutableList<A> nodesOfType(Class<A> type) {
        MutableList<A> result = Lists.mutable.empty();
        for (ComplexConceptAdaptor adaptor : adaptors) {
            if (type.isInstance(adaptor)) {
                result.add(type.cast(adaptor));
            }
        }
        return result.toImmutable();
    }
}
