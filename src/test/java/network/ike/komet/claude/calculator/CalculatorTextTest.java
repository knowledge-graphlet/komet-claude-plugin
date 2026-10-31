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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.decision.Bounds;
import org.junit.jupiter.api.Test;

/**
 * The calculator and calculate blocks in text: the Pooled Cohort calculator emits, parses back to
 * the same vertices with the same identities and evaluates to the same shares; a block written by
 * hand with symbolic identities reads store-free; the calculate block round-trips its bindings.
 */
class CalculatorTextTest {

    static final CalculatorTerms T = PooledCohortTest.T;

    /** Identities backed by a map: every concept of the Pooled Cohort fixture, by a symbol. */
    static final class MapIdentities implements Identities {
        private final Map<String, Long> nids = new HashMap<>();
        private final Map<Long, String> identifiers = new HashMap<>();

        MapIdentities() {
            add("pce", PooledCohortTest.CALCULATOR);
            add("ascvd-risk", PooledCohortTest.OUTPUT_TOPIC);
            add("pct-pce-2013", PooledCohortTest.PCT_SEMANTIC);
            long[] inputs = {PooledCohortTest.AGE, PooledCohortTest.SEX, PooledCohortTest.RACE, PooledCohortTest.SBP,
                    PooledCohortTest.TREATED, PooledCohortTest.TC, PooledCohortTest.HDL, PooledCohortTest.DIABETES,
                    PooledCohortTest.SMOKER};
            String[] names = {"age", "sex", "race", "sbp", "treated", "tc", "hdl", "diabetes", "smoker"};
            for (int i = 0; i < inputs.length; i++) {
                add(names[i], inputs[i]);
            }
            add("year", PooledCohortTest.YEAR);
            add("mmhg", PooledCohortTest.MMHG);
            add("mg-per-dl", PooledCohortTest.MG_PER_DL);
            add("mmol-per-l", PooledCohortTest.MMOL_PER_L);
            add("presence", PooledCohortTest.PRESENCE);
            add("male", PooledCohortTest.MALE);
            add("female", PooledCohortTest.FEMALE);
            add("white", PooledCohortTest.WHITE);
            add("african-american", PooledCohortTest.AFRICAN_AMERICAN);
            for (long nid = 541; nid <= 555; nid++) {
                add("t" + nid, nid);
            }
            add("s0", PooledCohortTest.S0);
            add("mean", PooledCohortTest.MEAN);
            add("white-female", PooledCohortTest.WHITE_FEMALE);
            add("aa-female", PooledCohortTest.AFRICAN_AMERICAN_FEMALE);
            add("white-male", PooledCohortTest.WHITE_MALE);
            add("aa-male", PooledCohortTest.AFRICAN_AMERICAN_MALE);
            add("low", PooledCohortTest.LOW);
            add("borderline", PooledCohortTest.BORDERLINE);
            add("intermediate", PooledCohortTest.INTERMEDIATE);
            add("high", PooledCohortTest.HIGH);
            add("goff2013", PooledCohortTest.GOFF);
            add("grundy2018", PooledCohortTest.GRUNDY);
            add("stmt-age", 601);
            add("stmt-sbp", 602);
        }

        private void add(String identifier, long nid) {
            nids.put(identifier, nid);
            identifiers.put(nid, identifier);
        }

        @Override
        public OptionalLong nid(String identifier) {
            Long nid = nids.get(identifier);
            return nid == null ? OptionalLong.empty() : OptionalLong.of(nid);
        }

        @Override
        public Optional<String> identifier(long nid) {
            return Optional.ofNullable(identifiers.get(nid));
        }

        @Override
        public String label(long nid) {
            return identifiers.getOrDefault(nid, "");
        }
    }

    static final Identities IDS = new MapIdentities();

