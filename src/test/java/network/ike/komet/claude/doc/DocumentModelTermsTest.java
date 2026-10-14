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

import dev.ikm.tinkar.terms.EntityProxy;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shared block model's terms are the IKE starter set's own: every identity here must equal the
 * one the set's generated bindings carry ({@code IkeTerms}, built from {@code ProseElementSet} and
 * {@code DocumentStructureSet} on 2026-10-14), so a store carrying the set and a store this plugin
 * seeds hold one component. The prose and manifest identities are rich-surface's, which the set
 * adopted; they replace the plugin's own wave-1 derivations ({@code IKE-Network/ike-issues#844}).
 */
class DocumentModelTermsTest {

    @Test
    void adoptedIdentitiesMatchTheLedgerAndTheSet() {
        assertIdentity(DocumentModelTerms.PROSE_ELEMENT, "e1d18f0d-77fb-572e-b395-62fb5de63257");
        assertIdentity(DocumentModelTerms.PROSE_CONTENT, "cee59367-e4eb-5bbf-839e-453e4b686230");
        assertIdentity(DocumentModelTerms.ELEMENT_CONTENT, "6507e073-af86-5ec3-bb26-35fde45c2bfe");
        assertIdentity(DocumentModelTerms.PROSE_ELEMENT_PATTERN, "89b831a1-e773-5f83-87a6-2cfc8e107fb0");
        assertIdentity(DocumentModelTerms.JOURNAL_MANIFEST, "fd19455c-7c08-545c-a951-6045001efbc1");
        assertIdentity(DocumentModelTerms.JOURNAL_ELEMENTS, "197ba7dc-03b5-5e3d-a607-3cc538509b03");
        assertIdentity(DocumentModelTerms.ELEMENT_ORDER, "6055c01b-96c2-58b2-bb13-465a135a7bdf");
        assertIdentity(DocumentModelTerms.JOURNAL_MANIFEST_PATTERN, "1bdc5ac6-e4ed-5b62-ad6d-8ae10208bb00");
    }

    @Test
    void structureIdentitiesMatchTheGeneratedStarterSetBindings() {
        assertIdentity(DocumentModelTerms.BLOCK_PATTERN, "2f269cf2-8336-5a13-b79f-db540c736f14");
        assertIdentity(DocumentModelTerms.DOCUMENT_ELEMENT, "8a4a40b6-50d3-553b-aece-fe1d2e225b8b");
        assertIdentity(DocumentModelTerms.SECTION, "10e96659-86c9-5d71-bed8-45f78f18daa1");
        assertIdentity(DocumentModelTerms.PARAGRAPH, "2c8a7324-28fd-5191-a92a-0b571c96d4d9");
        assertIdentity(DocumentModelTerms.LISTING, "cd41a193-24a9-59d4-ba24-e1fd2b0dee74");
        assertIdentity(DocumentModelTerms.MEMBERS, "1c189ac3-d9ac-5445-a1d6-225a03447838");
        assertIdentity(DocumentModelTerms.STRUCTURE, "a01a9242-6d5b-5ecd-9336-c0d9126e842b");
    }

    @Test
    void journalIdentitiesMatchTheGeneratedStarterSetBindings() {
        assertIdentity(DocumentModelTerms.CONVERSATION_JOURNAL, "0e090e44-8709-56a3-860f-2ce54a8eeed3");
        assertIdentity(DocumentModelTerms.CONVERSATION_TURN, "1c3abd81-73e5-5471-91b7-b05b5286515c");
        assertIdentity(DocumentModelTerms.KOMET_ASSISTANT, "7341619b-17c8-51e0-bdbb-7b1480d84a15");
        assertIdentity(DocumentModelTerms.CONVERSATION_JOURNAL_MODULE, "c88d6d5b-ce71-56e5-8cd5-f1bf30c3d381");
    }

    private static void assertIdentity(EntityProxy proxy, String uuid) {
        assertEquals(UUID.fromString(uuid), proxy.publicId().asUuidArray()[0], proxy.description());
    }
}
