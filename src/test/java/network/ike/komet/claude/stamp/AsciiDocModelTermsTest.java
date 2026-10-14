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
import network.ike.komet.claude.doc.DocumentModelTerms;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The AsciiDoc model terms are the IKE starter set's own: the identities here must equal the
 * ones the set's generated bindings carry ({@code IkeTerms}, built from {@code AsciiDocModelSet}
 * on 2026-10-14), so a store carrying the set and a store this plugin seeds hold one component.
 */
class AsciiDocModelTermsTest {

    @Test
    void identitiesMatchTheGeneratedStarterSetBindings() {
        assertEquals(UUID.fromString("42f72861-a1d4-5735-aaa1-8b82b8fdeedf"), uuid(AsciiDocModelTerms.DOCUMENT_ATTRIBUTES_PATTERN), "ASCIIDOC_DOCUMENT_ATTRIBUTES_PATTERN");
        assertEquals(UUID.fromString("7b6d88f3-a1b6-53c7-93fd-5d986e0b526e"), uuid(AsciiDocModelTerms.LITERAL), "ASCIIDOC_LITERAL_BLOCK");
        assertEquals(UUID.fromString("bc226198-6325-5ca0-8ce6-1fa99370c49a"), uuid(AsciiDocModelTerms.CROSS_REFERENCE_PATTERN), "ASCIIDOC_CROSS_REFERENCE_PATTERN");
        assertEquals(UUID.fromString("ef541de8-c2bd-5140-a185-62d96653fc8b"), uuid(AsciiDocModelTerms.REFERENCED_ENTRY), "ASCIIDOC_REFERENCED_ENTRY");
    }

    @Test
    void sharedKindsComeFromTheBlockModelDocumentsAndJournalsShare() {
        assertSame(DocumentModelTerms.PARAGRAPH, AsciiDocModelTerms.kind(Block.Kind.PARAGRAPH));
        assertSame(DocumentModelTerms.QUOTATION, AsciiDocModelTerms.kind(Block.Kind.QUOTE));
        assertSame(AsciiDocModelTerms.SIDEBAR, AsciiDocModelTerms.kind(Block.Kind.SIDEBAR));
    }

    @Test
    void everyParsedKindHasAConceptAndEveryReferenceKindAPattern() {
        for (Block.Kind kind : Block.Kind.values()) {
            assertNotNull(AsciiDocModelTerms.kind(kind), kind.name());
            assertEquals(kind, AsciiDocModelTerms.kindOf(AsciiDocModelTerms.kind(kind).publicId()).orElseThrow());
        }
        for (Reference.Kind kind : Reference.Kind.values()) {
            assertNotNull(AsciiDocModelTerms.referencePattern(kind), kind.name());
        }
        assertSame(StampedDocumentTerms.STAMPED_DOCUMENT, AsciiDocModelTerms.DOCUMENT, "the document kind is the set's AsciiDoc document");
    }

    @Test
    void unresolvedComponentsHoldTheirFieldsMeaning() {
        Reference citation = new Reference(Reference.Kind.CITATION, 4, 0, "aho1977", "", "<<aho1977>>");
        Object[] fields = AsciiDocModelTerms.referenceFields(citation, null);
        assertEquals("aho1977", fields[0]);
        assertSame(AsciiDocModelTerms.REFERENCED_ENTRY, fields[1]);
        Reference conditional = new Reference(Reference.Kind.CONDITIONAL, 9, 0, "env-site", "negated", "ifndef::env-site[]");
        assertEquals(Boolean.TRUE, AsciiDocModelTerms.referenceFields(conditional, null)[1]);
    }

    private static UUID uuid(dev.ikm.tinkar.terms.EntityProxy proxy) {
        return proxy.publicId().asUuidArray()[0];
    }
}
