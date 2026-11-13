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

import network.ike.komet.document.KonceptChips;

import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.benefitrisk.DeviceRecord;
import network.ike.komet.claude.benefitrisk.EffectSize;
import network.ike.komet.claude.benefitrisk.EffectsReading;
import network.ike.komet.claude.benefitrisk.EffectsReading.Row;
import network.ike.komet.claude.benefitrisk.EffectsReading.Verdict;
import network.ike.komet.claude.benefitrisk.EffectsTable;
import network.ike.komet.claude.benefitrisk.Findings;
import network.ike.komet.claude.benefitrisk.ForestFigure;
import network.ike.komet.claude.benefitrisk.ValueTree;
import network.ike.komet.claude.benefitrisk.ValueTree.Kind;
import network.ike.komet.claude.benefitrisk.ValueTree.Part;
import network.ike.komet.claude.benefitrisk.ValueTreeFigure;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.document.DecisionFigure;
import network.ike.komet.document.TextFigure;
import network.ike.komet.document.print.FigureNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The three views of a benefit-risk consult (ike-lab-documents topic
 * {@code dev-benefit-risk-blocks}): a {@code [value-tree]}, an {@code [effects-table]} naming the
 * tree and a {@code [forest-plot]} naming the table. A proof of concept: the blocks read from
 * their text and draw, edited as text, and nothing is written to the store.
 *
 * <p>A {@code [device-record]}, a {@code [findings]} block and a {@code [findings-lookup]} add a mock
 * of the addition to the Device Extension proposed in topic {@code rda-dex-findings}: the lookup
 * shows which findings reach the device, and its choice of year moves every view of the document.
 *
 * <p>Each block publishes its text on the document's {@link ProfileBlocks} and rebuilds when a block
 * it names lands, so the table and the plot follow the tree whichever is drawn first.
 */
final class BenefitRiskBlockRenderer implements network.ike.komet.document.StructuredBlockKind.FenceRenderer {

    /** Which of the three views a block is. */
    enum View { TREE, TABLE, FOREST, DEVICE, FINDINGS, LOOKUP }

    /** The year a lookup reads as "known now": later than any finding. */
    private static final int NOW = 9999;

    /** The natural width of the forest plot on the surface, at {@link #FOREST_SIZE}. */
    private static final double FOREST_WIDTH = 640;
    private static final double FOREST_SIZE = 10.5;
    private static final double COLUMN_GAP = 40;
    private static final double NAME_EMS = 13;
    private static final double ARM_EMS = 8;
    /** How long a cell may run on one line; a longer one wraps between its words. */
    private static final int WRAP_AFTER = 18;
    private static final double ROW_GAP = 8;
    private static final String CAPTION = "-fx-text-fill: #666;";

    private final View view;
    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private final ProfileBlocks context;

