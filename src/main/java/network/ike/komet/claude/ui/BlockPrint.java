/*
 * Copyright © 2025 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.ui;

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.complexconcept.ComplexConceptText;
import network.ike.komet.claude.decision.BlockSweep;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionElement;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeSensitivity;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.decision.GraphletModelText;
import network.ike.komet.claude.decision.LinkedTrees;
import network.ike.komet.claude.decision.Rollback;
import network.ike.komet.claude.decision.StatementMeasures;
import network.ike.komet.claude.decision.ValuationCoordinate;
import network.ike.komet.document.DecisionFigure;
import network.ike.komet.document.DecisionFigureText;
import network.ike.komet.claude.decision.render.DecisionTreeFigures;
import network.ike.komet.document.TextFigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the structured blocks are on a page. A control's sliders and fields mean nothing on paper,
 * so a block prints what it says: a tree its drawing, ledger, sentences and tornado; a complex
 * concept its sentence and prerequisites; a statement or a calculator its fields.
 */
final class BlockPrint {

    /** A concept as a block writes it, {@code k:Key[its name]} or {@code candidate[its name]}: read as its name. */
    private static final Pattern CONCEPT = Pattern.compile("(?:k:[\\w.-]*|candidate)\\[([^\\]]*)]");
    /** What a block says about itself rather than its subject. */
    private static final Pattern MACHINERY = Pattern.compile("\\s+(?:@id|disp|statement)=\\S+");
    /** A field: its name, then {@code :}, {@code ::} or {@code =}, then its value. */
    private static final Pattern FIELD = Pattern.compile("^\\s*([\\w][\\w -]*?)\\s*(?:::?|=)\\s+(.*)$");

    private BlockPrint() {
    }

    /** A decision tree block, as {@link DecisionTreeBlockRenderer} reads it: the tree, its ledger and sentences, no tornado. */
    static List<DecisionFigure> tree(String info, String body, ViewCalculator view, ProfileBlocks blocks) {
        DecisionTerms terms = DecisionTerms.fromBindings();
        Identities ids = blocks.identities(view);
        StatementMeasures statements = blocks.statementMeasures(ids, terms);
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(body, terms, ids, blocks.externals(), statements);
        if (parsed.tree().isEmpty()) {
            return List.of();
        }
        DecisionTree tree = parsed.tree().get();
        Identities shown = parsed.labelled(ids);
        double price = DecisionTreeText.healthPrice(info).orElse(DecisionTreeText.DEFAULT_HEALTH_PRICE);
        ValuationCoordinate coordinate = ValuationCoordinate.pricingHealthAt(Bounds.point(price),
                semantic(tree, DecisionElement.MoneyCost.class, terms.moneySemantic()),
                semantic(tree, DecisionElement.HealthCost.class, terms.healthSemantic()));
        Rollback rollback = Rollback.of(tree, coordinate, LinkedTrees.of(blocks.trees(), terms, ids, blocks.externals(), statements));
        long money = coordinate.moneySemantic();
        String unit = money == 0 ? "" : shown.label(money);
        OptionalDouble cohort = DecisionTreeText.cohort(info).map(OptionalDouble::of).orElse(OptionalDouble.empty());
        return DecisionTreeFigures.page(tree, rollback, shown, Optional.empty(), DecisionFigureText.symbolFor(unit == null ? "" : unit), cohort,
                vertex -> tree.node(vertex).filter(n -> n instanceof DecisionElement.Node node && !node.statements().isEmpty()).isPresent());
    }

    /** A tornado block, as {@link TornadoBlockRenderer} reads it: the model's one-way sensitivity, swept at the block's price. */
    static List<DecisionFigure> tornado(String info, String body, ViewCalculator view, ProfileBlocks blocks) {
        DecisionTerms terms = DecisionTerms.fromBindings();
        Identities ids = blocks.identities(view);
        StatementMeasures statements = blocks.statementMeasures(ids, terms);
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(body, terms, ids, blocks.externals(), statements);
        if (parsed.tree().isEmpty()) {
            return List.of();
        }
        DecisionTree tree = parsed.tree().get();
        Identities shown = parsed.labelled(ids);
        double price = DecisionTreeText.healthPrice(info).orElse(DecisionTreeText.DEFAULT_HEALTH_PRICE);
        Optional<DecisionTreeSensitivity> sweep = BlockSweep.sweep(body, terms, ids, price, BlockSweep.DEFAULT_STEPS,
                blocks.externals(), blocks.trees(), statements);
        long money = semantic(tree, DecisionElement.MoneyCost.class, terms.moneySemantic());
        String unit = money == 0 ? "" : shown.label(money);
        return sweep.flatMap(s -> DecisionTreeFigures.tornadoPage(s, shown, DecisionFigureText.symbolFor(unit == null ? "" : unit)))
                .map(List::of).orElse(List.of());
    }

