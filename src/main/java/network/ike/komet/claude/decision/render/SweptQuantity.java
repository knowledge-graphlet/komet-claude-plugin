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

import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionElement;
import network.ike.komet.claude.decision.DecisionElement.HealthCost;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import network.ike.komet.claude.decision.DecisionElement.Probability;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeSensitivity;
import network.ike.komet.claude.decision.Measure;
import network.ike.komet.claude.decision.ValuationCoordinate;
import network.ike.komet.claude.decision.render.DecisionFigureText.Unit;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * How a swept quantity is named and how its values are printed on a tornado, for the figure
 * writers and the live chart alike: a probability or a share-valued parameter as a percentage,
 * money after the symbol to the whole unit, a health cost or any other quantity to three
 * significant figures with its unit's name. The price of a healthy year is money.
 *
 * @param unit   what the quantity is
 * @param symbol the money symbol, or the unit's name for a plain quantity; empty for none
 */
public record SweptQuantity(Unit unit, String symbol) {

    /** The quantity a swept measure is, read from the tree and the coordinate it was rolled back under. */
    public static SweptQuantity of(DecisionTreeSensitivity sweep, Identities ids, int measureVertex, String moneySymbol) {
        String symbol = moneySymbol == null ? "" : moneySymbol;
        if (measureVertex == DecisionTreeSensitivity.HEALTH_PRICE) {
            return new SweptQuantity(Unit.MONEY, symbol);
        }
        // a value of a tree folded in reads as that tree reads it
        Optional<DecisionTreeSensitivity.FoldedValue> folded = sweep.folded(measureVertex);
        DecisionTree tree = folded.map(DecisionTreeSensitivity.FoldedValue::tree).orElse(sweep.tree());
        ValuationCoordinate coordinate = folded.map(f -> f.base().coordinate()).orElse(sweep.base().coordinate());
        int vertex = folded.map(DecisionTreeSensitivity.FoldedValue::vertex).orElse(measureVertex);
        Optional<DecisionElement> element = tree.node(vertex);
        if (element.isEmpty()) {
            return new SweptQuantity(Unit.PLAIN, "");
        }
        return switch (element.get()) {
            case MoneyCost m -> new SweptQuantity(Unit.MONEY, symbol);
            case HealthCost h -> new SweptQuantity(Unit.PLAIN, unitName(ids, coordinate.healthSemantic()));
            case DecisionElement.CountCost c -> new SweptQuantity(Unit.PLAIN, unitName(ids, c.measure().semanticNid()));
            case Probability p -> new SweptQuantity(Unit.SHARE, "");
            case Parameter p -> {
                Measure measure = p.measure();
                long semantic = measure.semanticNid();
                if (semantic == coordinate.moneySemantic()) {
                    yield new SweptQuantity(Unit.MONEY, symbol);
                }
                if (semantic == tree.terms().shareAtNode()) {
                    Bounds range = sweep.swing(measureVertex).map(s -> s.range()).orElse(measure.bounds());
                    boolean share = range.lower() >= 0 && range.upper() <= 1;
                    yield new SweptQuantity(share ? Unit.SHARE : Unit.PLAIN, "");
                }
                yield new SweptQuantity(Unit.PLAIN, unitName(ids, semantic));
            }
            default -> new SweptQuantity(Unit.PLAIN, "");
        };
    }

    private static String unitName(Identities ids, long semantic) {
        String label = semantic == 0 || ids == null ? null : ids.label(semantic);
        return label == null ? "" : label;
    }

    /** A value of the quantity, with its unit. */
    public String print(double value) {
        return DecisionFigureText.quantity(value, unit, symbol);
    }

    /**
     * How a swept measure is named: its quantity for a parameter, the price of a healthy year, or
     * its kind at the node that carries it, the node's name in quotes.
     */
    public static String name(DecisionTreeSensitivity sweep, Identities ids, int measureVertex) {
        Optional<DecisionTreeSensitivity.FoldedValue> folded = sweep.folded(measureVertex);
        if (folded.isEmpty()) {
            return name(sweep.tree(), ids, measureVertex);
        }
        return name(folded.get().tree(), ids, folded.get().vertex()) + " in \u201C" + ids.label(folded.get().treeRoot()) + "\u201D";
    }

    /** How a measure of a tree is named, as {@link #name(DecisionTreeSensitivity, Identities, int)} names it there. */
    public static String name(DecisionTree tree, Identities ids, int measureVertex) {
        if (measureVertex == DecisionTreeSensitivity.HEALTH_PRICE) {
            return "price of a healthy year";
        }
        Optional<DecisionElement> element = tree.node(measureVertex);
        if (element.isEmpty()) {
            return "a value";
        }
        return switch (element.get()) {
            case Parameter p -> p.quantity().isPresent() ? ids.label(p.quantity().getAsLong()) : "a parameter";
            case MoneyCost m -> "money cost at " + owner(tree, ids, measureVertex);
            case HealthCost h -> "health cost at " + owner(tree, ids, measureVertex);
            case DecisionElement.CountCost c -> ids.label(c.measure().semanticNid()) + " at " + owner(tree, ids, measureVertex);
            case Probability p -> "p of " + owner(tree, ids, measureVertex);
            default -> "a value";
        };
    }

    private static String owner(DecisionTree tree, Identities ids, int measureVertex) {
        OptionalInt parent = tree.parentIndex(measureVertex);
        if (parent.isEmpty()) {
            return "the root";
        }
        return tree.node(parent.getAsInt()).flatMap(n -> n.concept().isPresent()
                ? Optional.of("“" + ids.label(n.concept().getAsLong()) + "”") : Optional.empty()).orElse("a node");
    }
}
