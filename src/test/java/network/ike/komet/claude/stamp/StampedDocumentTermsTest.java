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

import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.terms.EntityProxy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The identity-agreement gate the #844 reconciliation asked for, for the git-provenance terms:
 * every identity is the IKE starter set's own derivation, {@code T5(set UUID, birth FQN)},
 * so this plugin's seeding and the set's ledger ({@code AsciiDocModelSet}) write one component.
 */
class StampedDocumentTermsTest {

    /** The IkeFoundation knowledge set's UUID, as {@code network.ike.foundation.ike.terms.Ike.SET} declares it. */
    private static final UUID IKE_SET = UUID.fromString("d890e06f-ec35-429a-b541-d0ead19695e2");

    @Test
    void namespaceIsTheStarterSets() {
        assertEquals(IKE_SET, StampedDocumentTerms.NAMESPACE,
                "identities are adopted from the IkeFoundation set, never minted under a plugin namespace");
    }

    @Test
    void identitiesAreTheSetsBirthNameDerivations() {
        for (var pair : List.of(
                pair(StampedDocumentTerms.STAMPED_DOCUMENT, "AsciiDoc document (IkeFoundation)"),
                pair(StampedDocumentTerms.GIT_PROVENANCE, "Git provenance (IkeFoundation)"),
                pair(StampedDocumentTerms.GIT_SOURCE, "Git source (IkeFoundation)"),
                pair(StampedDocumentTerms.SOURCE_PATH, "Source path (IkeFoundation)"),
                pair(StampedDocumentTerms.SOURCE_COMMIT, "Source commit (IkeFoundation)"),
                pair(StampedDocumentTerms.AFFIRMATION, "Affirmation (IkeFoundation)"),
                pair(StampedDocumentTerms.AFFIRMED_LINES, "Affirmed lines (IkeFoundation)"),
                pair(StampedDocumentTerms.AFFIRMS_TRAILER, "Affirms trailer (IkeFoundation)"),
                pair(StampedDocumentTerms.GIT_SOURCE_PATTERN, "Git source pattern (IkeFoundation)"),
                pair(StampedDocumentTerms.AFFIRMATION_PATTERN, "Affirmation pattern (IkeFoundation)"))) {
            assertEquals(UuidT5Generator.get(IKE_SET, pair.birthFqn()), pair.proxy().publicId().asUuidArray()[0], pair.birthFqn());
        }
    }

    private record Pair(EntityProxy proxy, String birthFqn) { }

    private static Pair pair(EntityProxy proxy, String birthFqn) {
        return new Pair(proxy, birthFqn);
    }
}
