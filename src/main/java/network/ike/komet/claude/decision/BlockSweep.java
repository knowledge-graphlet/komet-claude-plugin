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
    /** The document's statements, which a model's parameters read by name. */
    private final StatementMeasures statements;
    /** The trees an outcome folds in, read under this sweep's own identities. */
    private final LinkedTrees linked;
    /** The bodies of those trees with every stated interval at its midpoint, by their roots' identifiers. */
    private java.util.Map<String, String> linkedBodies = java.util.Map.of();

    private BlockSweep(String baseBody, DecisionTreeText.Parsed stated, DecisionTreeText.Parsed base, DecisionTerms terms,
                       Identities ids, double price, ExternalQuantities externals, LinkedTrees linked, StatementMeasures statements) {
        this.linked = linked;
        this.statements = statements == null ? StatementMeasures.none() : statements;
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
        return sweep(body, terms, ids, price, steps, externals, java.util.Map.of());
    }

    /**
     * Sweeps a block whose outcomes may fold in other blocks' trees, given by their root
     * identifiers; those trees are read under the sweep's identities, at their stated values.
     */
    public static Optional<DecisionTreeSensitivity> sweep(String body, DecisionTerms terms, Identities ids,
                                                          double price, int steps, ExternalQuantities externals,
                                                          java.util.Map<String, String> linkedBodies) {
        return sweep(body, terms, ids, price, steps, externals, linkedBodies, StatementMeasures.none());
    }

    /**
     * Sweeps a model whose parameters may read the document's statements: such a parameter
     * swings over the range its statement determined, while the tree runs at the parameter's
     * own value.
     */
    public static Optional<DecisionTreeSensitivity> sweep(String body, DecisionTerms terms, Identities ids,
                                                          double price, int steps, ExternalQuantities externals,
                                                          java.util.Map<String, String> linkedBodies, StatementMeasures statements) {
        return open(body, terms, ids, price, externals, linkedBodies, statements).map(mover -> {
            DecisionTree base = mover.base.tree().orElseThrow();
            return DecisionTreeSensitivity.of(mover.stated.tree().orElseThrow(),
                    Rollback.of(base, coordinate(base, price), mover.linked), mover, steps, mover.stated::range);
        });
    }

    /**
     * Sweeps a model for a review of it: each value over the range the review gives it, and the
     * net value drawn for one alternative throughout, whichever the tree chooses as its values move.
     *
     * @param ranges    the range each value swings over, by its vertex; a value it gives none keeps the model's own
     * @param reference the alternative whose net value is drawn, by its vertex; negative for the one the base chose
     */
    public static Optional<DecisionTreeSensitivity> sweep(String body, DecisionTerms terms, Identities ids,
                                                          double price, int steps, ExternalQuantities externals,
                                                          java.util.Map<String, String> linkedBodies, StatementMeasures statements,
                                                          DecisionTreeSensitivity.Ranges ranges, int reference) {
        return open(body, terms, ids, price, externals, linkedBodies, statements).map(mover -> {
            DecisionTree base = mover.base.tree().orElseThrow();
            return DecisionTreeSensitivity.of(mover.stated.tree().orElseThrow(),
                    Rollback.of(base, coordinate(base, price), mover.linked), mover, steps,
                    vertex -> ranges.range(vertex).or(() -> mover.stated.range(vertex)), reference);
        });
    }

    /** The mover alone, for a reader that moves one value at a time; empty when the block does not read as a tree. */
    public static Optional<BlockSweep> open(String body, DecisionTerms terms, Identities ids, double price) {
        return open(body, terms, ids, price, ExternalQuantities.none());
    }

    /** The mover alone, over a block that may read quantities another block computes. */
    public static Optional<BlockSweep> open(String body, DecisionTerms terms, Identities ids, double price,
                                            ExternalQuantities externals) {
        return open(body, terms, ids, price, externals, java.util.Map.of());
    }

    /** The mover alone, over a block whose outcomes may fold in other blocks' trees. */
    public static Optional<BlockSweep> open(String body, DecisionTerms terms, Identities ids, double price,
                                            ExternalQuantities externals, java.util.Map<String, String> linkedBodies) {
        return open(body, terms, ids, price, externals, linkedBodies, StatementMeasures.none());
    }

    /** The mover alone, over a model whose parameters may read the document's statements. */
    public static Optional<BlockSweep> open(String body, DecisionTerms terms, Identities ids, double price,
                                            ExternalQuantities externals, java.util.Map<String, String> linkedBodies,
                                            StatementMeasures statements) {
        StatementMeasures stated = statements == null ? StatementMeasures.none() : statements;
        // the trees folded in stand at their midpoints, as the swept tree does
        java.util.Map<String, String> linkedBase = new java.util.LinkedHashMap<>();
        linkedBodies.forEach((identifier, linkedBody) -> {
            DecisionTreeText.Parsed read = DecisionTreeText.parse(linkedBody, terms, ids, externals, stated);
            linkedBase.put(identifier, read.tree().isPresent() ? baseBody(linkedBody, read) : linkedBody);
        });
        LinkedTrees linked = LinkedTrees.of(linkedBase, terms, ids, externals, stated);
        DecisionTreeText.Parsed first = DecisionTreeText.parse(body, terms, ids, externals, stated);
        if (first.tree().isEmpty()) {
            return Optional.empty();
        }
        String baseBody = baseBody(body, first);
        DecisionTreeText.Parsed base = baseBody.equals(body) ? first : DecisionTreeText.parse(baseBody, terms, ids, externals, stated);
        return base.tree().map(tree -> {
            BlockSweep sweep = new BlockSweep(baseBody, first, base, terms, ids, price, externals, linked, stated);
            sweep.linkedBodies = java.util.Map.copyOf(linkedBase);
            return sweep;
        });
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
                .flatMap(text -> DecisionTreeText.parse(text, terms, ids, externals, statements).tree())
                .map(tree -> Rollback.of(tree, coordinate(tree, price), linked));
    }

    @Override
    public Optional<Rollback> movedIn(long treeRoot, int measureVertex, Bounds bounds) {
        Optional<String> identifier = ids.identifier(treeRoot);
        if (identifier.isEmpty() || !linkedBodies.containsKey(identifier.get())) {
            return Optional.empty();
        }
        String linkedBody = linkedBodies.get(identifier.get());
        OptionalInt line = DecisionTreeText.parse(linkedBody, terms, ids, externals, statements).lineOf(measureVertex);
        if (line.isEmpty()) {
            return Optional.empty();
        }
        return DecisionTreeEdits.restate(linkedBody, line.getAsInt(), bounds).flatMap(restated -> {
            java.util.Map<String, String> moved = new java.util.LinkedHashMap<>(linkedBodies);
            moved.put(identifier.get(), restated);
            LinkedTrees movedLinks = LinkedTrees.of(moved, terms, ids, externals, statements);
            return base.tree().map(tree -> Rollback.of(tree, coordinate(tree, price), movedLinks));
        });
    }

    @Override
    public Optional<Rollback> priced(double healthPrice) {
        return base.tree().map(tree -> Rollback.of(tree, coordinate(tree, healthPrice), linked));
    }
}
