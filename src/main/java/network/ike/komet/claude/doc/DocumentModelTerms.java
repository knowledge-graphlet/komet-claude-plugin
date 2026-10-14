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
 * The block model a document and a conversation journal share ({@code IKE-Network/ike-issues#844}),
 * at the identities the IKE starter set declares: {@code ProseElementSet} for the lines and their
 * manifest, {@code DocumentStructureSet} for the elements that group them and for the journal's own
 * concepts. {@code DocumentModelTermsTest} pins each identity to the set's generated bindings.
 *
 * <p>A structured document's lines are prose elements, ordered by the journal manifest; its
 * sections, blocks and, in a journal, turns are semantics of {@link #BLOCK_PATTERN}, whose members
 * are their lines and child elements in order. A block of a git document takes its identifying
 * line's identity; a journal block is given one when its turn is written.
 *
 * <p>Two derivations, because the set adopted the prose apparatus rather than minting it: the line
 * and manifest terms are {@code T5(eaa95241…, "<name> (RichSurfaceTerms)")}, the identities
 * {@code rich-surface-starter-knowledge} declared first; everything else is
 * {@code T5(d890e06f…, "<name> (IkeFoundation)")}. The plugin's own wave-1 identities (namespace
 * {@code 4cd80c95…}) are retired; journals written under them stay in their stores unread, and the
 * JSON copy of each conversation is the record they are rebuilt from.
 */
public final class DocumentModelTerms {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentModelTerms.class);

    /** The IkeFoundation knowledge set's UUID: the T5 namespace of its own identities. */
    public static final UUID IKE_NAMESPACE = UUID.fromString("d890e06f-ec35-429a-b541-d0ead19695e2");
    /** The rich-surface knowledge set's UUID: the T5 namespace of the adopted prose apparatus. */
    public static final UUID RICH_SURFACE_NAMESPACE = UUID.fromString("eaa95241-1a1b-4c48-8266-4be1fe9124b0");

    private static final String IKE_TAG = " (IkeFoundation)";
    private static final String RICH_SURFACE_TAG = " (RichSurfaceTerms)";

    // ── Lines and their order (adopted RichSurfaceTerms identities) ─────────────
    /** Meaning of a line: a prose element. */
    public static final Concept PROSE_ELEMENT = adopted("Prose element");
    /** Field: a line's text. */
    public static final Concept PROSE_CONTENT = adopted("Prose content");
    /** Purpose of the prose element pattern and its field. */
    public static final Concept ELEMENT_CONTENT = adopted("Element content");
    /** One field: {@link #PROSE_CONTENT} → string. One semantic per line. */
    public static final Pattern PROSE_ELEMENT_PATTERN = adoptedPattern("Prose element pattern");
    /** Meaning of a manifest: the ordered lines of one structured document. */
    public static final Concept JOURNAL_MANIFEST = adopted("Journal manifest");
    /** Field: the ordered line elements. */
    public static final Concept JOURNAL_ELEMENTS = adopted("Journal elements");
    /** Purpose of the manifest and its field. */
    public static final Concept ELEMENT_ORDER = adopted("Element order");
    /** One field: {@link #JOURNAL_ELEMENTS} → component id list. A new version per change of order. */
    public static final Pattern JOURNAL_MANIFEST_PATTERN = adoptedPattern("Journal manifest pattern");

    // ── Elements ───────────────────────────────────────────────────────────────
    public static final Concept DOCUMENT_STRUCTURE_MODEL = concept("Document structure model");
    public static final Concept STRUCTURED_DOCUMENT = concept("Structured document");
    public static final Concept DOCUMENT_ELEMENT = concept("Document element");
    public static final Concept SECTION = concept("Document section");
    public static final Concept BLOCK = concept("Document block");
    public static final Concept PARAGRAPH = concept("Document paragraph");
    public static final Concept LISTING = concept("Document listing");
    public static final Concept QUOTATION = concept("Document quotation");
    public static final Concept TABLE = concept("Document table");
    public static final Concept TABLE_ROW = concept("Document table row");
    public static final Concept TABLE_CELL = concept("Document table cell");
    public static final Concept LIST = concept("Document list");
    public static final Concept LIST_ITEM = concept("Document list item");
    public static final Concept IMAGE = concept("Document image");
    public static final Concept OPAQUE = concept("Document opaque block");

    // ── Fields and purpose ─────────────────────────────────────────────────────
    public static final Concept FIELD = concept("Document field");
    public static final Concept ELEMENT_KIND = concept("Document element kind");
    public static final Concept LEVEL = concept("Document element level");
    public static final Concept TITLE = concept("Document element title");
    public static final Concept PARAMETERS = concept("Document element parameters");
    public static final Concept MEMBERS = concept("Document element members");
    public static final Concept STRUCTURE = concept("Document structure");

    /** Fields: element kind (concept), level (integer), title, parameters, members (component id list). */
    public static final Pattern BLOCK_PATTERN = pattern("Document block pattern");

    // ── Conversation journals ──────────────────────────────────────────────────
    /** The kind of a journal's root block; each conversation's anchor concept stands for one journal. */
    public static final Concept CONVERSATION_JOURNAL = concept("Conversation journal");
    /** The kind of a turn's block; its STAMP author is the turn's author. */
    public static final Concept CONVERSATION_TURN = concept("Conversation turn");
    /** The author of the assistant's turns. */
    public static final Concept KOMET_ASSISTANT = concept("Komet assistant");
    /** The module every journal's lines, elements and manifest stamp in. */
    public static final Concept CONVERSATION_JOURNAL_MODULE = concept("Conversation journal module");

    private DocumentModelTerms() {
    }

    /**
     * @param name a birth name without the set's tag
     * @return the IkeFoundation identity of {@code name (IkeFoundation)}
     */
    public static UUID identity(String name) {
        return UuidT5Generator.get(IKE_NAMESPACE, name + IKE_TAG);
    }

    private static Concept concept(String name) {
        return Concept.make(name, identity(name));
    }

    private static Pattern pattern(String name) {
        return Pattern.make(name, identity(name));
    }

    private static Concept adopted(String name) {
        return Concept.make(name, UuidT5Generator.get(RICH_SURFACE_NAMESPACE, name + RICH_SURFACE_TAG));
    }

    private static Pattern adoptedPattern(String name) {
        return Pattern.make(name, UuidT5Generator.get(RICH_SURFACE_NAMESPACE, name + RICH_SURFACE_TAG));
    }

    /**
     * Writes the terms into the open datastore if absent, under the identities the starter set
     * declares, with the set's meanings, purposes and fields; a store that carries the set finds
     * them present and nothing is written. Idempotent and thread-safe. Retired once the plugin takes
     * its terms from the set's generated bindings.
     */
    public static synchronized void bootstrapIfAbsent() {
        for (Concept c : new Concept[] {PROSE_ELEMENT, PROSE_CONTENT, ELEMENT_CONTENT, JOURNAL_MANIFEST,
                JOURNAL_ELEMENTS, ELEMENT_ORDER}) {
            seedConceptIfAbsent(c, RICH_SURFACE_TAG);
        }
        for (Concept c : new Concept[] {DOCUMENT_STRUCTURE_MODEL, STRUCTURED_DOCUMENT, DOCUMENT_ELEMENT,
                SECTION, BLOCK, PARAGRAPH, LISTING, QUOTATION, TABLE, TABLE_ROW, TABLE_CELL, LIST, LIST_ITEM, IMAGE,
                OPAQUE, FIELD, ELEMENT_KIND, LEVEL, TITLE, PARAMETERS, MEMBERS, STRUCTURE, CONVERSATION_JOURNAL,
                CONVERSATION_TURN, KOMET_ASSISTANT, CONVERSATION_JOURNAL_MODULE}) {
            seedConceptIfAbsent(c, IKE_TAG);
        }
        seedPatternIfAbsent(PROSE_ELEMENT_PATTERN, RICH_SURFACE_TAG, PROSE_ELEMENT, ELEMENT_CONTENT,
                StampedWriter.field(PROSE_CONTENT, ELEMENT_CONTENT, KernelTerm.STRING));
        seedPatternIfAbsent(JOURNAL_MANIFEST_PATTERN, RICH_SURFACE_TAG, JOURNAL_MANIFEST, ELEMENT_ORDER,
                StampedWriter.field(JOURNAL_ELEMENTS, ELEMENT_ORDER, KernelTerm.COMPONENT_ID_LIST_FIELD));
        seedPatternIfAbsent(BLOCK_PATTERN, IKE_TAG, DOCUMENT_ELEMENT, STRUCTURE,
                StampedWriter.field(ELEMENT_KIND, STRUCTURE, KernelTerm.COMPONENT_FIELD),
                StampedWriter.field(LEVEL, STRUCTURE, KernelTerm.INTEGER_FIELD),
                StampedWriter.field(TITLE, STRUCTURE, KernelTerm.STRING),
                StampedWriter.field(PARAMETERS, STRUCTURE, KernelTerm.STRING),
                StampedWriter.field(MEMBERS, STRUCTURE, KernelTerm.COMPONENT_ID_LIST_FIELD));
    }

    private static void seedConceptIfAbsent(Concept concept, String tag) {
        if (!entityAbsent(concept)) {
            return;
        }
        try (StampedWriter writer = vocabularyWriter()) {
            writer.concept(concept);
            writer.fullyQualifiedName(concept, concept.description() + tag);
            writer.commit();
        }
        LOG.info("Seeded document model concept '{}'", concept.description());
    }

    private static void seedPatternIfAbsent(Pattern pattern, String tag, Concept meaning, Concept purpose, StampedWriter.Field... fields) {
        if (!entityAbsent(pattern)) {
            return;
        }
        try (StampedWriter writer = vocabularyWriter()) {
            writer.pattern(pattern, meaning, purpose, fields);
            writer.fullyQualifiedName(pattern, pattern.description() + tag);
            writer.commit();
        }
        LOG.info("Seeded '{}'", pattern.description());
    }

    /** A writer for the shared vocabulary: {@link KernelTerm#USER} on the development module. */
    private static StampedWriter vocabularyWriter() {
        return StampedWriter.open("document-model-vocabulary-seed", State.ACTIVE, System.currentTimeMillis(),
                KernelTerm.USER, KometTerm.DEVELOPMENT_MODULE, KernelTerm.DEVELOPMENT_PATH);
    }

    /**
     * Whether the proxy has no chronology in the open store. Stricter than {@code hasPublicId}: a
     * failed earlier write can leave a minted nid mapping with no entity behind it, and such a
     * residue must not gate out the (re-)seed.
     *
     * @param proxy the term to check
     * @return {@code true} when no entity exists for the proxy
     */
    public static boolean entityAbsent(EntityProxy proxy) {
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
