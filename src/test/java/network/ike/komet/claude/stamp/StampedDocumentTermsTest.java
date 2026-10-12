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

class StampedDocumentTermsTest {

    @Test
    void namespaceAndKeysAreFrozen() {
        assertEquals(UUID.fromString(NAMESPACE_LITERAL), StampedDocumentTerms.NAMESPACE);
        for (var pair : List.of(
                pair(StampedDocumentTerms.STAMPED_DOCUMENT, "stamped-document"),
                pair(StampedDocumentTerms.GIT_SOURCE, "git-source"),
                pair(StampedDocumentTerms.SOURCE_PATH, "source-path"),
                pair(StampedDocumentTerms.SOURCE_COMMIT, "source-commit"),
                pair(StampedDocumentTerms.AFFIRMATION, "affirmation"),
                pair(StampedDocumentTerms.AFFIRMED_LINES, "affirmed-lines"),
                pair(StampedDocumentTerms.AFFIRMS_TRAILER, "affirms-trailer"),
                pair(StampedDocumentTerms.GIT_SOURCE_PATTERN, "git-source-pattern"),
                pair(StampedDocumentTerms.AFFIRMATION_PATTERN, "affirmation-pattern"))) {
            assertEquals(UuidT5Generator.get(StampedDocumentTerms.NAMESPACE, pair.key()), pair.proxy().publicId().asUuidArray()[0], pair.key());
        }
    }

    private static final String NAMESPACE_LITERAL = "6800a8e3-2ff4-40e5-8e26-48dadc0ed573";

    private record Pair(EntityProxy proxy, String key) { }

    private static Pair pair(EntityProxy proxy, String key) {
        return new Pair(proxy, key);
    }
}
