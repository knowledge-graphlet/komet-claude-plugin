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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import network.ike.komet.claude.decision.Bounds;
import org.junit.jupiter.api.Test;

/**
 * The 2013 ACC/AHA Pooled Cohort Equations (Goff and others, 2014) as a calculator, evaluated
 * against the four worked patients the reference implementation publishes: a fifty-five-year-old
 * with total cholesterol 213 mg/dL, HDL 50 mg/dL, untreated systolic pressure 120 mmHg, a
 * non-smoker without diabetes, in each of the four strata. The expected shares are the outputs of
 * the PooledCohort R package (Jaeger, CRAN), to eight digits. Then the evaluation's honesty: a
 * missing input under the plausible policy widens the result; a missing required input with no
 * policy is not determinable; an assumption is preferred and reported; and the sweep ranks the
 * inputs.
 */
class PooledCohortTest {

    static final CalculatorTerms T = CalculatorTerms.synthetic();

    // The quantities, semantics and concepts of the model, as block-local concepts would number them.
    static final long CALCULATOR = 501, OUTPUT_TOPIC = 502, PCT_SEMANTIC = 503;
    static final long AGE = 511, SEX = 512, RACE = 513, SBP = 514, TREATED = 515, TC = 516, HDL = 517,
            DIABETES = 518, SMOKER = 519;
    static final long YEAR = 521, MMHG = 522, MG_PER_DL = 523, MMOL_PER_L = 524, PRESENCE = 525;
    static final long MALE = 531, FEMALE = 532, WHITE = 533, AFRICAN_AMERICAN = 534;
    static final long LN_AGE = 541, LN_AGE_SQ = 542, LN_TC = 543, LN_AGE_LN_TC = 544, LN_HDL = 545, LN_AGE_LN_HDL = 546,
            LN_SBP = 547, UNTREATED = 548, LN_TREATED_SBP = 549, LN_UNTREATED_SBP = 550, LN_AGE_LN_TREATED_SBP = 551,
            LN_AGE_LN_UNTREATED_SBP = 552, SMOKER_TERM = 553, LN_AGE_SMOKER = 554, DIABETES_TERM = 555;
    static final long S0 = 561, MEAN = 562;
    static final long WHITE_FEMALE = 571, AFRICAN_AMERICAN_FEMALE = 572, WHITE_MALE = 573, AFRICAN_AMERICAN_MALE = 574;
    static final long LOW = 581, BORDERLINE = 582, INTERMEDIATE = 583, HIGH = 584;
    static final long GOFF = 591, GRUNDY = 592;
    static final long STATEMENT = 601;

    /** A stratum's coefficients in the order ln age, ln age², ln TC, ln age × ln TC, ln HDL, ln age × ln HDL,
     *  ln treated SBP, ln age × ln treated SBP, ln untreated SBP, ln age × ln untreated SBP, smoker,
     *  ln age × smoker, diabetes; then S0 and the mean. From the PooledCohort package source. */
    record Coefficients(double[] beta, double baseline, double mean) {
    }

    static final Coefficients WHITE_WOMEN = new Coefficients(
            new double[] {-29.799, 4.884, 13.54, -3.114, -13.578, 3.149, 2.019, 0, 1.957, 0, 7.574, -1.665, 0.661},
            0.96652, -29.1817);
    static final Coefficients AFRICAN_AMERICAN_WOMEN = new Coefficients(
            new double[] {17.1141, 0, 0.9396, 0, -18.9196, 4.4748, 29.2907, -6.4321, 27.8197, -6.0873, 0.6908, 0, 0.8738},
            0.95334, 86.6081);
    static final Coefficients WHITE_MEN = new Coefficients(
            new double[] {12.344, 0, 11.853, -2.664, -7.99, 1.769, 1.797, 0, 1.764, 0, 7.837, -1.795, 0.658},
            0.91436, 61.1816);
    static final Coefficients AFRICAN_AMERICAN_MEN = new Coefficients(
            new double[] {2.469, 0, 0.302, 0, -0.307, 0, 1.916, 0, 1.809, 0, 0.549, 0, 0.645},
            0.89536, 19.5425);

