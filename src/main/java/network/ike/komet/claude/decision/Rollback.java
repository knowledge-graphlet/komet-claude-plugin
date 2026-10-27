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

import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionElement.Branch;
import network.ike.komet.claude.decision.DecisionElement.Chance;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.Decision;
import network.ike.komet.claude.decision.DecisionElement.Event;
import network.ike.komet.claude.decision.DecisionElement.HealthCost;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Node;
import network.ike.komet.claude.decision.DecisionElement.Outcome;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.IntIntMaps;
import org.eclipse.collections.api.factory.primitive.IntObjectMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.primitive.MutableIntIntMap;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The rollback of a decision tree under a valuation coordinate: the expected cost still ahead at
 * every node, the alternative chosen at every decision, computed from the leaves to the root and
 * never stored. At an outcome the cost is what the leaf carries; at a branch, what it incurs plus
 * what follows; at a chance, each event's probability times what follows it, summed, the residual
 * event taking one minus the others; at a decision, the least of its alternatives. Health is
 * priced into money by the coordinate's health price, so every value is on the coordinate's money
 * semantic. Costs are minimized; a benefit is a negative cost.
 *
 * <p>Every value is bounds, by interval arithmetic over the measures' bounds. A decision is
 * <em>decided</em> only when one alternative is no greater than every other across their bounds;
 * otherwise its value is still bounded but its choice is left open. A node whose inputs are
 * missing or mis-measured is <em>not determinable</em>: it has no value, the finding says why, and
 * every node above it is not determinable too. The rollback never refuses the tree.
 */
public final class Rollback {

    private final DecisionTree tree;
    private final ValuationCoordinate coordinate;
    private final MutableIntObjectMap<Bounds> values = IntObjectMaps.mutable.empty();
    private final MutableIntIntMap chosen = IntIntMaps.mutable.empty();
    private final MutableList<ProfileFinding> findings = Lists.mutable.empty();

    private Rollback(DecisionTree tree, ValuationCoordinate coordinate) {
        this.tree = tree;
        this.coordinate = coordinate;
    }

    /** Rolls a tree back under a coordinate. */
    public static Rollback of(DecisionTree tree, ValuationCoordinate coordinate) {
        Rollback rollback = new Rollback(tree, coordinate);
        Optional<Decision> root = tree.decision();
        if (root.isEmpty()) {
            rollback.findings.add(ProfileFinding.ofTree("the root is not a decision"));
        } else {
            rollback.value(root.get());
        }
        return rollback;
    }

    public DecisionTree tree() {
        return tree;
    }

    public ValuationCoordinate coordinate() {
        return coordinate;
    }

    /** The expected cost still ahead at a node, on the coordinate's money semantic; empty when not determinable. */
    public Optional<Bounds> value(int vertexIndex) {
        return Optional.ofNullable(values.get(vertexIndex));
    }

    /** The expected cost at the root. */
    public Optional<Bounds> rootValue() {
        return value(tree.rootIndex());
    }

    /** The alternative chosen at a decision, when one is no greater than every other across their bounds. */
    public OptionalInt chosen(int decisionIndex) {
        return chosen.containsKey(decisionIndex) ? OptionalInt.of(chosen.get(decisionIndex)) : OptionalInt.empty();
    }

    /** Why a node is not determinable, or what the rollback had to leave open. */
    public ImmutableList<ProfileFinding> findings() {
        return findings.toImmutable();
    }

    /** Whether the root has a value. */
    public boolean determinable() {
        return rootValue().isPresent();
    }

    // ── The fold ─────────────────────────────────────────────────────────────

    private Optional<Bounds> value(Node node) {
        Optional<Bounds> value = switch (node) {
            case Decision decision -> decide(decision);
            case Chance chance -> expect(chance);
            case Branch branch -> follow(branch);
            case Outcome outcome -> costs(outcome.vertexIndex(), outcome.costs());
        };
        value.ifPresent(bounds -> values.put(node.vertexIndex(), bounds));
        return value;
    }

