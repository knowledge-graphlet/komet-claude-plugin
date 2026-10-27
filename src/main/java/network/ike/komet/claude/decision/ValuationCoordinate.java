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

import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The standpoint a decision tree is rolled back from: one more constituent of the view beside
 * the stamp, language, logic, navigation and edit coordinates ({@code Valuation coordinate
 * properties} in the IKE starter set; settled KEC 2026-10-27). Chosen at evaluation, never stored
 * on the tree; a decision record stores the coordinate it was rolled back under.
 *
 * <p>The first rollback applies the health price and reads costs on the two semantics named
 * here, refusing a cost on any other. Perspective, price year, discounting and horizon are
 * carried so a record can state them; bringing a cost from another price year or discounting a
 * cost by the year it falls in waits on measures that carry a year.
 *
 * @param perspective    whose costs count, as a concept, when stated
 * @param priceYear      the year money is brought to, when stated
 * @param discounting    the yearly rate later costs are reduced by, zero for none
 * @param horizonYears   how far ahead consequences are counted, or infinity
 * @param healthPrice    the price of one unit of healthy life, in the money semantic's units
 * @param moneySemantic  the money semantic every money cost must be on
 * @param healthSemantic the health semantic every health cost must be on
 */
public record ValuationCoordinate(OptionalLong perspective, OptionalInt priceYear, double discounting,
                                  double horizonYears, Bounds healthPrice, long moneySemantic, long healthSemantic) {

    /** A coordinate that prices health and names the two cost semantics, with nothing else stated. */
    public static ValuationCoordinate pricingHealthAt(Bounds healthPrice, long moneySemantic, long healthSemantic) {
        return new ValuationCoordinate(OptionalLong.empty(), OptionalInt.empty(), 0, Double.POSITIVE_INFINITY,
                healthPrice, moneySemantic, healthSemantic);
    }

    /** The same coordinate at another health price: how a tree is read at several prices. */
    public ValuationCoordinate withHealthPrice(Bounds price) {
        return new ValuationCoordinate(perspective, priceYear, discounting, horizonYears, price, moneySemantic, healthSemantic);
    }
}
