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
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which documents are paired with an assistant conversation just now (ike-lab-documents topic
 * {@code dev-decision-document}, "The assistant edits the document"). A paired document is written
 * by two authors, the person between turns and the assistant within them, so their edits are one
 * pending edit, settled together, rather than each waiting on the other's commit.
 */
public final class DocumentPairing {

    /** The document each conversation is paired with, by the conversation's id. */
    private static final Map<String, PublicId> PAIRED = new ConcurrentHashMap<>();

    private DocumentPairing() {
    }

    /**
     * Records a conversation's document.
     *
     * @param conversation the conversation's id
     * @param anchor       the document's anchor, or {@code null} when it has none
     */
    public static void pair(String conversation, PublicId anchor) {
        if (anchor == null) {
            PAIRED.remove(conversation);
        } else {
            PAIRED.put(conversation, anchor);
        }
    }

    /**
     * @param anchor a document's anchor
     * @return whether a conversation is paired with it
     */
    public static boolean paired(PublicId anchor) {
        return anchor != null && PAIRED.values().stream().anyMatch(held -> PublicId.equals(held, anchor));
    }

    /**
     * @param block the nid of a block's element semantic
     * @return whether the block's document is paired with a conversation
     */
    public static boolean pairedBlock(long block) {
        return EntityHandle.get(block).orNull() instanceof SemanticEntity<?> element
                && paired(PrimitiveData.publicId(element.referencedComponentNid()));
    }
}
