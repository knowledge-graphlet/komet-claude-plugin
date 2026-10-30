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
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.decision.BlockSweep;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionElement;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.HealthCost;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeEdits;
import network.ike.komet.claude.decision.DecisionTreeFeedback;
import network.ike.komet.claude.decision.DecisionTreeSensitivity;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Flip;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Swing;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.decision.Derivations;
import network.ike.komet.claude.decision.Rollback;
import network.ike.komet.claude.decision.ValuationCoordinate;
import network.ike.komet.claude.decision.ViewIdentities;
import network.ike.komet.claude.koncept.ComponentText;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The live rendering of a {@code decision-tree} block on the assistant surface
 * ({@code dev-decision-tree-blocks}): the tree drawn as decision analysts draw it, by the
 * {@link DecisionTreeDiagram} on the shared {@link network.ike.komet.claude.decision.DecisionTreeLayout},
 * with the model's parameters above it, each node's concept as the shared Koncept chip, and the
 * rolled-back value beside every glyph under a valuation coordinate, computed on the view and
 * never stored. The reader may move any stated number and the health price as a what-if: the
 * block's text is restated on that one line, read again through the same parser, and the tree
 * redrawn, so the what-if stays what the block would say if written so. It stays a what-if until
 * the reader writes it back, which hands the restated block to the surface's
 * {@link BlockRewriter}; a surface without one offers the what-if alone. A finding of the parse,
 * the profile's checks or the rollback is listed beneath the tree and shown at the glyph it sits
 * on. Beneath that, the block's one-way sensitivity is drawn as a {@link TornadoChart}: the
 * {@link BlockSweep} runs off the application thread, since it reads the block once per step,
 * and the chart lands when it is done, with a mark on every chip whose range flips the choice.
 *
 * <p>The block declines when it is not this profile's, when it has no root decision line, or
 * when there is no view to resolve against, so the text renders preformatted as any other
 * unrendered block does. The block's info may set the price: {@code decision-tree
 * health-price=100000}; the default is $100,000, the regulatory topics' central value.
 */
final class DecisionTreeBlockRenderer implements BlockRenderer {

    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private KonceptLabelTypography typography = KonceptLabelTypography.DEFAULT;
    private BlockRewriter rewriter;

