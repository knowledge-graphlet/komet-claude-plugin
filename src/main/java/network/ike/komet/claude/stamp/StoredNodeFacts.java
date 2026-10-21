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
package network.ike.komet.claude.stamp;

import dev.ikm.tinkar.common.id.LongIdList;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.FieldDefinitionForEntity;
import dev.ikm.tinkar.entity.PatternEntity;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.terms.EntityFacade;
import network.ike.foundation.ike.bindings.IkeConcept;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the store says a node of the document graph is, read as the property graph holds it
 * (ike-lab-documents topic {@code kom-substrate-property-graph}): the kind of component, its
 * public id, the pattern it is an instance of and that pattern's meaning, its properties as the
 * pattern's field definitions key them, and the component it references. Nothing here is
 * specific to documents: a prose element, a document block, a reference and an annotation all
 * read the same way, and so will the role-typed line patterns to come.
 */
public final class StoredNodeFacts {

    /**
     * One property: the field's meaning as its key, the value in words, and the component the
     * value names where it is one.
     *
     * @param key       the field's meaning concept, named through the view
     * @param value     the value as text: a string as is, a number, a component's name, a list's size
     * @param targetNid the component a component-typed value names, {@code 0} otherwise
     */
    public record Property(String key, String value, long targetNid) { }

    /**
     * The facts of one node.
     *
     * @param nid            the node's nid
     * @param publicId       its public id
     * @param sigil          the kind sigil of the component-badge scheme: empty for a concept, {@code S} for a semantic, {@code P} for a pattern
     * @param patternName    the pattern's name, for a semantic; else the kind in words
     * @param meaning        the pattern's meaning concept, named; for a concept or pattern, its own name
     * @param properties     the properties in field order
     * @param referencedNid  the component a semantic references, {@code 0} otherwise
     * @param referencedName that component's name, empty otherwise
     */
    public record NodeFacts(long nid, PublicId publicId, String sigil, String patternName, String meaning, List<Property> properties,
                            long referencedNid, String referencedName) {

        /** @return the first text-valued property, the one a row previews: a string that is not a bare number or a count; empty when none */
        public Optional<Property> primary() {
            return properties.stream().filter(p -> p.targetNid() == 0 && !p.value().isEmpty() && !p.value().matches("-?\\d+(\\.\\d+)?")
                    && !p.value().matches("\\d+ components?")).findFirst();
        }

        /** @return the first component-valued property, the one that refines the meaning on a row; empty when none */
        public Optional<Property> refinement() {
            return properties.stream().filter(p -> p.targetNid() != 0).findFirst();
        }
    }

    private StoredNodeFacts() { }

    /**
     * Reads a node's facts from the store.
     *
     * @param nid     the node's nid
     * @param view    the view that names concepts and selects the pattern version
     * @param pending whether an uncommitted version of a semantic counts as its latest
     * @return the facts, or empty when the store holds no such component
     */
    public static Optional<NodeFacts> read(long nid, ViewCalculator view, boolean pending) {
        Optional<? extends Entity<? extends EntityVersion>> held = EntityHandle.get(nid).entity();
        if (held.isEmpty()) {
            return Optional.empty();
        }
        Entity<? extends EntityVersion> entity = held.get();
        return switch (entity) {
            case SemanticEntity<?> semantic -> Optional.of(semantic(semantic, view, pending));
            case ConceptEntity<?> concept -> Optional.of(new NodeFacts(nid, concept.publicId(), "", "Concept",
                    StampNames.name(view, nid), List.of(), 0, ""));
            case PatternEntity<?> pattern -> Optional.of(new NodeFacts(nid, pattern.publicId(), "P", "Pattern",
                    StampNames.name(view, nid), List.of(), 0, ""));
            default -> Optional.of(new NodeFacts(nid, entity.publicId(), "?", entity.getClass().getSimpleName(),
                    StampNames.name(view, nid), List.of(), 0, ""));
        };
    }