    @Test
    void theCalculatorRoundTripsByteStableWithItsIdentities() {
        Calculator original = PooledCohortTest.pooledCohort();
        String text = CalculatorText.toAdoc(original, IDS);
        assertTrue(text.startsWith("[calculator]\n----\ncalculator: k:Pce[pce] @id=pce source=goff2013 @vertex="), text);
        assertTrue(text.contains("  input: k:Hdl[hdl] @id=hdl in k:MgPerDl[mg-per-dl] @id=mg-per-dl domain [20,100] when-missing=plausible @vertex="), text);
        assertTrue(text.contains("    missing = [35,70] @vertex="), text);
        assertTrue(text.contains("  term = k:T541[t541] @id=t541 from NaturalLogarithm of k:Age[age] @id=age @vertex="), text);
        assertTrue(text.contains("  term = k:T553[t553] @id=t553 from k:Smoker[smoker] @id=smoker @vertex="), text);
        assertTrue(text.contains("    when = k:Sex[sex] @id=sex k:Male[male] @id=male @vertex="), text);
        assertTrue(text.contains("    coefficient = [12.344,12.344] k:T541[t541] @id=t541 basis=cited source=goff2013 @vertex="), text);
        assertTrue(text.contains("  formula = from CoxSurvival of k:S0[s0] @id=s0 k:Mean[mean] @id=mean @vertex="), text);
        assertTrue(text.contains("  band: k:Low[low] @id=low when [0,5) source=grundy2018 @vertex="), text);

        CalculatorText.Parsed parsed = CalculatorText.parse(text, T, IDS);
        assertEquals(0, parsed.findings().size(), () -> parsed.findings().toString());
        Calculator reread = parsed.calculator().get();
        assertEquals(0, reread.check().size(), () -> reread.check().toString());
        assertEquals(original.source().vertexCount(), reread.source().vertexCount());
        assertEquals(text, CalculatorText.toAdoc(reread, IDS));

        for (int i = 0; i < original.source().vertexCount(); i++) {
            assertEquals(original.vertex(i).mostSignificantBits(), reread.vertex(i).mostSignificantBits());
            assertEquals(original.vertex(i).leastSignificantBits(), reread.vertex(i).leastSignificantBits());
        }
        Evaluation evaluation = Evaluation.of(reread, PooledCohortTest.referencePatient(PooledCohortTest.MALE, PooledCohortTest.WHITE).build());
        assertEquals(0.05378606, evaluation.share().get().lower(), 5e-8);
    }

    @Test
    void aHandWrittenBlockWithSymbolicIdentitiesReadsStoreFree() {
        String text = """
                [calculator]
                ----
                calculator: k:Simple[A one-input calculator] @id=simple source=paper
                  output = k:Risk[Risk] @id=risk in k:Pct[% over 10 years] @id=pct domain [0,100] scale=100
                  input: k:Age[Age] @id=age in k:Year[year] @id=year domain [40,79] required
                  term = k:LnAge[ln age] @id=ln-age from ln of k:Age[] @id=age
                  stratum: k:All[everyone] @id=all
                    coefficient = [1,1] k:LnAge[] @id=ln-age basis=cited source=paper
                    coefficient = [0.9,0.9] k:S0[baseline survival] @id=s0 basis=cited
                    coefficient = [5,5] k:Mean[mean] @id=mean basis=cited
                  formula = from CoxSurvival of k:S0[] @id=s0 k:Mean[] @id=mean
                  band: k:Low[low] @id=low when [0,10)
                  band: k:High[high] @id=high when [10,100]
                ----
                """;
        BlockIdentities ids = BlockIdentities.storeFree();
        CalculatorText.Parsed parsed = CalculatorText.parse(text, T, ids);
        assertEquals(1, parsed.findings().size(), () -> parsed.findings().toString());
        assertTrue(parsed.findings().get(0).message().contains("local to the block"));
        Calculator calculator = parsed.calculator().get();
        assertEquals(0, calculator.check().size(), () -> calculator.check().toString());
        long age = ids.nid("age").getAsLong();
        long year = ids.nid("year").getAsLong();
        Evaluation evaluation = Evaluation.of(calculator, Bindings.builder()
                .bind(age, Binding.Supplied.measure(Bounds.point(55), year)).build());
        // 1 − 0.9^exp(ln 55 − 5) = 1 − 0.9^(55/e^5), about 3.8 %
        double expected = 1 - Math.pow(0.9, 55 / Math.exp(5));
        assertEquals(expected, evaluation.share().get().lower(), 1e-12);
        assertEquals(1, evaluation.bands().size());
        assertEquals("low", ids.label(evaluation.bands().get(0).concept().getAsLong()));
    }

