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
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import network.ike.komet.claude.anf.element.SegmentNarrative;

/**
 * A stamped document's prose as the narrative a lift reads ({@code dev-anf-element-isolation}, "The
 * written form"): the sentences the store holds for each of its blocks, in document order, each
 * named by its sentence vertex's identifier, so the lift's spans anchor to sentence segments and
 * survive an edit elsewhere in the note. Only a document stored as vertices has sentences
 * (IKE-Network/ike-issues#1313); the version read is the one pending in the store, so the lift
 * sees what the document card shows.
 */
public final class DocumentNarratives {

    /** A stored document a lift may read: its recorded path and its anchor. */
    public record Choice(String path, PublicId anchor) {
    }

    private DocumentNarratives() {
    }

    /** The documents stored as vertices, by recorded path. */
    public static List<Choice> documents() {
        List<Choice> choices = new ArrayList<>();
        GitDocumentMaterializer.storedDocumentsByPath().forEach((path, anchors) -> {
            for (PublicId anchor : anchors) {
                if (VertexReader.isVertexDocument(anchor)) {
                    choices.add(new Choice(path, anchor));
                }
            }
        });
        return choices;
    }

    /** The document's narrative: each block's sentences a paragraph, in document order; empty when it has none. */
    public static Optional<SegmentNarrative> narrative(PublicId anchor) {
        Optional<GitDocumentMaterializer.StoredDocument> loaded = GitDocumentMaterializer.load(anchor, true);
        if (loaded.isEmpty() || loaded.get().root() == null || loaded.get().sentences().isEmpty()) {
            return Optional.empty();
        }
        GitDocumentMaterializer.StoredDocument document = loaded.get();
        List<List<SegmentNarrative.Sentence>> paragraphs = new ArrayList<>();
        walk(document.root(), document.blocks(), document.sentences(), paragraphs, new HashSet<>());
        SegmentNarrative narrative = SegmentNarrative.of(paragraphs);
        return narrative.sentences().isEmpty() ? Optional.empty() : Optional.of(narrative);
    }

    /** The document a sentence belongs to, by the sentence vertex's identifier; empty when the store lacks it. */
    public static Optional<PublicId> documentOf(String sentenceId) {
        PublicId id;
        try {
            id = PublicIds.of(UUID.fromString(sentenceId));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
        if (!PrimitiveData.get().hasPublicId(id)) {
            return Optional.empty();
        }
        Entity<?> entity = EntityHandle.get(PrimitiveData.nid(id)).orNull();
        if (entity instanceof SemanticEntity<?> semantic) {
            return Optional.of(PrimitiveData.publicId(semantic.referencedComponentNid()));
        }
        return Optional.empty();
    }

    private static void walk(GitDocumentMaterializer.StoredBlock block, Map<Long, GitDocumentMaterializer.StoredBlock> blocks,
                             Map<Long, List<GitDocumentMaterializer.StoredSentence>> sentences,
                             List<List<SegmentNarrative.Sentence>> paragraphs, Set<Long> seen) {
        if (!seen.add(block.nid())) {
            return;
        }
        List<GitDocumentMaterializer.StoredSentence> own = sentences.getOrDefault(block.nid(), List.of());
        if (!own.isEmpty()) {
            paragraphs.add(own.stream()
                    .map(s -> new SegmentNarrative.Sentence(sentenceId(s.nid()), s.text()))
                    .toList());
        }
        for (long member : block.members()) {
            GitDocumentMaterializer.StoredBlock child = blocks.get(member);
            if (child != null) {
                walk(child, blocks, sentences, paragraphs, seen);
            }
        }
    }

    /** A sentence vertex's identifier: the UUID the store derives from its segment key. */
    static String sentenceId(long nid) {
        return PrimitiveData.publicId(nid).asUuidArray()[0].toString();
    }
}