    static final long[] TERMS = {LN_AGE, LN_AGE_SQ, LN_TC, LN_AGE_LN_TC, LN_HDL, LN_AGE_LN_HDL, LN_TREATED_SBP,
            LN_AGE_LN_TREATED_SBP, LN_UNTREATED_SBP, LN_AGE_LN_UNTREATED_SBP, SMOKER_TERM, LN_AGE_SMOKER, DIABETES_TERM};

    /** Builds the Pooled Cohort calculator: ten inputs, fifteen terms, four strata, the Cox formula, four bands. */
    static Calculator pooledCohort() {
        Calculator.Builder b = Calculator.builder(T);
        Calculator.Builder.Handle root = b.calculator(CALCULATOR).source(GOFF);
        root.output(OUTPUT_TOPIC, PCT_SEMANTIC, id()).bounds(Bounds.of(0, 100)).scale(100);

        root.input(AGE, YEAR, id()).bounds(Bounds.of(40, 79)).required();
        root.input(SEX, 0, id()).admittedValues(new long[] {MALE, FEMALE}).required();
        root.input(RACE, 0, id()).admittedValues(new long[] {WHITE, AFRICAN_AMERICAN}).whenMissing(T.assumed())
                .missingConcept(WHITE, id());
        Calculator.Builder.Handle sbp = root.input(SBP, MMHG, id()).bounds(Bounds.of(90, 200)).required();
        sbp.missingValue(Bounds.of(100, 160), id());   // the swing of a point reading; the input stays required
        root.input(TREATED, PRESENCE, id()).bounds(Bounds.of(0, 1)).whenMissing(T.assumed())
                .missingValue(Bounds.point(0), id());
        Calculator.Builder.Handle tc = root.input(TC, MG_PER_DL, id()).bounds(Bounds.of(130, 320)).required();
        tc.conversion(MMOL_PER_L, Bounds.point(38.67), id());
        root.input(HDL, MG_PER_DL, id()).bounds(Bounds.of(20, 100)).whenMissing(T.plausible())
                .missingValue(Bounds.of(35, 70), id());
        root.input(DIABETES, PRESENCE, id()).bounds(Bounds.of(0, 1)).required();
        root.input(SMOKER, PRESENCE, id()).bounds(Bounds.of(0, 1)).whenMissing(T.plausible())
                .missingValue(Bounds.of(0, 1), id());

        long ln = T.naturalLogarithm();
        long product = T.shared().product();
        long complement = T.shared().complement();
        root.term(LN_AGE, ln, new long[] {AGE}, id());
        root.term(LN_AGE_SQ, product, new long[] {LN_AGE, LN_AGE}, id());
        root.term(LN_TC, ln, new long[] {TC}, id());
        root.term(LN_AGE_LN_TC, product, new long[] {LN_AGE, LN_TC}, id());
        root.term(LN_HDL, ln, new long[] {HDL}, id());
        root.term(LN_AGE_LN_HDL, product, new long[] {LN_AGE, LN_HDL}, id());
        root.term(LN_SBP, ln, new long[] {SBP}, id());
        root.term(UNTREATED, complement, new long[] {TREATED}, id());
        root.term(LN_TREATED_SBP, product, new long[] {LN_SBP, TREATED}, id());
        root.term(LN_UNTREATED_SBP, product, new long[] {LN_SBP, UNTREATED}, id());
        root.term(LN_AGE_LN_TREATED_SBP, product, new long[] {LN_AGE, LN_TREATED_SBP}, id());
        root.term(LN_AGE_LN_UNTREATED_SBP, product, new long[] {LN_AGE, LN_UNTREATED_SBP}, id());
        root.term(SMOKER_TERM, SMOKER, new long[0], id());   // reads the input as it is
        root.term(LN_AGE_SMOKER, product, new long[] {LN_AGE, SMOKER}, id());
        root.term(DIABETES_TERM, DIABETES, new long[0], id());

        stratum(root, WHITE_FEMALE, FEMALE, WHITE, WHITE_WOMEN);
        stratum(root, AFRICAN_AMERICAN_FEMALE, FEMALE, AFRICAN_AMERICAN, AFRICAN_AMERICAN_WOMEN);
        stratum(root, WHITE_MALE, MALE, WHITE, WHITE_MEN);
        stratum(root, AFRICAN_AMERICAN_MALE, MALE, AFRICAN_AMERICAN, AFRICAN_AMERICAN_MEN);

        root.formula(T.coxSurvival(), new long[] {S0, MEAN}, id());

        root.band(LOW, new Bounds(0, 5, false, true), id()).source(GRUNDY);
        root.band(BORDERLINE, new Bounds(5, 7.5, false, true), id()).source(GRUNDY);
        root.band(INTERMEDIATE, new Bounds(7.5, 20, false, true), id()).source(GRUNDY);
        root.band(HIGH, Bounds.of(20, 100), id()).source(GRUNDY);
        return b.tree();
    }

