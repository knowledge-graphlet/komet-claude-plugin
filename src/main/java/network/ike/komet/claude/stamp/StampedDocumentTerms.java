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

import dev.ikm.komet.terms.KometTerm;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.EntityProxy.Concept;
import dev.ikm.tinkar.terms.EntityProxy.Pattern;
import dev.ikm.tinkar.terms.KernelTerm;
import dev.ikm.tinkar.terms.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * The git-provenance terms a materialized document is written with ({@code
 * IKE-Network/ike-issues#468}), as the IKE starter set declares them: the document anchor
 * kind, the git source pattern (path and commit, one version per commit) and the affirmation
 * pattern (affirmed lines and trailer), with their field and purpose concepts.
 *
 * <p><b>Identities are adopted, not minted.</b> Every identity here is the starter set's own
 * derivation, {@code T5(IkeFoundation set UUID, birth FQN)}, the same computation the ledger
 * ({@code AsciiDocModelSet} in ike-terms) performs and the generated {@code IkeTerms} bindings
 * carry, so a store that loads the starter set and a store this plugin seeded converge on the
 * same components. That is the discipline the conversation journal's hand-authored terms broke
 * (#844): a second namespace for the same names forks every identity already written. The
 * {@link #NAMESPACE} and the birth names are therefore the set's, and frozen with it.
 *
 * <p>{@link #bootstrapIfAbsent()} seeds the terms into a store that has not loaded the starter
 * set; once every store carries the set this seeding, and this class, give way to the bindings.
 */
public final class StampedDocumentTerms {

    private static final Logger LOG = LoggerFactory.getLogger(StampedDocumentTerms.class);

    /** The IkeFoundation knowledge set's UUID: the T5 namespace every identity in the set derives from. */
    public static final UUID NAMESPACE = UUID.fromString("d890e06f-ec35-429a-b541-d0ead19695e2");

    private static final String TAG = " (IkeFoundation)";

    /** The anchor kind of a materialized document, {@code AsciiDoc document (IkeFoundation)}. */
    public static final Concept STAMPED_DOCUMENT = concept("AsciiDoc document");
    /** Purpose of the git-provenance patterns and their fields. */
    public static final Concept GIT_PROVENANCE = concept("Git provenance");

    /** Meaning of the git source semantic. */
    public static final Concept GIT_SOURCE = concept("Git source");
    /** Field: the file's repository-relative path at the commit. */
    public static final Concept SOURCE_PATH = concept("Source path");
    /** Field: the full commit SHA the manifest version came from. */
    public static final Concept SOURCE_COMMIT = concept("Source commit");

    /** Meaning of the affirmation semantic. */
    public static final Concept AFFIRMATION = concept("Affirmation");
    /** Field: the affirmed line elements, in order. */
    public static final Concept AFFIRMED_LINES = concept("Affirmed lines");
    /** Field: the Affirms: trailer as written. */
    public static final Concept AFFIRMS_TRAILER = concept("Affirms trailer");

    /** Pattern: path (string), commit (string). */
    public static final Pattern GIT_SOURCE_PATTERN = pattern("Git source pattern");
    /** Pattern: affirmed lines (component id list), trailer (string). */
    public static final Pattern AFFIRMATION_PATTERN = pattern("Affirmation pattern");

    private StampedDocumentTerms() { }

    /**
     * @param name a birth name without the set's tag
     * @return the set's derived identity for {@code name (IkeFoundation)}
     */
    public static UUID identity(String name) {
        return UuidT5Generator.get(NAMESPACE, name + TAG);
    }

    private static Concept concept(String name) {
        return Concept.make(name, identity(name));
    }

    private static Pattern pattern(String name) {
        return Pattern.make(name, identity(name));
    }

    /**
     * Writes the terms into the open datastore if absent, under the same identities the
     * starter set declares; a store that already carries the set finds them present. Idempotent
     * and thread-safe.
     */
    public static synchronized void bootstrapIfAbsent() {
        for (Concept c : new Concept[] {STAMPED_DOCUMENT, GIT_PROVENANCE, GIT_SOURCE, SOURCE_PATH, SOURCE_COMMIT, AFFIRMATION, AFFIRMED_LINES, AFFIRMS_TRAILER}) {
            seedConceptIfAbsent(c);
        }
        if (entityAbsent(GIT_SOURCE_PATTERN)) {
            try (StampedWriter writer = vocabularyWriter()) {
                writer.pattern(GIT_SOURCE_PATTERN, GIT_SOURCE, GIT_PROVENANCE,
                        StampedWriter.field(SOURCE_PATH, GIT_PROVENANCE, KernelTerm.STRING),
                        StampedWriter.field(SOURCE_COMMIT, GIT_PROVENANCE, KernelTerm.STRING));
                writer.commit();
            }
            LOG.info("Seeded git source pattern");
        }
        if (entityAbsent(AFFIRMATION_PATTERN)) {
            try (StampedWriter writer = vocabularyWriter()) {
                writer.pattern(AFFIRMATION_PATTERN, AFFIRMATION, GIT_PROVENANCE,
                        StampedWriter.field(AFFIRMED_LINES, GIT_PROVENANCE, KernelTerm.COMPONENT_ID_LIST_FIELD),
                        StampedWriter.field(AFFIRMS_TRAILER, GIT_PROVENANCE, KernelTerm.STRING));
                writer.commit();
            }
            LOG.info("Seeded affirmation pattern");
        }
    }

    private static void seedConceptIfAbsent(Concept concept) {
        if (!entityAbsent(concept)) {
            return;
        }
        try (StampedWriter writer = vocabularyWriter()) {
            writer.concept(concept);
            writer.fullyQualifiedName(concept, concept.description() + TAG);
            writer.commit();
        }
        LOG.info("Seeded git-provenance concept '{}'", concept.description());
    }

    private static StampedWriter vocabularyWriter() {
        return StampedWriter.open("git-provenance-vocabulary-seed", State.ACTIVE, System.currentTimeMillis(),
                KernelTerm.USER, KometTerm.DEVELOPMENT_MODULE, KernelTerm.DEVELOPMENT_PATH);
    }

    static boolean entityAbsent(EntityProxy proxy) {
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
