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

import dev.ikm.tinkar.component.graph.DiTree;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;

/**
 * An ANF statement as a typed view over its tree: a {@code DiTree<EntityVertex>} source graph plus
 * one {@link AnfAdaptor} per vertex, mirroring {@code LogicalExpression} and the complex clause
 * plugin's {@code ClauseExpression} (IKE-Network/ike-issues#1312). The constructor instantiates
 * the adaptor for each vertex by one exhaustive switch over {@link AnfSemantic}, so a meaning
 * added without its adaptor cannot build; each adaptor registers itself into
 * {@link #mutableAdaptors()}.
 */
public final class AnfExpression {

    private final DiTree<EntityVertex> sourceGraph;
    private final MutableList<AnfAdaptor> adaptors;

    /**
     * Builds the adaptor view over a statement source graph, a built {@link DiTreeEntity} or a
     * {@link DiTreeEntity.Builder}.
     *
     * @param sourceGraph the statement tree
     * @throws IllegalStateException if a vertex's meaning is not an ANF statement meaning
     */
    public AnfExpression(DiTree<EntityVertex> sourceGraph) {
        this.sourceGraph = sourceGraph;
        int vertexCount = sourceGraph.vertexMap().size();
        this.adaptors = Lists.mutable.ofInitialCapacity(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            EntityVertex vertex = sourceGraph.vertex(i);
            if (vertex == null) {
                adaptors.add(null);
                continue;
            }
            switch (AnfSemantic.get(vertex.getMeaningNid())) {
                case STATEMENT -> new AnfAdaptor.RootAdaptor(this, i);
                case CONTEXT -> new AnfAdaptor.ContextAdaptor(this, i);
                case PERFORMANCE -> new AnfAdaptor.PerformanceAdaptor(this, i);
                case REQUEST -> new AnfAdaptor.RequestAdaptor(this, i);
                case NARRATIVE -> new AnfAdaptor.NarrativeAdaptor(this, i);
                case STATEMENT_TYPE, DISPOSITION, HEALTH_RISK, METHOD, DEVICE, BODY_SITE, LATERALITY, APPROACH,
                     SPECIMEN_TYPE, SPECIMEN_SOURCE_SITE, PRIORITY, CONDITIONAL_TRIGGER, LOCATION ->
                        new AnfAdaptor.ConceptReferenceAdaptor(this, i);
                case STATEMENT_TIME, TIMING, RESULT, NORMAL_RANGE, REQUESTED_RESULT, PERIOD_START, PERIOD_DURATION,
                     EVENT_SEPARATION, EVENT_DURATION, EVENT_FREQUENCY -> new AnfAdaptor.MeasureAdaptor(this, i);
                case SUBJECT_OF_INFORMATION, TOPIC, PURPOSE -> new AnfAdaptor.ExpressionAdaptor(this, i);
                case SUBJECT_OF_RECORD, PARTICIPANT -> new AnfAdaptor.ParticipantAdaptor(this, i);
                case AUTHOR -> new AnfAdaptor.AuthorAdaptor(this, i);
                case NARRATIVE_TEXT -> new AnfAdaptor.NarrativeTextAdaptor(this, i);
                case REPETITION -> new AnfAdaptor.RepetitionAdaptor(this, i);
                case ASSOCIATED_STATEMENT -> new AnfAdaptor.AssociatedStatementAdaptor(this, i);
            }
        }
        EntityVertex root = sourceGraph.root();
        if (root != null && root.getMeaningNid() != AnfSemantic.STATEMENT.nid) {
            throw new IllegalStateException("An ANF statement tree's root is the statement, not a "
                    + AnfSemantic.get(root.getMeaningNid()).describe());
        }
    }

    /** Package-private: the mutable adaptor list adaptors register themselves into. */
    MutableList<AnfAdaptor> mutableAdaptors() {
        return adaptors;
    }

    /** Package-private: the adaptor at a vertex index. */
    AnfStatement adaptor(int vertexIndex) {
        return adaptors.get(vertexIndex);
    }

    /** @return the underlying statement tree. */
    public DiTree<EntityVertex> sourceGraph() {
        return sourceGraph;
    }

    /** @return the statement root. */
    public AnfStatement.Root root() {
        return (AnfStatement.Root) adaptors.get(sourceGraph.root().vertexIndex());
    }

    /** @return every vertex of one type, in vertex order. */
    public <A extends AnfStatement> ImmutableList<A> nodesOfType(Class<A> type) {
        MutableList<A> result = Lists.mutable.empty();
        for (AnfAdaptor adaptor : adaptors) {
            if (type.isInstance(adaptor)) {
                result.add(type.cast(adaptor));
            }
        }
        return result.toImmutable();
    }

    /** @return the vertex with a UUID, or null when the tree has none. */
    public AnfStatement vertex(java.util.UUID vertexUuid) {
        for (AnfAdaptor adaptor : adaptors) {
            if (adaptor != null && adaptor.vertexUUID().equals(vertexUuid)) {
                return adaptor;
            }
        }
        return null;
    }

    /**
     * Wraps a built statement tree as an expression.
     *
     * @param tree the built statement tree
     * @return the adaptor view
     */
    public static AnfExpression from(DiTreeEntity tree) {
        return new AnfExpression(tree);
    }

    @Override
    public String toString() {
        return sourceGraph.toString();
    }
}
