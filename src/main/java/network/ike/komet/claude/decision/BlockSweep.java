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

import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.HealthCost;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The sensitivity of a {@code decision-tree} block swept over its text: each step restates the
 * one line a measure is on, as a what-if does, and reads the block again through the same parser,
 * so a swing is always something the block could say. The base body is the block with every
 * stated interval collapsed to its midpoint. Vertex indices are those of the parse, which a
 * block restated on one number shares with the original.
 */
public final class BlockSweep implements DecisionTreeSensitivity.Mover {

    /** The steps a swing is divided into when no one says otherwise. */
    public static final int DEFAULT_STEPS = 12;

    private final String baseBody;
    private final DecisionTreeText.Parsed stated;
    private final DecisionTreeText.Parsed base;
    private final DecisionTerms terms;
    private final Identities ids;
    private final double price;
    private final ExternalQuantities externals;

    private BlockSweep(String baseBody, DecisionTreeText.Parsed stated, DecisionTreeText.Parsed base, DecisionTerms terms,
                       Identities ids, double price, ExternalQuantities externals) {
        this.baseBody = baseBody;
        this.stated = stated;
        this.base = base;
        this.terms = terms;
        this.ids = ids;
        this.price = price;
        this.externals = externals;
    }

    /**
     * Sweeps a block.
     *
     * @param body  the block's body
     * @param terms the profile's concepts
     * @param ids   what resolves the block's symbols; a fresh one when the sweep runs off the surface's thread
     * @param price the price of a healthy year the block is read at
     * @param steps how many steps each swing takes
     * @return the sensitivity, or empty when the block does not read as a tree
     */
    public static Optional<DecisionTreeSensitivity> sweep(String body, DecisionTerms terms, Identities ids,
                                                          double price, int steps) {
        return sweep(body, terms, ids, price, steps, ExternalQuantities.none());
    }

    /** Sweeps a block that may read quantities another block computes. */
    public static Optional<DecisionTreeSensitivity> sweep(String body, DecisionTerms terms, Identities ids,
                                                          double price, int steps, ExternalQuantities externals) {
        return open(body, terms, ids, price, externals).map(mover -> {
            DecisionTree base = mover.base.tree().orElseThrow();
            return DecisionTreeSensitivity.of(mover.stated.tree().orElseThrow(),
                    Rollback.of(base, coordinate(base, price)), mover, steps);
        });
    }

    /** The mover alone, for a reader that moves one value at a time; empty when the block does not read as a tree. */
    public static Optional<BlockSweep> open(String body, DecisionTerms terms, Identities ids, double price) {
        return open(body, terms, ids, price, ExternalQuantities.none());
    }

    /** The mover alone, over a block that may read quantities another block computes. */
    public static Optional<BlockSweep> open(String body, DecisionTerms terms, Identities ids, double price,
                                            ExternalQuantities externals) {
        DecisionTreeText.Parsed first = DecisionTreeText.parse(body, terms, ids, externals);
        if (first.tree().isEmpty()) {
            return Optional.empty();
        }
        String baseBody = baseBody(body, first);
        DecisionTreeText.Parsed base = baseBody.equals(body) ? first : DecisionTreeText.parse(baseBody, terms, ids, externals);
        return base.tree().map(tree -> new BlockSweep(baseBody, first, base, terms, ids, price, externals));
    }

    /** The body with every stated interval collapsed to its midpoint. */
    static String baseBody(String body, DecisionTreeText.Parsed parsed) {
        String result = body;
        DecisionTree tree = parsed.tree().orElseThrow();
        for (DecisionElement element : tree.nodes()) {
            Measure measure = switch (element) {
                case Cost cost -> cost.measure();
                case DecisionElement.Probability p -> p.measure();
                case DecisionElement.Parameter p -> p.measure();
                default -> null;
            };
            if (measure == null || !measure.stated() || measure.bounds().isPoint()) {
                continue;
            }
            OptionalInt line = parsed.lineOf(element.vertexIndex());
            if (line.isPresent()) {
                result = DecisionTreeEdits.restate(result, line.getAsInt(), Bounds.point(measure.bounds().midpoint()))
                        .orElse(result);
            }
        }
        return result;
    }

    /** The coordinate a tree is read at: its costs' own money and health semantics, at a price. */
    public static ValuationCoordinate coordinate(DecisionTree tree, double price) {
        return ValuationCoordinate.pricingHealthAt(Bounds.point(price),
                semantic(tree, MoneyCost.class, tree.terms().moneySemantic()),
                semantic(tree, HealthCost.class, tree.terms().healthSemantic()));
    }

    private static long semantic(DecisionTree tree, Class<? extends Cost> kind, long fallback) {
        ImmutableList<? extends Cost> costs = tree.nodesOfType(kind);
        return costs.isEmpty() ? fallback : costs.get(0).measure().semanticNid();
    }

    @Override
    public Optional<Rollback> moved(int measureVertex, Bounds bounds) {
        OptionalInt line = base.lineOf(measureVertex);
        if (line.isEmpty()) {
            return Optional.empty();
        }
        return DecisionTreeEdits.restate(baseBody, line.getAsInt(), bounds)
                .flatMap(text -> DecisionTreeText.parse(text, terms, ids, externals).tree())
                .map(tree -> Rollback.of(tree, coordinate(tree, price)));
    }

    @Override
    public Optional<Rollback> priced(double healthPrice) {
        return base.tree().map(tree -> Rollback.of(tree, coordinate(tree, healthPrice)));
    }
}
