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

import java.util.Optional;
import network.ike.komet.claude.decision.Bounds;
import org.junit.jupiter.api.Test;

/** A what-if on a calculation is one assume line, added, replaced or removed; nothing else moves. */
class CalculationEditsTest {

    static final String BODY = """
            calculate: k:Pce[pce] @id=pce
              bind = k:Age[age] @id=age anf:stmt-age by latest
              bind = k:Sbp[sbp] @id=sbp anf:stmt-sbp
              bind = k:Hdl[hdl] @id=hdl missing
            """;

    @Test
    void anAssumptionIsAppendedAfterTheBindings() {
        Optional<String> edited = CalculationEdits.withAssumption(BODY, "sbp", Bounds.point(130), Optional.of("k:Mmhg[mmHg] @id=mmhg"));
        assertEquals(BODY + "  assume = k:Sbp[sbp] @id=sbp [130,130] k:Mmhg[mmHg] @id=mmhg\n", edited.get());
    }

    @Test
    void aSecondAssumptionForTheSameInputReplacesTheFirst() {
        String once = CalculationEdits.withAssumption(BODY, "sbp", Bounds.point(130), Optional.empty()).get();
        String twice = CalculationEdits.withAssumption(once, "sbp", Bounds.point(140), Optional.empty()).get();
        assertEquals(BODY + "  assume = k:Sbp[sbp] @id=sbp [140,140]\n", twice);
        assertEquals(BODY, CalculationEdits.withoutAssumption(twice, "sbp"));
    }

    @Test
    void anInputNoLineBindsCannotBeAssumed() {
        assertTrue(CalculationEdits.withAssumption(BODY, "smoker", Bounds.point(1), Optional.empty()).isEmpty());
    }

    @Test
    void theRestOfTheBlockIsUntouched() {
        String withOther = BODY + "  assume = k:Hdl[hdl] @id=hdl [45,45]\n";
        String edited = CalculationEdits.withAssumption(withOther, "sbp", Bounds.point(130), Optional.empty()).get();
        assertEquals(withOther + "  assume = k:Sbp[sbp] @id=sbp [130,130]\n", edited);
    }
}
