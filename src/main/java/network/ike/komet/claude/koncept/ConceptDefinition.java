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
package network.ike.komet.claude.koncept;

import dev.ikm.tinkar.coordinate.logic.PremiseType;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.KernelTerm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A medium-agnostic, flattened view of a concept's EL++ logical definition (stated or
 * inferred), extracted from the axiom {@code DiTreeEntity}. Mirrors how Komet's
 * {@code AxiomView}/{@code ClauseView} walk the tree, but yields plain nids so any
 * renderer (Zulip table, HTML/email, …) can present it.
 *
 * <p>Structure: a definition kind ({@link #defined} = sufficient/≡ vs primitive/⊑), the
 * supertypes (Is-a), ungrouped {@link Role}s, and {@link #roleGroups role groups} (each a
 * list of {@code ∃ attribute . value} restrictions). Every nid is a concept.
 */
public record ConceptDefinition(boolean defined,
                                long[] supertypes,
                                List<Role> ungroupedRoles,
                                List<List<Role>> roleGroups) {

    /** A single {@code ∃ attribute . value} existential restriction (both concept nids). */
    public record Role(long attributeNid, long valueNid) {
    }

    /**
     * Extracts the {@code premise} (stated or inferred) definition for a concept.
     *
     * @param conceptNid the concept
     * @param view       the active view (supplies the axiom tree)
     * @param premise    {@link PremiseType#STATED} or {@link PremiseType#INFERRED}
     * @return the flattened definition, or empty if the concept has no such axioms / no content
     */
    public static Optional<ConceptDefinition> extract(long conceptNid, ViewCalculator view, PremiseType premise) {
        Latest<DiTreeEntity> latest = view.getAxiomTreeForEntity(conceptNid, premise);
        if (!latest.isPresent()) {
            return Optional.empty();
        }
        DiTreeEntity tree = latest.get();
        boolean defined = tree.containsVertexWithMeaning(KernelTerm.SUFFICIENT_SET);

        Set<Long> supertypes = new LinkedHashSet<>();
        List<Role> ungrouped = new ArrayList<>();
        List<List<Role>> groups = new ArrayList<>();

        for (EntityVertex set : conjuncts(tree, tree.root())) {
            long meaning = set.getMeaningNid();
            if (meaning != KernelTerm.NECESSARY_SET.nid() && meaning != KernelTerm.SUFFICIENT_SET.nid()) {
                continue;
            }
            for (EntityVertex conjunct : conjuncts(tree, set)) {
                long cm = conjunct.getMeaningNid();
                if (cm == KernelTerm.CONCEPT_REFERENCE.nid()) {
                    supertypes.add(conceptNidOf(conjunct));
                } else if (cm == KernelTerm.ROLE.nid()) {
                    ConceptFacade roleType = conjunct.propertyFast(KernelTerm.ROLE_TYPE);
                    if (roleType.nid() == KernelTerm.ROLE_GROUP.nid()) {
                        List<Role> group = new ArrayList<>();
                        for (EntityVertex member : conjuncts(tree, conjunct)) {
                            if (member.getMeaningNid() == KernelTerm.ROLE.nid()) {
                                role(tree, member).ifPresent(group::add);
                            }
                        }
                        if (!group.isEmpty()) {
                            groups.add(group);
                        }
                    } else {
                        role(tree, conjunct).ifPresent(ungrouped::add);
                    }
                }
            }
        }

        if (supertypes.isEmpty() && ungrouped.isEmpty() && groups.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ConceptDefinition(defined,
                supertypes.stream().mapToLong(Long::longValue).toArray(), ungrouped, groups));
    }

    /** Successors of a vertex, transparently descending a single {@code AND} layer. */
    private static List<EntityVertex> conjuncts(DiTreeEntity tree, EntityVertex vertex) {
        List<EntityVertex> out = new ArrayList<>();
        for (EntityVertex child : tree.successors(vertex)) {
            if (child.getMeaningNid() == KernelTerm.AND.nid()) {
                for (EntityVertex grandchild : tree.successors(child)) {
                    out.add(grandchild);
                }
            } else {
                out.add(child);
            }
        }
        return out;
    }

    /** A role vertex as {@code attribute → value} (the value is its first concept filler). */
    private static Optional<Role> role(DiTreeEntity tree, EntityVertex roleVertex) {
        ConceptFacade attribute = roleVertex.propertyFast(KernelTerm.ROLE_TYPE);
        for (EntityVertex filler : conjuncts(tree, roleVertex)) {
            if (filler.getMeaningNid() == KernelTerm.CONCEPT_REFERENCE.nid()) {
                return Optional.of(new Role(attribute.nid(), conceptNidOf(filler)));
            }
        }
        return Optional.empty();
    }

    private static long conceptNidOf(EntityVertex conceptVertex) {
        ConceptFacade concept = conceptVertex.propertyFast(KernelTerm.CONCEPT_REFERENCE);
        return concept.nid();
    }
}
