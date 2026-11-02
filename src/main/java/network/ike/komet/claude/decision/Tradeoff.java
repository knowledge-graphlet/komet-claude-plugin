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

import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionElement.Branch;
import network.ike.komet.claude.decision.DecisionElement.Chance;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.Decision;
import network.ike.komet.claude.decision.DecisionElement.Event;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Node;
import network.ike.komet.claude.decision.DecisionElement.Outcome;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What the choice at the root trades: the alternative the rollback chose against the next best,
 * with money and health kept apart: the expected money and the expected healthy life lost along
 * the course the rollback chose beneath each, folded without pricing one into the other, so a
 * nested decision keeps the choice it made at the block's price. The chosen alternative then either dominates, costing no more money and
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
 */
public record Tradeoff(int chosen, int nextBest, double money, double health, double priced) {

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
        if (!dearer && !sicker) {
            return Kind.DOMINATES;
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
        Optional<Decision> root = tree.decision();
        OptionalInt chosen = base.chosen(tree.rootIndex());
        if (root.isEmpty() || chosen.isEmpty()) {
            return Optional.empty();
        }
        double price = base.coordinate().healthPrice().midpoint();
        int nextBest = -1;
        double nextCost = Double.POSITIVE_INFINITY;
        for (Alternative other : root.get().alternatives()) {
            if (other.vertexIndex() == chosen.getAsInt()) {
                continue;
            }
            Optional<Bounds> cost = base.value(other.vertexIndex());
            if (cost.isPresent() && cost.get().midpoint() < nextCost) {
                nextCost = cost.get().midpoint();
                nextBest = other.vertexIndex();
            }
        }
        if (nextBest < 0) {
            return Optional.empty();
        }
        Optional<double[]> chosenExpected = expected(tree, base, chosen.getAsInt());
        Optional<double[]> otherExpected = expected(tree, base, nextBest);
        if (chosenExpected.isEmpty() || otherExpected.isEmpty()) {
            return Optional.empty();
        }
        double money = chosenExpected.get()[0] - otherExpected.get()[0];
        double health = otherExpected.get()[1] - chosenExpected.get()[1];
        return Optional.of(new Tradeoff(chosen.getAsInt(), nextBest, money, health, price));
    }

    /**
     * The expected money and the expected health lost from a node on, along the course the
     * rollback chose at every decision beneath it, kept apart rather than priced together: the
     * costs a leaf or a branch carries, a chance's events weighted by their shares, a decision's
     * chosen alternative. Empty where a share or a cost is not determinable, or a decision is open.
     */
    static Optional<double[]> expected(DecisionTree tree, Rollback base, int vertexIndex) {
        Optional<DecisionElement> element = tree.node(vertexIndex);
        if (element.isEmpty() || !(element.get() instanceof Node node)) {
            return Optional.empty();
        }
        return switch (node) {
            case Decision decision -> {
                OptionalInt chosen = base.chosen(decision.vertexIndex());
                yield chosen.isPresent() ? expected(tree, base, chosen.getAsInt()) : Optional.empty();
            }
            case Chance chance -> {
                Map<Event, Double> shares = Cohort.shares(chance, base);
                double money = 0;
                double health = 0;
                for (Event event : chance.events()) {
                    Double share = shares.get(event);
                    Optional<double[]> ahead = expected(tree, base, event.vertexIndex());
                    if (share == null || ahead.isEmpty()) {
                        yield Optional.empty();
                    }
                    money += share * ahead.get()[0];
                    health += share * ahead.get()[1];
                }
                yield Optional.of(new double[] {money, health});
            }
            case Branch branch -> {
                Optional<double[]> own = costs(base, branch.costs());
                Optional<Node> next = branch.next();
                if (own.isEmpty()) {
                    yield Optional.empty();
                }
                if (next.isEmpty()) {
                    yield own;
                }
                Optional<double[]> ahead = expected(tree, base, next.get().vertexIndex());
                yield ahead.map(a -> new double[] {own.get()[0] + a[0], own.get()[1] + a[1]});
            }
            case Outcome outcome -> costs(base, outcome.costs());
        };
    }

    private static Optional<double[]> costs(Rollback base, ImmutableList<Cost> costs) {
        double money = 0;
        double health = 0;
        for (Cost cost : costs) {
            Optional<Bounds> bounds = base.derivations().bounds(cost.vertexIndex());
            if (bounds.isEmpty()) {
                return Optional.empty();
            }
            if (cost instanceof MoneyCost) {
                money += bounds.get().midpoint();
            } else {
                health += bounds.get().midpoint();
            }
        }
        return Optional.of(new double[] {money, health});
    }
}
