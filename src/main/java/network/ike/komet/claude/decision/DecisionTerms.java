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

import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.foundation.ike.bindings.IkeConcept;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The concepts the decision-tree profile reads vertices by, as nids: the node and measure kinds,
 * the property keys, the measure semantics, the basis kinds and the derivation kinds
 * {@code DecisionTreeSet} declares in the IKE starter set, and the kernel's concept-reference and
 * interval-role keys the tree reuses. {@link #fromBindings()} resolves them through the store
 * once; {@link #synthetic()} numbers them arbitrarily, so the profile and its rollback are
 * testable with no store at all, since nothing in either depends on what a nid is beyond its
 * identity.
 */
public record DecisionTerms(
        long decisionNode, long alternativeNode, long chanceNode, long eventNode, long outcomeNode,
        long probabilityMeasure, long moneyCostMeasure, long healthCostMeasure, long parameterMeasure,
        long statement, long derivedFrom, long operands, long measureBasis, long basisSource, long treeReference,
        long inputKind, long residual,
        long shareAtNode, long moneySemantic, long healthSemantic,
        long estimatedBasis, long citedBasis, long derivedBasis,
        long complement, long product, long cumulativeHazard, long bayesPosterior, long bayesMarginal,
        long conceptReference, long intervalLowerBound, long intervalUpperBound,
        long lowerBoundOpen, long upperBoundOpen, long unitOfMeasure) {

    /** The terms as the running store numbers them. */
    public static DecisionTerms fromBindings() {
        return new DecisionTerms(
                IkeConcept.DECISION_NODE.nid(), IkeConcept.ALTERNATIVE_NODE.nid(), IkeConcept.CHANCE_NODE.nid(),
                IkeConcept.EVENT_NODE.nid(), IkeConcept.OUTCOME_NODE.nid(),
                IkeConcept.PROBABILITY_MEASURE.nid(), IkeConcept.MONEY_COST_MEASURE.nid(),
                IkeConcept.HEALTH_COST_MEASURE.nid(), IkeConcept.PARAMETER_MEASURE.nid(),
                IkeConcept.STATEMENT.nid(), IkeConcept.DERIVED_FROM.nid(), IkeConcept.DERIVATION_OPERANDS.nid(),
                IkeConcept.MEASURE_BASIS.nid(), IkeConcept.BASIS_SOURCE.nid(), IkeConcept.TREE_REFERENCE.nid(),
                IkeConcept.INPUT_KIND.nid(), IkeConcept.RESIDUAL.nid(),
                IkeConcept.SHARE_AT_NODE.nid(), IkeConcept.MONEY_SEMANTIC.nid(), IkeConcept.HEALTH_SEMANTIC.nid(),
                IkeConcept.ESTIMATED_BASIS.nid(), IkeConcept.CITED_BASIS.nid(), IkeConcept.DERIVED_BASIS.nid(),
                IkeConcept.COMPLEMENT_DERIVATION.nid(), IkeConcept.PRODUCT_DERIVATION.nid(),
                IkeConcept.CUMULATIVE_HAZARD_DERIVATION.nid(), IkeConcept.BAYES_POSTERIOR_DERIVATION.nid(),
                IkeConcept.BAYES_MARGINAL_DERIVATION.nid(),
                KernelConcept.CONCEPT_REFERENCE.nid(), KernelConcept.INTERVAL_LOWER_BOUND.nid(),
                KernelConcept.INTERVAL_UPPER_BOUND.nid(), KernelConcept.LOWER_BOUND_OPEN.nid(),
                KernelConcept.UPPER_BOUND_OPEN.nid(), KernelConcept.UNIT_OF_MEASURE.nid());
    }

    /** Distinct arbitrary nids, for a profile read with no store. */
    public static DecisionTerms synthetic() {
        long[] nid = new long[34];
        for (int i = 0; i < nid.length; i++) {
            nid[i] = -1_000 - i;
        }
        return new DecisionTerms(nid[0], nid[1], nid[2], nid[3], nid[4], nid[5], nid[6], nid[7], nid[8], nid[9],
                nid[10], nid[11], nid[12], nid[13], nid[14], nid[15], nid[16], nid[17], nid[18], nid[19], nid[20],
                nid[21], nid[22], nid[23], nid[24], nid[25], nid[26], nid[27], nid[28], nid[29], nid[30], nid[31],
                nid[32], nid[33]);
    }

    /** Whether a nid names one of the derivation kinds with a native implementation. */
    public boolean isDerivationKind(long nid) {
        return nid == complement || nid == product || nid == cumulativeHazard || nid == bayesPosterior
                || nid == bayesMarginal;
    }

    /** How many operands a derivation kind reads, when that is fixed; empty for a product, which reads two or more. */
    public OptionalInt arity(long kind) {
        if (kind == complement) {
            return OptionalInt.of(1);
        }
        if (kind == cumulativeHazard) {
            return OptionalInt.of(2);
        }
        if (kind == bayesPosterior || kind == bayesMarginal) {
            return OptionalInt.of(3);
        }
        return OptionalInt.empty();
    }

    /**
     * The derivation kind a written name means, case and spacing aside and with or without the
     * word derivation, as the documentation build reads it: {@code Complement},
     * {@code cumulative hazard}, {@code BayesPosterior}. Empty for any other name.
     */
    public OptionalLong derivationKind(String name) {
        if (name == null) {
            return OptionalLong.empty();
        }
        String bare = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (bare.endsWith("derivation")) {
            bare = bare.substring(0, bare.length() - "derivation".length());
        }
        return switch (bare) {
            case "complement" -> OptionalLong.of(complement);
            case "product" -> OptionalLong.of(product);
            case "cumulativehazard" -> OptionalLong.of(cumulativeHazard);
            case "bayesposterior" -> OptionalLong.of(bayesPosterior);
            case "bayesmarginal" -> OptionalLong.of(bayesMarginal);
            default -> OptionalLong.empty();
        };
    }
}
