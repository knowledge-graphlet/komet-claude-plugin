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
package network.ike.komet.claude.decision;

import dev.ikm.tinkar.entity.graph.VertexIdentityTable;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.IdentityRule;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.IdentityRule.Successors;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.foundation.ike.bindings.IkeConcept;
import org.eclipse.collections.api.factory.Lists;

import java.util.Optional;

/**
 * The vertex identity rules of a decision tree, one exhaustive switch over its vertex meanings
 * (IKE-Network/ike-issues#1310; ike-lab-documents topics {@code arch-vertex-identity-*} and
 * {@code dev-decision-document}), so a tree written again keeps each vertex's identity: a
 * probability's history is one vertex's history, and a comment on an outcome follows it from
 * version to version.
 *
 * <p>Every node matches its children as operands, since a node holds its measures and its
 * branches as an unordered collection. A node is identified by its concept, so an alternative
 * or an event whose numbers change is updated, and one renamed to another concept is replaced.
 * A node's probability is singular and identified by its meaning alone; a money, health or count
 * cost by its unit, the thing counted for a count; a parameter by the quantity it states.
 */
public final class DecisionTreeIdentityTable implements VertexIdentityTable {

    /** The meanings a decision tree's vertices carry. */
    enum Meaning {
        DECISION(IkeConcept.DECISION_NODE), ALTERNATIVE(IkeConcept.ALTERNATIVE_NODE), CHANCE(IkeConcept.CHANCE_NODE),
        EVENT(IkeConcept.EVENT_NODE), OUTCOME(IkeConcept.OUTCOME_NODE),
        PROBABILITY(IkeConcept.PROBABILITY_MEASURE), MONEY(IkeConcept.MONEY_COST_MEASURE),
        HEALTH(IkeConcept.HEALTH_COST_MEASURE), COUNT(IkeConcept.COUNT_COST_MEASURE),
        PARAMETER(IkeConcept.PARAMETER_MEASURE);

        private final IkeConcept concept;

        Meaning(IkeConcept concept) {
            this.concept = concept;
        }

        static Optional<Meaning> of(long meaningNid) {
            for (Meaning meaning : values()) {
                if (meaning.concept.nid() == meaningNid) {
                    return Optional.of(meaning);
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public boolean covers(long meaningNid) {
        return Meaning.of(meaningNid).isPresent();
    }

    @Override
    public IdentityRule rule(long meaningNid) {
        return rule(Meaning.of(meaningNid).orElseThrow(() -> new IllegalArgumentException("not a decision tree vertex: " + meaningNid)));
    }

    /**
     * @param meaning a vertex meaning
     * @return the identity rule for vertices of that meaning
     */
    static IdentityRule rule(Meaning meaning) {
        return switch (meaning) {
            case DECISION, ALTERNATIVE, CHANCE, EVENT, OUTCOME -> rule(Successors.OPERANDS, KernelConcept.CONCEPT_REFERENCE);
            case PROBABILITY -> rule(Successors.NONE);
            case MONEY, HEALTH, COUNT -> rule(Successors.NONE, KernelConcept.UNIT_OF_MEASURE);
            case PARAMETER -> rule(Successors.NONE, KernelConcept.CONCEPT_REFERENCE);
        };
    }

    private static IdentityRule rule(Successors successors, ConceptFacade... identifying) {
        return new IdentityRule(successors, Lists.immutable.of(identifying));
    }

    @Override
    public String toString() {
        return "DecisionTreeSemantic";
    }
}
