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

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.EntityProxy.Concept;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.terms.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds the concepts a git STAMP refers to — its author, module and path — into the open
 * datastore when they are not there yet, so a {@link GitStampTerms.GitStamp#openWriter writer}
 * stamped with them resolves. Follows the conversation journal's vocabulary bootstrap: the
 * seed itself stamps as {@link KernelConcept#USER} under {@link KometConcept#DEVELOPMENT_MODULE} on
 * the development path, because an author concept is shared infrastructure, not content.
 * Idempotent and thread-safe.
 */
public final class GitStampVocabulary {

    private static final Logger LOG = LoggerFactory.getLogger(GitStampVocabulary.class);

    private GitStampVocabulary() { }

    /**
     * @param stamp the STAMP whose author, module and path concepts must exist
     */
    public static synchronized void seedIfAbsent(GitStampTerms.GitStamp stamp) {
        seedConceptIfAbsent(stamp.author());
        seedConceptIfAbsent(stamp.module());
        seedConceptIfAbsent(stamp.path());
    }

    private static void seedConceptIfAbsent(ConceptFacade concept) {
        if (!entityAbsent(concept)) {
            return;
        }
        try (StampedWriter writer = StampedWriter.open("git-stamp-vocabulary-seed", State.ACTIVE, System.currentTimeMillis(),
                KernelConcept.USER, KometConcept.DEVELOPMENT_MODULE, KernelConcept.DEVELOPMENT_PATH)) {
            writer.concept(concept.publicId());
            writer.fullyQualifiedName(concept.publicId(), concept.description());
            writer.commit();
        }
        LOG.info("Seeded git STAMP concept '{}' ({})", concept.description(), concept.publicId().asUuidArray()[0]);
    }

    /**
     * Whether the proxy has no chronology in the open store — stricter than a public-id check,
     * since a failed earlier write can leave a minted nid with no entity behind it.
     */
    static boolean entityAbsent(EntityFacade proxy) {
        if (!PrimitiveData.get().hasPublicId(proxy.publicId())) {
            return true;
        }
        try {
            return EntityHandle.get(EntityService.get().nidForPublicId(proxy.publicId()))
                    .entity().filter(e -> !e.canceled())
                    .isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
