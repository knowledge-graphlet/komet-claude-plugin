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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionElement;
import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionElement.Chance;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.Decision;
import network.ike.komet.claude.decision.DecisionElement.Event;
import network.ike.komet.claude.decision.DecisionElement.HealthCost;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Outcome;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.decision.Measure;
import network.ike.komet.claude.decision.Rollback;
import network.ike.komet.claude.decision.ValuationCoordinate;
import network.ike.komet.claude.decision.ViewIdentities;
import network.ike.komet.claude.koncept.ComponentText;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The live rendering of a {@code decision-tree} block on the assistant surface
 * ({@code dev-decision-tree-blocks}): the tree as indented rows in the decision-analytic
 * notation, a square for a decision, a circle for a chance, each node's concept as the shared
 * Koncept chip, its measures beside it, and the rolled-back value every node is left with under
 * a valuation coordinate, computed on the view and never stored. The health price is the one
 * dimension of the coordinate the block lets the reader move: a field beside the title re-rolls
 * the tree back, so the same tree reads at several prices of a healthy year, as the regulatory
 * topics present it. A finding of the parse, the profile's checks or the rollback is listed
 * beneath the tree, as a definition block lists its modelling gaps.
 *
 * <p>The block declines when it is not this profile's, when it has no root decision line, or
 * when there is no view to resolve against, so the text renders preformatted as any other
 * unrendered block does. The block's info may set the price: {@code decision-tree
 * health-price=100000}; the default is $100,000, the regulatory topics' central value.
 */
final class DecisionTreeBlockRenderer implements BlockRenderer {

    /** The default price of a year of healthy life, in the tree's money semantic. */
    static final double DEFAULT_HEALTH_PRICE = 100_000;

