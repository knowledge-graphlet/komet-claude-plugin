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

import design.knowledge.stamp.adoc.model.AnnotationKind;
import dev.ikm.tinkar.terms.ConceptFacade;
import network.ike.foundation.ike.bindings.IkeConcept;

/**
 * The annotation kinds a materialized document is written with ({@code AnnotationModelSet},
 * IKE-Network/ike-issues#1300): the concept the IKE starter set declares for each kind an
 * {@code Annotation:} trailer names. The engine derives the same identities, so git and graph
 * agree; the git-provenance and annotation terms themselves are the set's generated bindings.
 */
public final class StampedDocumentTerms {

    private StampedDocumentTerms() { }

    /**
     * @param kind an engine annotation kind
     * @return the concept the starter set declares for it; core derives the same identity
     */
    public static ConceptFacade kind(AnnotationKind kind) {
        return switch (kind) {
            case AFFIRMATION -> IkeConcept.AFFIRMATION;
            case REFUTATION -> IkeConcept.REFUTATION;
            case COMMENT -> IkeConcept.UNCATEGORIZED_COMMENT;
        };
    }
}