    @Test
    void aMalformedLineIsAFindingAndTheRestStillReads() {
        String text = """
                calculator: k:Simple[] @id=simple
                  output = k:Risk[] @id=risk in k:Pct[] @id=pct
                  input: k:Age[] @id=age in k:Year[] @id=year
                  mystery: k:X[] @id=x
                  term = k:LnAge[] @id=ln-age
                  stratum: k:All[] @id=all
                    coefficient = k:LnAge[] @id=ln-age
                  formula = from CoxSurvival of k:S0[] @id=s0 k:Mean[] @id=mean
                """;
        CalculatorText.Parsed parsed = CalculatorText.parse(text, T, BlockIdentities.storeFree());
        assertTrue(parsed.calculator().isPresent());
        assertTrue(parsed.findings().anySatisfy(f -> f.message().contains("unknown kind of vertex under a calculator: mystery")));
        assertTrue(parsed.findings().anySatisfy(f -> f.message().contains("a term is derived from a kind or reads a quantity after from")));
        assertTrue(parsed.findings().anySatisfy(f -> f.message().contains("a coefficient begins with its value")));
    }

    @Test
    void theCalculateBlockRoundTripsItsBindings() {
        Calculator calculator = PooledCohortTest.pooledCohort();
        String text = """
                [calculate]
                ----
                calculate: k:Pce[pce] @id=pce
                  bind = k:Age[age] @id=age anf:stmt-age by latest
                  bind = k:Sex[sex] @id=sex k:Male[male] @id=male
                  bind = k:Tc[tc] @id=tc [213,213] k:MgPerDl[mg-per-dl] @id=mg-per-dl
                  bind = k:Hdl[hdl] @id=hdl missing
                  bind = k:Smoker[smoker] @id=smoker clarify "smoking status is not in the note"
                  assume = k:Sbp[sbp] @id=sbp [130,130] k:Mmhg[mmhg] @id=mmhg
                ----
                """;
        CalculationText.Parsed parsed = CalculationText.parse(text, IDS);
        assertEquals(0, parsed.findings().size(), () -> parsed.findings().toString());
        Calculation calculation = parsed.calculation().get();
        assertEquals(PooledCohortTest.CALCULATOR, calculation.calculatorNid());
        assertEquals(6, calculation.lines().size());
        assertEquals(OptionalLong.of(601), calculation.statementFor(PooledCohortTest.AGE));
        assertEquals(130, calculation.assumption(PooledCohortTest.SBP).get().bounds().lower());

        String emitted = CalculationText.toAdoc(calculation, Optional.of(calculator), IDS);
        assertEquals(text, emitted);

        // the statement source reads the age statement; the rest bind as written
        Calculation.StatementSource source = (statementNid, input) -> statementNid == 601
                ? Optional.of(Binding.Observed.measure(Bounds.point(55), PooledCohortTest.YEAR, 601))
                : Optional.empty();
        Bindings bindings = calculation.bindings(calculator, source);
        assertTrue(bindings.stated(PooledCohortTest.AGE).get() instanceof Binding.Observed);
        assertTrue(bindings.stated(PooledCohortTest.HDL).get() instanceof Binding.Missing);
        assertTrue(bindings.stated(PooledCohortTest.SMOKER).get() instanceof Binding.Clarify);
        assertTrue(bindings.assumption(PooledCohortTest.SBP).isPresent());
        Binding.Supplied sex = (Binding.Supplied) bindings.stated(PooledCohortTest.SEX).get();
        assertEquals(PooledCohortTest.MALE, sex.conceptValue().getAsLong());

        Calculation moved = calculation.withAssumption(PooledCohortTest.SBP, Bounds.point(140), PooledCohortTest.MMHG);
        assertEquals(140, moved.assumption(PooledCohortTest.SBP).get().bounds().lower());
        assertEquals(6, moved.lines().size());
        assertTrue(moved.withoutAssumption(PooledCohortTest.SBP).assumption(PooledCohortTest.SBP).isEmpty());
    }
}