    private Optional<Bounds> decide(Decision decision) {
        ImmutableList<Alternative> alternatives = decision.alternatives();
        if (alternatives.isEmpty()) {
            return notDeterminable(decision.vertexIndex(), "a decision has no alternative");
        }
        MutableList<Bounds> costs = Lists.mutable.empty();
        boolean determinable = true;
        for (Alternative alternative : alternatives) {
            Optional<Bounds> cost = value(alternative);
            if (cost.isEmpty()) {
                determinable = false;
            } else {
                costs.add(cost.get());
            }
        }
        if (!determinable) {
            return notDeterminable(decision.vertexIndex(), "an alternative is not determinable");
        }
        Bounds least = costs.get(0);
        for (int i = 1; i < costs.size(); i++) {
            least = least.least(costs.get(i));
        }
        for (int i = 0; i < costs.size(); i++) {
            boolean sure = true;
            for (int j = 0; j < costs.size(); j++) {
                if (i != j && !costs.get(i).noGreaterThan(costs.get(j))) {
                    sure = false;
                    break;
                }
            }
            if (sure) {
                chosen.put(decision.vertexIndex(), alternatives.get(i).vertexIndex());
                break;
            }
        }
        return Optional.of(least);
    }

    private Optional<Bounds> follow(Branch branch) {
        Optional<Bounds> own = costs(branch.vertexIndex(), branch.costs());
        if (own.isEmpty()) {
            return Optional.empty();
        }
        Optional<Node> next = branch.next();
        if (next.isEmpty()) {
            return own;
        }
        Optional<Bounds> ahead = value(next.get());
        if (ahead.isEmpty()) {
            return notDeterminable(branch.vertexIndex(), "what follows the branch is not determinable");
        }
        return Optional.of(own.get().plus(ahead.get()));
    }

    private Optional<Bounds> expect(Chance chance) {
        ImmutableList<Event> events = chance.events();
        if (events.isEmpty()) {
            return notDeterminable(chance.vertexIndex(), "a chance has no event");
        }
        MutableList<Bounds> probabilities = Lists.mutable.empty();
        Event residual = null;
        Bounds others = Bounds.point(0);
        for (Event event : events) {
            if (event.residual()) {
                if (residual != null) {
                    return notDeterminable(chance.vertexIndex(), "a chance has more than one residual event");
                }
                residual = event;
                probabilities.add(null);
                continue;
            }
            if (event.probability().isEmpty()) {
                return notDeterminable(event.vertexIndex(), "an event carries no probability and is not residual");
            }
            Measure measure = event.probability().get().measure();
            if (measure.semanticNid() != tree.terms().shareAtNode()) {
                return notDeterminable(event.vertexIndex(), "a probability is not on the Share at node semantic");
            }
            probabilities.add(measure.bounds());
            others = others.plus(measure.bounds());
        }
        if (residual != null) {
            Bounds remainder = others.complement().clip(0, 1);
            probabilities.set(events.indexOf(residual), remainder);
        } else if (others.lower() > 1 + Bounds.EPSILON || others.upper() < 1 - Bounds.EPSILON) {
            return notDeterminable(chance.vertexIndex(), "the events do not close: their probabilities sum to "
                    + others + ", and no event is residual");
        }
        Bounds expected = Bounds.point(0);
        for (int i = 0; i < events.size(); i++) {
            Optional<Bounds> ahead = value(events.get(i));
            if (ahead.isEmpty()) {
                return notDeterminable(chance.vertexIndex(), "an event is not determinable");
            }
            expected = expected.plus(probabilities.get(i).times(ahead.get()));
        }
        return Optional.of(expected);
    }

    /** The costs a node carries, priced onto the coordinate's money semantic. */
    private Optional<Bounds> costs(int vertexIndex, ImmutableList<Cost> costs) {
        Bounds total = Bounds.point(0);
        for (Cost cost : costs) {
            Measure measure = cost.measure();
            if (Double.isNaN(measure.bounds().lower())) {
                return notDeterminable(cost.vertexIndex(), "a cost has no bounds");
            }
            switch (cost) {
                case MoneyCost money -> {
                    if (measure.semanticNid() != coordinate.moneySemantic()) {
                        return notDeterminable(cost.vertexIndex(),
                                "a money cost is not on the coordinate's money semantic");
                    }
                    total = total.plus(measure.bounds());
                }
                case HealthCost health -> {
                    if (measure.semanticNid() != coordinate.healthSemantic()) {
                        return notDeterminable(cost.vertexIndex(),
                                "a health cost is not on the coordinate's health semantic");
                    }
                    total = total.plus(measure.bounds().times(coordinate.healthPrice()));
                }
            }
        }
        return Optional.of(total);
    }

    private Optional<Bounds> notDeterminable(int vertexIndex, String why) {
        findings.add(new ProfileFinding(vertexIndex, why));
        return Optional.empty();
    }
}
