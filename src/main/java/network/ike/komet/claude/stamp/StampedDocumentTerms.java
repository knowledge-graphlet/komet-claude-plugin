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
 * kind, the git source pattern (path and commit, one version per commit) and the annotation
 * apparatus ({@code AnnotationModelSet}, IKE-Network/ike-issues#1300): the Annotation pattern
 * (kind, reviewed version, annotated elements, text) and the kind concepts an
 * {@code Annotation:} trailer names, with their field and purpose concepts.
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

    /** Meaning of the annotation semantic. */
    public static final Concept ANNOTATION = concept("Annotation");
    /** Purpose of the Annotation pattern and its fields. */
    public static final Concept REVIEWED_PROVENANCE = concept("Reviewed provenance");
    /** Field 0: the annotation kind, and the root of the kinds. */
    public static final Concept ANNOTATION_KIND = concept("Annotation kind");
    /** Field 1: the STAMP of the version the annotator saw. */
    public static final Concept REVIEWED_VERSION = concept("Reviewed version");
    /** Field 2: the elements within the referenced component the annotation covers, in order. */
    public static final Concept ANNOTATED_ELEMENTS = concept("Annotated elements");
    /** Field 3: the annotation text, the SOLOR comment concept the Comment pattern's field also means. */
    public static final Concept COMMENT_TEXT = Concept.make("Comment (SOLOR)", UUID.fromString("147832d4-b9b8-5062-8891-19f9c4e4760a"));

    /** Kind: free commentary with no stance. */
    public static final Concept UNCATEGORIZED_COMMENT = concept("Uncategorized comment");
    /** Kind: a stance on the content; parent of affirmation and refutation. */
    public static final Concept JUDGMENT = concept("Judgment");
    /** Kind: the annotator stands behind the version. */
    public static final Concept AFFIRMATION = concept("Affirmation");
    /** Kind: the annotator disputes the version. */
    public static final Concept REFUTATION = concept("Refutation");

    /** Pattern: path (string), commit (string). */
    public static final Pattern GIT_SOURCE_PATTERN = pattern("Git source pattern");
    /** Pattern: kind (concept), reviewed version (component), annotated elements (component id list), text (string). */
    public static final Pattern ANNOTATION_PATTERN = pattern("Annotation pattern");

    private StampedDocumentTerms() { }

    /**
     * @param name a birth name without the set's tag
     * @return the set's derived identity for {@code name (IkeFoundation)}
     */
    public static UUID identity(String name) {
        return UuidT5Generator.get(NAMESPACE, name + TAG);
    }

    /**
     * @param kind an engine annotation kind
     * @return the concept the starter set declares for it; core derives the same identity
     */
    public static Concept kind(design.knowledge.stamp.adoc.model.AnnotationKind kind) {
        return switch (kind) {
            case AFFIRMATION -> AFFIRMATION;
            case REFUTATION -> REFUTATION;
            case COMMENT -> UNCATEGORIZED_COMMENT;
        };
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
        for (Concept c : new Concept[] {STAMPED_DOCUMENT, GIT_PROVENANCE, GIT_SOURCE, SOURCE_PATH, SOURCE_COMMIT,
                ANNOTATION, REVIEWED_PROVENANCE, ANNOTATION_KIND, REVIEWED_VERSION, ANNOTATED_ELEMENTS,
                UNCATEGORIZED_COMMENT, JUDGMENT, AFFIRMATION, REFUTATION}) {
            seedConceptIfAbsent(c);
        }
        if (entityAbsent(COMMENT_TEXT)) {                          // a SOLOR concept, not the set's: its own name
            try (StampedWriter writer = vocabularyWriter()) {
                writer.concept(COMMENT_TEXT);
                writer.fullyQualifiedName(COMMENT_TEXT, COMMENT_TEXT.description());
                writer.commit();
            }
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
        if (entityAbsent(ANNOTATION_PATTERN)) {
            try (StampedWriter writer = vocabularyWriter()) {
                writer.pattern(ANNOTATION_PATTERN, ANNOTATION, REVIEWED_PROVENANCE,
                        StampedWriter.field(ANNOTATION_KIND, REVIEWED_PROVENANCE, KernelTerm.CONCEPT_FIELD),
                        StampedWriter.field(REVIEWED_VERSION, REVIEWED_PROVENANCE, KernelTerm.COMPONENT_FIELD),
                        StampedWriter.field(ANNOTATED_ELEMENTS, REVIEWED_PROVENANCE, KernelTerm.COMPONENT_ID_LIST_FIELD),
                        StampedWriter.field(COMMENT_TEXT, REVIEWED_PROVENANCE, KernelTerm.STRING));
                writer.commit();
            }
            LOG.info("Seeded annotation pattern");
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
