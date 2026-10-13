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
import dev.ikm.komet.terms.KometTerm;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.EntityProxy.Concept;
import dev.ikm.tinkar.terms.EntityProxy.Pattern;
import dev.ikm.tinkar.terms.KernelTerm;
import dev.ikm.tinkar.terms.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * The AsciiDoc document model's terms as the IKE starter set declares them
 * ({@code AsciiDocModelSet}, IKE-Network/ike-issues#468): the element kinds a parse produces,
 * the fields and purposes, and the patterns that hold a document's attributes, its structure
 * and its inline references. Every identity is the set's own derivation, so a store that
 * carries the set and a store this class seeds hold one component; the seeding here is only
 * for stores that predate the set's release and is skipped where the terms already exist.
 *
 * <p>Field orders are the set's: the document attributes pattern carries title, topic id,
 * type, status, keywords, scope note, license, citation and provenance; the block pattern
 * carries kind, level, title, parameters and members; each reference pattern carries the
 * fields listed on its constant. An unresolved component field (an index term's concept, a
 * citation's entry) holds the field's own meaning concept, which reads as "unresolved" and
 * never needs a null.
 */
public final class AsciiDocModelTerms {

    private static final Logger LOG = LoggerFactory.getLogger(AsciiDocModelTerms.class);

    // ── element kinds ──────────────────────────────────────────────────────────
    public static final Concept ELEMENT = concept("AsciiDoc element");
    public static final Concept DOCUMENT = StampedDocumentTerms.STAMPED_DOCUMENT;
    public static final Concept SECTION = concept("AsciiDoc section");
    public static final Concept BLOCK = concept("AsciiDoc block");
    public static final Concept PARAGRAPH = concept("AsciiDoc paragraph");
    public static final Concept LISTING = concept("AsciiDoc listing block");
    public static final Concept LITERAL = concept("AsciiDoc literal block");
    public static final Concept EXAMPLE = concept("AsciiDoc example block");
    public static final Concept QUOTE = concept("AsciiDoc quote block");
    public static final Concept SIDEBAR = concept("AsciiDoc sidebar block");
    public static final Concept OPEN = concept("AsciiDoc open block");
    public static final Concept ADMONITION = concept("AsciiDoc admonition block");
    public static final Concept TABLE = concept("AsciiDoc table");
    public static final Concept TABLE_ROW = concept("AsciiDoc table row");
    public static final Concept TABLE_CELL = concept("AsciiDoc table cell");
    public static final Concept LIST = concept("AsciiDoc list");
    public static final Concept LIST_ITEM = concept("AsciiDoc list item");
    public static final Concept DIAGRAM = concept("AsciiDoc diagram block");
    public static final Concept IMAGE = concept("AsciiDoc image");
    public static final Concept BIBLIOGRAPHY_ENTRY = concept("AsciiDoc bibliography entry");
    public static final Concept OPAQUE = concept("AsciiDoc opaque block");

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
    public static final Concept ELEMENT_KIND = concept("AsciiDoc element kind");
    public static final Concept LEVEL = concept("AsciiDoc level");
    public static final Concept TITLE = concept("AsciiDoc title");
    public static final Concept BLOCK_PARAMETERS = concept("AsciiDoc block parameters");
    public static final Concept MEMBERS = concept("AsciiDoc members");
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
    public static final Concept STRUCTURE = concept("AsciiDoc structure");
    public static final Concept REFERENCE = concept("AsciiDoc reference");

    // ── patterns ───────────────────────────────────────────────────────────────
    /** Fields: document title, topic id, type, status, keywords, scope note, license, citation, provenance. */
    public static final Pattern DOCUMENT_ATTRIBUTES_PATTERN = pattern("AsciiDoc document attributes pattern");
    /** Fields: element kind (concept), level (integer), title, parameters, members (component id list). */
    public static final Pattern BLOCK_PATTERN = pattern("AsciiDoc block pattern");
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

    private static final Map<Block.Kind, Concept> KINDS = Map.ofEntries(
            Map.entry(Block.Kind.DOCUMENT, DOCUMENT), Map.entry(Block.Kind.SECTION, SECTION),
            Map.entry(Block.Kind.PARAGRAPH, PARAGRAPH), Map.entry(Block.Kind.LISTING, LISTING),
            Map.entry(Block.Kind.LITERAL, LITERAL), Map.entry(Block.Kind.EXAMPLE, EXAMPLE),
            Map.entry(Block.Kind.QUOTE, QUOTE), Map.entry(Block.Kind.SIDEBAR, SIDEBAR),
            Map.entry(Block.Kind.OPEN, OPEN), Map.entry(Block.Kind.ADMONITION, ADMONITION),
            Map.entry(Block.Kind.TABLE, TABLE), Map.entry(Block.Kind.TABLE_ROW, TABLE_ROW),
            Map.entry(Block.Kind.TABLE_CELL, TABLE_CELL), Map.entry(Block.Kind.LIST, LIST),
            Map.entry(Block.Kind.LIST_ITEM, LIST_ITEM), Map.entry(Block.Kind.DIAGRAM, DIAGRAM),
            Map.entry(Block.Kind.IMAGE, IMAGE), Map.entry(Block.Kind.BIBLIOGRAPHY_ENTRY, BIBLIOGRAPHY_ENTRY),
            Map.entry(Block.Kind.OPAQUE, OPAQUE));

    private static final Map<Reference.Kind, Pattern> REFERENCE_PATTERNS = Map.of(
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
    public static Concept kind(Block.Kind kind) {
        return KINDS.get(kind);
    }

    /** @return the parsed block kind a kind concept stands for, or empty when the concept is none of them */
    public static java.util.Optional<Block.Kind> kindOf(dev.ikm.tinkar.common.id.PublicId concept) {
        for (Map.Entry<Block.Kind, Concept> e : KINDS.entrySet()) {
            if (e.getValue().publicId().equals(concept)) {
                return java.util.Optional.of(e.getKey());
            }
        }
        return java.util.Optional.empty();
    }

    /** @return the pattern a reference of that kind instantiates */
    public static Pattern referencePattern(Reference.Kind kind) {
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
        StampedDocumentTerms.bootstrapIfAbsent();
        for (Concept c : new Concept[] {ELEMENT, SECTION, BLOCK, PARAGRAPH, LISTING, LITERAL, EXAMPLE, QUOTE, SIDEBAR, OPEN,
                ADMONITION, TABLE, TABLE_ROW, TABLE_CELL, LIST, LIST_ITEM, DIAGRAM, IMAGE, BIBLIOGRAPHY_ENTRY, OPAQUE,
                INLINE_REFERENCE, INDEX_TERM_REFERENCE, CROSS_REFERENCE, CITATION, FOOTNOTE, LINK, ESTIMATE_MARK,
                COMPOSITION, INCLUDE, CONDITIONAL,
                FIELD, ELEMENT_KIND, LEVEL, TITLE, BLOCK_PARAMETERS, MEMBERS, TERM_TEXT, REFERENCED_CONCEPT, REFERENCE_TARGET,
                REFERENCE_TEXT, CITATION_KEY, REFERENCED_ENTRY, FOOTNOTE_TEXT, ATTRIBUTE_NAME, NEGATED, DOCUMENT_TITLE,
                TOPIC_ID, TOPIC_TYPE, TOPIC_STATUS, TOPIC_KEYWORDS, TOPIC_SCOPE_NOTE, TOPIC_LICENSE, TOPIC_CITATION,
                TOPIC_PROVENANCE, STRUCTURE, REFERENCE}) {
            seedConceptIfAbsent(c);
        }
        seedPattern(DOCUMENT_ATTRIBUTES_PATTERN, DOCUMENT, STRUCTURE,
                StampedWriter.field(DOCUMENT_TITLE, STRUCTURE, KernelTerm.STRING),
                StampedWriter.field(TOPIC_ID, STRUCTURE, KernelTerm.STRING),
                StampedWriter.field(TOPIC_TYPE, STRUCTURE, KernelTerm.STRING),
                StampedWriter.field(TOPIC_STATUS, STRUCTURE, KernelTerm.STRING),
                StampedWriter.field(TOPIC_KEYWORDS, STRUCTURE, KernelTerm.STRING),
                StampedWriter.field(TOPIC_SCOPE_NOTE, STRUCTURE, KernelTerm.STRING),
                StampedWriter.field(TOPIC_LICENSE, STRUCTURE, KernelTerm.STRING),
                StampedWriter.field(TOPIC_CITATION, STRUCTURE, KernelTerm.STRING),
                StampedWriter.field(TOPIC_PROVENANCE, STRUCTURE, KernelTerm.STRING));
        seedPattern(BLOCK_PATTERN, BLOCK, STRUCTURE,
                StampedWriter.field(ELEMENT_KIND, STRUCTURE, KernelTerm.COMPONENT_FIELD),
                StampedWriter.field(LEVEL, STRUCTURE, KernelTerm.INTEGER_FIELD),
                StampedWriter.field(TITLE, STRUCTURE, KernelTerm.STRING),
                StampedWriter.field(BLOCK_PARAMETERS, STRUCTURE, KernelTerm.STRING),
                StampedWriter.field(MEMBERS, STRUCTURE, KernelTerm.COMPONENT_ID_LIST_FIELD));
        seedPattern(INDEX_TERM_REFERENCE_PATTERN, INDEX_TERM_REFERENCE, REFERENCE,
                StampedWriter.field(TERM_TEXT, REFERENCE, KernelTerm.STRING),
                StampedWriter.field(REFERENCED_CONCEPT, REFERENCE, KernelTerm.COMPONENT_FIELD));
        seedPattern(CROSS_REFERENCE_PATTERN, CROSS_REFERENCE, REFERENCE,
                StampedWriter.field(REFERENCE_TARGET, REFERENCE, KernelTerm.STRING),
                StampedWriter.field(REFERENCE_TEXT, REFERENCE, KernelTerm.STRING));
        seedPattern(CITATION_PATTERN, CITATION, REFERENCE,
                StampedWriter.field(CITATION_KEY, REFERENCE, KernelTerm.STRING),
                StampedWriter.field(REFERENCED_ENTRY, REFERENCE, KernelTerm.COMPONENT_FIELD));
        seedPattern(FOOTNOTE_PATTERN, FOOTNOTE, REFERENCE,
                StampedWriter.field(FOOTNOTE_TEXT, REFERENCE, KernelTerm.STRING));
        seedPattern(LINK_PATTERN, LINK, REFERENCE,
                StampedWriter.field(REFERENCE_TARGET, REFERENCE, KernelTerm.STRING),
                StampedWriter.field(REFERENCE_TEXT, REFERENCE, KernelTerm.STRING));
        seedPattern(ESTIMATE_MARK_PATTERN, ESTIMATE_MARK, REFERENCE,
                StampedWriter.field(FOOTNOTE_TEXT, REFERENCE, KernelTerm.STRING));
        seedPattern(INCLUDE_PATTERN, INCLUDE, REFERENCE,
                StampedWriter.field(REFERENCE_TARGET, REFERENCE, KernelTerm.STRING),
                StampedWriter.field(BLOCK_PARAMETERS, REFERENCE, KernelTerm.STRING));
        seedPattern(CONDITIONAL_PATTERN, CONDITIONAL, REFERENCE,
                StampedWriter.field(ATTRIBUTE_NAME, REFERENCE, KernelTerm.STRING),
                StampedWriter.field(NEGATED, REFERENCE, KernelTerm.BOOLEAN_FIELD));
    }

    private static void seedPattern(Pattern pattern, Concept meaning, Concept purpose, StampedWriter.Field... fields) {
        if (!StampedDocumentTerms.entityAbsent(pattern)) {
            return;
        }
        try (StampedWriter writer = vocabularyWriter()) {
            writer.pattern(pattern, meaning, purpose, fields);
            writer.fullyQualifiedName(pattern, pattern.description() + " (IkeFoundation)");
            writer.commit();
        }
        LOG.info("Seeded '{}'", pattern.description());
    }

    private static void seedConceptIfAbsent(Concept concept) {
        if (!StampedDocumentTerms.entityAbsent(concept)) {
            return;
        }
        try (StampedWriter writer = vocabularyWriter()) {
            writer.concept(concept);
            writer.fullyQualifiedName(concept, concept.description() + " (IkeFoundation)");
            writer.commit();
        }
        LOG.info("Seeded AsciiDoc model concept '{}'", concept.description());
    }

    private static StampedWriter vocabularyWriter() {
        return StampedWriter.open("asciidoc-model-vocabulary-seed", State.ACTIVE, System.currentTimeMillis(),
                KernelTerm.USER, KometTerm.DEVELOPMENT_MODULE, KernelTerm.DEVELOPMENT_PATH);
    }
}
