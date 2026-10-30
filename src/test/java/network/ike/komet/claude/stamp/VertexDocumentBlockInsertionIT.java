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
 * Every insertion scenario of {@link DocumentBlockInsertionIT}, over documents materialized as
 * semantic vertices (IKE-Network/ike-issues#1313), plus the shape the insertion writes there: a
 * vertex per new line, a block vertex whose Members are the text lines and whose Content is their
 * sentences, and a pending version of the parent whose Members hold the blank line and the new
 * block right after the chosen one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VertexDocumentBlockInsertionIT extends DocumentBlockInsertionIT {

    @Override
    String model() {
        return "vertex";
    }

    @Test
    void anInsertionWritesLineVerticesABlockVertexAndTheParentsMembers() throws Exception {
        Path dir = repository("vertex-shape");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("insertion.adoc")).anchor();
        long after = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first line.");
        GitDocumentMaterializer.StoredDocument before = GitDocumentMaterializer.load(anchor).orElseThrow();
        GitDocumentMaterializer.StoredBlock parentBefore = before.blocks().values().stream().filter(b -> b.members().contains(after)).findFirst().orElseThrow();

        DocumentEditor.Edit added = DocumentEditor.addBlock(anchor, after, Block.Kind.PARAGRAPH, "A new sentence here.\nAnd a second one.\n", edit);

        EntityVertex block = pendingVertex(added.block());
        assertEquals(IkeConcept.DOCUMENT_PARAGRAPH.nid(), block.getMeaningNid(), "a paragraph vertex");
        long[] members = ((LongIdList) block.properties().get(IkeConcept.DOCUMENT_ELEMENT_MEMBERS.nid())).toArray();
        assertEquals(2, members.length, "the text lines are the block's members; the blank separator is the parent's");
        assertEquals("A new sentence here.", pendingVertex(members[0]).properties().get(IkeConcept.LINE_SOURCE.nid()));
        assertEquals(IkeConcept.PROSE_LINE.nid(), pendingVertex(members[0]).getMeaningNid());
        if (VertexMaterializer.segmentsAvailable()) {
            long[] content = ((LongIdList) block.properties().get(IkeConcept.BLOCK_CONTENT.nid())).toArray();
            assertEquals(2, content.length, "two sentences, with identities of their own below the line");
            assertEquals("And a second one.", pendingVertex(content[1]).properties().get(IkeConcept.SEGMENT_TEXT.nid()));
        }
        long rootNid = EntityHandle.get(anchor.asUuidArray()[0]).expectEntity().nid();
        assertEquals(rootNid, EntityHandle.get(added.block()).expectSemantic().referencedComponentNid(), "the block references the document's root");
        EntityVertex parent = pendingVertex(parentBefore.nid());
        List<Long> tree = java.util.Arrays.stream(((LongIdList) parent.properties().get(IkeConcept.DOCUMENT_ELEMENT_MEMBERS.nid())).toArray()).boxed().toList();
        int at = tree.indexOf(after);
        assertEquals(IkeConcept.BLANK_LINE.nid(), pendingVertex(tree.get(at + 1)).getMeaningNid(), "a blank line follows the chosen block");
        assertEquals(added.block(), tree.get(at + 2), "then the new block");
        assertEquals(parentBefore.tree().size() + 2, tree.size(), "nothing else moved");
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