    private static void stratum(Calculator.Builder.Handle root, long concept, long sex, long race, Coefficients c) {
        Calculator.Builder.Handle stratum = root.stratum(concept, id());
        stratum.condition(SEX, sex, id());
        stratum.condition(RACE, race, id());
        for (int i = 0; i < TERMS.length; i++) {
            if (c.beta()[i] != 0) {
                stratum.coefficient(TERMS[i], Bounds.point(c.beta()[i]), id()).basis(T.shared().citedBasis()).source(GOFF);
            }
        }
        stratum.coefficient(S0, Bounds.point(c.baseline()), id()).basis(T.shared().citedBasis()).source(GOFF);
        stratum.coefficient(MEAN, Bounds.point(c.mean()), id()).basis(T.shared().citedBasis()).source(GOFF);
    }

    private static UUID id() {
        return UUID.randomUUID();
    }

    /** The reference patient, bound as observed statements. */
    static Bindings.Builder referencePatient(long sex, long race) {
        return Bindings.builder()
                .bind(AGE, Binding.Observed.measure(Bounds.point(55), YEAR, STATEMENT))
                .bind(SEX, Binding.Observed.concept(sex, STATEMENT))
                .bind(RACE, Binding.Observed.concept(race, STATEMENT))
                .bind(SBP, Binding.Observed.measure(Bounds.point(120), MMHG, STATEMENT))
                .bind(TREATED, Binding.Observed.measure(Bounds.point(0), PRESENCE, STATEMENT))
                .bind(TC, Binding.Observed.measure(Bounds.point(213), MG_PER_DL, STATEMENT))
                .bind(HDL, Binding.Observed.measure(Bounds.point(50), MG_PER_DL, STATEMENT))
                .bind(DIABETES, Binding.Observed.measure(Bounds.point(0), PRESENCE, STATEMENT))
                .bind(SMOKER, Binding.Observed.measure(Bounds.point(0), PRESENCE, STATEMENT));
    }

    @Test
    void theCalculatorIsWellFormed() {
        Calculator calculator = pooledCohort();
        assertEquals(0, calculator.check().size(), () -> calculator.check().toString());
    }

    @Test
    void theFourReferencePatientsReproduceThePublishedShares() {
        Calculator calculator = pooledCohort();
        assertShare(calculator, FEMALE, WHITE, 0.02054450, WHITE_FEMALE);
        assertShare(calculator, FEMALE, AFRICAN_AMERICAN, 0.02998196, AFRICAN_AMERICAN_FEMALE);
        assertShare(calculator, MALE, WHITE, 0.05378606, WHITE_MALE);
        assertShare(calculator, MALE, AFRICAN_AMERICAN, 0.06061116, AFRICAN_AMERICAN_MALE);
    }

    private static void assertShare(Calculator calculator, long sex, long race, double expected, long stratum) {
        Evaluation evaluation = Evaluation.of(calculator, referencePatient(sex, race).build());
        assertTrue(evaluation.determinable(), () -> evaluation.findings().toString());
        assertEquals(stratum, evaluation.stratum().get().concept().getAsLong());
        Bounds share = evaluation.share().get();
        assertTrue(share.isPoint());
        assertEquals(expected, share.lower(), 5e-8);
        assertEquals(expected * 100, evaluation.result().get().lower(), 5e-6);
        assertFalse(evaluation.interim());
        assertEquals(0, evaluation.findings().size(), () -> evaluation.findings().toString());
    }

