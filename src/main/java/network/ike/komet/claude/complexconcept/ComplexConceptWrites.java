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
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Parsed;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Ref;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Spec;
import network.ike.komet.claude.complexconcept.model.ComplexConceptBuilder;
import network.ike.komet.claude.complexconcept.model.ComplexConceptRefusal;
import network.ike.komet.claude.decision.ViewIdentities;
import network.ike.komet.claude.stamp.SemanticVersions;
import org.eclipse.collections.api.factory.Lists;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Writes the complex concept a {@code complex-concept} block states, uncommitted, as
 * {@link PromoteToComplexConcept} writes a promotion (IKE-Network/ike-issues#812, #1312;
 * ike-lab-documents topic {@code dev-decision-document}, "Versions").
 *
 * <p>The first write is the block's Propose: the complex concept in the Complex concepts module,
 * named by the block's label as its fully qualified name and its regular name, both preferred in
 * US English; its specification, built through {@link ComplexConceptBuilder} so every rule holds;
 * and, first, a concept for every symbol the block names that the store lacks, under the public
 * id the symbol derives, since pre-coordination at write time creates the concept a statement
 * needs rather than leaving a composition in the data ({@code arch-anf-precoordination}). Each
 * write after is a version of the same specification semantic, never a second one: one author's
 * edits before a commit rewrite the pending version, and after a commit the next edit is a new
 * version. Nothing is written when the block has a finding, is not yet complete, or the builder
 * refuses it.
 */
public final class ComplexConceptWrites {

    private ComplexConceptWrites() {
    }

    /** What a write did: the complex concept's public id when it was written, and a sentence for the person. */
    public record Written(Optional<PublicId> concept, String message) {
        static Written refused(String message) {
            return new Written(Optional.empty(), message);
        }

        /** @return whether anything was written */
        public boolean wrote() {
            return concept.isPresent();
        }
    }

    /**
     * Writes the complex concept a block's text states, after the block was saved.
     *
     * @param body the block's text
     * @param view the view the block's identifiers are read under
     * @param edit whose change it is, and on which path
     * @return what was written, or why nothing was
     */
    public static Written write(String body, ViewCalculator view, EditCoordinate edit) {
        return write(body, view, edit, false);
    }

    /**
     * Writes the complex concept a block's text states, after the block was saved.
     *
     * @param body   the block's text
     * @param view   the view the block's identifiers are read under
     * @param edit   whose change it is, and on which path
     * @param shared whether the block is on a paired document, whose two authors share one pending edit
     * @return what was written, or why nothing was
     */
    public static Written write(String body, ViewCalculator view, EditCoordinate edit, boolean shared) {
        Parsed parsed = ComplexConceptText.parse(body, new BlockIdentities(new ViewIdentities(view)));
        if (parsed.spec().isEmpty()) {
            return Written.refused("The block states no complex concept, so none was written.");
        }
        List<String> findings = parsed.problems();
        if (!findings.isEmpty()) {
            return Written.refused("The complex concept was not written: " + findings.getFirst()
                    + (findings.size() > 1 ? ", and " + (findings.size() - 1) + " more." : "."));
        }
        return write(parsed.spec().get(), view, edit, shared);
    }

    /**
     * Writes a complex concept: its first version, as Propose does, or a version of its
     * specification.
     *
     * @param spec the block's spec, without findings
     * @param view the view the store is read under
     * @param edit whose change it is, and on which path
     * @return what was written, or why nothing was
     */
    public static Written write(Spec spec, ViewCalculator view, EditCoordinate edit) {
        return write(spec, view, edit, false);
    }

    private static Written write(Spec spec, ViewCalculator view, EditCoordinate edit, boolean shared) {
        List<String> missing = ComplexConceptText.completeness(spec);
        if (!missing.isEmpty()) {
            return Written.refused("The complex concept is not written yet: the specification still needs "
                    + String.join("; ", missing) + ".");
        }
        String name = spec.concept().label();
        // each local symbol builds as the concept its symbol derives; the ones the store lacks are written first
        Map<Ref, ConceptFacade> facades = new LinkedHashMap<>();
        Map<PublicId, String> lacking = new LinkedHashMap<>();
        for (Ref ref : spec.refs()) {
            if (facades.containsKey(ref)) {
                continue;
            }
            ConceptFacade facade = ComplexConceptText.facade(ref);
            facades.put(ref, facade);
            if (ref.local() && !SemanticVersions.exists(facade.publicId(), view)) {
                lacking.putIfAbsent(facade.publicId(), ref.label());
            }
        }
        PublicId complexConcept = facades.get(spec.concept()).publicId();
        DiTreeEntity specification;
        try {
            specification = ComplexConceptText.builder(spec, facades::get).asDiTree();
        } catch (ComplexConceptRefusal refused) {
            return Written.refused("The complex concept was not written: " + refused.getMessage());
        }
        OptionalLong existing = lacking.containsKey(complexConcept) ? OptionalLong.empty() : specificationOf(complexConcept, view);
        ConceptFacade author = ConceptFacade.make(edit.getAuthorNidForChanges());
        ConceptFacade path = ConceptFacade.make(edit.getDefaultPathNid());
        StampedWriter writer = lacking.isEmpty() && existing.isPresent() ? null
                : StampedWriter.open("Complex concept block: " + name, State.ACTIVE, author, IkeConcept.COMPLEX_CONCEPTS_MODULE, path);
        try {
            if (writer != null) {
                lacking.forEach((concept, label) -> {
                    writer.concept(concept);
                    name(writer, concept, label);
                });
            }
            int others = lacking.size() - (lacking.containsKey(complexConcept) ? 1 : 0);
            String concepts = others == 0 ? "" : ", with " + others + (others == 1 ? " concept" : " concepts")
                    + " the knowledge base lacked";
            if (existing.isEmpty()) {
                writer.semantic(PublicIds.newRandom(), IkePattern.COMPLEX_CONCEPT_PATTERN.publicId(), complexConcept, specification);
                return new Written(Optional.of(complexConcept), "Proposed the complex concept " + name + ", "
                        + complexConcept.idString() + ", with its specification" + concepts + "; uncommitted, commit to keep it.");
            }
            SemanticVersions.Versioned versioned = SemanticVersions.version(existing.getAsLong(),
                    IkePattern.COMPLEX_CONCEPT_PATTERN.nid(), Lists.immutable.of(specification), view, edit, "specification", shared);
            if (versioned.refusal().isPresent()) {
                cancel(writer);
                return Written.refused(versioned.refusal().get());
            }
            return new Written(Optional.of(complexConcept), (versioned.replacedPending()
                    ? "Rewrote the specification of " + name + " in its uncommitted version"
                    : "Wrote a new version of the specification of " + name) + concepts + "; uncommitted, commit to keep it.");
        } catch (RuntimeException e) {
            cancel(writer);
            return Written.refused("The complex concept was not written: " + e.getMessage());
        }
    }

    /** The specification semantic a complex concept has, when it has one this view sees. */
    private static OptionalLong specificationOf(PublicId concept, ViewCalculator view) {
        if (!SemanticVersions.exists(concept, view)) {
            return OptionalLong.empty();
        }
        for (var semantic : EntityService.get().semanticsForComponentOfPattern(PrimitiveData.nid(concept),
                IkePattern.COMPLEX_CONCEPT_PATTERN.nid()).toList()) {
            if (SemanticVersions.visible(semantic.nid(), view)) {
                return OptionalLong.of(semantic.nid());
            }
        }
        return OptionalLong.empty();
    }

    private static void cancel(StampedWriter writer) {
        if (writer != null) {
            writer.cancel();
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