    /**
     * A graphlet-model block: its parameters, each with the value the tree runs at and, for one
     * that reads a statement, the range the statement determined; the structure is the tree
     * rendering's to draw.
     */
    static List<DecisionFigure> model(String info, String body, ViewCalculator view, ProfileBlocks blocks) {
        DecisionTerms terms = DecisionTerms.fromBindings();
        Identities ids = blocks.identities(view);
        StatementMeasures statements = blocks.statementMeasures(ids, terms);
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(body, terms, ids, blocks.externals(), statements);
        if (parsed.tree().isEmpty()) {
            return List.of();
        }
        DecisionTree tree = parsed.tree().get();
        Identities shown = parsed.labelled(ids);
        network.ike.komet.claude.decision.Derivations derivations = network.ike.komet.claude.decision.Derivations.of(tree);
        long money = semantic(tree, DecisionElement.MoneyCost.class, terms.moneySemantic());
        String symbol = DecisionFigureText.symbolFor(money == 0 ? "" : shown.label(money));
        List<List<String>> table = new ArrayList<>();
        table.add(List.of("parameter", "value", "statement", "range"));
        for (DecisionElement.Parameter parameter : tree.nodesOfType(DecisionElement.Parameter.class)) {
            int index = parameter.vertexIndex();
            Optional<Bounds> bounds = derivations.bounds(index);
            boolean share = parameter.measure().semanticNid() == terms.shareAtNode();
            boolean isMoney = parameter.measure().semanticNid() == money && money != 0;
            DecisionFigureText.Unit unit = share ? DecisionFigureText.Unit.SHARE : isMoney ? DecisionFigureText.Unit.MONEY : DecisionFigureText.Unit.PLAIN;
            String value = bounds.map(b -> b.isPoint() ? DecisionFigureText.quantity(b.lower(), unit, symbol)
                    : DecisionFigureText.quantity(b.lower(), unit, symbol) + " to " + DecisionFigureText.quantity(b.upper(), unit, symbol)).orElse("not determinable");
            String name = parameter.quantity().isPresent() ? shown.label(parameter.quantity().getAsLong()) : "a parameter";
            String statement = parsed.parameterStatement(index).map(s -> "anf:" + s).orElse("");
            String range = parsed.range(index).map(r -> r.isPoint() ? DecisionFigureText.quantity(r.lower(), unit, symbol)
                    : DecisionFigureText.quantity(r.lower(), unit, symbol) + " to " + DecisionFigureText.quantity(r.upper(), unit, symbol)).orElse("");
            table.add(List.of(name, value, statement, range));
        }
        String id = GraphletModelText.modelId(info, body).orElse("");
        return List.of(TextFigure.of("graphlet-model", Optional.of("Graphlet model" + (id.isEmpty() ? "" : " " + id)),
                List.of("The model's parameters, each at the value the tree runs at; a parameter that reads a statement is swept over the statement's range."), table));
    }

    private static long semantic(DecisionTree tree, Class<? extends DecisionElement.Cost> kind, long fallback) {
        var costs = tree.nodesOfType(kind);
        return costs.isEmpty() ? fallback : costs.get(0).measure().semanticNid();
    }

    /** A complex concept block: its name, the sentence that defines it, and what it is given. */
    static List<DecisionFigure> complexConcept(String literal, ViewCalculator view, ProfileBlocks blocks) {
        Optional<ComplexConceptText.Spec> spec = ComplexConceptText.parse(literal, blocks.identities(view)).spec();
        if (spec.isEmpty()) {
            return List.of();
        }
        List<List<String>> table = new ArrayList<>();
        if (spec.get().shape().filter(ComplexConceptText.Focused.class::isInstance).isPresent()) {
            ComplexConceptText.Focused focused = (ComplexConceptText.Focused) spec.get().shape().get();
            if (!focused.prerequisites().isEmpty()) {
                table.add(List.of("given", "value"));
                for (ComplexConceptText.Prerequisite prerequisite : focused.prerequisites()) {
                    table.add(List.of(prerequisite.topic().label(), ComplexConceptText.valueWords(prerequisite)));
                }
            }
            return List.of(TextFigure.of("complex-concept", Optional.of(spec.get().concept().label()),
                    List.of("Complex concept, focused on " + focused.focus().label() + "."), table));
        }
        return List.of(TextFigure.of("complex-concept", Optional.of(spec.get().concept().label()),
                List.of(ComplexConceptText.describe(spec.get())), table));
    }

    /** A block of fields, a statement's or a calculator's: its kind's name, and each field with the names it uses. */
    static List<DecisionFigure> fields(String name, List<String> lines) {
        List<List<String>> table = new ArrayList<>();
        for (String line : lines) {
            String read = named(line);
            if (read.isBlank() || read.strip().matches("-{2,}")) {
                continue;
            }
            Matcher field = FIELD.matcher(read);
            table.add(field.matches() ? List.of(field.group(1).strip(), field.group(2).strip()) : List.of("", read.strip()));
        }
        return table.isEmpty() ? List.of() : List.of(TextFigure.of(name, Optional.of(name), List.of(), withHeader(table)));
    }

    /** The header the table figure takes its first row as: a block of fields has none to name, so it is blank. */
    private static List<List<String>> withHeader(List<List<String>> rows) {
        List<List<String>> table = new ArrayList<>();
        table.add(List.of("field", "value"));
        table.addAll(rows);
        return table;
    }

    /** A line with its concepts read as their names and its block machinery gone. */
    static String named(String line) {
        return MACHINERY.matcher(CONCEPT.matcher(line).replaceAll(match -> Matcher.quoteReplacement(match.group(1)))).replaceAll("");
    }
}
