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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The annotation kinds a trailer names map to the concepts the IKE starter set declares, so the
 * engine and the graph agree on one identity per kind.
 */
class StampedDocumentTermsTest {

    /**
     * The engine names a trailer's kind by the same identity the set declares, so git and graph agree.
     * A retraction has no concept of its own in the graph: it retires what it retracts (IKE-Network/ike-issues#1300).
     */
    @Test
    void engineKindsAreTheSetsConcepts() {
        for (design.knowledge.stamp.adoc.model.AnnotationKind kind : design.knowledge.stamp.adoc.model.AnnotationKind.values()) {
            if (kind == design.knowledge.stamp.adoc.model.AnnotationKind.RETRACTION) {
                org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> StampedDocumentTerms.kind(kind));
                continue;
            }
            assertEquals(kind.uuid(), StampedDocumentTerms.kind(kind).publicId().asUuidArray()[0], kind.name());
        }
    }
}
