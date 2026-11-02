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
package network.ike.komet.claude.complexconcept;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Ref;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Spec;
import network.ike.komet.claude.complexconcept.model.ComplexConceptBuilder;
import network.ike.komet.claude.complexconcept.model.ComplexConceptRefusal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proposes the complex concept a {@code complex-concept} block states: one uncommitted change,
 * in one transaction, as {@link PromoteToComplexConcept} writes a promotion
 * (IKE-Network/ike-issues#812, #1312).
 *
 * <p>The change writes the complex concept in the Complex concepts module, named by the block's
 * label as its fully qualified name and its regular name, both preferred in US English; its
 * specification, built through {@link ComplexConceptBuilder} so every rule holds; and, first, a
 * concept for every symbol the block names that the store lacks, under the public id the symbol
 * derives, since pre-coordination at write time creates the concept a statement needs rather than
 * leaving a composition in the data ({@code arch-anf-precoordination}). Nothing is written when
 * the builder refuses.
 */
public final class ComplexConceptWrites {

    private ComplexConceptWrites() {
    }

    /** What a proposal wrote: the complex concept's public id and the stamp, with a sentence for the person. */
    public record Proposed(PublicId concept, StampEntity<?> stamp, String message) {
    }

    /**
     * Writes the proposal.
     *
     * @param spec the block's spec, complete and without findings
     * @param edit whose change it is, and on which path
     * @return what was written
     * @throws ComplexConceptRefusal     if the spec breaks a rule of specifications; nothing is written
     * @throws IllegalStateException     if the store refuses the write; nothing is written
     */
    public static Proposed propose(Spec spec, EditCoordinate edit) {
        String name = spec.concept().label();
        ConceptFacade author = ConceptFacade.make(edit.getAuthorNidForChanges());
        ConceptFacade path = ConceptFacade.make(edit.getDefaultPathNid());
        StampedWriter writer = StampedWriter.open("Complex concept block: " + name, State.ACTIVE,
                author, IkeConcept.COMPLEX_CONCEPTS_MODULE, path);
        try {
            Map<Ref, ConceptFacade> facades = new LinkedHashMap<>();
            PublicId complexConcept = null;
            for (Ref ref : spec.refs()) {
                if (facades.containsKey(ref)) {
                    continue;
                }
                if (!ref.local()) {
                    facades.put(ref, ComplexConceptText.facade(ref));
                    continue;
                }
                PublicId publicId = PublicIds.of(ComplexConceptText.localPublicId(ref));
                boolean written = facades.values().stream().anyMatch(f -> f.publicId().equals(publicId));
                if (!written) {
                    writer.concept(publicId);
                    name(writer, publicId, ref.label());
                }
                facades.put(ref, EntityProxy.Concept.make(ref.label(), publicId));
                if (ref == spec.concept()) {
                    complexConcept = publicId;
                }
            }
            if (complexConcept == null) {
                complexConcept = facades.get(spec.concept()).publicId();
            }
            ComplexConceptBuilder specification = ComplexConceptText.builder(spec, facades::get);
            writer.semantic(PublicIds.newRandom(), IkePattern.COMPLEX_CONCEPT_PATTERN.publicId(), complexConcept,
                    specification.asDiTree());
            StampEntity<?> stamp = writer.stamp();
            return new Proposed(complexConcept, stamp, "Proposed the complex concept " + name + ", "
                    + complexConcept.idString() + ", with its specification; uncommitted, commit to keep it.");
        } catch (ComplexConceptRefusal refused) {
            writer.cancel();
            throw refused;
        } catch (RuntimeException e) {
            writer.cancel();
            throw new IllegalStateException("Could not propose the complex concept: " + e.getMessage(), e);
        }
    }

    /** The fully qualified name and a preferred regular name, both in US English. */
    private static void name(StampedWriter writer, PublicId concept, String name) {
        writer.fullyQualifiedName(concept, name);
        PublicId regularName = PublicIds.newRandom();
        writer.semantic(regularName, KernelPattern.DESCRIPTION_PATTERN.publicId(), concept,
                KernelConcept.ENGLISH_LANGUAGE, name, KernelConcept.DESCRIPTION_NOT_CASE_SENSITIVE,
                KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE);
        writer.semantic(PublicIds.newRandom(), KernelPattern.US_DIALECT_PATTERN.publicId(), regularName,
                KernelConcept.PREFERRED);
    }
}
