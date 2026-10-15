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

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.terms.EntityFacade;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;

import java.util.List;

/**
 * Whether the open store carries the vocabulary conversation journals and STAMPed documents are
 * written in: the IKE starter set's document, journal, git-provenance and annotation terms. The
 * plugin names them through the set's generated bindings and writes none of them itself
 * ({@code IKE-Network/ike-issues#844}); a store built from an earlier starter set lacks them, and
 * the plugin refuses to write rather than mint a second copy.
 */
public final class DocumentVocabulary {

    /** The first starter-set checkpoint that carries every term this class requires. */
    public static final String MINIMUM_CHECKPOINT = "checkpoint/main-20261014-232448";

    /** The terms a journal or a STAMPed document writes with: patterns and the concepts their versions name. */
    private static final List<EntityFacade> REQUIRED = List.of(
            IkePattern.PROSE_ELEMENT_PATTERN, IkePattern.JOURNAL_MANIFEST_PATTERN, IkePattern.DOCUMENT_BLOCK_PATTERN,
            IkePattern.ASCIIDOC_DOCUMENT_ATTRIBUTES_PATTERN, IkePattern.GIT_SOURCE_PATTERN, IkePattern.ANNOTATION_PATTERN,
            IkePattern.ASCIIDOC_INDEX_TERM_REFERENCE_PATTERN, IkePattern.ASCIIDOC_CROSS_REFERENCE_PATTERN,
            IkePattern.ASCIIDOC_CITATION_PATTERN, IkePattern.ASCIIDOC_FOOTNOTE_PATTERN, IkePattern.ASCIIDOC_LINK_PATTERN,
            IkePattern.ASCIIDOC_ESTIMATE_MARK_PATTERN, IkePattern.ASCIIDOC_INCLUDE_PATTERN, IkePattern.ASCIIDOC_CONDITIONAL_PATTERN,
            IkeConcept.CONVERSATION_JOURNAL, IkeConcept.CONVERSATION_TURN, IkeConcept.KOMET_ASSISTANT,
            IkeConcept.CONVERSATION_JOURNAL_MODULE);

    private DocumentVocabulary() {
    }

    /**
     * Requires the vocabulary in the open store.
     *
     * @throws IllegalStateException naming the first missing term and the starter set that has it
     */
    public static void requirePresent() {
        requirePresent(REQUIRED);
    }

    static void requirePresent(List<? extends EntityFacade> terms) {
        for (EntityFacade term : terms) {
            if (entityAbsent(term)) {
                throw new IllegalStateException("This knowledge base lacks the IKE starter set's document vocabulary ("
                        + term.description() + "). Load a knowledge base built from the starter set at "
                        + MINIMUM_CHECKPOINT + " or later.");
            }
        }
    }

    /**
     * Whether the term has no chronology in the open store. Stricter than {@code hasPublicId}: a
     * failed earlier write can leave a minted nid mapping with no entity behind it.
     *
     * @param term the term to check
     * @return {@code true} when no entity exists for it
     */
    public static boolean entityAbsent(EntityFacade term) {
        if (!PrimitiveData.get().hasPublicId(term.publicId())) {
            return true;
        }
        try {
            return EntityHandle.get(EntityService.get().nidForPublicId(term.publicId()))
                    .entity().filter(e -> !e.canceled())
                    .isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
