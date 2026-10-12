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
 * The vocabulary a git-materialized document adds to the conversation journal's
 * ({@code IKE-Network/ike-issues#468}). A document reuses the journal's own shape — the
 * {@link network.ike.komet.claude.doc.RichSurfaceTerms#JOURNAL_MANIFEST_PATTERN manifest}
 * holding the ordered element id-list, one version per commit, and the
 * {@link network.ike.komet.claude.doc.RichSurfaceTerms#PROSE_ELEMENT_PATTERN prose element}
 * per line — and adds two patterns of its own: the git source (path and commit SHA, one
 * version per commit, the bridge back to git) and the affirmation (the affirmed line ids and
 * the {@code Affirms:} trailer, STAMPed with the affirmer's commit).
 *
 * <p><b>Identity discipline.</b> Every identity is a deterministic UUID5 of {@link #NAMESPACE}
 * and a frozen name key, as in {@code RichSurfaceTerms}; both are frozen, since changing either
 * forks every identity already written to a store. {@code StampedDocumentTermsTest} asserts the
 * literals.
 */
public final class StampedDocumentTerms {

    private static final Logger LOG = LoggerFactory.getLogger(StampedDocumentTerms.class);

    /** Frozen 2026-10-12. */
    public static final UUID NAMESPACE = UUID.fromString("6800a8e3-2ff4-40e5-8e26-48dadc0ed573");

    /** Kind marker: the anchor concept of a git-materialized document. */
    public static final Concept STAMPED_DOCUMENT = concept("Stamped document", "stamped-document");

    /** Meaning of the git source semantic. */
    public static final Concept GIT_SOURCE = concept("Git source", "git-source");
    /** Field: the file's repository-relative path at the commit. */
    public static final Concept SOURCE_PATH = concept("Source path", "source-path");
    /** Field: the full commit SHA the manifest version came from. */
    public static final Concept SOURCE_COMMIT = concept("Source commit", "source-commit");

    /** Meaning of the affirmation semantic. */
    public static final Concept AFFIRMATION = concept("Affirmation", "affirmation");
    /** Field: the affirmed line elements, in order. */
    public static final Concept AFFIRMED_LINES = concept("Affirmed lines", "affirmed-lines");
    /** Field: the Affirms: trailer as written. */
    public static final Concept AFFIRMS_TRAILER = concept("Affirms trailer", "affirms-trailer");

    /** Pattern: path (string), commit (string). */
    public static final Pattern GIT_SOURCE_PATTERN = pattern("Git source pattern", "git-source-pattern");
    /** Pattern: affirmed lines (component id list), trailer (string). */
    public static final Pattern AFFIRMATION_PATTERN = pattern("Affirmation pattern", "affirmation-pattern");

    private StampedDocumentTerms() { }

    private static Concept concept(String name, String nameKey) {
        return Concept.make(name, UuidT5Generator.get(NAMESPACE, nameKey));
    }

    private static Pattern pattern(String name, String nameKey) {
        return Pattern.make(name, UuidT5Generator.get(NAMESPACE, nameKey));
    }

    /** Writes the vocabulary into the open datastore if absent. Idempotent and thread-safe. */
    public static synchronized void bootstrapIfAbsent() {
        for (Concept c : new Concept[] {STAMPED_DOCUMENT, GIT_SOURCE, SOURCE_PATH, SOURCE_COMMIT, AFFIRMATION, AFFIRMED_LINES, AFFIRMS_TRAILER}) {
            seedConceptIfAbsent(c);
        }
        if (entityAbsent(GIT_SOURCE_PATTERN)) {
            try (StampedWriter writer = vocabularyWriter()) {
                writer.pattern(GIT_SOURCE_PATTERN, GIT_SOURCE, STAMPED_DOCUMENT,
                        StampedWriter.field(SOURCE_PATH, SOURCE_PATH, KernelTerm.STRING),
                        StampedWriter.field(SOURCE_COMMIT, SOURCE_COMMIT, KernelTerm.STRING));
                writer.commit();
            }
            LOG.info("Seeded git source pattern");
        }
        if (entityAbsent(AFFIRMATION_PATTERN)) {
            try (StampedWriter writer = vocabularyWriter()) {
                writer.pattern(AFFIRMATION_PATTERN, AFFIRMATION, STAMPED_DOCUMENT,
                        StampedWriter.field(AFFIRMED_LINES, AFFIRMED_LINES, KernelTerm.COMPONENT_ID_LIST_FIELD),
                        StampedWriter.field(AFFIRMS_TRAILER, AFFIRMS_TRAILER, KernelTerm.STRING));
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
            writer.fullyQualifiedName(concept, concept.description());
            writer.commit();
        }
        LOG.info("Seeded stamped-document concept '{}'", concept.description());
    }

    private static StampedWriter vocabularyWriter() {
        return StampedWriter.open("stamped-document-vocabulary-seed", State.ACTIVE, System.currentTimeMillis(),
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
