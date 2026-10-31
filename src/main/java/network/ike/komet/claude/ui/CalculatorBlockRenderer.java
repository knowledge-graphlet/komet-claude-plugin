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
package network.ike.komet.claude.ui;

import dev.ikm.komet.framework.controls.KonceptBadge;
import dev.ikm.komet.framework.controls.KonceptLabelTypography;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.calculator.Binding;
import network.ike.komet.claude.calculator.Bindings;
import network.ike.komet.claude.calculator.Calculation;
import network.ike.komet.claude.calculator.CalculationEdits;
import network.ike.komet.claude.calculator.CalculationText;
import network.ike.komet.claude.calculator.Calculator;
import network.ike.komet.claude.calculator.CalculatorElement;
import network.ike.komet.claude.calculator.CalculatorElement.Input;
import network.ike.komet.claude.calculator.CalculatorSensitivity;
import network.ike.komet.claude.calculator.CalculatorTerms;
import network.ike.komet.claude.calculator.CalculatorText;
import network.ike.komet.claude.calculator.Evaluation;
import network.ike.komet.claude.calculator.StoreStatements;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.ExternalQuantities;
import network.ike.komet.claude.koncept.ComponentText;

/**
 * The live rendering of the calculator profile's two blocks on the assistant surface
 * ({@code dev-calculator-blocks}). A {@code [calculator]} block, the method, renders as a compact
 * card of what it is and what binds to it, and defines the calculator for the document through
 * the {@link BlockContext}. A {@code [calculate]} block, the record, binds the document's
 * statements to the method's inputs and shows the input rail with each binding and its status,
 * the result as an interval with its band and the disposition a recording would carry, the
 * findings, and the tornado of which inputs move the result; it publishes the result through the
 * context so a decision tree in the document reads it as a parameter. A what-if is an assumption
 * beside the observed statement, never a change to it: the value control's slider previews, its
 * button writes an {@code assume} line, and the bar offers to revert or write it back through the
 * surface's {@link BlockRewriter}.
 *
 * <p>The block declines when there is no view to resolve against, or when it is not this
 * profile's, so the text renders preformatted as any other unrendered block does.
 */
final class CalculatorBlockRenderer implements BlockRenderer {

    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private final BlockContext context;
    private KonceptLabelTypography typography = KonceptLabelTypography.DEFAULT;
    private BlockRewriter rewriter;

    CalculatorBlockRenderer(ViewCalculator viewCalc, double base, BlockContext context) {
        this(viewCalc, null, base, context);
    }

    CalculatorBlockRenderer(ViewProperties viewProperties, double base, BlockContext context) {
        this(viewProperties == null ? null : viewProperties.calculator(), viewProperties, base, context);
    }

    private CalculatorBlockRenderer(ViewCalculator viewCalc, ViewProperties viewProperties, double base, BlockContext context) {
        this.viewCalc = viewCalc;
        this.viewProperties = viewProperties;
        this.base = base;
        this.context = context;
    }

    void setChipTypography(KonceptLabelTypography typography) {
        this.typography = typography == null ? KonceptLabelTypography.DEFAULT : typography;
    }

