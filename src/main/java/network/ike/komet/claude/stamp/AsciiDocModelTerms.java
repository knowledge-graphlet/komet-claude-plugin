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

import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.dialect.Reference;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.PatternFacade;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static network.ike.foundation.ike.bindings.IkeConcept.*;
import static network.ike.foundation.ike.bindings.IkePattern.*;

/**
 * How a parsed AsciiDoc document maps onto the AsciiDoc document model the IKE starter set
 * declares ({@code AsciiDocModelSet}, IKE-Network/ike-issues#468): which concept stands for each
 * parsed block kind, which pattern each inline reference instantiates, and the field values it
 * writes. Sections, the shared block kinds and the block pattern belong to the block model a
 * document and a conversation journal share (IKE-Network/ike-issues#844); {@link #kind} maps every
 * parsed kind to its concept in either. The terms themselves are the set's generated bindings; a
 * store that lacks them is refused by {@link network.ike.komet.claude.doc.DocumentVocabulary}.
 *
 * <p>Field orders are the set's: the document attributes pattern carries title, topic id,
 * type, status, keywords, scope note, license, citation and provenance; each reference pattern
 * carries the fields {@link #referenceFields} returns. An unresolved component field (an index
 * term's concept, a citation's entry) holds the field's own meaning concept, which reads as
 * "unresolved" and never needs a null.
 */
public final class AsciiDocModelTerms {

    /** The attribute entry names the document attributes pattern carries after the title, in field order. */
    public static final List<String> TOPIC_ATTRIBUTES = List.of("topic-id", "topic-type", "topic-status", "topic-keywords",
            "topic-scope-note", "topic-license", "topic-citation", "topic-provenance");

    private static final Map<Block.Kind, ConceptFacade> KINDS = Map.ofEntries(
            Map.entry(Block.Kind.DOCUMENT, ASCIIDOC_DOCUMENT), Map.entry(Block.Kind.SECTION, DOCUMENT_SECTION),
            Map.entry(Block.Kind.PARAGRAPH, DOCUMENT_PARAGRAPH), Map.entry(Block.Kind.LISTING, DOCUMENT_LISTING),
            Map.entry(Block.Kind.LITERAL, ASCIIDOC_LITERAL_BLOCK), Map.entry(Block.Kind.EXAMPLE, ASCIIDOC_EXAMPLE_BLOCK),
            Map.entry(Block.Kind.QUOTE, DOCUMENT_QUOTATION), Map.entry(Block.Kind.SIDEBAR, ASCIIDOC_SIDEBAR_BLOCK),
            Map.entry(Block.Kind.OPEN, ASCIIDOC_OPEN_BLOCK), Map.entry(Block.Kind.ADMONITION, ASCIIDOC_ADMONITION_BLOCK),
            Map.entry(Block.Kind.TABLE, DOCUMENT_TABLE), Map.entry(Block.Kind.TABLE_ROW, DOCUMENT_TABLE_ROW),
            Map.entry(Block.Kind.TABLE_CELL, DOCUMENT_TABLE_CELL), Map.entry(Block.Kind.LIST, DOCUMENT_LIST),
            Map.entry(Block.Kind.LIST_ITEM, DOCUMENT_LIST_ITEM), Map.entry(Block.Kind.DIAGRAM, ASCIIDOC_DIAGRAM_BLOCK),
            Map.entry(Block.Kind.IMAGE, DOCUMENT_IMAGE), Map.entry(Block.Kind.BIBLIOGRAPHY_ENTRY, ASCIIDOC_BIBLIOGRAPHY_ENTRY),
            Map.entry(Block.Kind.OPAQUE, DOCUMENT_OPAQUE_BLOCK));

    private static final Map<Reference.Kind, PatternFacade> REFERENCE_PATTERNS = Map.of(
            Reference.Kind.INDEX_TERM, ASCIIDOC_INDEX_TERM_REFERENCE_PATTERN,
            Reference.Kind.CROSS_REFERENCE, ASCIIDOC_CROSS_REFERENCE_PATTERN,
            Reference.Kind.CITATION, ASCIIDOC_CITATION_PATTERN,
            Reference.Kind.FOOTNOTE, ASCIIDOC_FOOTNOTE_PATTERN,
            Reference.Kind.LINK, ASCIIDOC_LINK_PATTERN,
            Reference.Kind.ESTIMATE_MARK, ASCIIDOC_ESTIMATE_MARK_PATTERN,
            Reference.Kind.INCLUDE, ASCIIDOC_INCLUDE_PATTERN,
            Reference.Kind.CONDITIONAL, ASCIIDOC_CONDITIONAL_PATTERN);

    private AsciiDocModelTerms() { }

    /** @return the concept standing for a parsed block kind */
    public static ConceptFacade kind(Block.Kind kind) {
        return KINDS.get(kind);
    }

    /** @return the parsed block kind a kind concept stands for, or empty when the concept is none of them */
    public static Optional<Block.Kind> kindOf(PublicId concept) {
        for (Map.Entry<Block.Kind, ConceptFacade> e : KINDS.entrySet()) {
            if (e.getValue().publicId().equals(concept)) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    /** @return the pattern a reference of that kind instantiates */
    public static PatternFacade referencePattern(Reference.Kind kind) {
        return REFERENCE_PATTERNS.get(kind);
    }

    /** @return the field values a reference writes, in its pattern's order; unresolved components hold their meaning */
    public static Object[] referenceFields(Reference reference, EntityFacade resolvedComponent) {
        return switch (reference.kind()) {
            case INDEX_TERM -> new Object[] {reference.target(), resolvedComponent == null ? ASCIIDOC_REFERENCED_CONCEPT : resolvedComponent};
            case CROSS_REFERENCE, LINK -> new Object[] {reference.target(), reference.text()};
            case CITATION -> new Object[] {reference.target(), resolvedComponent == null ? ASCIIDOC_REFERENCED_ENTRY : resolvedComponent};
            case FOOTNOTE, ESTIMATE_MARK -> new Object[] {reference.target()};
            case INCLUDE -> new Object[] {reference.target(), reference.text()};
            case CONDITIONAL -> new Object[] {reference.target(), reference.negated()};
        };
    }
}
