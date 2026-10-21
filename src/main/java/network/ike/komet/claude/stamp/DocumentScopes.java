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

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.scope.Scope;
import dev.ikm.tinkar.entity.scope.ScopeOperations;

import java.util.ArrayList;
import java.util.List;

/**
 * A stored document's scope (IKE-Network/ike-issues#1313): everything that document is in the
 * store, which a scope operation exports, imports or deletes whole. A document stored as vertices
 * is rooted at its own pattern; a document stored as prose elements at its anchor concept, whose
 * lines, blocks, manifest, references, git source, annotations and names all cite it.
 */
public final class DocumentScopes {

    private DocumentScopes() {
    }

    /**
     * A document's scope.
     *
     * @param anchor the document's anchor: a vertex document's root vertex, or a prose-element document's anchor concept
     * @return its scope
     */
    public static Scope of(PublicId anchor) {
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        if (VertexReader.isVertexDocument(anchor)) {
            return Scope.of(((SemanticEntity<?>) EntityHandle.get(anchorNid).expectEntity()).patternNid());
        }
        return Scope.of(anchorNid);
    }

    /**
     * Deletes a document's scope: every entity of it, and the STAMPs only it is written under. A
     * document in git comes back by materializing its file again.
     *
     * @param anchor the document's anchor
     * @return what was erased
     * @throws IllegalStateException when something outside the document cites into it, or an edit of it is pending in Komet
     */
    public static ScopeOperations.Deleted delete(PublicId anchor) {
        if (DocumentEditor.pending(anchor).isPresent()) {
            throw new IllegalStateException("An edit of the document is pending in Komet; commit or cancel it first");
        }
        return ScopeOperations.delete(of(anchor));
    }

    /**
     * What deleting the superseded documents did.
     *
     * @param deleted    the documents deleted
     * @param components the entities erased
     * @param stamps     the STAMPs collected
     * @param skipped    the documents left, each with why
     */
    public record Cleared(int deleted, int components, int stamps, List<String> skipped) { }

    /**
     * Deletes the prose-element documents that the vertex documents of the same working tree and
     * path supersede (IKE-Network/ike-issues#1313), which the document lists already leave out.
     * They are deleted together, as one scope, so the references between them, such as the
     * 2026-10-11 line-identity collision left, are inside it; when something outside them still
     * references one, each is deleted on its own wherever that is safe, and the rest are left. A
     * document with an edit pending in Komet is left, and so is one that still classifies as
     * knowledge: its working tree's module is stated a kind of Ephemeral module when a file of
     * that tree is next materialized.
     *
     * @return what was deleted and what was left
     */
    public static Cleared deleteSuperseded() {
        List<String> skipped = new ArrayList<>();
        List<PublicId> candidates = new ArrayList<>();
        for (PublicId anchor : GitDocumentMaterializer.superseded()) {
            if (DocumentEditor.pending(anchor).isPresent()) {
                skipped.add(anchor.idString() + ": an edit of it is pending in Komet");
            } else if (dev.ikm.tinkar.entity.boundary.EntityBoundaries.kindOf(EntityService.get().nidForPublicId(anchor))
                    == dev.ikm.tinkar.entity.boundary.DataKind.KNOWLEDGE) {
                skipped.add(anchor.idString() + ": it classifies as knowledge until a file of its working tree is materialized again");
            } else {
                candidates.add(anchor);
            }
        }
        if (candidates.isEmpty()) {
            return new Cleared(0, 0, 0, List.copyOf(skipped));
        }
        try {
            long[] roots = candidates.stream().mapToLong(a -> EntityService.get().nidForPublicId(a)).toArray();
            ScopeOperations.Deleted done = ScopeOperations.delete(Scope.of(roots));
            return new Cleared(candidates.size(), done.components(), done.stamps(), List.copyOf(skipped));
        } catch (IllegalStateException together) {
            // Something outside them references one: delete each that is safe on its own, until none is.
        }
        int deleted = 0;
        int components = 0;
        int stamps = 0;
        List<PublicId> remaining = new ArrayList<>(candidates);
        java.util.Map<PublicId, String> why = new java.util.LinkedHashMap<>();
        boolean progress = true;
        while (progress && !remaining.isEmpty()) {
            progress = false;
            for (java.util.Iterator<PublicId> it = remaining.iterator(); it.hasNext(); ) {
                PublicId anchor = it.next();
                try {
                    ScopeOperations.Deleted done = delete(anchor);
                    deleted++;
                    components += done.components();
                    stamps += done.stamps();
                    it.remove();
                    progress = true;
                } catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException left) {
                    why.put(anchor, left.getMessage());
                }
            }
        }
        for (PublicId anchor : remaining) {
            skipped.add(anchor.idString() + ": " + why.get(anchor));
        }
        return new Cleared(deleted, components, stamps, List.copyOf(skipped));
    }
}
