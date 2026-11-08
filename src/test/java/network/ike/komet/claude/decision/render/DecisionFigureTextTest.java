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
package network.ike.komet.claude.decision.render;

import network.ike.komet.claude.stamp.DecisionFigureText;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The numbers a figure prints carry no more figures than they earn. */
class DecisionFigureTextTest {

    @Test
    @DisplayName("A computed number is held to three significant figures, money to the whole unit")
    void computedNumbersAreHeld() {
        assertEquals("27,314", DecisionFigureText.shown(27_313.9594, true, true));
        assertEquals("1.82", DecisionFigureText.shown(1.8209, true, false));
        assertEquals("0.0841", DecisionFigureText.shown(0.08411214953271028, true, false));
        assertEquals("0.00254", DecisionFigureText.shown(0.002542, true, false));
        assertEquals("0.182", DecisionFigureText.shown(0.18209, true, false));
        assertEquals("150,000", DecisionFigureText.shown(150_000, true, true));
        assertEquals("20", DecisionFigureText.shown(20, true, false));
    }

    @Test
    @DisplayName("A stated number prints as the block wrote it, no wider")
    void statedNumbersPrintAsWritten() {
        assertEquals("0.02", DecisionFigureText.shown(0.02, false, false));
        assertEquals("0.002", DecisionFigureText.shown(0.002, false, false));
        assertEquals("150,000", DecisionFigureText.shown(150_000, false, false));
        assertEquals("700", DecisionFigureText.shown(700, false, true));
        assertEquals("0.9", DecisionFigureText.shown(0.9, false, false));
        assertEquals("?", DecisionFigureText.shown(Double.NaN, false, false));
    }

    @Test
    @DisplayName("A value at a step's resolution has the step's decimals, free of binary noise")
    void atStep() {
        assertEquals("0.35", DecisionFigureText.atStep(0.1 + 0.25, 0.05));
        assertEquals("0.3", DecisionFigureText.atStep(0.1 + 0.2, 0.1));
        assertEquals("1,200", DecisionFigureText.atStep(1_200.0000001, 10));
        assertEquals("86,392", DecisionFigureText.atStep(86_392.4, 100), "the decimals, not a rounding to the step");
        assertEquals("0.0200", DecisionFigureText.atStep(0.02, 0.0001));
    }

    @Test
    @DisplayName("A swept quantity prints with its unit: a share as a percentage, money after its symbol, the rest with a name")
    void quantities() {
        assertEquals("1.75%", DecisionFigureText.percent(0.017463));
        assertEquals("90%", DecisionFigureText.percent(0.9));
        assertEquals("0.5%", DecisionFigureText.percent(0.005));
        assertEquals("$6,441", DecisionFigureText.quantity(6_441.2, DecisionFigureText.Unit.MONEY, "$"));
        assertEquals("\u2212$1,200", DecisionFigureText.quantity(-1_200, DecisionFigureText.Unit.MONEY, "$"));
        assertEquals("350", DecisionFigureText.quantity(350, DecisionFigureText.Unit.MONEY, ""));
        assertEquals("9 QALY", DecisionFigureText.quantity(9.0, DecisionFigureText.Unit.PLAIN, "QALY"));
        assertEquals("18", DecisionFigureText.quantity(18.0066, DecisionFigureText.Unit.PLAIN, ""));
        assertEquals("74.8%", DecisionFigureText.quantity(0.7475, DecisionFigureText.Unit.SHARE, ""));
        assertEquals("21.4%", DecisionFigureText.percent(0.214, false), "a stated share as written");
        assertEquals("0.2%", DecisionFigureText.percent(0.002, false));
        assertEquals("$", DecisionFigureText.symbolFor("US dollar"));
        assertEquals("", DecisionFigureText.symbolFor("QALY"));
        assertEquals("$700", DecisionFigureText.chip(700, 700, false, false, false, DecisionFigureText.Unit.MONEY, "$"));
        assertEquals("[$600, $800]", DecisionFigureText.chip(600, 800, false, false, false, DecisionFigureText.Unit.MONEY, "$"));
        assertEquals("0.002 QALY", DecisionFigureText.chip(0.002, 0.002, false, false, false, DecisionFigureText.Unit.PLAIN, "QALY"));
        assertEquals("[20%, 30%]", DecisionFigureText.chip(0.2, 0.3, false, false, false, DecisionFigureText.Unit.SHARE, ""));
    }

    @Test
    @DisplayName("Axis ticks are round: one, two or five times a power of ten, zero among them when it is in range")
    void ticks() {
        assertEquals(java.util.List.of(0.0, 5_000.0, 10_000.0, 15_000.0), DecisionFigureTornado.ticks(-1_200, 15_300, 320));
        assertEquals(java.util.List.of(0.0, 20_000.0, 40_000.0, 60_000.0, 80_000.0), DecisionFigureTornado.ticks(0, 91_087, 400));
        assertEquals(java.util.List.of(0.2, 0.4, 0.6, 0.8, 1.0), DecisionFigureTornado.ticks(0.1, 1.0, 400));
    }
}
