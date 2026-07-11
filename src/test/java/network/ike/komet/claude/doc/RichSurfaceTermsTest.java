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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the {@link RichSurfaceTerms} identities. These UUIDs are written into datastores — a
 * changed namespace or name key silently forks every identity already persisted, so each resolved
 * literal is asserted exactly. If this test fails after an edit to {@code RichSurfaceTerms}, the
 * edit forked an identity: revert it (identities are immutable; add NEW terms instead).
 */
class RichSurfaceTermsTest {

    @Test
    void identitiesAreFrozen() {
        assertIdentity(RichSurfaceTerms.CONVERSATION_JOURNAL, "eb648784-da4b-5486-9fb1-11b967201d1c");
        assertIdentity(RichSurfaceTerms.JOURNAL_ELEMENT, "5ba12f61-2648-5f0e-bafa-19fd82b63143");
        assertIdentity(RichSurfaceTerms.PROSE_ELEMENT, "ecd80340-3675-56e8-8030-95094f14924b");
        assertIdentity(RichSurfaceTerms.COMPONENT_LIST_ELEMENT, "10231ba3-dbaf-5f98-a553-4c53bb38f94e");
        assertIdentity(RichSurfaceTerms.REFERENCE_ELEMENT, "08f71eff-19da-5ebd-a4d5-2af26b5e539f");
        assertIdentity(RichSurfaceTerms.TOMBSTONE_ELEMENT, "0decf236-e042-5f3b-9bbd-10f2e51f2cd6");
        assertIdentity(RichSurfaceTerms.JOURNAL_ELEMENTS, "76b5b788-3ce4-594e-b2cc-f34196ffa435");
        assertIdentity(RichSurfaceTerms.PROSE_CONTENT, "39bc2a63-237d-583d-99cf-6b95564f048b");
        assertIdentity(RichSurfaceTerms.KOMET_ASSISTANT_AUTHOR, "4ab12dc8-f381-5ae9-a29c-4560596a9de3");
        assertIdentity(RichSurfaceTerms.CONVERSATION_JOURNAL_MODULE, "631d5df5-5011-567a-9367-035d63dc47fb");
        assertIdentity(RichSurfaceTerms.JOURNAL_MANIFEST_PATTERN, "ff78d061-a153-5c8d-846c-9647b28a323f");
        assertIdentity(RichSurfaceTerms.PROSE_ELEMENT_PATTERN, "eab7d821-8b02-5382-be47-4f7df4ab5a2a");
    }

    @Test
    void namespaceIsFrozen() {
        assertEquals(UUID.fromString("4cd80c95-ca7c-4cf6-a160-257d83db5e50"),
                RichSurfaceTerms.NAMESPACE, "the identity namespace is frozen");
    }

    @Test
    void identitiesAreDistinct() {
        Set<UUID> seen = new HashSet<>();
        for (EntityProxy proxy : new EntityProxy[] {
                RichSurfaceTerms.CONVERSATION_JOURNAL, RichSurfaceTerms.JOURNAL_ELEMENT,
                RichSurfaceTerms.PROSE_ELEMENT, RichSurfaceTerms.COMPONENT_LIST_ELEMENT,
                RichSurfaceTerms.REFERENCE_ELEMENT, RichSurfaceTerms.TOMBSTONE_ELEMENT,
                RichSurfaceTerms.JOURNAL_ELEMENTS, RichSurfaceTerms.PROSE_CONTENT,
                RichSurfaceTerms.KOMET_ASSISTANT_AUTHOR, RichSurfaceTerms.CONVERSATION_JOURNAL_MODULE,
                RichSurfaceTerms.JOURNAL_MANIFEST_PATTERN, RichSurfaceTerms.PROSE_ELEMENT_PATTERN}) {
            assertTrue(seen.add(proxy.publicId().asUuidArray()[0]),
                    "duplicate identity: " + proxy.description());
        }
        assertEquals(12, seen.size(), "all wave-1 identities present and distinct");
    }

    private static void assertIdentity(EntityProxy proxy, String expectedUuid) {
        assertEquals(UUID.fromString(expectedUuid), proxy.publicId().asUuidArray()[0],
                "FROZEN identity forked for '" + proxy.description() + "'");
    }
}