    private static final String HEALTH_PRICE = "health-price";
    private static final double INDENT_PER_LEVEL = 16;

    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private KonceptLabelTypography typography = KonceptLabelTypography.DEFAULT;

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

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        if (viewCalc == null || !accepts(info, literal)) {
            return List.of();
        }
        String body = literal == null ? "" : literal;
        double price = healthPrice(info).orElse(DEFAULT_HEALTH_PRICE);
        return List.of(new BlockPiece(() -> buildView(body, price), body.stripTrailing()));
    }

    // ── Pure decisions (no store access) ────────────────────────────────

    /**
     * Whether a block is this profile's: tagged {@code decision-tree}, or {@code property-graph}
     * with {@code profile=decision-tree}, and opening with a root decision line.
     */
    static boolean accepts(String info, String literal) {
        String tag = KonceptTreeBlockRenderer.firstWord(info);
        boolean tagged = DecisionTreeText.TAG.equals(tag)
                || ("property-graph".equals(tag) && info != null && info.contains("profile=" + DecisionTreeText.TAG));
        if (!tagged) {
            return false;
        }
        BlockLines.Parsed lines = BlockLines.parse(literal);
        return lines.roots().size() == 1
                && lines.roots().get(0).form() == BlockLines.Form.VERTEX
                && "decision".equals(lines.roots().get(0).word());
    }

    /** The health price the block's info sets, when it does. */
    static Optional<Double> healthPrice(String info) {
        if (info == null) {
            return Optional.empty();
        }
        for (String word : Tokens.words(info)) {
            if (word.startsWith(HEALTH_PRICE + "=")) {
                try {
                    return Optional.of(Double.parseDouble(word.substring(HEALTH_PRICE.length() + 1).replace(",", "")));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    // ── View building (JavaFX application thread) ───────────────────────

    private Node buildView(String body, double price) {
        DecisionTerms terms = DecisionTerms.fromBindings();
        ViewIdentities ids = new ViewIdentities(viewCalc);
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(body, terms, ids);
        List<ProfileFinding> findings = new ArrayList<>(parsed.findings().castToList());
        if (parsed.tree().isEmpty()) {
            return findingsOnly(findings);
        }
        DecisionTree tree = parsed.tree().get();
        findings.addAll(tree.check().castToList());

        VBox column = new VBox(3);
        column.getStyleClass().add("decision-tree");
        column.setCursor(Cursor.DEFAULT);
        column.setFillWidth(false);

        Live live = new Live(tree, terms, findings);
        column.getChildren().add(header(tree, live, price));
        Optional<DecisionElement> root = tree.root();
        if (root.isPresent() && root.get() instanceof DecisionElement.Node node) {
            rows(column, tree, node, 0, live);
        }
        Label gaps = new Label();
        gaps.setFont(Font.font(base * 0.9));
        gaps.setWrapText(true);
        gaps.setStyle("-fx-text-fill: #8a5a00;");
        column.getChildren().add(gaps);
        live.gaps = gaps;
        live.rollBack(price);

        column.setMaxWidth(Double.MAX_VALUE);
        column.parentProperty().addListener((obs, oldParent, newParent) -> {
            column.maxWidthProperty().unbind();
            if (newParent instanceof Region host) {
                column.maxWidthProperty().bind(host.widthProperty());
            }
        });
        return column;
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

    /** The title row: what the tree decides, and the health price the reader may move. */
    private HBox header(DecisionTree tree, Live live, double price) {
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Decision tree");
        title.setFont(Font.font(null, FontWeight.BOLD, base));
        header.getChildren().add(title);
        tree.decision().ifPresent(decision -> concept(decision.concept()).ifPresent(header.getChildren()::add));
        Label priceLabel = new Label("healthy year at");
        priceLabel.setFont(Font.font(base * 0.9));
        TextField priceField = new TextField(Tokens.number(price));
        priceField.setFont(Font.font(base * 0.9));
        priceField.setPrefColumnCount(8);
        priceField.setOnAction(event -> {
            try {
                live.rollBack(Double.parseDouble(priceField.getText().replace(",", "").strip()));
                priceField.setStyle("");
            } catch (NumberFormatException e) {
                priceField.setStyle("-fx-border-color: #c0392b;");
            }
        });
        header.getChildren().addAll(priceLabel, priceField);
        return header;
    }

    /** One row per node, depth first, with its measures in the row and its value at the end. */
    private void rows(VBox column, DecisionTree tree, DecisionElement.Node node, int depth, Live live) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 0, 0, depth * INDENT_PER_LEVEL));

        Label glyph = new Label(glyph(node));
        glyph.setFont(Font.font(base));
        glyph.setMinWidth(base * 1.2);
        row.getChildren().add(glyph);

        row.getChildren().add(concept(node.concept()).orElseGet(() -> plain(word(node))));

        String measures = measures(tree, node);
        if (!measures.isEmpty()) {
            Label text = new Label(measures);
            text.setFont(Font.font(base * 0.9));
            text.setStyle("-fx-text-fill: #555;");
            row.getChildren().add(text);
        }

        Label value = new Label();
        value.setFont(Font.font(null, FontWeight.BOLD, base * 0.9));
        row.getChildren().add(value);
        live.values.put(node.vertexIndex(), value);

        column.getChildren().add(row);
        for (DecisionElement.Node child : tree.children(node.vertexIndex(), DecisionElement.Node.class)) {
            rows(column, tree, child, depth + 1, live);
        }
    }

    private Optional<Node> concept(OptionalLong nid) {
        if (nid.isEmpty()) {
            return Optional.empty();
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

    private Label plain(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(base));
        return label;
    }

    /** The measures a node carries, as text: its probability, then its costs. */
    private static String measures(DecisionTree tree, DecisionElement.Node node) {
        List<String> parts = new ArrayList<>();
        if (node instanceof Event event) {
            event.probability().ifPresentOrElse(
                    probability -> parts.add("p " + probability.measure().bounds()),
                    () -> parts.add(event.residual() ? "residual" : "p ?"));
        }
        for (DecisionElement child : tree.children(node.vertexIndex())) {
            if (child instanceof Cost cost) {
                Measure measure = cost.measure();
                parts.add((cost instanceof MoneyCost ? "money " : "health ") + measure.bounds());
            }
        }
        return String.join(" · ", parts);
    }

    private static String glyph(DecisionElement.Node node) {
        return switch (node) {
            case Decision d -> "■";
            case Chance c -> "●";
            case Alternative a -> "▸";
            case Event e -> "→";
            case Outcome o -> "◆";
        };
    }

    private static String word(DecisionElement.Node node) {
        return switch (node) {
            case Decision d -> "decision";
            case Chance c -> "chance";
            case Alternative a -> "alternative";
            case Event e -> e.residual() ? "residual" : "event";
            case Outcome o -> "outcome";
        };
    }

    /** The rolled-back values on the rows, re-computed when the reader moves the health price. */
    private static final class Live {
        final DecisionTree tree;
        final DecisionTerms terms;
        final List<ProfileFinding> staticFindings;
        final Map<Integer, Label> values = new HashMap<>();
        Label gaps;

        Live(DecisionTree tree, DecisionTerms terms, List<ProfileFinding> staticFindings) {
            this.tree = tree;
            this.terms = terms;
            this.staticFindings = staticFindings;
        }

        void rollBack(double price) {
            ValuationCoordinate coordinate = ValuationCoordinate.pricingHealthAt(Bounds.point(price),
                    semantic(MoneyCost.class, terms.moneySemantic()), semantic(HealthCost.class, terms.healthSemantic()));
            Rollback rollback = Rollback.of(tree, coordinate);
            values.forEach((index, label) -> {
                Optional<Bounds> value = rollback.value(index);
                String text = value.map(bounds -> "⇒ " + bounds).orElse("⇒ not determinable");
                OptionalInt parent = tree.parentIndex(index);
                if (parent.isPresent() && rollback.chosen(parent.getAsInt()).orElse(-1) == index) {
                    text += "  ✓ chosen";
                }
                label.setText(text);
            });
            List<String> messages = new ArrayList<>();
            staticFindings.forEach(finding -> messages.add(finding.toString()));
            rollback.findings().forEach(finding -> messages.add(finding.toString()));
            gaps.setText(messages.isEmpty() ? "" : String.join("\n", messages));
            gaps.setVisible(!messages.isEmpty());
            gaps.setManaged(!messages.isEmpty());
        }

        /** The semantic the tree's costs of a kind are on, taken from the first such cost; the parent semantic otherwise. */
        private long semantic(Class<? extends Cost> kind, long fallback) {
            ImmutableList<? extends Cost> costs = tree.nodesOfType(kind);
            return costs.isEmpty() ? fallback : costs.get(0).measure().semanticNid();
        }
    }
}
