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
package network.ike.komet.claude.documenttools;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.document.VertexReader;

/**
 * How many pending versions a document's most edited element has, for the tests of the
 * assistant's document tools; the document's own suite (DocumentEditingIT in
 * komet-stamped-document-plugin) counts the same way.
 */
final class PendingVersions {

    private PendingVersions() {
    }

    /** The largest number of pending, uncancelled versions of one of the document's elements. */
    static int perElement(PublicId anchor) {
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        if (!VertexReader.isVertexDocument(anchor)) {
            long manifest = EntityService.get().semanticsForComponentOfPattern(anchorNid, IkePattern.JOURNAL_MANIFEST_PATTERN.nid())
                    .findFirst().orElseThrow().nid();
            return uncommitted(manifest);
        }
        long patternNid = EntityHandle.get(anchorNid).expectSemantic().patternNid();
        int[] most = {0};
        EntityService.get().forEachSemanticOfPattern(patternNid, s -> most[0] = Math.max(most[0], uncommitted(s.nid())));
        return most[0];
    }

    private static int uncommitted(long nid) {
        SemanticEntity<?> fresh = (SemanticEntity<?>) EntityHandle.get(nid).orNull();
        int count = 0;
        for (var v : fresh.versions()) {
            if (v.uncommitted() && !v.canceled()) {
                count++;
            }
        }
        return count;
    }
}
