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
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The AsciiDoc model terms are the IKE starter set's own: the identities here must equal the
 * ones the set's generated bindings carry ({@code IkeTerms}, built from {@code AsciiDocModelSet}
 * on 2026-10-13), so a store carrying the set and a store this plugin seeds hold one component.
 */
class AsciiDocModelTermsTest {

    @Test
    void identitiesMatchTheGeneratedStarterSetBindings() {
        assertEquals(UUID.fromString("afbaa86b-f595-5ee6-8db3-c8c343c37306"), uuid(AsciiDocModelTerms.BLOCK_PATTERN), "ASCIIDOC_BLOCK_PATTERN");
        assertEquals(UUID.fromString("42f72861-a1d4-5735-aaa1-8b82b8fdeedf"), uuid(AsciiDocModelTerms.DOCUMENT_ATTRIBUTES_PATTERN), "ASCIIDOC_DOCUMENT_ATTRIBUTES_PATTERN");
        assertEquals(UUID.fromString("355fd531-c971-5718-bb21-e17b09f67c0d"), uuid(AsciiDocModelTerms.PARAGRAPH), "ASCIIDOC_PARAGRAPH");
        assertEquals(UUID.fromString("bc226198-6325-5ca0-8ce6-1fa99370c49a"), uuid(AsciiDocModelTerms.CROSS_REFERENCE_PATTERN), "ASCIIDOC_CROSS_REFERENCE_PATTERN");
        assertEquals(UUID.fromString("f9a99223-83ec-5650-8e5a-3eefacec1297"), uuid(AsciiDocModelTerms.MEMBERS), "ASCIIDOC_MEMBERS");
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
