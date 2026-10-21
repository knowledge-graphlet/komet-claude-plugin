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
package network.ike.komet.claude.doc;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.boundary.CommitBoundary;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.stamp.StampNames;
import network.ike.komet.claude.stamp.VertexReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every journal scenario of {@link JournalStoreIT}, with new journals written as semantic
 * vertices (IKE-Network/ike-issues#1313) and every append checked by the commit boundary in fail
 * mode. The two scenarios that read the prose-element model's manifest and block fields are
 * replaced by their vertex counterparts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VertexJournalStoreIT extends JournalStoreIT {

    @Override
    String model() {
        return "vertex";
    }

    @BeforeAll
    @Override
    void setupDatabase() {
        System.setProperty(CommitBoundary.MODE_PROPERTY, "fail");
        super.setupDatabase();
    }

    /** Replaced by {@link #eachAppendIsANewVersionOfTheRoot()}: a vertex journal has no manifest. */
    @Override
    void secondAppendGrowsTheManifestAsANewVersionInOrder() {
    }

    /** Replaced by {@link #eachTurnIsAVertexUnderTheRoot()}. */
    @Override
    void eachTurnIsABlockOfTheSharedModelUnderTheRoot() {
    }

    @Test
    void eachAppendIsANewVersionOfTheRoot() {
        PublicId anchor = store.appendExchange(null, "Growing vertex conversation", "u1", "a1");
        assertTrue(VertexReader.isVertexDocument(anchor), "a new journal is begun as vertices");
        long rootNid = EntityService.get().nidForPublicId(anchor);
        int after = EntityHandle.get(rootNid).expectSemantic().versions().size();
        store.appendExchange(anchor, "Growing vertex conversation", "u2", "a2");
        assertEquals(after + 1, EntityHandle.get(rootNid).expectSemantic().versions().size(), "one root version per exchange");
        assertTrue(EntityService.get().semanticsForComponentOfPattern(rootNid, IkePattern.JOURNAL_MANIFEST_PATTERN.nid()).toList().isEmpty(),
                "no manifest: the order is the tree's");
        assertEquals(List.of("u1", "a1", "u2", "a2"), store.load(anchor).orElseThrow().stream().map(JournalStore.TurnRecord::markdown).toList());
    }

    @Test
    void eachTurnIsAVertexUnderTheRoot() {
        PublicId anchor = store.appendExchange(null, "Vertex conversation", "Plain question", "Intro\n\n```java\nint x;\n```");
        SemanticEntity<?> rootEntity = EntityHandle.get(EntityService.get().nidForPublicId(anchor)).expectSemantic();
        long patternNid = rootEntity.patternNid();

        // The journal's own pattern: instance data, named for the conversation.
        PatternEntityVersion pattern = (PatternEntityVersion) EntityHandle.get(patternNid).expectPattern().versions().getFirst();
        assertEquals(IkeConcept.DOCUMENT_VERTEX.nid(), pattern.semanticMeaningNid());
        assertEquals(DataKind.INSTANCE, EntityBoundaries.birthKind(patternNid), "a journal is instance data");
        assertEquals("Vertex conversation", StampNames.name(Calculators.View.Default(), patternNid));
        assertEquals(patternNid, rootEntity.referencedComponentNid(), "the root references its pattern");

        EntityVertex root = vertex(rawLatest(rootEntity.nid()));
        assertEquals(IkeConcept.CONVERSATION_JOURNAL.nid(), root.getMeaningNid());
        assertEquals("Vertex conversation", root.properties().get(IkeConcept.DOCUMENT_ELEMENT_TITLE.nid()));
        long[] turns = VertexReader.members(root);
        assertEquals(2, turns.length, "one exchange = two turns");

        SemanticEntityVersion assistantTurn = rawLatest(turns[1]);
        assertEquals(IkeConcept.CONVERSATION_TURN.nid(), vertex(assistantTurn).getMeaningNid());
        assertEquals(IkeConcept.KOMET_ASSISTANT.nid(), assistantTurn.authorNid(), "the turn's author");
        long[] members = VertexReader.members(vertex(assistantTurn));
        assertEquals(3, members.length, "paragraph, blank line, listing");
        assertEquals(IkeConcept.DOCUMENT_PARAGRAPH.nid(), vertex(rawLatest(members[0])).getMeaningNid());
        assertEquals(IkeConcept.PROSE_LINE.nid(), vertex(rawLatest(VertexReader.members(vertex(rawLatest(members[0])))[0])).getMeaningNid());
        assertEquals(IkeConcept.BLANK_LINE.nid(), vertex(rawLatest(members[1])).getMeaningNid(), "a blank line is the turn's own line");
        EntityVertex listing = vertex(rawLatest(members[2]));
        assertEquals(IkeConcept.DOCUMENT_LISTING.nid(), listing.getMeaningNid());
        assertEquals("java", listing.properties().get(IkeConcept.DOCUMENT_ELEMENT_PARAMETERS.nid()), "the fence's info string");
        long[] code = VertexReader.members(listing);
        assertEquals(List.of(IkeConcept.DELIMITER_LINE.nid(), IkeConcept.VERBATIM_LINE.nid(), IkeConcept.DELIMITER_LINE.nid()),
                java.util.Arrays.stream(code).mapToObj(n -> vertex(rawLatest(n)).getMeaningNid()).toList());
        assertEquals("int x;", vertex(rawLatest(code[1])).properties().get(IkeConcept.LINE_SOURCE.nid()));

        int[] elements = {0};
        EntityService.get().forEachSemanticOfPattern(patternNid, s -> {
            elements[0]++;
            assertEquals(s.nid() == rootEntity.nid() ? patternNid : rootEntity.nid(), s.referencedComponentNid(),
                    "every vertex references the root");
        });
        assertEquals(1 + 2 + 2 + 2 + 1 + 1 + 3, elements[0],
                "root, two turns, two paragraphs and their two prose lines, a blank line, a listing and its three lines");
    }

    private static EntityVertex vertex(SemanticEntityVersion version) {
        return (EntityVertex) version.fieldValues().get(0);
    }
}