    @Test
    void theBandIsTheOneTheResultFallsIn() {
        Calculator calculator = pooledCohort();
        Evaluation evaluation = Evaluation.of(calculator, referencePatient(MALE, WHITE).build());
        assertEquals(1, evaluation.bands().size());
        assertEquals(BORDERLINE, evaluation.bands().get(0).concept().getAsLong());
    }

    @Test
    void aCholesterolInMillimolesIsConverted() {
        Calculator calculator = pooledCohort();
        Bindings bindings = referencePatient(MALE, WHITE)
                .bind(TC, Binding.Observed.measure(Bounds.point(213 / 38.67), MMOL_PER_L, STATEMENT)).build();
        Evaluation evaluation = Evaluation.of(calculator, bindings);
        assertEquals(0.05378606, evaluation.share().get().lower(), 1e-7);
        assertTrue(evaluation.input(TC).get().note().get().startsWith("converted"));
    }

    @Test
    void aMissingSmokerUnderThePlausiblePolicyWidensTheResult() {
        Calculator calculator = pooledCohort();
        Bindings bindings = referencePatient(MALE, WHITE).bind(SMOKER, new Binding.Missing()).build();
        Evaluation evaluation = Evaluation.of(calculator, bindings);
        assertTrue(evaluation.determinable(), () -> evaluation.findings().toString());
        Bounds share = evaluation.share().get();
        assertEquals(0.05378606, share.lower(), 1e-7);
        assertTrue(share.upper() > 0.08, share.toString());   // a smoker of 55 runs well above a non-smoker
        assertEquals(Evaluation.Status.PLAUSIBLE, evaluation.input(SMOKER).get().status());
        assertTrue(evaluation.interim());
        assertTrue(evaluation.bands().size() >= 2, evaluation.bands().toString());
    }

    @Test
    void aMissingHdlSweepsAcrossTheCornersNotTheIntervalArithmetic() {
        // HDL enters two terms with opposite signs; naive interval arithmetic would blow the
        // result open, while the pointwise sweep keeps it to what the equation gives.
        Calculator calculator = pooledCohort();
        Bindings bindings = referencePatient(MALE, WHITE).bind(HDL, new Binding.Missing()).build();
        Evaluation evaluation = Evaluation.of(calculator, bindings);
        Bounds share = evaluation.share().get();
        Evaluation at35 = Evaluation.of(calculator, referencePatient(MALE, WHITE)
                .bind(HDL, Binding.Observed.measure(Bounds.point(35), MG_PER_DL, STATEMENT)).build());
        Evaluation at70 = Evaluation.of(calculator, referencePatient(MALE, WHITE)
                .bind(HDL, Binding.Observed.measure(Bounds.point(70), MG_PER_DL, STATEMENT)).build());
        assertEquals(at70.share().get().lower(), share.lower(), 1e-9);
        assertEquals(at35.share().get().lower(), share.upper(), 1e-9);
        assertTrue(share.width() < 0.1, share.toString());
    }

    @Test
    void aMissingRequiredInputWithNoPolicyIsNotDeterminable() {
        Calculator calculator = pooledCohort();
        Bindings bindings = referencePatient(MALE, WHITE).bind(DIABETES, new Binding.Clarify("diabetes is not in the note")).build();
        Evaluation evaluation = Evaluation.of(calculator, bindings);
        assertFalse(evaluation.determinable());
        assertEquals(Evaluation.Status.NOT_DETERMINED, evaluation.input(DIABETES).get().status());
        assertTrue(evaluation.findings().anySatisfy(f -> f.message().contains("diabetes is not in the note")),
                evaluation.findings().toString());
    }

    @Test
    void aMissingRaceIsAssumedByTheCalculatorsPolicyAndReported() {
        Calculator calculator = pooledCohort();
        Bindings bindings = referencePatient(MALE, WHITE).bind(RACE, new Binding.Missing()).build();
        Evaluation evaluation = Evaluation.of(calculator, bindings);
        assertEquals(WHITE_MALE, evaluation.stratum().get().concept().getAsLong());
        assertEquals(Evaluation.Status.ASSUMED, evaluation.input(RACE).get().status());
        assertTrue(evaluation.interim());
        assertEquals(0.05378606, evaluation.share().get().lower(), 1e-7);
    }

