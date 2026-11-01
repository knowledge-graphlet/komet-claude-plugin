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
import design.knowledge.stamp.adoc.model.CommitEvent;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import network.ike.foundation.ike.bindings.IkeConcept;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
            // A retraction is recorded as an inactive version of what it retracts, never as an annotation of its own.
            case RETRACTION -> throw new IllegalArgumentException("a retraction has no annotation concept of its own; it retires its target");
        };
    }

    /**
     * What an annotation says, as a concept: its kind's when the engine knows it, else the concept
     * the trailer names, any concept the store holds (the segments design's decision 9, revised
     * 2026-10-31), such as Not clinical.
     *
     * @param annotation the annotation as walked
     * @return the meaning; empty when the store does not hold the concept the trailer names, which is
     *         never minted
     */
    public static Optional<ConceptFacade> meaning(CommitEvent.AnnotationEvent annotation) {
        if (annotation.kind() != null) {
            return Optional.of(kind(annotation.kind()));
        }
        PublicId id = PublicIds.of(annotation.meaning().uuid());
        return PrimitiveData.get().hasPublicId(id)
                ? Optional.of(EntityProxy.Concept.make(annotation.meaning().label(), id))
                : Optional.empty();
    }

    /**
     * The Annotation pattern's Text range field for an annotation's elements: its lines, each whole,
     * then its sentence fragments, each {@code start-end} or {@code *} for a whole sentence, separated
     * by spaces; empty when every element is whole.
     *
     * @param lines     how many lines lead the element list
     * @param fragments the sentence fragments that follow them
     * @return the field value
     */
    public static String textRange(int lines, List<CommitEvent.SentenceFragment> fragments) {
        if (fragments.stream().allMatch(CommitEvent.SentenceFragment::isWhole)) {
            return "";
        }
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            entries.add("*");
        }
        for (CommitEvent.SentenceFragment f : fragments) {
            entries.add(f.isWhole() ? "*" : f.start() + "-" + f.end());
        }
        return String.join(" ", entries);
    }

    /**
     * Reads a Text range field back.
     *
     * @param field    the field value; empty, or absent from an annotation written before the field existed
     * @param elements how many elements the annotation names
     * @return per element, its {@code {start, end}} columns, or null for the whole element
     */
    public static List<int[]> textRanges(String field, int elements) {
        List<int[]> ranges = new ArrayList<>(elements);
        String[] entries = field == null || field.isBlank() ? new String[0] : field.strip().split("\\s+");
        for (int i = 0; i < elements; i++) {
            String entry = i < entries.length ? entries[i] : "*";
            int dash = entry.indexOf('-');
            ranges.add(dash < 0 ? null : new int[] {Integer.parseInt(entry.substring(0, dash)), Integer.parseInt(entry.substring(dash + 1))});
        }
        return ranges;
    }
}