    /** Where a sweep runs: one thread behind the surface; a test may run it in place. */
    static Executor sweepExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "decision-tree-sweep");
        thread.setDaemon(true);
        return thread;
    });

    DecisionTreeBlockRenderer(ViewCalculator viewCalc, double base) {
        this(viewCalc, null, base);
    }

    DecisionTreeBlockRenderer(ViewProperties viewProperties, double base) {
        this(viewProperties == null ? null : viewProperties.calculator(), viewProperties, base);
    }

    private DecisionTreeBlockRenderer(ViewCalculator viewCalc, ViewProperties viewProperties, double base) {
        this.viewCalc = viewCalc;
        this.viewProperties = viewProperties;
        this.base = base;
    }

    void setChipTypography(KonceptLabelTypography typography) {
        this.typography = typography == null ? KonceptLabelTypography.DEFAULT : typography;
    }

    /** Where a what-if goes when the reader writes it back; null offers the what-if alone. */
    void setRewriter(BlockRewriter rewriter) {
        this.rewriter = rewriter;
    }

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        if (viewCalc == null || !accepts(info, literal)) {
            return List.of();
        }
        String body = literal == null ? "" : literal;
        double price = DecisionTreeText.healthPrice(info).orElse(DecisionTreeText.DEFAULT_HEALTH_PRICE);
        return List.of(new BlockPiece(() -> new Live(info, body, price).column, body.stripTrailing()));
    }

    // ── Pure decisions (no store access) ────────────────────────────────

    /**
     * Whether a block is this profile's: tagged {@code decision-tree}, or {@code property-graph}
     * with {@code profile=decision-tree}, and opening with a root decision line.
     */
    static boolean accepts(String info, String literal) {
        if (!DecisionTreeText.isTagged(info)) {
            return false;
        }
        BlockLines.Parsed lines = BlockLines.parse(literal);
        return lines.roots().size() == 1
                && lines.roots().get(0).form() == BlockLines.Form.VERTEX
                && "decision".equals(lines.roots().get(0).word());
    }

    /**
     * A rolled-back value, which is money, to the whole unit, as the regulatory topics print
     * their worked numbers: a point as one number, an interval with its two bounds.
     */
    static String money(Bounds bounds) {
        if (bounds.isPoint()) {
            return String.format(Locale.ROOT, "%,d", Math.round(bounds.lower()));
        }
        return (bounds.lowerOpen() ? "(" : "[") + String.format(Locale.ROOT, "%,d", Math.round(bounds.lower()))
                + ", " + String.format(Locale.ROOT, "%,d", Math.round(bounds.upper())) + (bounds.upperOpen() ? ")" : "]");
    }

    // ── The live block (JavaFX application thread) ───────────────────────

    /**
     * One rendering of the block: the text it was read from, the text the what-if has made of
     * it, the price, which nodes are folded, and the column that shows it all, rebuilt whenever
     * any of these move.
     */
    private final class Live implements DecisionTreeDiagram.Host, TornadoChart.Host {
        final String originalInfo;
        final String originalBody;
        final double originalPrice;
        String body;
        double price;
        int moves;
        final Set<Integer> collapsed = new HashSet<>();
        final VBox column = new VBox(6);
        final DecisionTerms terms = DecisionTerms.fromBindings();
        // The view resolves what the store holds; a symbol the store lacks, as a block written for
        // the documentation build names its concepts, is read as a concept local to the block.
        final Identities ids = new BlockIdentities(new ViewIdentities(viewCalc));
        DecisionTreeText.Parsed parsed;
        DecisionTree tree;
        DecisionTreeDiagram diagram;
        TornadoChart tornado;
        Label priceMark;
        final Map<Integer, MeasureChip> parameterChips = new HashMap<>();
        /** Which rebuild a sweep belongs to: a sweep that lands after the next rebuild is dropped. */
        int generation;
        /** The top of the column: header, parameters and the drawing, redrawn alone during a preview. */
        final VBox top = new VBox(6);
        /** The rollback the block stands at, which a preview is drawn against. */
        Rollback committed;
        List<ProfileFinding> committedFindings = List.of();
        DecisionTreeSensitivity lastSweep;
        boolean previewing;
        /** The preview asked for last, applied once per pulse so a dragged slider does not queue redraws. */
        Runnable pendingPreview;

        Live(String info, String body, double price) {
            this.originalInfo = info;
            this.originalBody = body;
            this.originalPrice = price;
            this.body = body;
            this.price = price;
            column.getStyleClass().add("decision-tree");
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
            rebuild();
        }

        void rebuild() {
            generation++;
            tornado = null;
            lastSweep = null;
            previewing = false;
            pendingPreview = null;
            parameterChips.clear();
            parsed = DecisionTreeText.parse(body, terms, ids);
            List<ProfileFinding> findings = new ArrayList<>(parsed.findings().castToList());
            column.getChildren().clear();
            if (parsed.tree().isEmpty()) {
                column.getChildren().add(findingsOnly(findings));
                return;
            }
            tree = parsed.tree().get();
            findings.addAll(tree.check().castToList());
            Rollback rollback = Rollback.of(tree, coordinate(tree, price));
            committed = rollback;
            committedFindings = findings;
            fillTop(tree, rollback, price, Optional.empty(), findings);
            column.getChildren().add(top);

            List<String> messages = new ArrayList<>();
            // Named by the node they are on, as the feedback to the assistant names them, not by vertex index.
            // The note that only lists the block's local symbols is not a gap: the dashed chips say it.
            findings.stream().filter(finding -> !DecisionTreeFeedback.isLocalSymbolsNote(finding))
                    .forEach(finding -> messages.add(DecisionTreeFeedback.describe(tree, ids, finding)));
            rollback.findings().forEach(finding -> messages.add(DecisionTreeFeedback.describe(tree, ids, finding)));
            if (!messages.isEmpty()) {
                Label gaps = new Label(String.join("\n", messages));
                gaps.setFont(Font.font(base * 0.9));
                gaps.setWrapText(true);
                gaps.setStyle("-fx-text-fill: #8a5a00;");
                column.getChildren().add(gaps);
            }
            column.getChildren().add(sensitivity(rollback));
            if (moves > 0) {
                column.getChildren().add(whatIfBar());
            }
        }

        /**
         * The sensitivity section: a heading now, the tornado when the sweep lands. The sweep reads
         * the block once per step, so it runs behind the surface on its own identities, and is
         * dropped if the block has been rebuilt meanwhile.
         */
        private VBox sensitivity(Rollback rollback) {
            VBox section = new VBox(3);
            section.setPadding(new Insets(6, 0, 0, 0));
            Label heading = new Label("Sensitivity of the expected cost, one value at a time");
            heading.setFont(Font.font(null, FontWeight.BOLD, base * 0.9));
            section.getChildren().add(heading);
            if (!rollback.determinable()) {
                Label none = new Label("Nothing to sweep until the tree is determinable.");
                none.setFont(Font.font(base * 0.85));
                none.setStyle("-fx-text-fill: #666;");
                section.getChildren().add(none);
                return section;
            }
            Label wait = new Label("computing…");
            wait.setFont(Font.font(base * 0.85));
            wait.setStyle("-fx-text-fill: #666;");
            section.getChildren().add(wait);
            int mine = generation;
            String text = body;
            double at = price;
            sweepExecutor.execute(() -> {
                Optional<DecisionTreeSensitivity> swept;
                try {
                    swept = BlockSweep.sweep(text, terms, new BlockIdentities(new ViewIdentities(viewCalc)), at,
                            BlockSweep.DEFAULT_STEPS);
                } catch (RuntimeException e) {
                    swept = Optional.empty();
                }
                Optional<DecisionTreeSensitivity> landed = swept;
                Platform.runLater(() -> {
                    if (mine != generation) {
                        return;
                    }
                    section.getChildren().remove(wait);
                    if (landed.isEmpty()) {
                        Label none = new Label("The block could not be swept.");
                        none.setFont(Font.font(base * 0.85));
                        none.setStyle("-fx-text-fill: #8a5a00;");
                        section.getChildren().add(none);
                        return;
                    }
                    tornado = new TornadoChart(landed.get(), base, this);
                    tornado.setId("tornado-chart");
                    section.getChildren().add(tornado);
                    lastSweep = landed.get();
                    markFlips(landed.get());
                });
            });
            return section;
        }

        /** A mark on every chip whose range flips the root's choice, and on the price when it does. */
        private void markFlips(DecisionTreeSensitivity sweep) {
            int root = tree.rootIndex();
            for (Swing swing : sweep.swings()) {
                ImmutableList<Flip> flips = swing.flipsAt(root);
                if (flips.isEmpty()) {
                    continue;
                }
                Flip flip = flips.get(0);
                String tip = "Flips the choice at " + TornadoChart.value(flip.at()) + ": " + alternativeName(flip.chosenBelow())
                        + " up to there, " + alternativeName(flip.chosenAbove()) + " beyond";
                if (swing.measureVertex() == DecisionTreeSensitivity.HEALTH_PRICE) {
                    if (priceMark != null) {
                        priceMark.setText("▲ flips at " + TornadoChart.value(flip.at()));
                        priceMark.setTooltip(new Tooltip(tip));
                        priceMark.setVisible(true);
                        priceMark.setManaged(true);
                    }
                    continue;
                }
                MeasureChip chip = parameterChips.get(swing.measureVertex());
                if (chip == null && diagram != null) {
                    chip = diagram.chip(swing.measureVertex()).orElse(null);
                }
                if (chip != null) {
                    chip.mark(tip);
                }
            }
        }

        /** The coordinate a tree is read at: its own cost semantics, at a price. */
        private ValuationCoordinate coordinate(DecisionTree tree, double at) {
            return ValuationCoordinate.pricingHealthAt(Bounds.point(at),
                    semantic(tree, MoneyCost.class, terms.moneySemantic()), semantic(tree, HealthCost.class, terms.healthSemantic()));
        }

        /** Fills the top of the column: the header, the parameters and the drawing, for a tree at a price. */
        private void fillTop(DecisionTree drawn, Rollback rollback, double at, Optional<Rollback> reference,
                             List<ProfileFinding> findings) {
            parameterChips.clear();
            top.getChildren().clear();
            top.getChildren().add(header(drawn, rollback, at));
            Node parameters = parameters(drawn, rollback.derivations());
            if (parameters != null) {
                top.getChildren().add(parameters);
            }
            diagram = new DecisionTreeDiagram(drawn, rollback, reference, ids, findings, collapsed, base, this);
            ScrollPane scroll = new ScrollPane(diagram);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setPannable(true);
            scroll.setFitToHeight(true);
            // Only the colour, never -fx-background itself: modena ladders every label's text colour off
            // -fx-background, and a transparent one reads as light, which fades every chip in the drawing.
            scroll.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
            scroll.setPrefViewportHeight(diagram.prefHeight(-1));
            diagram.drawnHeightProperty().addListener((obs, was, now) -> scroll.setPrefViewportHeight(now.doubleValue()));
            top.getChildren().add(scroll);
            top.getChildren().add(legend(rollback));
            if (lastSweep != null) {
                markFlips(lastSweep);
            }
        }

        /** One line beneath the drawing saying what its numbers are. */
        private Label legend(Rollback rollback) {
            String moneyUnit = unitName(rollback.coordinate().moneySemantic(), "money");
            String healthUnit = unitName(rollback.coordinate().healthSemantic(), "healthy years");
            Label legend = new Label("⇒ is the expected cost per patient from that point on, in " + moneyUnit
                    + ", with a year of healthy life priced at " + money(rollback.coordinate().healthPrice())
                    + ". p is the share who go that way; money and health are what a branch costs, health in " + healthUnit
                    + ". The heavy run is the alternative chosen. Hover a number for its working.");
            legend.setId("decision-tree-legend");
            legend.setWrapText(true);
            legend.setFont(Font.font(base * 0.78));
            legend.setTextFill(javafx.scene.paint.Color.web("#666"));
            legend.setPadding(new Insets(2, 0, 4, 0));
            return legend;
        }

        private String unitName(long nid, String fallback) {
            String label = nid == 0 ? null : ids.label(nid);
            return label == null || label.isBlank() ? fallback : label;
        }

        /** The title row: what the tree decides, its value, and the health price the reader may move. */
        private HBox header(DecisionTree drawn, Rollback rollback, double at) {
            HBox header = new HBox(8);
            header.setAlignment(Pos.CENTER_LEFT);
            Label title = new Label("Decision tree");
            title.setFont(Font.font(null, FontWeight.BOLD, base));
            header.getChildren().add(title);
            drawn.decision().ifPresent(decision -> concept(decision.concept()).ifPresent(header.getChildren()::add));
            Label priceLabel = new Label("healthy year at");
            priceLabel.setFont(Font.font(base * 0.9));
            TextField priceField = new TextField(Tokens.number(at));
            priceField.setId("health-price-field");
            priceField.setFont(Font.font(base * 0.9));
            priceField.setPrefColumnCount(8);
            priceField.setOnAction(event -> {
                try {
                    double typed = Double.parseDouble(priceField.getText().replace(",", "").replace("$", "").strip());
                    if (typed != price) {
                        price = typed;
                        moves++;
                        rebuild();
                    }
                } catch (NumberFormatException e) {
                    priceField.setStyle("-fx-border-color: #c0392b;");
                }
            });
            header.getChildren().addAll(priceLabel, priceField);
            priceMark = new Label();
            priceMark.setId("price-flip-mark");
            priceMark.setFont(Font.font(null, FontWeight.BOLD, base * 0.75));
            priceMark.setStyle("-fx-text-fill: #d98b1a;");
            priceMark.setVisible(false);
            priceMark.setManaged(false);
            header.getChildren().add(priceMark);
            rollback.rootValue().ifPresent(value -> {
                Label expected = new Label("expected cost " + money(value));
                expected.setFont(Font.font(null, FontWeight.BOLD, base * 0.9));
                header.getChildren().add(expected);
            });
            return header;
        }

        /** The model's parameters, each its quantity and its value, stated ones offered for a what-if. */
        private Node parameters(DecisionTree drawn, Derivations derivations) {
            ImmutableList<Parameter> all = drawn.nodesOfType(Parameter.class);
            if (all.isEmpty()) {
                return null;
            }
            FlowPane flow = new FlowPane(14, 4);
            flow.setAlignment(Pos.CENTER_LEFT);
            flow.setPadding(new Insets(0, 0, 2, 0));
            // A FlowPane reports its height for its wrap length, not its width: wrap at the column's width
            // or it asks for rows it will not fill and leaves a blank band beneath.
            flow.prefWrapLengthProperty().bind(column.widthProperty().subtract(4));
            for (Parameter parameter : all) {
                HBox item = new HBox(5);
                item.setAlignment(Pos.CENTER_LEFT);
                item.getChildren().add(concept(parameter.quantity()).orElseGet(() -> plain("parameter")));
                int index = parameter.vertexIndex();
                boolean editable = editable(index);
                MeasureChip chip = new MeasureChip("", derivations.bounds(index), parameter.measure(), ids, base,
                        editable ? bounds -> edit(index, bounds) : null);
                chip.explain(DecisionTreeDiagram.explain("", parameter.measure(), derivations.bounds(index), derivations, ids,
                        drawn.terms().shareAtNode()));
                chip.onHover(on -> hover(index, on));
                parameterChips.put(index, chip);
                item.getChildren().add(chip);
                flow.getChildren().add(item);
            }
            return flow;
        }

        /** What the what-if has moved, and the two ways out: back to the block, or into it. */
        private HBox whatIfBar() {
            HBox bar = new HBox(10);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setPadding(new Insets(2, 6, 2, 6));
            bar.setStyle("-fx-background-color: #fff8e6; -fx-background-radius: 6; -fx-border-color: #e6c87a;"
                    + " -fx-border-radius: 6;");
            Label what = new Label("What-if: " + (moves == 1 ? "one value moved" : moves + " values moved")
                    + (rewriter == null ? "; the block is unchanged" : ""));
            what.setFont(Font.font(base * 0.9));
            bar.getChildren().add(what);
            Hyperlink revert = new Hyperlink("Revert");
            revert.setFont(Font.font(base * 0.9));
            revert.setOnAction(e -> {
                body = originalBody;
                price = originalPrice;
                moves = 0;
                rebuild();
            });
            bar.getChildren().add(revert);
            if (rewriter != null) {
                Button write = new Button("Write back to the block");
                write.setFont(Font.font(base * 0.9));
                write.setTooltip(new Tooltip("Restates the block in the document with these values"));
                write.setOnAction(e -> {
                    String newInfo = price == originalPrice ? originalInfo
                            : DecisionTreeEdits.withHealthPrice(originalInfo, price);
                    rewriter.rewrite(originalInfo, originalBody, newInfo, body);
                });
                bar.getChildren().add(write);
            }
            return bar;
        }

        // ── The diagram's host ──────────────────────────────────────────────

        @Override
        public Optional<Node> concept(OptionalLong nid) {
            if (nid.isEmpty()) {
                return Optional.empty();
            }
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

        @Override
        public boolean editable(int measureVertex) {
            return parsed.lineOf(measureVertex).isPresent() && tree.measure(measureVertex).stated();
        }

        @Override
        public void edit(int measureVertex, Bounds bounds) {
            OptionalInt line = parsed.lineOf(measureVertex);
            if (line.isEmpty()) {
                return;
            }
            DecisionTreeEdits.restate(body, line.getAsInt(), bounds).ifPresent(restated -> {
                if (!restated.equals(body)) {
                    body = restated;
                    moves++;
                    rebuild();
                }
            });
        }

        @Override
        public void toggle(int nodeVertex) {
            if (!collapsed.remove(nodeVertex)) {
                collapsed.add(nodeVertex);
            }
            rebuild();
        }

        @Override
        public void hover(int measureVertex, boolean on) {
            if (tornado != null) {
                tornado.highlight(measureVertex, on);
            }
        }

        // ── The tornado's host ──────────────────────────────────────────────

        @Override
        public String nameOf(int measureVertex) {
            if (measureVertex == DecisionTreeSensitivity.HEALTH_PRICE) {
                return "price of a healthy year";
            }
            Optional<DecisionElement> element = tree.node(measureVertex);
            if (element.isEmpty()) {
                return "a value";
            }
            return switch (element.get()) {
                case Parameter p -> p.quantity().isPresent() ? ids.label(p.quantity().getAsLong()) : "a parameter";
                case MoneyCost m -> owner(measureVertex) + " · money";
                case HealthCost h -> owner(measureVertex) + " · health";
                case DecisionElement.Probability p -> "p of " + owner(measureVertex);
                default -> "a value";
            };
        }

        private String owner(int measureVertex) {
            OptionalInt parent = tree.parentIndex(measureVertex);
            if (parent.isEmpty()) {
                return "the root";
            }
            return tree.node(parent.getAsInt()).flatMap(n -> n.concept().isPresent()
                    ? Optional.of(ids.label(n.concept().getAsLong())) : Optional.empty()).orElse("a node");
        }

        @Override
        public String alternativeName(int alternativeVertex) {
            if (alternativeVertex < 0) {
                return "no sure choice";
            }
            return tree.node(alternativeVertex).flatMap(n -> n.concept().isPresent()
                    ? Optional.of(ids.label(n.concept().getAsLong())) : Optional.empty()).orElse("an alternative");
        }

        @Override
        public void light(int measureVertex, boolean on) {
            if (measureVertex == DecisionTreeSensitivity.HEALTH_PRICE) {
                return;
            }
            MeasureChip chip = parameterChips.get(measureVertex);
            if (chip != null) {
                chip.setHot(on);
            }
            if (diagram != null) {
                diagram.lightMeasure(measureVertex, on);
            }
        }

        @Override
        public void preview(int measureVertex, OptionalDouble value) {
            if (value.isEmpty()) {
                pendingPreview = null;
                if (previewing) {
                    previewing = false;
                    fillTop(tree, committed, price, Optional.empty(), committedFindings);
                }
                return;
            }
            boolean queued = pendingPreview != null;
            pendingPreview = () -> drawPreview(measureVertex, value.getAsDouble());
            if (!queued) {
                Platform.runLater(() -> {
                    Runnable run = pendingPreview;
                    pendingPreview = null;
                    if (run != null) {
                        run.run();
                    }
                });
            }
        }

        /** Draws the top of the column at one value of a measure, the committed rollback as the reference. */
        private void drawPreview(int measureVertex, double value) {
            String text = body;
            double at = price;
            if (measureVertex == DecisionTreeSensitivity.HEALTH_PRICE) {
                at = value;
            } else {
                OptionalInt line = parsed.lineOf(measureVertex);
                if (line.isEmpty()) {
                    return;
                }
                Optional<String> restated = DecisionTreeEdits.restate(body, line.getAsInt(), Bounds.point(value));
                if (restated.isEmpty()) {
                    return;
                }
                text = restated.get();
            }
            DecisionTreeText.Parsed again = DecisionTreeText.parse(text, terms, ids);
            if (again.tree().isEmpty()) {
                return;
            }
            DecisionTree drawn = again.tree().get();
            List<ProfileFinding> findings = new ArrayList<>(again.findings().castToList());
            findings.addAll(drawn.check().castToList());
            previewing = true;
            fillTop(drawn, Rollback.of(drawn, coordinate(drawn, at)), at, Optional.of(committed), findings);
        }

        /** Whether the top of the column shows a preview rather than where the block stands; for a test. */
        boolean previewing() {
            return previewing;
        }

        @Override
        public void tryValue(int measureVertex, double value) {
            if (measureVertex == DecisionTreeSensitivity.HEALTH_PRICE) {
                if (value != price) {
                    price = value;
                    moves++;
                    rebuild();
                }
                return;
            }
            edit(measureVertex, Bounds.point(value));
        }

        /** The semantic a tree's costs of a kind are on, taken from the first such cost; the parent semantic otherwise. */
        private long semantic(DecisionTree of, Class<? extends Cost> kind, long fallback) {
            ImmutableList<? extends Cost> costs = of.nodesOfType(kind);
            return costs.isEmpty() ? fallback : costs.get(0).measure().semanticNid();
        }
    }

    private Node findingsOnly(List<ProfileFinding> findings) {
        VBox column = new VBox(2);
        Label heading = new Label("Decision tree not read");
        heading.setFont(Font.font(null, FontWeight.BOLD, base));
        column.getChildren().add(heading);
        for (ProfileFinding finding : findings) {
            Label entry = new Label("• " + finding.message());
            entry.setFont(Font.font(base));
            entry.setWrapText(true);
            column.getChildren().add(entry);
        }
        return column;
    }

    private Label plain(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(base));
        return label;
    }

    /**
     * A concept the knowledge base lacks, drawn as a chip with a dashed edge: the block's own
     * label, and a tooltip saying why it is not the shared Koncept chip.
     */
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
