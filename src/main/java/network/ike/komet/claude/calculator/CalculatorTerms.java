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
package network.ike.komet.claude.calculator;

import java.util.Locale;
import java.util.OptionalLong;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.decision.DecisionTerms;

/**
 * The concepts the calculator profile reads and writes ({@code CalculatorSet} in the IKE starter
 * set, design {@code dev-calculator-blocks}): the twelve vertex kinds, the six property keys, the
 * three when-missing policies and the two derivation kinds the calculator adds, beside the
 * {@link DecisionTerms} it shares for derivations, bases and the kernel's concept, interval and
 * unit keys. {@link #fromBindings()} resolves them through the store once; {@link #synthetic()}
 * numbers them arbitrarily, so the profile and its evaluation are testable with no store.
 */
public record CalculatorTerms(
        DecisionTerms shared,
        long calculatorNode, long inputNode, long outputNode, long termNode, long stratumNode,
        long conditionNode, long coefficientNode, long formulaNode, long bandNode,
        long conversionNode, long recencyNode, long missingValueNode,
        long inputTopic, long required, long admittedValues, long whenMissing, long conditionValue, long resultScale,
        long notDeterminable, long plausible, long assumed,
        long naturalLogarithm, long coxSurvival) {

    /** The terms as the running store numbers them. */
    public static CalculatorTerms fromBindings() {
        return new CalculatorTerms(DecisionTerms.fromBindings(),
                IkeConcept.CALCULATOR_NODE.nid(), IkeConcept.INPUT_NODE.nid(), IkeConcept.OUTPUT_NODE.nid(),
                IkeConcept.EQUATION_TERM_NODE.nid(), IkeConcept.STRATUM_NODE.nid(),
                IkeConcept.STRATUM_CONDITION_NODE.nid(), IkeConcept.COEFFICIENT_NODE.nid(),
                IkeConcept.FORMULA_NODE.nid(), IkeConcept.BAND_NODE.nid(),
                IkeConcept.CONVERSION_NODE.nid(), IkeConcept.RECENCY_NODE.nid(), IkeConcept.MISSING_VALUE_NODE.nid(),
                IkeConcept.INPUT_TOPIC_CONSTRAINT.nid(), IkeConcept.REQUIRED_INPUT.nid(),
                IkeConcept.ADMITTED_VALUES.nid(), IkeConcept.WHEN_MISSING.nid(), IkeConcept.CONDITION_VALUE.nid(),
                IkeConcept.RESULT_SCALE.nid(),
                IkeConcept.NOT_DETERMINABLE_WHEN_MISSING.nid(), IkeConcept.PLAUSIBLE_RANGE_WHEN_MISSING.nid(),
                IkeConcept.ASSUMED_VALUE_WHEN_MISSING.nid(),
                IkeConcept.NATURAL_LOGARITHM_DERIVATION.nid(), IkeConcept.COX_SURVIVAL_DERIVATION.nid());
    }

    /** Distinct arbitrary nids, for a profile read with no store. */
    public static CalculatorTerms synthetic() {
        long[] nid = new long[23];
        for (int i = 0; i < nid.length; i++) {
            nid[i] = -2_000 - i;
        }
        return new CalculatorTerms(DecisionTerms.synthetic(),
                nid[0], nid[1], nid[2], nid[3], nid[4], nid[5], nid[6], nid[7], nid[8], nid[9], nid[10], nid[11],
                nid[12], nid[13], nid[14], nid[15], nid[16], nid[17],
                nid[18], nid[19], nid[20],
                nid[21], nid[22]);
    }

    // ── The shared keys, by their familiar names ─────────────────────────────

    public long conceptReference() {
        return shared.conceptReference();
    }

    public long intervalLowerBound() {
        return shared.intervalLowerBound();
    }

    public long intervalUpperBound() {
        return shared.intervalUpperBound();
    }

    public long lowerBoundOpen() {
        return shared.lowerBoundOpen();
    }

    public long upperBoundOpen() {
        return shared.upperBoundOpen();
    }

    public long unitOfMeasure() {
        return shared.unitOfMeasure();
    }

    public long derivedFrom() {
        return shared.derivedFrom();
    }

    public long operands() {
        return shared.operands();
    }

    public long measureBasis() {
        return shared.measureBasis();
    }

    public long basisSource() {
        return shared.basisSource();
    }

    /** Whether a nid names a derivation kind an equation term may be computed with. */
    public boolean isTermKind(long nid) {
        return nid == naturalLogarithm || nid == shared.complement() || nid == shared.product();
    }

    /** Whether a nid names a derivation kind a formula may be computed with. */
    public boolean isFormulaKind(long nid) {
        return nid == coxSurvival;
    }

    /** Whether a nid names any derivation kind this profile or the shared one runs. */
    public boolean isDerivationKind(long nid) {
        return isTermKind(nid) || isFormulaKind(nid) || shared.isDerivationKind(nid);
    }

    /**
     * The derivation kind a written name means, case and spacing aside and with or without the
     * word derivation: {@code NaturalLogarithm}, {@code ln}, {@code Cox survival}, and the shared
     * kinds. Empty for any other name.
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
            case "naturallogarithm", "ln", "log" -> OptionalLong.of(naturalLogarithm);
            case "coxsurvival", "cox" -> OptionalLong.of(coxSurvival);
            default -> shared.derivationKind(name);
        };
    }

    /** The when-missing policy a written word means: {@code not-determinable}, {@code plausible}, {@code assumed}. */
    public OptionalLong policy(String word) {
        if (word == null) {
            return OptionalLong.empty();
        }
        return switch (word.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "")) {
            case "notdeterminable" -> OptionalLong.of(notDeterminable);
            case "plausible", "plausiblerange" -> OptionalLong.of(plausible);
            case "assumed", "assume", "assumedvalue" -> OptionalLong.of(assumed);
            default -> OptionalLong.empty();
        };
    }

    /** The written word of a when-missing policy, empty for a nid that is none. */
    public OptionalLong policyWordless(long nid) {
        return nid == notDeterminable || nid == plausible || nid == assumed ? OptionalLong.of(nid) : OptionalLong.empty();
    }
}
