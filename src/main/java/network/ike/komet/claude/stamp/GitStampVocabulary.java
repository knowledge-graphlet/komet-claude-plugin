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
import dev.ikm.tinkar.common.id.PublicIds;
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
        seedConceptIfAbsent(stamp.author(), null);
        // A working tree's module is ephemeral: the repository is the record of what is written under
        // it (IKE-Network/ike-issues#1313). Stated only where the store holds the parent.
        seedConceptIfAbsent(stamp.module(), entityAbsent(KernelConcept.EPHEMERAL_MODULE) ? null : KernelConcept.EPHEMERAL_MODULE);
        seedConceptIfAbsent(stamp.path(), null);
    }

    private static void seedConceptIfAbsent(ConceptFacade concept, ConceptFacade parent) {
        if (!entityAbsent(concept)) {
            if (parent != null && entityAbsent(dev.ikm.tinkar.terms.EntityProxy.Semantic.make(statedAxiomsId(concept)))) {
                // A module seeded before its store held Ephemeral module, as in every store
                // materialized before 2026-10-21: state its parent now, so what is written under
                // it classifies as ephemeral and its documents are scopes (IKE-Network/ike-issues#1313).
                try (StampedWriter writer = seedWriter()) {
                    stateParent(writer, concept, parent);
                    writer.commit();
                }
                dev.ikm.tinkar.entity.boundary.ModuleKinds.clear();
                dev.ikm.tinkar.entity.boundary.EntityBoundaries.clear();
                LOG.info("Stated git STAMP concept '{}' a kind of '{}'", concept.description(), parent.description());
            }
            return;
        }
        try (StampedWriter writer = seedWriter()) {
            writer.concept(concept.publicId());
            writer.fullyQualifiedName(concept.publicId(), concept.description());
            if (parent != null) {
                stateParent(writer, concept, parent);
            }
            writer.commit();
        }
        LOG.info("Seeded git STAMP concept '{}' ({})", concept.description(), concept.publicId().asUuidArray()[0]);
    }

    private static StampedWriter seedWriter() {
        return StampedWriter.open("git-stamp-vocabulary-seed", State.ACTIVE, System.currentTimeMillis(),
                KernelConcept.USER, KometConcept.DEVELOPMENT_MODULE, KernelConcept.DEVELOPMENT_PATH);
    }

    /** The identity of a seeded concept's stated axioms: derived from the concept, so it is written once. */
    private static dev.ikm.tinkar.common.id.PublicId statedAxiomsId(ConceptFacade concept) {
        return PublicIds.of(design.knowledge.stamp.adoc.identity.Uuid5.of(
                design.knowledge.stamp.adoc.identity.GitIdentityAdapter.NAMESPACE, "stated-axioms:" + concept.publicId().idString()));
    }

    private static void stateParent(StampedWriter writer, ConceptFacade concept, ConceptFacade parent) {
        dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpressionBuilder leb =
                new dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpressionBuilder();
        leb.NecessarySet(leb.And(new dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalAxiom.Atom[] {leb.ConceptAxiom(parent)}));
        writer.semantic(statedAxiomsId(concept), dev.ikm.tinkar.terms.KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.publicId(),
                concept.publicId(), (dev.ikm.tinkar.entity.graph.DiTreeEntity) leb.build().sourceGraph());
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
