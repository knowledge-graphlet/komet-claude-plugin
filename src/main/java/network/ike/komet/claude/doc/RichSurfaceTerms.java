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

import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.terms.EntityProxy.Concept;
import dev.ikm.tinkar.terms.EntityProxy.Pattern;

import java.util.UUID;

/**
 * The wave-1 rich-interaction-surface vocabulary ({@code IKE-Network/ike-issues#807}) — the
 * conversation-journal document model's concepts and patterns, hand-authored from the
 * {@code dev-rich-surface-terms-inventory} topic pending the {@code ike:knowledge-bindings}
 * generator route (whose tinkar-side {@code BindingsMain} is not yet in this platform version).
 *
 * <p><b>Identity discipline.</b> Every identity is a deterministic UUID5 of
 * {@link #NAMESPACE} and a frozen name key (the {@code NarratorIdentity} pattern), so every
 * machine, datastore, and re-run agrees on the same PublicIds with no literal to mistype.
 * <em>The namespace and the name keys are frozen</em>: changing either forks every identity
 * already written to a store. {@code RichSurfaceTermsTest} asserts the resolved literals. When
 * the generator route lands, its ledger declares these same identities.
 *
 * <p>The model (see {@code dev-rich-interaction-surface}): each conversation is anchored by a
 * <em>Conversation journal</em> concept; a <em>Journal manifest</em> semantic on the anchor holds
 * the ordered element id-list (append/reorder = new version = replay); each element is a semantic
 * on the anchor — wave 1 renders <em>Prose elements</em> (a turn's Markdown source). Turn
 * attribution is the STAMP author dimension — {@link #KOMET_ASSISTANT_AUTHOR} for assistant
 * turns, the edit coordinate's author for user turns — never a content field. All conversation
 * instance data stamps under {@link #CONVERSATION_JOURNAL_MODULE}, so journals are filterable by
 * the module dimension.
 */
public final class RichSurfaceTerms {

    /** Fixed namespace for the rich-surface deterministic (UUID5) identities. FROZEN. */
    public static final UUID NAMESPACE = UUID.fromString("4cd80c95-ca7c-4cf6-a160-257d83db5e50");

    // ── Anchor + element taxonomy (concepts) ────────────────────────────

    /** The journal-anchor class: one instance concept per conversation. */
    public static final Concept CONVERSATION_JOURNAL =
            concept("Conversation journal", "rich-surface:conversation-journal");

    /** Root of the journal-element taxonomy. */
    public static final Concept JOURNAL_ELEMENT =
            concept("Journal element", "rich-surface:journal-element");

    /** A flowing-prose element (a turn's Markdown source). */
    public static final Concept PROSE_ELEMENT =
            concept("Prose element", "rich-surface:prose-element");

    /** An element listing components (wave 2 renders it; minted now — the taxonomy is one unit). */
    public static final Concept COMPONENT_LIST_ELEMENT =
            concept("Component-list element", "rich-surface:component-list-element");

    /** An element referencing an existing chronology (reference-never-copy). */
    public static final Concept REFERENCE_ELEMENT =
            concept("Reference element", "rich-surface:reference-element");

    /** The retirement marker an element leaves behind when policy removes it. */
    public static final Concept TOMBSTONE_ELEMENT =
            concept("Tombstone element", "rich-surface:tombstone-element");

    // ── Property keys (field meanings) ──────────────────────────────────

    /** Field meaning: the manifest's ordered element id-list. */
    public static final Concept JOURNAL_ELEMENTS =
            concept("Journal elements", "rich-surface:journal-elements");

    /** Field meaning: a prose element's Markdown source. */
    public static final Concept PROSE_CONTENT =
            concept("Prose content", "rich-surface:prose-content");

    // ── STAMP infrastructure ─────────────────────────────────────────────

    /** The assistant's author concept — assistant turns stamp with this author. */
    public static final Concept KOMET_ASSISTANT_AUTHOR =
            concept("Komet Assistant", "rich-surface:komet-assistant-author");

    /** The module all conversation instance data stamps under (filterable as a dimension). */
    public static final Concept CONVERSATION_JOURNAL_MODULE =
            concept("Conversation journal module", "rich-surface:conversation-journal-module");

    // ── Patterns ─────────────────────────────────────────────────────────

    /** One field: {@link #JOURNAL_ELEMENTS} → component id list. A new version per append/reorder. */
    public static final Pattern JOURNAL_MANIFEST_PATTERN =
            pattern("Journal manifest pattern", "rich-surface:journal-manifest-pattern");

    /** One field: {@link #PROSE_CONTENT} → string. */
    public static final Pattern PROSE_ELEMENT_PATTERN =
            pattern("Prose element pattern", "rich-surface:prose-element-pattern");

    private RichSurfaceTerms() {
    }

    /** A concept proxy at the deterministic identity of {@code nameKey} in {@link #NAMESPACE}. */
    private static Concept concept(String name, String nameKey) {
        return Concept.make(name, UuidT5Generator.get(NAMESPACE, nameKey));
    }

    /** A pattern proxy at the deterministic identity of {@code nameKey} in {@link #NAMESPACE}. */
    private static Pattern pattern(String name, String nameKey) {
        return Pattern.make(name, UuidT5Generator.get(NAMESPACE, nameKey));
    }
}