    void setRewriter(BlockRewriter rewriter) {
        this.rewriter = rewriter;
    }

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        if (viewCalc == null) {
            return List.of();
        }
        String body = literal == null ? "" : literal;
        if (acceptsMethod(info, body)) {
            return List.of(new BlockPiece(() -> new Method(body).column, body.stripTrailing()));
        }
        if (acceptsRecord(info, body)) {
            return List.of(new BlockPiece(() -> new Record(info, body).column, body.stripTrailing()));
        }
        return List.of();
    }

    /** Whether a block is a calculator method: tagged so and opening with a calculator line. */
    static boolean acceptsMethod(String info, String literal) {
        return CalculatorText.isTagged(info) && rootWord(literal, "calculator");
    }

    /** Whether a block is a calculation: tagged so and opening with a calculate line. */
    static boolean acceptsRecord(String info, String literal) {
        return CalculationText.isTagged(info) && rootWord(literal, "calculate");
    }

    private static boolean rootWord(String literal, String word) {
        BlockLines.Parsed lines = BlockLines.parse(literal);
        return lines.roots().size() == 1
                && lines.roots().get(0).form() == BlockLines.Form.VERTEX
                && word.equals(lines.roots().get(0).word());
    }

    /**
     * A result at the output's scale as the card prints it: a stated number as written, a
     * computed one held to what it earns, three significant figures, and an interval as its two
     * bounds with an en dash.
     */
    static String format(Bounds bounds, String unit) {
        String text = bounds.isPoint() ? figure(bounds.lower()) : figure(bounds.lower()) + "–" + figure(bounds.upper());
        return unit.isEmpty() ? text : text + " " + unit;
    }

    static String figure(double value) {
        double magnitude = Math.abs(value);
        if (magnitude >= 100) {
            return String.format(Locale.ROOT, "%,.0f", value);
        }
        if (magnitude >= 10) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        if (magnitude >= 1) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    // ── The method card ─────────────────────────────────────────────────────

    /** A calculator block as a card: what it is, and what may bind to it. */
    private final class Method {
        final VBox column = new VBox(4);
        final CalculatorTerms terms = CalculatorTerms.fromBindings();
        final Identities ids = context.identities(viewCalc);

        Method(String body) {
            column.getStyleClass().add("calculator-method");
            column.setFillWidth(true);
            CalculatorText.Parsed parsed = CalculatorText.parse(body, terms, ids);
            List<ProfileFinding> findings = new ArrayList<>(parsed.findings().castToList());
            if (parsed.calculator().isEmpty()) {
                column.getChildren().add(findingsOnly("Calculator not read", findings));
                return;
            }
            Calculator calculator = parsed.calculator().get();
            findings.addAll(calculator.check().castToList());
            Optional<CalculatorElement.Root> root = calculator.calculator();
            root.flatMap(CalculatorBlockRenderer::conceptOf)
                    .ifPresent(nid -> context.defineCalculator(nid, calculator));

            HBox header = new HBox(8);
            header.setAlignment(Pos.CENTER_LEFT);
            Label title = new Label("Calculator");
            title.setFont(Font.font(null, FontWeight.BOLD, base));
            header.getChildren().add(title);
            root.flatMap(r -> concept(r.concept())).ifPresent(header.getChildren()::add);
            root.ifPresent(r -> {
                Label counts = new Label(r.inputs().size() + " inputs · " + r.strata().size()
                        + (r.strata().size() == 1 ? " stratum · " : " strata · ") + r.bands().size() + " bands");
                counts.setFont(Font.font(base * 0.85));
                counts.setTextFill(Color.web("#666"));
                header.getChildren().add(counts);
                r.source().ifPresent(source -> {
                    Label from = new Label("from " + ids.label(source));
                    from.setFont(Font.font(base * 0.85));
                    from.setTextFill(Color.web("#666"));
                    header.getChildren().add(from);
                });
            });
            column.getChildren().add(header);

            root.ifPresent(r -> {
                GridPane rail = rail();
                int row = 0;
                for (Input input : r.inputs()) {
                    rail.add(concept(OptionalLong.of(input.quantity())).orElseGet(() -> plain(ids.label(input.quantity()))), 0, row);
                    StringBuilder what = new StringBuilder();
                    if (input.conceptValued()) {
                        List<String> values = new ArrayList<>();
                        input.admittedValues().forEach(nid -> values.add(ids.label(nid)));
                        what.append("one of ").append(String.join(", ", values));
                    } else {
                        what.append("in ").append(ids.label(input.expectedSemantic()));
                        input.domain().ifPresent(domain -> what.append(" · valid ").append(domain));
                    }
                    rail.add(muted(what.toString()), 1, row);
                    StringBuilder policy = new StringBuilder(input.required() ? "required" : "optional");
                    OptionalLong when = input.whenMissing();
                    if (when.isPresent()) {
                        CalculatorText.policyWord(terms, when.getAsLong()).ifPresent(word -> {
                            policy.append(" · when missing: ").append(word);
                            input.missingValue().ifPresent(missing -> {
                                missing.bounds().ifPresent(b -> policy.append(' ').append(b));
                                missing.conceptValue().ifPresent(c -> policy.append(' ').append(ids.label(c)));
                            });
                        });
                    }
                    rail.add(muted(policy.toString()), 2, row);
                    row++;
                }
                column.getChildren().add(rail);
            });
            if (!findings.isEmpty()) {
                column.getChildren().add(gaps(findings.stream().map(ProfileFinding::message).toList()));
            }
        }
    }

    // ── The record card ─────────────────────────────────────────────────────

    /** One rendering of a calculate block: the bindings, the evaluation, the rail, the result, the tornado. */
    private final class Record implements CalculatorTornado.Host {
        final String info;
        final String originalBody;
        String body;
        int moves;
        final VBox column = new VBox(6);
        final VBox top = new VBox(6);
        final CalculatorTerms terms = CalculatorTerms.fromBindings();
        final Identities ids = context.identities(viewCalc);
                final Calculation.StatementSource statements = new DocumentStatements(context, ids, new StoreStatements(viewCalc));
        Calculation calculation;
        Optional<Calculator> calculator = Optional.empty();
        Bindings bindings = Bindings.none();
        Evaluation committed;
        final java.util.Map<Long, HBox> rows = new java.util.HashMap<>();
        boolean previewing;
        Runnable pendingPreview;
        /** Rebuilds when a calculator is defined later in the document than this record. */
                final Runnable onContext = this::rebuildIfWaiting;
        boolean waitingForCalculator;
        /** Set while a rebuild runs: the context notifies as the card publishes, and a card never rebuilds itself from inside. */
        boolean rebuilding;

        Record(String info, String body) {
            this.info = info;
            this.originalBody = body;
            this.body = body;
            column.getStyleClass().add("calculator-record");
            column.setCursor(Cursor.DEFAULT);
            column.setFillWidth(true);
            top.setFillWidth(true);
            column.setMaxWidth(Double.MAX_VALUE);
            column.parentProperty().addListener((obs, oldParent, newParent) -> {
                column.maxWidthProperty().unbind();
                if (newParent instanceof Region host) {
                    column.maxWidthProperty().bind(host.widthProperty());
                }
            });
            context.listen(onContext);
            rebuild();
        }

                        private void rebuildIfWaiting() {
            if (calculation == null || rebuilding) {
                return;
            }
            boolean calculatorArrived = waitingForCalculator && context.calculator(calculation.calculatorNid()).isPresent();
            // a statement an anf block of the document named may have been edited: read it again
            boolean bindsDocumentStatements = calculation.lines().anySatisfy(line -> line.spec() instanceof Calculation.Statement s
                    && ids.isLocal(s.statementNid()));
            if (calculatorArrived || bindsDocumentStatements) {
                rebuild();
            }
        }

                void rebuild() {
            if (rebuilding) {
                return;
            }
            rebuilding = true;
            try {
                doRebuild();
            } finally {
                rebuilding = false;
            }
        }

        private void doRebuild() {
            previewing = false;
            pendingPreview = null;
            rows.clear();
            column.getChildren().clear();
            CalculationText.Parsed parsed = CalculationText.parse(body, ids);
            List<String> messages = new ArrayList<>();
            parsed.findings().stream().filter(f -> !f.message().contains("local to the block"))
                    .forEach(f -> messages.add(f.message()));
            if (parsed.calculation().isEmpty()) {
                column.getChildren().add(findingsOnly("Calculation not read", parsed.findings().castToList()));
                return;
            }
            calculation = parsed.calculation().get();
            calculator = context.calculator(calculation.calculatorNid());
            waitingForCalculator = calculator.isEmpty();
            if (calculator.isEmpty()) {
                VBox waiting = new VBox(3);
                HBox header = new HBox(8);
                header.setAlignment(Pos.CENTER_LEFT);
                Label title = new Label("Calculation");
                title.setFont(Font.font(null, FontWeight.BOLD, base));
                header.getChildren().add(title);
                concept(OptionalLong.of(calculation.calculatorNid())).ifPresent(header.getChildren()::add);
                waiting.getChildren().add(header);
                messages.add("No calculator block in this document defines " + ids.label(calculation.calculatorNid())
                        + "; the calculation waits for one.");
                waiting.getChildren().add(gaps(messages));
                column.getChildren().add(waiting);
                return;
            }
            bindings = calculation.bindings(calculator.get(), statements);
            committed = Evaluation.of(calculator.get(), bindings);
            publish(committed);
            fillTop(committed, Optional.empty());
            column.getChildren().add(top);
            committed.findings().forEach(f -> messages.add(describe(f)));
            if (!messages.isEmpty()) {
                column.getChildren().add(gaps(messages));
            }
            column.getChildren().add(sensitivity(committed));
            if (moves > 0) {
                column.getChildren().add(whatIfBar());
            }
        }

        /** The result, as a share on the share semantic, under the calculator's concept and its output's topic. */
        private void publish(Evaluation evaluation) {
            long[] quantities = quantities(evaluation);
            if (evaluation.share().isPresent()) {
                context.publish(quantities, new ExternalQuantities.Value(evaluation.share().get(), terms.shared().shareAtNode()));
            } else {
                context.withdraw(quantities);
            }
        }

        private long[] quantities(Evaluation evaluation) {
            long topic = evaluation.calculator().calculator().flatMap(CalculatorElement.Root::output)
                    .flatMap(CalculatorBlockRenderer::conceptOf).orElse(0L);
            return new long[] {calculation.calculatorNid(), topic};
        }

        /** The header, the rail and the result, for an evaluation; a reference makes it a preview. */
        private void fillTop(Evaluation evaluation, Optional<Evaluation> reference) {
            top.getChildren().clear();
            rows.clear();
            top.getChildren().add(header(evaluation));
            top.getChildren().add(rail(evaluation, reference));
            top.getChildren().add(result(evaluation, reference));
        }

        private HBox header(Evaluation evaluation) {
            HBox header = new HBox(8);
            header.setAlignment(Pos.CENTER_LEFT);
            Label title = new Label("Calculation");
            title.setFont(Font.font(null, FontWeight.BOLD, base));
            header.getChildren().add(title);
            concept(OptionalLong.of(calculation.calculatorNid())).ifPresent(header.getChildren()::add);
            StringBuilder about = new StringBuilder();
            evaluation.stratum().ifPresent(stratum -> stratum.concept()
                    .ifPresent(nid -> about.append("stratum ").append(ids.label(nid))));
            evaluation.calculator().calculator().flatMap(r -> optional(r.source())).ifPresent(source -> {
                if (!about.isEmpty()) {
                    about.append(" · ");
                }
                about.append("from ").append(ids.label(source));
            });
            if (!about.isEmpty()) {
                Label label = new Label(about.toString());
                label.setFont(Font.font(base * 0.85));
                label.setTextFill(Color.web("#666"));
                header.getChildren().add(label);
            }
            return header;
        }

        /** One row per input: its name, what binds, the value as read, and its status. */
        private GridPane rail(Evaluation evaluation, Optional<Evaluation> reference) {
            GridPane rail = CalculatorBlockRenderer.this.rail();
            int row = 0;
            for (Evaluation.InputState state : evaluation.inputs()) {
                Input input = state.input();
                long quantity = input.quantity();
                HBox nameCell = new HBox(4);
                nameCell.setAlignment(Pos.CENTER_LEFT);
                nameCell.getChildren().add(concept(OptionalLong.of(quantity)).orElseGet(() -> plain(ids.label(quantity))));
                nameCell.setId("calculator-input-" + quantity);
                rows.put(quantity, nameCell);
                rail.add(nameCell, 0, row);
                rail.add(bindingCell(state), 1, row);
                Label value = muted(valueText(state));
                Optional<Evaluation.InputState> was = reference.flatMap(r -> r.input(quantity));
                if (was.isPresent() && !sameValue(was.get(), state)) {
                    value.setTextFill(TornadoChart.HOT);
                    value.setTooltip(new Tooltip("was " + valueText(was.get())));
                }
                rail.add(value, 2, row);
                rail.add(statusTag(state), 3, row);
                row++;
            }
            return rail;
        }

        private boolean sameValue(Evaluation.InputState a, Evaluation.InputState b) {
            return a.status() == b.status() && a.bounds().equals(b.bounds()) && a.conceptValue().equals(b.conceptValue());
        }

        private Node bindingCell(Evaluation.InputState state) {
            Optional<Binding> binding = state.binding();
            if (binding.isPresent()) {
                switch (binding.get()) {
                                        case Binding.Observed observed -> {
                        if (ids.isLocal(observed.statementNid())) {
                            return localConcept("statement " + ids.label(observed.statementNid()));
                        }
                        Optional<Node> chip = concept(OptionalLong.of(observed.statementNid()));
                        return chip.orElseGet(() -> muted("statement " + ids.label(observed.statementNid())));
                    }
                    case Binding.Supplied supplied -> {
                        return muted(supplied.assumption() ? "assumed in the block" : "stated in the block");
                    }
                    case Binding.Clarify clarify -> {
                        Label label = localConcept("clarify");
                        label.setTooltip(new Tooltip(clarify.question()));
                        return label;
                    }
                    case Binding.Missing missing -> {
                        return localConcept("no statement");
                    }
                }
            }
            return localConcept("no statement");
        }

        private String valueText(Evaluation.InputState state) {
            if (state.conceptValue().isPresent()) {
                return ids.label(state.conceptValue().getAsLong());
            }
            if (state.bounds().isEmpty()) {
                return state.note().orElse("");
            }
            String unit = state.input().expectedSemantic() == 0 ? "" : ids.label(state.input().expectedSemantic());
            String value = state.bounds().get().toString() + (unit.isBlank() ? "" : " " + unit);
            String text = state.status() == Evaluation.Status.PLAUSIBLE ? "swept over " + value : value;
            return state.note().filter(note -> note.startsWith("converted")).map(note -> text + " · " + note).orElse(text);
        }

        private Label statusTag(Evaluation.InputState state) {
            String word;
            String fill;
            String ink;
            switch (state.status()) {
                case OBSERVED -> { word = "observed"; fill = "#ddefe3"; ink = "#0f6e56"; }
                case SUPPLIED -> { word = "stated"; fill = "#e6f1fb"; ink = "#185fa5"; }
                case ASSUMED -> { word = "assumed"; fill = "#faeeda"; ink = "#854f0b"; }
                case PLAUSIBLE -> { word = "plausible range"; fill = "#fcebeb"; ink = "#a32d2d"; }
                case NOT_DETERMINED -> { word = "not determined"; fill = "#fcebeb"; ink = "#a32d2d"; }
                case NOT_ADMITTED -> { word = "not admitted"; fill = "#fcebeb"; ink = "#a32d2d"; }
                default -> { word = "outside the domain"; fill = "#faeeda"; ink = "#854f0b"; }
            }
            Label tag = new Label(word);
            tag.setFont(Font.font(base * 0.78));
            tag.setStyle("-fx-background-color: " + fill + "; -fx-text-fill: " + ink + "; -fx-background-radius: 4;"
                    + " -fx-padding: 1 6 1 6;");
            state.note().ifPresent(note -> tag.setTooltip(new Tooltip(note)));
            return tag;
        }

        /** The result row: the number, its band, and the disposition a recording would carry. */
        private Node result(Evaluation evaluation, Optional<Evaluation> reference) {
            HBox row = new HBox(24);
            row.setAlignment(Pos.BASELINE_LEFT);
            row.setPadding(new Insets(6, 0, 0, 0));
            String unit = unitOf(evaluation);
            VBox value = new VBox(1);
            value.getChildren().add(muted(outputName(evaluation)));
            Label number = new Label(evaluation.result().map(r -> format(r, unit)).orElse("not determinable"));
            number.setId("calculator-result");
            number.setFont(Font.font(null, FontWeight.BOLD, base * 1.8));
            number.setTextFill(evaluation.result().isPresent() ? MeasureChip.INK : MeasureChip.GAP);
            Optional<Bounds> was = reference.flatMap(Evaluation::result);
            if (was.isPresent() && !was.equals(evaluation.result())) {
                number.setTextFill(TornadoChart.HOT);
                number.setTooltip(new Tooltip("was " + format(was.get(), unit)));
            } else {
                evaluation.result().ifPresent(r -> number.setTooltip(new Tooltip(working(evaluation, unit))));
            }
            value.getChildren().add(number);
            row.getChildren().add(value);
            if (!evaluation.bands().isEmpty()) {
                VBox band = new VBox(1);
                band.getChildren().add(muted("band"));
                List<String> names = new ArrayList<>();
                evaluation.bands().forEach(b -> b.concept().ifPresent(nid -> names.add(ids.label(nid))));
                Label bandLabel = new Label(String.join(" to ", names));
                bandLabel.setId("calculator-band");
                bandLabel.setFont(Font.font(base * 1.05));
                band.getChildren().add(bandLabel);
                row.getChildren().add(band);
            }
            VBox disposition = new VBox(1);
            disposition.getChildren().add(muted("if recorded now"));
            Label dispositionLabel = new Label(!evaluation.determinable() ? "nothing to record"
                    : evaluation.interim() ? "in progress, interim result" : "performed");
            dispositionLabel.setId("calculator-disposition");
            dispositionLabel.setFont(Font.font(base * 1.05));
            disposition.getChildren().add(dispositionLabel);
            row.getChildren().add(disposition);
            return row;
        }

        private String unitOf(Evaluation evaluation) {
            return evaluation.calculator().calculator().flatMap(CalculatorElement.Root::output)
                    .map(output -> output.scale() == 100 ? "%" : output.semantic() == 0 ? "" : ids.label(output.semantic()))
                    .orElse("");
        }

        private String outputName(Evaluation evaluation) {
            return evaluation.calculator().calculator().flatMap(CalculatorElement.Root::output)
                    .flatMap(CalculatorBlockRenderer::conceptOf).map(ids::label).orElse("result");
        }

        /** The working behind the number: which inputs are intervals, so the reader sees where the width comes from. */
        private String working(Evaluation evaluation, String unit) {
            List<String> lines = new ArrayList<>();
            lines.add(outputName(evaluation) + ", " + evaluation.result().map(r -> format(r, unit)).orElse("?"));
            evaluation.stratum().flatMap(CalculatorBlockRenderer::conceptOf).map(ids::label)
                    .ifPresent(name -> lines.add("the " + name + " equation"));
            List<String> wide = new ArrayList<>();
            for (Evaluation.InputState state : evaluation.inputs()) {
                if (state.interval()) {
                    wide.add(ids.label(state.input().quantity()) + " " + state.bounds().get()
                            + (state.status() == Evaluation.Status.PLAUSIBLE ? " (no statement; swept)" : ""));
                }
            }
            if (wide.isEmpty()) {
                lines.add("every input is a point, so the result is one");
            } else {
                lines.add("the width comes from " + String.join("; ", wide));
                lines.add("computed at every corner of those bounds, never by interval arithmetic over the equation");
            }
            if (evaluation.interim()) {
                lines.add("an input stood in for a statement, so a recording would be interim");
            }
            return String.join("\n", lines);
        }

        /** The sensitivity section: the tornado over the inputs. */
        private Node sensitivity(Evaluation evaluation) {
            VBox section = new VBox(3);
            section.setPadding(new Insets(6, 0, 0, 0));
            Label heading = new Label("What moves the result, one input at a time");
            heading.setFont(Font.font(null, FontWeight.BOLD, base * 0.9));
            section.getChildren().add(heading);
            if (!evaluation.determinable()) {
                section.getChildren().add(muted("Nothing to sweep until the result is determinable."));
                return section;
            }
            CalculatorSensitivity sensitivity = CalculatorSensitivity.of(evaluation);
            if (sensitivity.rows().isEmpty()) {
                section.getChildren().add(muted("No numeric input to sweep."));
                return section;
            }
            section.getChildren().add(new CalculatorTornado(sensitivity, base, this));
            return section;
        }

        private HBox whatIfBar() {
            HBox bar = new HBox(10);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setPadding(new Insets(2, 6, 2, 6));
            bar.setStyle("-fx-background-color: #fff8e6; -fx-background-radius: 6; -fx-border-color: #e6c87a;"
                    + " -fx-border-radius: 6;");
            Label what = new Label("What-if: " + (moves == 1 ? "one assumption" : moves + " assumptions")
                    + (rewriter == null ? "; the block is unchanged" : ""));
            what.setFont(Font.font(base * 0.9));
            bar.getChildren().add(what);
            Hyperlink revert = new Hyperlink("Revert");
            revert.setFont(Font.font(base * 0.9));
            revert.setOnAction(e -> {
                body = originalBody;
                moves = 0;
                rebuild();
            });
            bar.getChildren().add(revert);
            if (rewriter != null) {
                Button write = new Button("Write back to the block");
                write.setFont(Font.font(base * 0.9));
                write.setTooltip(new Tooltip("Writes the assumptions into the document's block; the observed statements stay as they are"));
                write.setOnAction(e -> rewriter.rewrite(info, originalBody, info, body));
                bar.getChildren().add(write);
            }
            return bar;
        }

        private String describe(ProfileFinding finding) {
            if (finding.vertexIndex() < 0 || calculator.isEmpty()) {
                return finding.message();
            }
            Optional<CalculatorElement> element = calculator.get().node(finding.vertexIndex());
            if (element.isPresent() && element.get() instanceof Input input) {
                return ids.label(input.quantity()) + ": " + finding.message();
            }
            return finding.message();
        }

        // ── The tornado's host ──────────────────────────────────────────────

        @Override
        public String nameOf(long inputQuantity) {
            return ids.label(inputQuantity);
        }

        @Override
        public void light(long inputQuantity, boolean on) {
            HBox row = rows.get(inputQuantity);
            if (row != null) {
                row.setStyle(on ? "-fx-background-color: #fff3cd; -fx-background-radius: 4;" : "");
            }
        }

        @Override
        public void preview(long inputQuantity, OptionalDouble value) {
            if (value.isEmpty()) {
                pendingPreview = null;
                if (previewing) {
                    previewing = false;
                    fillTop(committed, Optional.empty());
                }
                return;
            }
            boolean queued = pendingPreview != null;
            pendingPreview = () -> drawPreview(inputQuantity, value.getAsDouble());
            if (!queued) {
                javafx.application.Platform.runLater(() -> {
                    Runnable run = pendingPreview;
                    pendingPreview = null;
                    if (run != null) {
                        run.run();
                    }
                });
            }
        }

        private void drawPreview(long inputQuantity, double value) {
            if (calculator.isEmpty()) {
                return;
            }
            long semantic = calculator.get().input(inputQuantity).map(Input::expectedSemantic).orElse(0L);
            Evaluation drawn = Evaluation.of(calculator.get(),
                    bindings.withAssumption(inputQuantity, Binding.Supplied.assumed(Bounds.point(value), semantic)));
            previewing = true;
            fillTop(drawn, Optional.of(committed));
        }

        @Override
        public void assume(long inputQuantity, double value) {
            Optional<String> identifier = ids.identifier(inputQuantity);
            if (identifier.isEmpty()) {
                return;
            }
            long semantic = calculator.flatMap(c -> c.input(inputQuantity)).map(Input::expectedSemantic).orElse(0L);
            Optional<String> unit = semantic == 0 ? Optional.empty()
                    : ids.identifier(semantic).map(id -> Tokens.concept(ids.label(semantic), id));
            CalculationEdits.withAssumption(body, identifier.get(), Bounds.point(value), unit).ifPresent(restated -> {
                if (!restated.equals(body)) {
                    body = restated;
                    moves++;
                    rebuild();
                }
            });
        }

        @Override
        public String shown(Bounds result) {
            return format(result, committed == null ? "" : unitOf(committed));
        }
    }

    // ── Shared pieces ───────────────────────────────────────────────────────

    /** The concept an element names, as an Optional of its nid. */
    private static Optional<Long> conceptOf(CalculatorElement element) {
        return optional(element.concept());
    }

    private static Optional<Long> optional(OptionalLong nid) {
        return nid.isPresent() ? Optional.of(nid.getAsLong()) : Optional.empty();
    }

    private GridPane rail() {
        GridPane rail = new GridPane();
        rail.setHgap(10);
        rail.setVgap(3);
        rail.setPadding(new Insets(2, 0, 2, 0));
        ColumnConstraints name = new ColumnConstraints();
        name.setMinWidth(120);
        ColumnConstraints binding = new ColumnConstraints();
        binding.setMinWidth(140);
        ColumnConstraints value = new ColumnConstraints();
        value.setHgrow(Priority.ALWAYS);
        ColumnConstraints status = new ColumnConstraints();
        rail.getColumnConstraints().addAll(name, binding, value, status);
        return rail;
    }

    private Optional<Node> concept(OptionalLong nid) {
        if (nid.isEmpty() || nid.getAsLong() == 0) {
            return Optional.empty();
        }
        Identities ids = context.identities(viewCalc);
        if (ids.isLocal(nid.getAsLong())) {
            return Optional.of(localConcept(ids.label(nid.getAsLong())));
        }
        Optional<PublicId> pid = ComponentText.publicId(nid.getAsLong());
        if (pid.isEmpty()) {
            return Optional.empty();
        }
        String sctid = ComponentText.sctid(viewCalc, nid.getAsLong()).orElse(null);
        Node chip = viewProperties != null
                ? KonceptChips.chip(pid.get(), sctid, viewProperties, base, typography)
                : KonceptChips.chip(pid.get(), sctid, viewCalc, base, typography);
        if (chip instanceof KonceptBadge badge) {
            badge.setStatusSlotReserved(true);
        }
        return Optional.of(chip);
    }

    private Node findingsOnly(String heading, List<ProfileFinding> findings) {
        VBox column = new VBox(2);
        Label title = new Label(heading);
        title.setFont(Font.font(null, FontWeight.BOLD, base));
        column.getChildren().add(title);
        for (ProfileFinding finding : findings) {
            Label entry = new Label("• " + finding.message());
            entry.setFont(Font.font(base));
            entry.setWrapText(true);
            column.getChildren().add(entry);
        }
        return column;
    }

    private Label gaps(List<String> messages) {
        Label gaps = new Label(String.join("\n", messages));
        gaps.setFont(Font.font(base * 0.9));
        gaps.setWrapText(true);
        gaps.setStyle("-fx-text-fill: #8a5a00;");
        return gaps;
    }

    private Label plain(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(base));
        return label;
    }

    private Label muted(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(base * 0.85));
        label.setTextFill(Color.web("#555"));
        label.setWrapText(true);
        return label;
    }

    private Label localConcept(String text) {
        Label chip = new Label(text);
        chip.setFont(Font.font(base * 0.95));
        chip.getStyleClass().add("decision-local-concept");
        chip.setStyle("-fx-background-color: #f4f2ec; -fx-background-radius: 9; -fx-padding: 1 7 1 7;"
                + " -fx-text-fill: #333; -fx-border-color: #b9b3a3; -fx-border-radius: 9; -fx-border-style: dashed;"
                + " -fx-border-width: 1;");
        chip.setTooltip(new Tooltip("Not in the knowledge base: read as a concept local to this block"));
        return chip;
    }
}
