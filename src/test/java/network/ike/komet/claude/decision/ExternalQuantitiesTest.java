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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import org.junit.jupiter.api.Test;

/**
 * A tree that reads a quantity another block computes ({@code dev-calculator-blocks}): with no
 * external the read is not determinable and the choice stays open; with the calculator's risk
 * supplied as an external the parameter appears in memory, with no line, and the statin decision
 * rolls back. The text never carries the number.
 */
class ExternalQuantitiesTest {

    static final DecisionTerms T = DecisionTerms.synthetic();

    static final String STATIN = """
            decision: k:StartStatin[Start a statin?] @id=start-statin
              parameter = [0.25,0.25] k:Rrr[Relative risk reduction] @id=rrr basis=cited
              parameter = k:RiskLeft[Risk left on a statin] @id=risk-left from Complement of k:Rrr[] @id=rrr
              parameter = k:RiskOnStatin[Risk on a statin] @id=risk-on-statin from Product of k:RiskLeft[] @id=risk-left k:Risk[] @id=risk
              alternative: k:Statin[statin] @id=statin
                money = [1500,1500] k:Usd[US dollar] @id=usd basis=estimated
                health = [0.05,0.05] k:Qaly[QALY] @id=qaly basis=estimated
                chance: k:Event[ASCVD event in 10 years] @id=event
                  event: k:Occurs[event] @id=occurs
                    p = from k:RiskOnStatin[] @id=risk-on-statin
                    money = [60000,60000] k:Usd[US dollar] @id=usd basis=estimated
                    health = [2,2] k:Qaly[QALY] @id=qaly basis=estimated
                  residual: k:NoEvent[no event] @id=no-event
                    outcome: k:Well[well] @id=well
              alternative: k:NoStatin[no statin] @id=no-statin
                chance: k:Event[ASCVD event in 10 years] @id=event-2
                  event: k:Occurs[event] @id=occurs-2
                    p = from k:Risk[10-year ASCVD risk] @id=risk
                    money = [60000,60000] k:Usd[US dollar] @id=usd basis=estimated
                    health = [2,2] k:Qaly[QALY] @id=qaly basis=estimated
                  residual: k:NoEvent[no event] @id=no-event-2
                    outcome: k:Well[well] @id=well-2
            """;

    @Test
    void withoutTheExternalTheReadIsNotDeterminable() {
        BlockIdentities ids = BlockIdentities.storeFree();
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(STATIN, T, ids);
        DecisionTree tree = parsed.tree().get();
        Rollback rollback = Rollback.of(tree, coordinate(ids));
        assertFalse(rollback.determinable());
        assertTrue(rollback.findings().anySatisfy(f -> f.message().contains("has no parameter for")), rollback.findings().toString());
    }

    @Test
    void theCalculatorsRiskArrivesAsAParameterWithNoLine() {
        BlockIdentities ids = BlockIdentities.storeFree();
        // the identities are shared across blocks, so the calculator has already named the symbol
        long risk = ids.local("risk", "10-year ASCVD risk").getAsLong();
        ExternalQuantities externals = quantity -> quantity == risk
                ? Optional.of(new ExternalQuantities.Value(Bounds.of(0.076, 0.142), T.shareAtNode()))
                : Optional.empty();
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(STATIN, T, ids, externals);
        DecisionTree tree = parsed.tree().get();
        Optional<Parameter> injected = tree.nodesOfType(Parameter.class).detectOptional(p -> p.quantity().getAsLong() == risk);
        assertTrue(injected.isPresent());
        assertTrue(parsed.lineOf(injected.get().vertexIndex()).isEmpty(), "an external parameter has no line to edit");
        assertEquals(Bounds.of(0.076, 0.142), injected.get().measure().bounds());
        assertEquals(0, tree.check().size(), () -> tree.check().toString());

        Rollback rollback = Rollback.of(tree, coordinate(ids));
        assertTrue(rollback.determinable(), () -> rollback.findings().toString());
        // 7.6–14.2 % straddles the break-even near 10 %, so the choice is left open
        assertTrue(rollback.chosen(tree.rootIndex()).isEmpty(), "the risk interval straddles the break-even");
        // and the text is untouched: the parameter exists only in memory
        assertFalse(DecisionTreeText.body(tree, ids).isEmpty());
        assertFalse(STATIN.contains("0.076"));
    }

    @Test
    void aPointRiskClosesTheChoice() {
        BlockIdentities ids = BlockIdentities.storeFree();
        long risk = ids.local("risk", "10-year ASCVD risk").getAsLong();
        ExternalQuantities low = quantity -> quantity == risk
                ? Optional.of(new ExternalQuantities.Value(Bounds.point(0.05), T.shareAtNode())) : Optional.empty();
        DecisionTree tree = DecisionTreeText.parse(STATIN, T, ids, low).tree().get();
        Rollback rollback = Rollback.of(tree, coordinate(ids));
        assertTrue(rollback.determinable());
        assertTrue(rollback.chosen(tree.rootIndex()).isPresent());
        long noStatin = ids.nid("no-statin").getAsLong();
        assertEquals(noStatin, tree.node(rollback.chosen(tree.rootIndex()).getAsInt()).get().concept().getAsLong());
    }

    private static ValuationCoordinate coordinate(BlockIdentities ids) {
        return ValuationCoordinate.pricingHealthAt(Bounds.point(100_000), ids.local("usd", "US dollar").getAsLong(),
                ids.local("qaly", "QALY").getAsLong());
    }
}