    @Test
    void anAssumptionIsPreferredToTheObservedStatementAndNeverChangesIt() {
        Calculator calculator = pooledCohort();
        Bindings bindings = referencePatient(MALE, WHITE)
                .assume(SBP, Binding.Supplied.assumed(Bounds.point(140), MMHG)).build();
        Evaluation evaluation = Evaluation.of(calculator, bindings);
        Evaluation.InputState sbp = evaluation.input(SBP).get();
        assertEquals(Evaluation.Status.ASSUMED, sbp.status());
        assertEquals(140, sbp.bounds().get().lower());
        assertTrue(evaluation.share().get().lower() > 0.05378606);
        // the observed statement is still what the block states
        assertTrue(bindings.stated(SBP).get() instanceof Binding.Observed observed && observed.bounds().lower() == 120);
    }

    @Test
    void aValueOutsideTheDomainIsAFindingNotARefusal() {
        Calculator calculator = pooledCohort();
        Bindings bindings = referencePatient(MALE, WHITE)
                .bind(AGE, Binding.Observed.measure(Bounds.point(35), YEAR, STATEMENT)).build();
        Evaluation evaluation = Evaluation.of(calculator, bindings);
        assertTrue(evaluation.determinable());
        assertEquals(Evaluation.Status.OUT_OF_DOMAIN, evaluation.input(AGE).get().status());
        assertTrue(evaluation.findings().anySatisfy(f -> f.message().contains("outside the domain")));
    }

    @Test
    void aSemanticTheInputDoesNotAdmitIsNotAdmitted() {
        Calculator calculator = pooledCohort();
        Bindings bindings = referencePatient(MALE, WHITE)
                .bind(SBP, Binding.Observed.measure(Bounds.point(16), YEAR, STATEMENT)).build();
        Evaluation evaluation = Evaluation.of(calculator, bindings);
        assertFalse(evaluation.determinable());
        assertEquals(Evaluation.Status.NOT_ADMITTED, evaluation.input(SBP).get().status());
    }

    @Test
    void theSweepDrawsTheMissingInputsSolidAndThePointReadingsHollow() {
        Calculator calculator = pooledCohort();
        Bindings bindings = referencePatient(MALE, WHITE)
                .bind(SMOKER, new Binding.Missing())
                .bind(HDL, new Binding.Missing()).build();
        CalculatorSensitivity sensitivity = CalculatorSensitivity.of(Evaluation.of(calculator, bindings));
        assertFalse(sensitivity.rows().isEmpty());
        // the missing inputs swing over their plausible ranges, solid, and each swing spans the result's own bounds
        CalculatorSensitivity.Row smoker = sensitivity.row(SMOKER).get();
        CalculatorSensitivity.Row hdl = sensitivity.row(HDL).get();
        assertTrue(smoker.solid());
        assertTrue(hdl.solid());
        assertEquals(Bounds.of(0, 1), smoker.swing());
        assertEquals(Bounds.of(35, 70), hdl.swing());
        assertTrue(smoker.width() > 3, "a smoker of 55 moves the ten-year risk by several points: " + smoker.width());
        // a point reading swings hollow: the declared range for the pressure, a tenth either way for the age
        CalculatorSensitivity.Row sbp = sensitivity.row(SBP).get();
        assertFalse(sbp.solid());
        assertEquals(Bounds.of(100, 160), sbp.swing());
        CalculatorSensitivity.Row age = sensitivity.row(AGE).get();
        assertFalse(age.solid());
        assertEquals(49.5, age.swing().lower(), 1e-9);
        assertEquals(60.5, age.swing().upper(), 1e-9);
        // a concept-valued input has no row; a presence observed as a point swings zero to one, hollow
        assertTrue(sensitivity.row(SEX).isEmpty());
        CalculatorSensitivity.Row diabetes = sensitivity.row(DIABETES).get();
        assertFalse(diabetes.solid());
        assertEquals(Bounds.of(0, 1), diabetes.swing());
        // the rows are ordered by how far they move the result
        for (int i = 1; i < sensitivity.rows().size(); i++) {
            assertTrue(sensitivity.rows().get(i - 1).width() >= sensitivity.rows().get(i).width());
        }
    }
}