    BenefitRiskBlockRenderer(View view, ViewCalculator viewCalc, ViewProperties viewProperties, double base, ProfileBlocks context) {
        this.view = view;
        this.viewCalc = viewCalc;
        this.viewProperties = viewProperties;
        this.base = base;
        this.context = context == null ? new ProfileBlocks() : context;
    }

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        String body = literal == null ? "" : literal;
        publish(info, body, context);
        return List.of(new BlockPiece(() -> new Host(info, body).node, body.stripTrailing()));
    }

    /** Puts a tree or table on the context, for the blocks that name it. */
    static void publish(String info, String body, ProfileBlocks context) {
        attribute(info, "id").ifPresent(id -> context.defineEvidenceBlock(id, info, body));
    }

    /** A named attribute of a block's info string. */
    private static Optional<String> attribute(String info, String name) {
        for (String word : Tokens.words(info == null ? "" : info)) {
            if (word.startsWith(name + "=") && word.length() > name.length() + 1) {
                return Optional.of(word.substring(name.length() + 1).replace("\"", ""));
            }
        }
        return Optional.empty();
    }

    /** The effects table of a block's text, read against the tree it names; empty until that tree has landed. */
    private static Optional<EffectsReading> reading(String tableInfo, String tableBody, ProfileBlocks context) {
        return attribute(tableInfo, "tree").flatMap(context::evidenceBlock)
                .flatMap(tree -> EffectsReading.of(ValueTree.parse(tree.body()), EffectsTable.parse(tableBody), findings(tableInfo, context),
                        context.evidenceYear()));
    }

    /** The findings a block's {@code findings=} attribute names, none when it names none or they have not landed. */
    private static Findings findings(String info, ProfileBlocks context) {
        return attribute(info, "findings").flatMap(context::evidenceBlock).map(block -> Findings.parse(block.body(), context::statement))
                .orElse(new Findings(List.of(), List.of()));
    }

    private static Optional<DeviceRecord> device(String info, ProfileBlocks context) {
        return attribute(info, "device").flatMap(context::evidenceBlock).map(block -> DeviceRecord.parse(block.body()));
    }

    /** The rows of a plot of findings: each share on the plot's topic in print by the year, with its interval. */
    private static List<ForestFigure.Line> shares(String info, Findings findings, int year) {
        Optional<String> topic = attribute(info, "topic");
        List<ForestFigure.Line> lines = new ArrayList<>();
        for (Findings.Finding finding : findings.inPrintBy(year)) {
            if (finding.sharePercent().isPresent() && finding.among().isPresent() && topic.filter(t -> !t.equalsIgnoreCase(finding.topic())).isEmpty()) {
                double[] share = Findings.wilson(finding.sharePercent().getAsDouble(), finding.among().getAsInt());
                lines.add(new ForestFigure.Line("", finding.source() + ", " + finding.among().getAsInt() + " " + finding.unit() + ", " + finding.period(),
                        Optional.of(share[0]), share[1], share[2], false,
                        String.format(java.util.Locale.ROOT, "%.0f (%.0f, %.0f)", share[0], share[1], share[2])));
            }
        }
        return lines;
    }

    private static DecisionFigure shareFigure(List<ForestFigure.Line> lines, double width, double size) {
        return ForestFigure.build(lines, new ForestFigure.Axis(0, 100, 20, 0, "Percent (95% CI)", List.of("Percent removed"),
                tick -> String.format(java.util.Locale.ROOT, "%.0f", tick)), width, size);
    }

    /** The year of a lookup's first choice, which a document opens on. */
    private static int firstYear(String info) {
        return attribute(info, "year").map(Integer::parseInt).orElse(NOW);
    }

    private static String yearWords(int year) {
        return year >= NOW ? "Known now" : "In print by " + year;
    }

    /** What the plot draws: the table a {@code table=} attribute names, read. */
    private static Optional<EffectsReading> reading(String forestInfo, ProfileBlocks context) {
        return attribute(forestInfo, "table").flatMap(context::evidenceBlock)
                .flatMap(table -> reading(table.info(), table.body(), context));
    }

    /** What a block is on a page. */
    static List<DecisionFigure> figures(View view, String info, String body, ProfileBlocks context) {
        publish(info, body, context);
        return switch (view) {
            case TREE -> ValueTree.parse(body).goal()
                    .map(goal -> TextFigure.titled("Value tree", ValueTreeFigure.build(goal, TextFigure.WIDTH, 8.5))).map(List::of).orElse(List.of());
            case TABLE -> reading(info, body, context).map(BenefitRiskBlockRenderer::pageTable).map(List::of).orElse(List.of());
            case FOREST -> {
                if (attribute(info, "findings").isPresent()) {
                    List<ForestFigure.Line> lines = shares(info, findings(info, context), context.evidenceYear());
                    yield lines.isEmpty() ? List.of() : List.of(shareFigure(lines, TextFigure.WIDTH, 8.5));
                }
                yield reading(info, context).map(read -> ForestFigure.build(read, TextFigure.WIDTH, 8.5)).map(List::of).orElse(List.of());
            }
            case DEVICE -> {
                DeviceRecord record = DeviceRecord.parse(body);
                List<List<String>> table = new ArrayList<>();
                table.add(List.of("field", "value"));
                record.materials().forEach(m -> table.add(List.of("material", m.name())));
                record.targets().forEach(t -> table.add(List.of("target", t.name())));
                record.hazards().forEach(h -> table.add(List.of("hazard", h.name())));
                record.statements().forEach(line -> table.add(List.of("states", line)));
                yield List.of(TextFigure.of("device-record", record.device().map(Findings.Named::name), List.of(), table));
            }
            case FINDINGS -> {
                List<List<String>> table = new ArrayList<>();
                table.add(List.of("In print", "Finding", "Source"));
                Findings.parse(body, context::statement).all().forEach(f -> table.add(List.of(String.valueOf(f.inPrint()), f.statement(), f.source())));
                yield List.of(TextFigure.of("findings", Optional.of("Mock of the proposed findings"), List.of(), table));
            }
            case LOOKUP -> {
                settleYear(info, context);
                yield device(info, context).<List<DecisionFigure>>map(record -> {
                    List<List<String>> table = new ArrayList<>();
                    table.add(List.of("In print", "Finding", "Why it reaches this device"));
                    record.reached(findings(info, context), context.evidenceYear())
                            .forEach(r -> table.add(List.of(String.valueOf(r.finding().inPrint()), r.finding().statement(), r.why())));
                    return List.of(TextFigure.of("findings-lookup", Optional.of(yearWords(context.evidenceYear())), List.of(), table));
                }).orElse(List.of());
            }
        };
    }

    /** A lookup opens the document on its first year, once: after that the reader's choice stands. */
    private static void settleYear(String info, ProfileBlocks context) {
        if (context.evidenceYear() == Integer.MAX_VALUE) {
            context.setEvidenceYear(firstYear(info));
        }
    }

    private static DecisionFigure pageTable(EffectsReading read) {
        List<List<String>> table = new ArrayList<>();
        table.add(List.of("Criterion", read.first().name(), read.second().name(), "Effect (95% CI)", "Favors"));
        for (Row row : read.rows()) {
            table.add(List.of(row.leaf().part().name(), EffectsReading.words(row.firstArm()), EffectsReading.words(row.secondArm()),
                    row.effect().map(EffectSize::words).orElse("None"), row.favors()));
        }
        return TextFigure.of("effects-table", Optional.of("Effects table"), List.of(read.summary(), read.legend()), table);
    }

    // ── The block on the surface (JavaFX application thread) ─────────────

    /** One block's node, redrawn when a block it names lands. */
    private final class Host {
        final VBox node = new VBox(6);
        private final String info;
        private final String body;
        /** What the view last drew from, so a change elsewhere in the document leaves it alone. */
        private String drawnFrom;

        Host(String info, String body) {
            this.info = info;
            this.body = body;
            node.getStyleClass().add("benefit-risk");
            node.setPadding(new Insets(4, 0, 4, 0));
            node.setFillWidth(true);
            node.parentProperty().addListener((obs, oldParent, newParent) -> {
                node.maxWidthProperty().unbind();
                if (newParent instanceof Region host) {
                    node.maxWidthProperty().bind(host.widthProperty());
                }
            });
            Runnable rebuild = () -> Platform.runLater(this::draw);
            // the context holds its listeners weakly: the node keeps this one for as long as it is shown
            node.getProperties().put("benefit-risk-listener", rebuild);
            context.listen(rebuild);
            draw();
        }

        private void draw() {
            if (view == View.LOOKUP) {
                settleYear(info, context);
            }
            String from = view == View.TREE || view == View.DEVICE ? body : context.evidenceState();
            if (from.equals(drawnFrom)) {
                return;
            }
            drawnFrom = from;
            node.getChildren().setAll(switch (view) {
                case TREE -> tree(body);
                case TABLE -> reading(info, body, context).map(BenefitRiskBlockRenderer.this::table).orElseGet(() -> waiting("the value tree this table names"));
                case FOREST -> attribute(info, "findings").isPresent() ? shareForest(info)
                        : reading(info, context).map(BenefitRiskBlockRenderer.this::forest).orElseGet(() -> waiting("the effects table this plot names"));
                case DEVICE -> deviceCard(body);
                case FINDINGS -> findingsLine(body);
                case LOOKUP -> device(info, context).map(record -> lookup(info, record)).orElseGet(() -> waiting("the device record this lookup names"));
            });
        }
    }

    private List<Node> waiting(String what) {
        return List.of(caption("Waiting for " + what + "."));
    }

    private List<Node> tree(String body) {
        ValueTree tree = ValueTree.parse(body);
        return tree.goal().<List<Node>>map(goal -> {
            List<Node> shown = new ArrayList<>();
            shown.add(heading("Value tree"));
            shown.add(new FittedFigure(new TreeDiagram(goal), base, width -> { }, null));
            shown.addAll(findings(tree.findings()));
            return shown;
        }).orElseGet(() -> findings(tree.findings()));
    }

    private List<Node> table(EffectsReading read) {
        GridPane grid = new GridPane();
        grid.setId("effects-table");
        grid.setHgap(14);
        grid.setVgap(4);
        // the names keep a readable measure: they wrap between words, and the numbers give way first
        javafx.scene.layout.ColumnConstraints names = new javafx.scene.layout.ColumnConstraints();
        names.setMinWidth(base * NAME_EMS);
        names.setPrefWidth(base * NAME_EMS);
        names.setMaxWidth(base * NAME_EMS);
        grid.getColumnConstraints().add(names);
        List<String> header = List.of("Criterion", read.first().name(), read.second().name(), "Effect (95% CI)", "Favors");
        for (int c = 0; c < header.size(); c++) {
            Label cell = cell(header.get(c), c == 1 || c == 2);
            cell.setFont(Font.font(null, FontWeight.BOLD, base * 0.85));
            grid.add(cell, c, 0);
        }
        int r = 1;
        Optional<Part> shownDomain = Optional.empty();
        boolean gates = false;
        for (Row row : read.rows()) {
            Part part = row.leaf().part();
            Optional<Part> domain = row.leaf().domain();
            String group = null;
            if (part.kind() == Kind.GATE) {
                group = gates ? null : "Gates, not traded";
                gates = true;
            } else if (domain.isPresent() && !domain.equals(shownDomain)) {
                shownDomain = domain;
                group = domain.get().name();
            }
            if (group != null) {
                Label label = new Label(group);
                label.setFont(Font.font(null, FontWeight.NORMAL, FontPosture.ITALIC, base * 0.85));
                label.setStyle(CAPTION);
                label.setPadding(new Insets(4, 0, 0, 0));
                grid.add(label, 0, r++, header.size(), 1);
            }
            grid.add(KonceptChips.capped(KonceptChips.blockConcept(part.name(), viewProperties, viewCalc, base, null), base * (NAME_EMS - 1), true), 0, r);
            grid.add(arm(row.firstArm()), 1, r);
            grid.add(arm(row.secondArm()), 2, r);
            Label effect = cell(row.effect().map(EffectSize::words).orElse(part.kind() == Kind.GATE ? "Not an effect" : "None"), false);
            if (!row.method().isEmpty()) {
                effect.setTooltip(new Tooltip("Computed by Komet: " + row.method()));
            }
            grid.add(effect, 3, r);
            Label favors = cell(row.favors(), true);
            boolean decided = row.verdict() == Verdict.FIRST || row.verdict() == Verdict.SECOND || row.verdict() == Verdict.GATE_FAILED;
            favors.setFont(Font.font(null, decided ? FontWeight.BOLD : FontWeight.NORMAL, base * 0.85));
            favors.setStyle(decided ? "-fx-text-fill: #1f3b73;" : CAPTION);
            grid.add(favors, 4, r);
            r++;
        }
        List<Node> shown = new ArrayList<>();
        shown.add(heading("Effects table, " + read.first().name() + " against " + read.second().name()));
        shown.add(new FittedFigure(grid, base, 0));
        shown.add(sentence(read.summary()));
        shown.add(caption(read.legend()));
        shown.addAll(findings(read.findings()));
        return shown;
    }

    private Label arm(Optional<EffectsTable.Arm> arm) {
        // a value stands over its spread, so two arms and an interval fit beside a readable name
        Label cell = cell(EffectsReading.words(arm).replace(" (", "\n("), true);
        arm.flatMap(EffectsTable.Arm::source).ifPresent(source -> cell.setTooltip(new Tooltip("Source: " + source)));
        return cell;
    }

    private Label cell(String text, boolean mayWrap) {
        Label cell = new Label(text);
        cell.setFont(Font.font(base * 0.85));
        boolean wraps = mayWrap && text.length() > WRAP_AFTER && !text.contains("\n");
        cell.setWrapText(wraps);
        cell.setMaxWidth(wraps ? base * ARM_EMS : Region.USE_PREF_SIZE);
        cell.setMinWidth(wraps ? base * ARM_EMS : Region.USE_PREF_SIZE);
        return cell;
    }

    private List<Node> forest(EffectsReading read) {
        DecisionFigure figure = ForestFigure.build(read, FOREST_WIDTH, FOREST_SIZE);
        List<Node> shown = new ArrayList<>();
        shown.add(heading("Forest plot of the effects table"));
        javafx.scene.layout.Pane drawn = FigureNode.of(figure, 1, "System");
        drawn.setId("forest-plot");
        shown.add(new FittedFigure(drawn, base, width -> { }, null));
        shown.add(caption("A filled square: the interval leaves out zero. A hollow square: it does not. "
                + read.legend() + " Gates are not effects, so they are not drawn."));
        return shown;
    }

    private List<Node> shareForest(String info) {
        List<ForestFigure.Line> lines = shares(info, findings(info, context), context.evidenceYear());
        List<Node> shown = new ArrayList<>();
        shown.add(heading("Share of implants removed, as each report counted it"));
        if (lines.isEmpty()) {
            shown.add(sentence(yearWords(context.evidenceYear()) + ": no report had counted a removal."));
            return shown;
        }
        javafx.scene.layout.Pane drawn = FigureNode.of(shareFigure(lines, FOREST_WIDTH, FOREST_SIZE), 1, "System");
        drawn.setId("share-plot");
        shown.add(new FittedFigure(drawn, base, width -> { }, null));
        shown.add(caption("Komet computes each 95% Wilson interval from the share and the number counted. The reports counted "
                + "different things over different times, so their shares are not one estimate."));
        return shown;
    }

    private List<Node> deviceCard(String body) {
        DeviceRecord record = DeviceRecord.parse(body);
        List<Node> shown = new ArrayList<>();
        shown.add(heading(record.isTestRecord() ? "What a test record would state" : "What a device record would state"));
        record.device().ifPresent(device -> shown.add(chip(device.name())));
        for (String line : record.statements()) {
            shown.add(sentence(line));
        }
        // a record of materials draws its row even when it names none, as it always has
        if (!record.materials().isEmpty() || record.targets().isEmpty()) {
            shown.add(chipRow("Materials", record.materials()));
        }
        if (!record.targets().isEmpty()) {
            shown.add(chipRow("Targets", record.targets()));
        }
        record.family().ifPresent(family -> shown.add(chipRow("Family", List.of(family))));
        shown.add(chipRow("Hazards of its design", record.hazards()));
        shown.addAll(findings(record.problems()));
        return shown;
    }

    private Node chip(String name) {
        return KonceptChips.capped(KonceptChips.blockConcept(name, viewProperties, viewCalc, base, null), base * 30);
    }

    private Node chipRow(String label, List<Findings.Named> concepts) {
        Label title = caption(label);
        title.setMinWidth(base * 9);
        title.setMaxWidth(base * 9);
        VBox chips = new VBox(3);
        concepts.forEach(concept -> chips.getChildren().add(chip(concept.name())));
        return new HBox(8, title, chips);
    }

    private List<Node> findingsLine(String body) {
        Findings held = Findings.parse(body, context::statement);
        List<Node> shown = new ArrayList<>();
        Label mock = sentence("Mock of the proposed findings: " + held.all().size() + " findings. DeX holds no findings against " + held.filedAgainst() + " today. "
                + "These were drafted for this document from published reports, and no curator has accepted them.");
        mock.setStyle("-fx-text-fill: #b5541a;");
        shown.add(mock);
        if (held.narrativeOnly() > 0) {
            shown.add(caption(held.narrativeOnly() + " of them hold narrative only: they have no scale yet, so they can be read but not counted."));
        }
        shown.addAll(findings(held.problems()));
        return shown;
    }

    private List<Node> lookup(String info, DeviceRecord record) {
        Findings held = findings(info, context);
        int year = context.evidenceYear();
        network.ike.komet.document.SegmentedControl<Integer> years = new network.ike.komet.document.SegmentedControl<>(
                List.of(firstYear(info), NOW), BenefitRiskBlockRenderer::yearWords, y -> "Read every view of this document " + yearWords(y).toLowerCase(java.util.Locale.ROOT),
                y -> y >= NOW ? "evidence-known-now" : "evidence-in-print");
        years.light(base * 0.85);
        years.select(year);
        years.valueProperty().addListener((obs, was, chosen) -> {
            if (chosen != null) {
                context.setEvidenceYear(chosen);
            }
        });
        List<DeviceRecord.Reach> reached = record.reached(held, year);
        int inPrint = held.inPrintBy(year).size();
        List<Node> shown = new ArrayList<>();
        shown.add(heading("What the findings would have shown the reviewer"));
        shown.add(years);
        shown.add(sentence(reached.size() + " of the " + inPrint + " findings in the mock reach this " + (record.isTestRecord() ? "test." : "device.")));
        for (DeviceRecord.Reach reach : reached) {
            shown.add(findingRow(reach));
        }
        shown.addAll(findings(held.problems()));
        return shown;
    }

    private Node findingRow(DeviceRecord.Reach reach) {
        Findings.Finding finding = reach.finding();
        Label year = new Label(String.valueOf(finding.inPrint()));
        year.setFont(Font.font(null, FontWeight.BOLD, base * 0.85));
        year.setMinWidth(Region.USE_PREF_SIZE);
        Label found = sentence(finding.statement());
        Label why = caption(reach.why() + ". " + finding.group() + ". " + finding.source() + ".");
        VBox words = new VBox(1, found, why);
        HBox.setHgrow(words, javafx.scene.layout.Priority.ALWAYS);
        HBox row = new HBox(10, year, words, statementMark(finding));
        row.setPadding(new Insets(3, 0, 0, 0));
        javafx.scene.control.Tooltip.install(row, new Tooltip(fiveThings(finding)));
        return row;
    }

    /** What a finding carries, so a later reviewer can judge it. */
    private static String fiveThings(Findings.Finding finding) {
        return "Observed: " + finding.topic() + "\nCounted: " + finding.countedAmong() + "\nOver: " + finding.period()
                + "\nConditions: " + finding.group() + "\nHow: " + finding.method() + "\nSource: " + finding.source()
                + "\nDrafted for this document; no curator has accepted it.";
    }

    /** The mark that a finding is a statement: resting on it shows the statement, and a click peeks at its card. */
    private Node statementMark(Findings.Finding finding) {
        List<String> names = new ArrayList<>(List.of(finding.statementName()));
        finding.countName().ifPresent(names::add);
        Label mark = new Label("ANF");
        mark.setId("anf-statement-mark");
        mark.setMinWidth(Region.USE_PREF_SIZE);
        mark.setFont(Font.font(null, FontWeight.BOLD, base * 0.7));
        mark.setStyle("-fx-background-color: #e6eef8; -fx-text-fill: #1f3b73; -fx-background-radius: 4;"
                + " -fx-padding: 0 4 0 4; -fx-border-color: #9db3d6; -fx-border-radius: 4; -fx-border-width: 1;");
        mark.setCursor(javafx.scene.Cursor.HAND);
        Tooltip tip = new Tooltip(String.join("\n\n", names.stream().map(name -> context.statement(name)
                .map(statement -> "anf:" + name + "\n" + AnfAdoc.toAdoc(statement).strip()).orElse("anf:" + name)).toList())
                + "\n\nClick to see and edit the statement");
        tip.setFont(Font.font("Menlo", base * 0.8));
        mark.setTooltip(tip);
        mark.setOnMouseClicked(e -> {
            VBox cards = new VBox(6);
            for (String name : names) {
                context.statementCard(name).ifPresent(card -> {
                    Label title = heading("anf:" + name);
                    cards.getChildren().addAll(title, card.get());
                });
            }
            if (cards.getChildren().isEmpty()) {
                return;
            }
            javafx.geometry.Bounds at = mark.localToScreen(mark.getBoundsInLocal());
            dev.ikm.komet.framework.controls.PeekPopover.show(cards, base * 36, "ANF statement", mark,
                    at == null ? 0 : at.getMinX() + at.getWidth() / 2, at == null ? 0 : at.getMaxY(), null, () -> { });
            e.consume();
        });
        return mark;
    }

    private Label heading(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(null, FontWeight.BOLD, base * 0.9));
        return label;
    }

    private Label sentence(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setFont(Font.font(base * 0.85));
        return label;
    }

    private Label caption(String text) {
        Label label = sentence(text);
        label.setStyle(CAPTION);
        return label;
    }

    private List<Node> findings(List<String> findings) {
        List<Node> shown = new ArrayList<>();
        for (String finding : findings) {
            Label label = sentence(finding);
            label.setStyle("-fx-text-fill: #b5541a;");
            shown.add(label);
        }
        return shown;
    }

    /** The tree drawn left to right: each level a column, each parent centered on its children. */
    private final class TreeDiagram extends Region {
        private final Box root;
        private final List<Box> boxes = new ArrayList<>();
        private final Path edges = new Path();

        private final class Box {
            final Region region;
            final List<Box> children = new ArrayList<>();
            final int depth;
            double center;

            Box(Part part, int depth) {
                this.depth = depth;
                this.region = chip(part);
                boxes.add(this);
                part.parts().forEach(child -> children.add(new Box(child, depth + 1)));
            }
        }

        TreeDiagram(Part goal) {
            setId("value-tree");
            edges.setStroke(Color.web("#777777"));
            edges.setStrokeWidth(1);
            edges.setManaged(false);
            getChildren().add(edges);
            root = new Box(goal, 0);
            boxes.forEach(box -> getChildren().add(box.region));
        }

        private Region chip(Part part) {
            double cap = base * (part.kind() == Kind.CRITERION ? 19 : 12);
            Node concept = KonceptChips.capped(KonceptChips.blockConcept(part.name(), viewProperties, viewCalc, base, null), cap, true);
            VBox chip = new VBox(1, concept);
            chip.setAlignment(Pos.CENTER_LEFT);
            String tag = part.reading();
            if (!tag.isEmpty()) {
                Label label = new Label(tag);
                label.setFont(Font.font(base * 0.75));
                label.setStyle(part.kind() == Kind.GATE ? "-fx-text-fill: #b5541a;" : CAPTION);
                chip.getChildren().add(label);
            }
            return chip;
        }

        private double[] columns() {
            double[] widths = new double[boxes.stream().mapToInt(b -> b.depth).max().orElse(0) + 1];
            for (Box box : boxes) {
                widths[box.depth] = Math.max(widths[box.depth], box.region.prefWidth(-1));
            }
            return widths;
        }

        private double height(Box box) {
            return box.region.prefHeight(box.region.prefWidth(-1));
        }

        /** Places a box and all beneath it from a top edge; a parent's center falls midway over its children. */
        private double place(Box box, double top) {
            if (box.children.isEmpty()) {
                box.center = top + height(box) / 2;
                return top + height(box);
            }
            double y = top;
            for (int i = 0; i < box.children.size(); i++) {
                y = place(box.children.get(i), y + (i == 0 ? 0 : ROW_GAP));
            }
            box.center = (box.children.getFirst().center + box.children.getLast().center) / 2;
            // a parent taller than what stands beneath it would reach above its own top edge, into the box before it
            double over = top - (box.center - height(box) / 2);
            if (over > 0) {
                shift(box, over);
                y += over;
            }
            return Math.max(y, box.center + height(box) / 2);
        }

        private void shift(Box box, double by) {
            box.center += by;
            box.children.forEach(child -> shift(child, by));
        }

        @Override
        protected double computePrefWidth(double height) {
            double total = 0;
            for (double width : columns()) {
                total += width + COLUMN_GAP;
            }
            return total - COLUMN_GAP;
        }

        @Override
        protected double computePrefHeight(double width) {
            return place(root, 0);
        }

        @Override
        protected void layoutChildren() {
            double[] widths = columns();
            double[] lefts = new double[widths.length];
            for (int i = 1; i < widths.length; i++) {
                lefts[i] = lefts[i - 1] + widths[i - 1] + COLUMN_GAP;
            }
            place(root, 0);
            edges.getElements().clear();
            for (Box box : boxes) {
                double w = box.region.prefWidth(-1);
                double h = height(box);
                box.region.resizeRelocate(lefts[box.depth], box.center - h / 2, w, h);
                double out = lefts[box.depth] + w + 3;
                double elbow = lefts[box.depth] + widths[box.depth] + COLUMN_GAP / 2;
                for (Box child : box.children) {
                    edges.getElements().addAll(new MoveTo(out, box.center), new LineTo(elbow, box.center),
                            new LineTo(elbow, child.center), new LineTo(lefts[child.depth] - 3, child.center));
                }
            }
        }
    }
}
