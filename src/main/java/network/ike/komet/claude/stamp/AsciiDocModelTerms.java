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
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy.Concept;
import dev.ikm.tinkar.terms.EntityProxy.Pattern;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.PatternFacade;
import dev.ikm.tinkar.terms.State;
import network.ike.komet.claude.doc.DocumentModelTerms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * The AsciiDoc document model's terms as the IKE starter set declares them
 * ({@code AsciiDocModelSet}, IKE-Network/ike-issues#468): the element kinds only AsciiDoc has,
 * the fields and purposes, and the patterns that hold a document's attributes and its inline
 * references. Sections, the shared block kinds and the block pattern belong to the block model a
 * document and a conversation journal share ({@link DocumentModelTerms},
 * IKE-Network/ike-issues#844); {@link #kind} maps every parsed kind to its concept in either. Every identity is the set's own derivation, so a store that
 * carries the set and a store this class seeds hold one component; the seeding here is only
 * for stores that predate the set's release and is skipped where the terms already exist.
 *
 * <p>Field orders are the set's: the document attributes pattern carries title, topic id,
 * type, status, keywords, scope note, license, citation and provenance; each reference pattern carries the
 * fields listed on its constant. An unresolved component field (an index term's concept, a
 * citation's entry) holds the field's own meaning concept, which reads as "unresolved" and
 * never needs a null.
 */
public final class AsciiDocModelTerms {

    private static final Logger LOG = LoggerFactory.getLogger(AsciiDocModelTerms.class);

    // ── element kinds ──────────────────────────────────────────────────────────
    public static final Concept ELEMENT = concept("AsciiDoc element");
    public static final Concept DOCUMENT = StampedDocumentTerms.STAMPED_DOCUMENT;
    public static final Concept LITERAL = concept("AsciiDoc literal block");
    public static final Concept EXAMPLE = concept("AsciiDoc example block");
    public static final Concept SIDEBAR = concept("AsciiDoc sidebar block");
    public static final Concept OPEN = concept("AsciiDoc open block");
    public static final Concept ADMONITION = concept("AsciiDoc admonition block");
    public static final Concept DIAGRAM = concept("AsciiDoc diagram block");
    public static final Concept BIBLIOGRAPHY_ENTRY = concept("AsciiDoc bibliography entry");

    // ── inline references and composition ──────────────────────────────────────
    public static final Concept INLINE_REFERENCE = concept("AsciiDoc inline reference");
    public static final Concept INDEX_TERM_REFERENCE = concept("AsciiDoc index term reference");
    public static final Concept CROSS_REFERENCE = concept("AsciiDoc cross reference");
    public static final Concept CITATION = concept("AsciiDoc citation");
    public static final Concept FOOTNOTE = concept("AsciiDoc footnote");
    public static final Concept LINK = concept("AsciiDoc link");
    public static final Concept ESTIMATE_MARK = concept("AsciiDoc estimate mark");
    public static final Concept COMPOSITION = concept("AsciiDoc composition");
    public static final Concept INCLUDE = concept("AsciiDoc include");
    public static final Concept CONDITIONAL = concept("AsciiDoc conditional");

    // ── fields and purposes ────────────────────────────────────────────────────
    public static final Concept FIELD = concept("AsciiDoc field");
    public static final Concept TERM_TEXT = concept("AsciiDoc term text");
    public static final Concept REFERENCED_CONCEPT = concept("AsciiDoc referenced concept");
    public static final Concept REFERENCE_TARGET = concept("AsciiDoc reference target");
    public static final Concept REFERENCE_TEXT = concept("AsciiDoc reference text");
    public static final Concept CITATION_KEY = concept("AsciiDoc citation key");
    public static final Concept REFERENCED_ENTRY = concept("AsciiDoc referenced entry");
    public static final Concept FOOTNOTE_TEXT = concept("AsciiDoc footnote text");
    public static final Concept ATTRIBUTE_NAME = concept("AsciiDoc attribute name");
    public static final Concept NEGATED = concept("AsciiDoc negated");
    public static final Concept DOCUMENT_TITLE = concept("AsciiDoc document title");
    public static final Concept TOPIC_ID = concept("Topic id");
    public static final Concept TOPIC_TYPE = concept("Topic type");
    public static final Concept TOPIC_STATUS = concept("Topic status");
    public static final Concept TOPIC_KEYWORDS = concept("Topic keywords");
    public static final Concept TOPIC_SCOPE_NOTE = concept("Topic scope note");
    public static final Concept TOPIC_LICENSE = concept("Topic license");
    public static final Concept TOPIC_CITATION = concept("Topic citation");
    public static final Concept TOPIC_PROVENANCE = concept("Topic provenance");
    public static final Concept REFERENCE = concept("AsciiDoc reference");

    // ── patterns ───────────────────────────────────────────────────────────────
    /** Fields: document title, topic id, type, status, keywords, scope note, license, citation, provenance. */
    public static final Pattern DOCUMENT_ATTRIBUTES_PATTERN = pattern("AsciiDoc document attributes pattern");
    /** Fields: term text, referenced concept. */
    public static final Pattern INDEX_TERM_REFERENCE_PATTERN = pattern("AsciiDoc index term reference pattern");
    /** Fields: reference target, reference text. */
    public static final Pattern CROSS_REFERENCE_PATTERN = pattern("AsciiDoc cross reference pattern");
    /** Fields: citation key, referenced entry. */
    public static final Pattern CITATION_PATTERN = pattern("AsciiDoc citation pattern");
    /** Fields: footnote text. */
    public static final Pattern FOOTNOTE_PATTERN = pattern("AsciiDoc footnote pattern");
    /** Fields: reference target, reference text. */
    public static final Pattern LINK_PATTERN = pattern("AsciiDoc link pattern");
    /** Fields: footnote text (the note the mark carries). */
    public static final Pattern ESTIMATE_MARK_PATTERN = pattern("AsciiDoc estimate mark pattern");
    /** Fields: reference target, block parameters. */
    public static final Pattern INCLUDE_PATTERN = pattern("AsciiDoc include pattern");
    /** Fields: attribute name, negated (boolean). */
    public static final Pattern CONDITIONAL_PATTERN = pattern("AsciiDoc conditional pattern");

    /** The attribute entry names the document attributes pattern carries after the title, in field order. */
    public static final List<String> TOPIC_ATTRIBUTES = List.of("topic-id", "topic-type", "topic-status", "topic-keywords",
            "topic-scope-note", "topic-license", "topic-citation", "topic-provenance");

    private static final Map<Block.Kind, ConceptFacade> KINDS = Map.ofEntries(
            Map.entry(Block.Kind.DOCUMENT, DOCUMENT), Map.entry(Block.Kind.SECTION, DocumentModelTerms.SECTION),
            Map.entry(Block.Kind.PARAGRAPH, DocumentModelTerms.PARAGRAPH), Map.entry(Block.Kind.LISTING, DocumentModelTerms.LISTING),
            Map.entry(Block.Kind.LITERAL, LITERAL), Map.entry(Block.Kind.EXAMPLE, EXAMPLE),
            Map.entry(Block.Kind.QUOTE, DocumentModelTerms.QUOTATION), Map.entry(Block.Kind.SIDEBAR, SIDEBAR),
            Map.entry(Block.Kind.OPEN, OPEN), Map.entry(Block.Kind.ADMONITION, ADMONITION),
            Map.entry(Block.Kind.TABLE, DocumentModelTerms.TABLE), Map.entry(Block.Kind.TABLE_ROW, DocumentModelTerms.TABLE_ROW),
            Map.entry(Block.Kind.TABLE_CELL, DocumentModelTerms.TABLE_CELL), Map.entry(Block.Kind.LIST, DocumentModelTerms.LIST),
            Map.entry(Block.Kind.LIST_ITEM, DocumentModelTerms.LIST_ITEM), Map.entry(Block.Kind.DIAGRAM, DIAGRAM),
            Map.entry(Block.Kind.IMAGE, DocumentModelTerms.IMAGE), Map.entry(Block.Kind.BIBLIOGRAPHY_ENTRY, BIBLIOGRAPHY_ENTRY),
            Map.entry(Block.Kind.OPAQUE, DocumentModelTerms.OPAQUE));

    private static final Map<Reference.Kind, PatternFacade> REFERENCE_PATTERNS = Map.of(
            Reference.Kind.INDEX_TERM, INDEX_TERM_REFERENCE_PATTERN,
            Reference.Kind.CROSS_REFERENCE, CROSS_REFERENCE_PATTERN,
            Reference.Kind.CITATION, CITATION_PATTERN,
            Reference.Kind.FOOTNOTE, FOOTNOTE_PATTERN,
            Reference.Kind.LINK, LINK_PATTERN,
            Reference.Kind.ESTIMATE_MARK, ESTIMATE_MARK_PATTERN,
            Reference.Kind.INCLUDE, INCLUDE_PATTERN,
            Reference.Kind.CONDITIONAL, CONDITIONAL_PATTERN);

    private AsciiDocModelTerms() { }

    /** @return the concept standing for a parsed block kind */
    public static ConceptFacade kind(Block.Kind kind) {
        return KINDS.get(kind);
    }

    /** @return the parsed block kind a kind concept stands for, or empty when the concept is none of them */
    public static java.util.Optional<Block.Kind> kindOf(dev.ikm.tinkar.common.id.PublicId concept) {
        for (Map.Entry<Block.Kind, ConceptFacade> e : KINDS.entrySet()) {
            if (e.getValue().publicId().equals(concept)) {
                return java.util.Optional.of(e.getKey());
            }
        }
        return java.util.Optional.empty();
    }

    /** @return the pattern a reference of that kind instantiates */
    public static PatternFacade referencePattern(Reference.Kind kind) {
        return REFERENCE_PATTERNS.get(kind);
    }

    /** @return the field values a reference writes, in its pattern's order; unresolved components hold their meaning */
    public static Object[] referenceFields(Reference reference, dev.ikm.tinkar.terms.EntityFacade resolvedComponent) {
        return switch (reference.kind()) {
            case INDEX_TERM -> new Object[] {reference.target(), resolvedComponent == null ? REFERENCED_CONCEPT : resolvedComponent};
            case CROSS_REFERENCE, LINK -> new Object[] {reference.target(), reference.text()};
            case CITATION -> new Object[] {reference.target(), resolvedComponent == null ? REFERENCED_ENTRY : resolvedComponent};
            case FOOTNOTE, ESTIMATE_MARK -> new Object[] {reference.target()};
            case INCLUDE -> new Object[] {reference.target(), reference.text()};
            case CONDITIONAL -> new Object[] {reference.target(), reference.negated()};
        };
    }

    private static Concept concept(String name) {
        return Concept.make(name, StampedDocumentTerms.identity(name));
    }

    private static Pattern pattern(String name) {
        return Pattern.make(name, StampedDocumentTerms.identity(name));
    }

    /**
     * Seeds the terms a store lacks: for stores that predate the starter set's AsciiDoc module.
     * A store carrying the set has every one of them already and nothing is written.
     */
    public static synchronized void bootstrapIfAbsent() {
        DocumentModelTerms.bootstrapIfAbsent();
        StampedDocumentTerms.bootstrapIfAbsent();
        for (ConceptFacade c : new Concept[] {ELEMENT, LITERAL, EXAMPLE, SIDEBAR, OPEN,
                ADMONITION, DIAGRAM, BIBLIOGRAPHY_ENTRY,
                INLINE_REFERENCE, INDEX_TERM_REFERENCE, CROSS_REFERENCE, CITATION, FOOTNOTE, LINK, ESTIMATE_MARK,
                COMPOSITION, INCLUDE, CONDITIONAL,
                FIELD, TERM_TEXT, REFERENCED_CONCEPT, REFERENCE_TARGET,
                REFERENCE_TEXT, CITATION_KEY, REFERENCED_ENTRY, FOOTNOTE_TEXT, ATTRIBUTE_NAME, NEGATED, DOCUMENT_TITLE,
                TOPIC_ID, TOPIC_TYPE, TOPIC_STATUS, TOPIC_KEYWORDS, TOPIC_SCOPE_NOTE, TOPIC_LICENSE, TOPIC_CITATION,
                TOPIC_PROVENANCE, REFERENCE}) {
            seedConceptIfAbsent(c);
        }
        seedPattern(DOCUMENT_ATTRIBUTES_PATTERN, DOCUMENT, DocumentModelTerms.STRUCTURE,
                StampedWriter.field(DOCUMENT_TITLE, DocumentModelTerms.STRUCTURE, KernelConcept.STRING),
                StampedWriter.field(TOPIC_ID, DocumentModelTerms.STRUCTURE, KernelConcept.STRING),
                StampedWriter.field(TOPIC_TYPE, DocumentModelTerms.STRUCTURE, KernelConcept.STRING),
                StampedWriter.field(TOPIC_STATUS, DocumentModelTerms.STRUCTURE, KernelConcept.STRING),
                StampedWriter.field(TOPIC_KEYWORDS, DocumentModelTerms.STRUCTURE, KernelConcept.STRING),
                StampedWriter.field(TOPIC_SCOPE_NOTE, DocumentModelTerms.STRUCTURE, KernelConcept.STRING),
                StampedWriter.field(TOPIC_LICENSE, DocumentModelTerms.STRUCTURE, KernelConcept.STRING),
                StampedWriter.field(TOPIC_CITATION, DocumentModelTerms.STRUCTURE, KernelConcept.STRING),
                StampedWriter.field(TOPIC_PROVENANCE, DocumentModelTerms.STRUCTURE, KernelConcept.STRING));
        seedPattern(INDEX_TERM_REFERENCE_PATTERN, INDEX_TERM_REFERENCE, REFERENCE,
                StampedWriter.field(TERM_TEXT, REFERENCE, KernelConcept.STRING),
                StampedWriter.field(REFERENCED_CONCEPT, REFERENCE, KernelConcept.COMPONENT_FIELD));
        seedPattern(CROSS_REFERENCE_PATTERN, CROSS_REFERENCE, REFERENCE,
                StampedWriter.field(REFERENCE_TARGET, REFERENCE, KernelConcept.STRING),
                StampedWriter.field(REFERENCE_TEXT, REFERENCE, KernelConcept.STRING));
        seedPattern(CITATION_PATTERN, CITATION, REFERENCE,
                StampedWriter.field(CITATION_KEY, REFERENCE, KernelConcept.STRING),
                StampedWriter.field(REFERENCED_ENTRY, REFERENCE, KernelConcept.COMPONENT_FIELD));
        seedPattern(FOOTNOTE_PATTERN, FOOTNOTE, REFERENCE,
                StampedWriter.field(FOOTNOTE_TEXT, REFERENCE, KernelConcept.STRING));
        seedPattern(LINK_PATTERN, LINK, REFERENCE,
                StampedWriter.field(REFERENCE_TARGET, REFERENCE, KernelConcept.STRING),
                StampedWriter.field(REFERENCE_TEXT, REFERENCE, KernelConcept.STRING));
        seedPattern(ESTIMATE_MARK_PATTERN, ESTIMATE_MARK, REFERENCE,
                StampedWriter.field(FOOTNOTE_TEXT, REFERENCE, KernelConcept.STRING));
        seedPattern(INCLUDE_PATTERN, INCLUDE, REFERENCE,
                StampedWriter.field(REFERENCE_TARGET, REFERENCE, KernelConcept.STRING),
                StampedWriter.field(DocumentModelTerms.PARAMETERS, REFERENCE, KernelConcept.STRING));
        seedPattern(CONDITIONAL_PATTERN, CONDITIONAL, REFERENCE,
                StampedWriter.field(ATTRIBUTE_NAME, REFERENCE, KernelConcept.STRING),
                StampedWriter.field(NEGATED, REFERENCE, KernelConcept.BOOLEAN_FIELD));
    }

    private static void seedPattern(PatternFacade pattern, ConceptFacade meaning, ConceptFacade purpose, StampedWriter.Field... fields) {
        if (!StampedDocumentTerms.entityAbsent(pattern)) {
            return;
        }
        try (StampedWriter writer = vocabularyWriter()) {
            writer.pattern(pattern.publicId(), meaning, purpose, fields);
            writer.fullyQualifiedName(pattern.publicId(), pattern.description() + " (IkeFoundation)");
            writer.commit();
        }
        LOG.info("Seeded '{}'", pattern.description());
    }

    private static void seedConceptIfAbsent(ConceptFacade concept) {
        if (!StampedDocumentTerms.entityAbsent(concept)) {
            return;
        }
        try (StampedWriter writer = vocabularyWriter()) {
            writer.concept(concept.publicId());
            writer.fullyQualifiedName(concept.publicId(), concept.description() + " (IkeFoundation)");
            writer.commit();
        }
        LOG.info("Seeded AsciiDoc model concept '{}'", concept.description());
    }

    private static StampedWriter vocabularyWriter() {
        return StampedWriter.open("asciidoc-model-vocabulary-seed", State.ACTIVE, System.currentTimeMillis(),
                KernelConcept.USER, KometConcept.DEVELOPMENT_MODULE, KernelConcept.DEVELOPMENT_PATH);
    }
}
