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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What the choice at the root trades: the alternative the rollback chose against the next best,
 * with money and health kept apart, read off the {@link Ledger}: the expected money and the
 * expected healthy life lost along the course the rollback chose beneath each, folded without
 * pricing one into the other, and every counted unit beside them. The chosen alternative then either dominates, costing no more money and
 * losing no more health, or buys health with money at a price per healthy year, or saves money
 * by giving health up at one. That price is the one at which the two alternatives tie, which is
 * where the tornado's bar for the price of a healthy year flips, and it is how a reader who does
 * not share the block's price can still read the tree: a healthy year bought at $86,000 is a
 * plain fact, where "scanning is cheaper" holds only at a price.
 *
 * @param chosen   the alternative the rollback chose at the root
 * @param nextBest the other alternative with the least priced cost
 * @param money    how much more money the chosen alternative costs than the next best, per case; negative when less
 * @param health   how much more healthy life the chosen alternative keeps than the next best, per case; negative when less
 * @param priced   the price of a healthy year the tree was rolled back at
 * @param counts   each counted unit, per case, under the chosen alternative and under the next best
 */
public record Tradeoff(int chosen, int nextBest, double money, double health, double priced, List<CountTrade> counts) {

    /** How a counted unit stands between the chosen alternative and the next best, per case. */
    public record CountTrade(long unit, double chosen, double other) {
        /** How many more of the unit the chosen alternative brings; negative when fewer. */
        public double more() {
            return chosen - other;
        }
    }

    /** Whether the chosen alternative brings more of any counted unit than the next best. */
    public boolean countedAgainst() {
        return counts.stream().anyMatch(trade -> trade.more() > 1e-9);
    }

    /** How the chosen alternative stands against the next best. */
    public enum Kind {
        /** No more money, no less health: it wins at any price. */
        DOMINATES,
        /** More money for more health. */
        BUYS_HEALTH,
        /** Less money for less health. */
        SAVES_MONEY,
        /** More money and less health: it should not have been chosen, and is not, unless the costs tie. */
        DOMINATED
    }

    public Kind kind() {
        boolean dearer = money > Bounds.EPSILON;
        boolean healthier = health > Bounds.EPSILON;
        boolean cheaper = money < -Bounds.EPSILON;
        boolean sicker = health < -Bounds.EPSILON;
        if (!dearer && !sicker && !countedAgainst()) {
            return Kind.DOMINATES;
        }
        if (!dearer && !sicker) {
            return Kind.SAVES_MONEY;
        }
        if (dearer && healthier) {
            return Kind.BUYS_HEALTH;
        }
        if (cheaper && sicker) {
            return Kind.SAVES_MONEY;
        }
        return Kind.DOMINATED;
    }

    /**
     * The price of a healthy year at which the two alternatives tie: money over health, the cost
     * of a healthy year bought or the saving per healthy year given up. NaN when nothing is traded.
     */
    public double pricePerHealthyYear() {
        return kind() == Kind.BUYS_HEALTH || kind() == Kind.SAVES_MONEY ? Math.abs(money / health) : Double.NaN;
    }

    /** The trade at the root of a tree rolled back under a coordinate; empty when the root did not decide or is alone. */
    public static Optional<Tradeoff> of(DecisionTree tree, Rollback base) {
        Optional<Ledger> ledger = Ledger.of(tree, base);
        OptionalInt chosen = base.chosen(tree.rootIndex());
        if (ledger.isEmpty() || chosen.isEmpty()) {
            return Optional.empty();
        }
        double price = base.coordinate().healthPrice().midpoint();
        int nextBest = -1;
        double nextCost = Double.POSITIVE_INFINITY;
        for (Ledger.Entry entry : ledger.get().entries()) {
            if (entry.alternativeVertex() == chosen.getAsInt()) {
                continue;
            }
            Optional<Bounds> cost = base.value(entry.alternativeVertex());
            if (cost.isPresent() && cost.get().midpoint() < nextCost) {
                nextCost = cost.get().midpoint();
                nextBest = entry.alternativeVertex();
            }
        }
        Optional<Ledger.Entry> chosenEntry = ledger.get().entry(chosen.getAsInt());
        Optional<Ledger.Entry> otherEntry = nextBest < 0 ? Optional.empty() : ledger.get().entry(nextBest);
        if (chosenEntry.isEmpty() || otherEntry.isEmpty()) {
            return Optional.empty();
        }
        List<CountTrade> counts = new ArrayList<>();
        for (long unit : ledger.get().units()) {
            counts.add(new CountTrade(unit, chosenEntry.get().count(unit), otherEntry.get().count(unit)));
        }
        return Optional.of(new Tradeoff(chosen.getAsInt(), nextBest, chosenEntry.get().money() - otherEntry.get().money(),
                otherEntry.get().health() - chosenEntry.get().health(), price, List.copyOf(counts)));
    }
}