    @SuppressWarnings("unchecked")
    private static NodeFacts semantic(SemanticEntity<?> semantic, ViewCalculator view, boolean pending) {
        long patternNid = semantic.patternNid();
        String patternName = StampNames.name(view, patternNid);
        SemanticEntityVersion latest = GitDocumentMaterializer.latest((SemanticEntity<SemanticEntityVersion>) semantic, pending);
        if (latest != null && latest.fieldValues().size() == 1 && latest.fieldValues().get(0) instanceof EntityVertex vertex) {
            return vertex(semantic, vertex, patternName, view);
        }
        String meaning = patternName;
        List<? extends FieldDefinitionForEntity> definitions = List.of();
        Latest<PatternEntityVersion> pattern = view.stampCalculator().latest(patternNid);
        if (pattern.isPresent()) {
            meaning = StampNames.name(view, pattern.get().semanticMeaningNid());
            definitions = pattern.get().fieldDefinitions().castToList();
        }
        List<Property> properties = new ArrayList<>();
        SemanticEntityVersion version = GitDocumentMaterializer.latest((SemanticEntity<SemanticEntityVersion>) semantic, pending);
        if (version != null) {
            List<Object> values = version.fieldValues().castToList();
            for (int i = 0; i < values.size(); i++) {
                String key = i < definitions.size() ? StampNames.name(view, definitions.get(i).meaningNid()) : "field " + (i + 1);
                properties.add(property(key, values.get(i), view));
            }
        }
        long referenced = semantic.referencedComponentNid();
        return new NodeFacts(semantic.nid(), semantic.publicId(), "S", patternName, meaning, List.copyOf(properties),
                referenced, StampNames.name(view, referenced));
    }

    /**
     * A semantic vertex read as the vertex it is (IKE-Network/ike-issues#1313): its meaning is the
     * vertex's, not its pattern's (every vertex of a document shares the pattern's meaning,
     * Document vertex), and its properties are the vertex's, keyed by concept. The text a row
     * previews comes first: a line's Line source, a block's title; Members, the edges the graph
     * draws as children, last; the rest by name.
     */
    private static NodeFacts vertex(SemanticEntity<?> semantic, EntityVertex vertex, String patternName, ViewCalculator view) {
        List<long[]> keys = new ArrayList<>();
        vertex.properties().forEachKey(key -> keys.add(new long[] {rank(key), key}));
        List<Property> properties = new ArrayList<>();
        keys.stream()
                .sorted(java.util.Comparator.<long[]>comparingLong(k -> k[0]).thenComparing(k -> StampNames.name(view, k[1])))
                .forEach(k -> properties.add(property(StampNames.name(view, k[1]), vertex.properties().get(k[1]), view)));
        long referenced = semantic.referencedComponentNid();
        return new NodeFacts(semantic.nid(), semantic.publicId(), "S", patternName, StampNames.name(view, vertex.getMeaningNid()),
                List.copyOf(properties), referenced, StampNames.name(view, referenced));
    }

    /** Where a property key sorts: the previewed text first, Members last. */
    private static long rank(long key) {
        if (key == IkeConcept.LINE_SOURCE.nid()) {
            return 0;
        }
        if (key == IkeConcept.DOCUMENT_ELEMENT_TITLE.nid()) {
            return 1;
        }
        return key == IkeConcept.DOCUMENT_ELEMENT_MEMBERS.nid() ? 3 : 2;
    }

    /** A field value in words: a string as is, a number, a component's name, a list's size. */
    static Property property(String key, Object value, ViewCalculator view) {
        return switch (value) {
            case null -> new Property(key, "", 0);
            case String s -> new Property(key, s, 0);
            case EntityFacade facade -> new Property(key, StampNames.name(view, facade.nid()), facade.nid());
            case LongIdList ids -> new Property(key, ids.size() + (ids.size() == 1 ? " component" : " components"), 0);
            case PublicId id -> new Property(key, id.idString(), 0);
            default -> new Property(key, String.valueOf(value), 0);
        };
    }
}
