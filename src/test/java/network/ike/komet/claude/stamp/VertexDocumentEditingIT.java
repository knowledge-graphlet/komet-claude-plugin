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

import design.knowledge.stamp.adoc.dialect.Block;
import dev.ikm.tinkar.common.id.LongIdList;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import network.ike.foundation.ike.bindings.IkeConcept;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every editing scenario of {@link DocumentEditingIT}, over documents materialized as semantic
 * vertices (IKE-Network/ike-issues#1313): the edit, editing again, cancelling, settling against
 * the person's commit, carrying an edit forward onto a commit that left its blocks alone, and
 * restoring a kept edit all behave as they do over the prose-element model.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VertexDocumentEditingIT extends DocumentEditingIT {

    @Override
    String model() {
        return "vertex";
    }

    /**
     * What an edit writes over vertices: the revised line is a pending version of the same line
     * vertex, the inserted line a new vertex of the document's own pattern referencing its root,
     * each with the meaning and Line source its text has; the paragraph a pending version whose
     * Members are its lines in their new order, its meaning and other properties kept; and the
     * committed document untouched.
     */
    @Test
    void anEditWritesLineVerticesAndTheBlocksMembers() throws Exception {
        Path dir = repository("vertex-shape", ":topic-id: anf-vertex-shape\n= Shape\n\nThe first line.\nThe second line.\n");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        long paragraph = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first line.");
        GitDocumentMaterializer.StoredDocument before = GitDocumentMaterializer.load(anchor).orElseThrow();
        List<Long> old = before.blocks().get(paragraph).members();

        DocumentEditor.Edit done = DocumentEditor.editBlock(anchor, paragraph, "The first line, revised.\nA new line.\nThe second line.\n", edit);
        assertEquals(new DocumentEditor.Edit(paragraph, 1, 1, 0), done);

        long[] members = ((LongIdList) pendingVertex(paragraph).properties().get(IkeConcept.DOCUMENT_ELEMENT_MEMBERS.nid())).toArray();
        assertEquals(3, members.length);
        assertEquals(old.get(0), members[0], "the revised line keeps its vertex");
        assertEquals(old.get(1), members[2]);
        assertEquals(IkeConcept.DOCUMENT_PARAGRAPH.nid(), pendingVertex(paragraph).getMeaningNid(), "the paragraph keeps its meaning");

        EntityVertex revised = pendingVertex(members[0]);
        assertEquals(IkeConcept.PROSE_LINE.nid(), revised.getMeaningNid());
        assertEquals("The first line, revised.", revised.properties().get(IkeConcept.LINE_SOURCE.nid()));
        assertEquals(EntityHandle.get(members[0]).expectEntity().publicId().asUuidArray()[0], revised.vertexId().asUuid(),
                "the vertex's identity is its semantic's");

        SemanticEntity<?> inserted = EntityHandle.get(members[1]).expectSemantic();
        long rootNid = EntityHandle.get(anchor.asUuidArray()[0]).expectEntity().nid();
        assertEquals(EntityHandle.get(rootNid).expectSemantic().patternNid(), inserted.patternNid(), "a new line is a vertex of the document's own pattern");
        assertEquals(rootNid, inserted.referencedComponentNid(), "and references the document's root");
        assertEquals("A new line.", pendingVertex(members[1]).properties().get(IkeConcept.LINE_SOURCE.nid()));

        assertEquals(before.lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList(),
                GitDocumentMaterializer.load(anchor).orElseThrow().lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList(),
                "the committed document is untouched");
        assertTrue(DocumentEditor.cancel(anchor));
    }

    /**
     * An edit of one sentence (design-2026-10-21-stored-text-segments, decision 6): the author's own
     * line breaks inside the sentence are kept and only the changed clause is rewritten; the
     * sentence gets a pending version under its own identity, its neighbour none.
     */
    @Test
    void anEditOfOneSentenceRecomposesOnlyItsChangedFragment() throws Exception {
        Path dir = repository("vertex-sentence", ":topic-id: anf-vertex-sentence\n= Sentences\n\nThe lease is a loan.\nA machine takes it,\n"
                + "renews it while working,\nand returns it when done.\n");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        long paragraph = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The lease is a loan.");
        GitDocumentMaterializer.StoredDocument before = GitDocumentMaterializer.load(anchor).orElseThrow();
        List<GitDocumentMaterializer.StoredSentence> sentences = before.sentences().get(paragraph);
        assertEquals(List.of("The lease is a loan.", "A machine takes it, renews it while working, and returns it when done."),
                sentences.stream().map(GitDocumentMaterializer.StoredSentence::text).toList());

        DocumentEditor.Edit done = DocumentEditor.editSentence(anchor, sentences.get(1).nid(),
                "A machine takes it, renews it while working, and returns it when the work is done.", edit);
        assertEquals(new DocumentEditor.Edit(paragraph, 1, 0, 0), done, "one line changed: the fragment whose words changed");

        GitDocumentMaterializer.StoredDocument pending = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        assertEquals(List.of("The lease is a loan.", "A machine takes it,", "renews it while working,", "and returns it when the work is done."),
                pending.lines().stream().map(GitDocumentMaterializer.StoredLine::text).filter(t -> !t.isBlank()).skip(2).toList());
        List<GitDocumentMaterializer.StoredSentence> after = pending.sentences().get(paragraph);
        assertEquals(sentences.get(1).nid(), after.get(1).nid(), "the sentence keeps its identity");
        assertEquals("A machine takes it, renews it while working, and returns it when the work is done.",
                pendingVertex(after.get(1).nid()).properties().get(IkeConcept.SEGMENT_TEXT.nid()));
        assertEquals(sentences.get(0).nid(), after.get(0).nid());
        assertTrue(EntityHandle.get(sentences.get(0).nid()).expectSemantic().versions().stream()
                .noneMatch(v -> ((SemanticEntityVersion) v).uncommitted()), "the unchanged sentence gets no version");
        assertTrue(DocumentEditor.cancel(anchor));
    }

    private static EntityVertex pendingVertex(long nid) {
        for (Object o : EntityHandle.get(nid).expectSemantic().versions()) {
            if (o instanceof SemanticEntityVersion v && v.uncommitted() && !v.canceled()) {
                return (EntityVertex) v.fieldValues().get(0);
            }
        }
        throw new AssertionError("no pending version of " + nid);
    }
}
