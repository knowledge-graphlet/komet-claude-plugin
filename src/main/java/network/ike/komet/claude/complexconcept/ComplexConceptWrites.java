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
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.authoring.BlockConcepts;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.stamp.LocalConcepts;
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
 * and, first, a concept for every name the block uses that the store lacks, under the public id
 * the name derives ({@link LocalConcepts}), since pre-coordination at write time creates the
 * concept a statement needs rather than leaving a composition in the data
 * ({@code arch-anf-precoordination}). A specification that differs from one this block did not
 * state is held for the person rather than written over it. Each
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
     * @param body    the block's text
     * @param view    the view the block's identifiers are read under
     * @param edit    whose change it is, and on which path
     * @param context how the block stands to its document
     * @return what was written, or why nothing was
     */
    public static Written write(String body, ViewCalculator view, EditCoordinate edit, LocalConcepts.Context context) {
        Parsed parsed = ComplexConceptText.parse(body, new BlockIdentities(new ViewIdentities(view)));
        if (parsed.spec().isEmpty()) {
            return Written.refused("The block states no complex concept, so none was written.");
        }
        List<String> findings = parsed.problems();
        if (!findings.isEmpty()) {
            return Written.refused("The complex concept was not written: " + findings.getFirst()
                    + (findings.size() > 1 ? ", and " + (findings.size() - 1) + " more." : "."));
        }
        return write(parsed.spec().get(), view, edit, context);
    }

    /**
     * Writes a complex concept as Propose does: its first version, or a version of its
     * specification.
     *
     * @param spec the block's spec, without findings
     * @param view the view the store is read under
     * @param edit whose change it is, and on which path
     * @return what was written, or why nothing was
     */
    public static Written write(Spec spec, ViewCalculator view, EditCoordinate edit) {
        return write(spec, view, edit, LocalConcepts.Context.ALONE);
    }

    private static Written write(Spec spec, ViewCalculator view, EditCoordinate edit, LocalConcepts.Context context) {
        List<String> missing = ComplexConceptText.completeness(spec);
        if (!missing.isEmpty()) {
            return Written.refused("The complex concept is not written yet: the specification still needs "
                    + String.join("; ", missing) + ".");
        }
        String name = spec.concept().label();
        // each name builds as the concept it names; the ones the store lacks are written first
        Map<Ref, ConceptFacade> facades = new LinkedHashMap<>();
        Map<PublicId, String> lacking = new LinkedHashMap<>();
        Map<PublicId, List<String>> others = new LinkedHashMap<>();
        for (Ref ref : spec.refs()) {
            if (facades.containsKey(ref)) {
                continue;
            }
            if (!ref.local()) {
                facades.put(ref, ComplexConceptText.facade(ref));
                continue;
            }
            String symbol = ref.token().identifier().orElse(ref.label());
            String fqn = context.names().fqn(symbol, ref.label());
            PublicId concept = LocalConcepts.identity(fqn, view);
            facades.put(ref, EntityProxy.Concept.make(fqn, concept));
            if (!SemanticVersions.exists(concept, view) && !lacking.containsKey(concept)) {
                lacking.put(concept, fqn);
                others.put(concept, context.names().others(symbol, fqn));
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
        if (existing.isPresent()) {
            SemanticEntityVersion head = view.<SemanticEntityVersion>latest(existing.getAsLong()).get();
            if (specification.equals(head.fieldValues().get(0))) {
                return new Written(Optional.of(complexConcept), "The complex concept " + name
                        + " is already in the knowledge base as this block states it; nothing was written.");
            }
            if (!revises(context, head, facades, view)) {
                return Written.refused("The complex concept was not written: \u201c" + name + "\u201d already has a different specification "
                        + "in the knowledge base, and this block did not state it. Ask the person whether they are the same concept. If they "
                        + "are, keep one specification; if not, give this concept a more specific name, since its label is its fully "
                        + "qualified name.");
            }
        }
        ConceptFacade author = ConceptFacade.make(edit.getAuthorNidForChanges());
        ConceptFacade path = ConceptFacade.make(edit.getDefaultPathNid());
        BlockConcepts writer = new BlockConcepts("Complex concept block: " + name, view, author, IkeConcept.COMPLEX_CONCEPTS_MODULE, path);
        try {
            lacking.forEach((concept, fqn) -> writer.concept(concept, fqn, others.get(concept)));
            int created = lacking.size() - (lacking.containsKey(complexConcept) ? 1 : 0);
            String concepts = created == 0 ? "" : ", with " + created + (created == 1 ? " concept" : " concepts")
                    + " the knowledge base lacked";
            if (existing.isEmpty()) {
                writer.semantic(specificationId(complexConcept), IkePattern.COMPLEX_CONCEPT_PATTERN,
                        EntityProxy.Concept.make(name, complexConcept), specification);
                return new Written(Optional.of(complexConcept), "Proposed the complex concept " + name + ", "
                        + complexConcept.idString() + ", with its specification" + concepts + "; uncommitted, commit to keep it.");
            }
            SemanticVersions.Versioned versioned = SemanticVersions.version(existing.getAsLong(),
                    IkePattern.COMPLEX_CONCEPT_PATTERN.nid(), Lists.immutable.of(specification), view, edit, "specification", context.shared());
            if (versioned.refusal().isPresent()) {
                writer.rollback();
                return Written.refused(versioned.refusal().get());
            }
            return new Written(Optional.of(complexConcept), (versioned.replacedPending()
                    ? "Rewrote the specification of " + name + " in its uncommitted version"
                    : "Wrote a new version of the specification of " + name) + concepts + "; uncommitted, commit to keep it.");
        } catch (RuntimeException e) {
            writer.rollback();
            return Written.refused("The complex concept was not written: " + e.getMessage());
        }
    }

    /** Whether this block's text before the edit stated the specification the store holds. */
    private static boolean revises(LocalConcepts.Context context, SemanticEntityVersion head, Map<Ref, ConceptFacade> facades, ViewCalculator view) {
        if (context.previous().isEmpty()) {
            return false;
        }
        Parsed before = ComplexConceptText.parse(context.previous().get(), new BlockIdentities(new ViewIdentities(view)));
        if (before.spec().isEmpty()) {
            return false;
        }
        Map<String, ConceptFacade> byName = new LinkedHashMap<>();
        facades.forEach((ref, facade) -> byName.put(LocalConcepts.normal(ref.label()), facade));
        try {
            return ComplexConceptText.builder(before.spec().get(), ref -> ref.local()
                    ? byName.getOrDefault(LocalConcepts.normal(ref.label()), ComplexConceptText.facade(ref))
                    : ComplexConceptText.facade(ref)).asDiTree().equals(head.fieldValues().get(0));
        } catch (RuntimeException differentRules) {
            return false;
        }
    }

    /** The public id of a complex concept's specification semantic, derived from the concept. */
    static PublicId specificationId(PublicId concept) {
        return LocalConcepts.derived(concept, "complex-concept specification");
    }

    /** The specification semantic a complex concept has, when it has one this view sees. */
    public static OptionalLong specificationOf(PublicId concept, ViewCalculator view) {
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

}
