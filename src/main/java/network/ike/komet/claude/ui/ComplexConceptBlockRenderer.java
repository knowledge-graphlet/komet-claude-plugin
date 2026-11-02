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

import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.complexconcept.ComplexConceptText;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Focused;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Parsed;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Prerequisite;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Ref;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Spec;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Uniform;
import network.ike.komet.claude.complexconcept.ComplexConceptWrites;
import network.ike.komet.claude.complexconcept.model.ComplexConceptRefusal;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.List;
import java.util.Optional;

/**
 * A {@code complex-concept} fenced block: a complex concept's specification stated in the house
 * grammar, drawn as a card beneath the prose (ike-lab-documents topic
 * {@code dev-decision-point-knowledge}; IKE-Network/ike-issues#812). The card shows the concept,
 * what it is a kind of, and its expansion: the focus and each prerequisite with its fixed value
 * for a focused one, the conjunction, domain and members for a uniform one; then what the
 * specification still lacks, the findings, and the symbols local to the block.
 *
 * <p>The block registers its concept on the shared {@link BlockContext}, so a decision-tree
 * block of the same document whose token names the same symbol draws a chip that says what the
 * concept is. A complete specification whose concept the store lacks offers <b>Propose</b>,
 * which writes the concept and its specification as one uncommitted change
 * ({@link ComplexConceptWrites}).
 *
 * <p>Parsing is pure string work, done eagerly to decide whether the block is well formed; the
 * card is built lazily on the JavaFX application thread. A block whose root is not a concept
 * declines, and renders as preformatted text.
 */
final class ComplexConceptBlockRenderer implements BlockRenderer {

    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private final BlockContext context;

    ComplexConceptBlockRenderer(ViewCalculator viewCalc, double base, BlockContext context) {
        this(viewCalc, null, base, context);
    }

    ComplexConceptBlockRenderer(ViewProperties viewProperties, double base, BlockContext context) {
        this(viewProperties == null ? null : viewProperties.calculator(), viewProperties, base, context);
    }

    private ComplexConceptBlockRenderer(ViewCalculator viewCalc, ViewProperties viewProperties, double base,
                                        BlockContext context) {
        this.viewCalc = viewCalc;
        this.viewProperties = viewProperties;
        this.base = base;
        this.context = context == null ? new BlockContext() : context;
    }

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        if (viewCalc == null || !ComplexConceptText.isTagged(info) || !rootIsConcept(literal)) {
            return List.of();
        }
        String body = literal == null ? "" : literal;
        return List.of(new BlockPiece(() -> buildView(body), body.stripTrailing()));
    }

    private static boolean rootIsConcept(String literal) {
        BlockLines.Parsed lines = BlockLines.parse(literal == null ? "" : literal);
        return lines.roots().size() == 1
                && lines.roots().get(0).form() == BlockLines.Form.VERTEX
                && "concept".equals(lines.roots().get(0).word());
    }

    // ── View building (JavaFX application thread) ───────────────────────

    private Node buildView(String body) {
        Identities ids = context.identities(viewCalc);
        Parsed parsed = ComplexConceptText.parse(body, ids);
        VBox column = new VBox(6);
        column.getStyleClass().add("complex-concept");
        column.setCursor(Cursor.DEFAULT);
        column.setFillWidth(false);
        column.setPadding(new Insets(4, 0, 4, 0));

        if (parsed.spec().isPresent()) {
            Spec spec = parsed.spec().get();
            context.defineComplexConcept(spec.concept().nid(), spec);
            column.getChildren().add(header(spec));
            spec.definitionParent().ifPresent(parent -> column.getChildren().add(row("a kind of", chip(parent))));
            spec.shape().ifPresent(shape -> {
                switch (shape) {
                    case Focused focused -> column.getChildren().add(focused(focused));
                    case Uniform uniform -> column.getChildren().add(uniform(uniform));
                }
            });
            List<String> missing = ComplexConceptText.completeness(spec);
            if (!missing.isEmpty()) {
                column.getChildren().add(list("Still needs", missing));
            }
            if (missing.isEmpty() && parsed.findings().size() <= (parsed.localSymbols().isEmpty() ? 0 : 1)
                    && spec.concept().local() && viewProperties != null) {
                column.getChildren().add(proposeBar(spec));
            }
        }
        List<String> findings = parsed.findings();
        if (!findings.isEmpty()) {
            column.getChildren().add(list(findings.size() == 1 ? "Finding" : "Findings", findings));
        }
        if (column.getChildren().isEmpty()) {
            return new Region();
        }
        column.setMaxWidth(Double.MAX_VALUE);
        column.parentProperty().addListener((obs, oldParent, newParent) -> {
            column.maxWidthProperty().unbind();
            if (newParent instanceof Region host) {
                column.maxWidthProperty().bind(host.widthProperty());
            }
        });
        return column;
    }

    private Node header(Spec spec) {
        Label title = new Label("Complex concept");
        title.setFont(Font.font(null, FontWeight.BOLD, base));
        HBox row = new HBox(8, title, chip(spec.concept()));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node row(String words, Node chip) {
        Label label = new Label(words);
        label.setFont(Font.font(null, FontPosture.ITALIC, base * 0.95));
        HBox row = new HBox(6, label, chip);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node focused(Focused focused) {
        VBox section = new VBox(4);
        section.getChildren().add(row("focused on", chip(focused.focus())));
        if (focused.prerequisites().isEmpty()) {
            return section;
        }
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(3);
        grid.setPadding(new Insets(2, 0, 0, 12));
        grid.add(head("given"), 0, 0);
        grid.add(head("value"), 1, 0);
        grid.add(head("relation"), 2, 0);
        int r = 1;
        for (Prerequisite prerequisite : focused.prerequisites()) {
            grid.add(chip(prerequisite.topic()), 0, r);
            Label value = new Label(ComplexConceptText.valueWords(prerequisite));
            value.setFont(Font.font(base * 0.95));
            if (prerequisite.value().isEmpty()) {
                value.setStyle("-fx-text-fill: #a33;");
            }
            grid.add(value, 1, r);
            Label relation = new Label(prerequisite.association());
            relation.setFont(Font.font(null, FontPosture.ITALIC, base * 0.9));
            relation.setStyle("-fx-text-fill: #666;");
            grid.add(relation, 2, r);
            r++;
        }
        section.getChildren().add(grid);
        return section;
    }

    private Node uniform(Uniform uniform) {
        VBox section = new VBox(4);
        String words = "and".equals(uniform.conjunction()) ? "every member, on" : "any member, on";
        Node domain = uniform.domain().map(this::chip).orElseGet(() -> {
            Label none = new Label("(no measurement domain)");
            none.setFont(Font.font(base * 0.95));
            none.setStyle("-fx-text-fill: #a33;");
            return none;
        });
        section.getChildren().add(row(words, domain));
        uniform.unsafe().ifPresent(policy -> {
            Label note = new Label(switch (policy) {
                case "flag" -> "a present value expands every member, each flagged as indeterminate positive";
                case "retain" -> "a present value keeps the compound code, without expansion";
                default -> "a present value expands nothing, and asks which members hold it";
            });
            note.setFont(Font.font(null, FontPosture.ITALIC, base * 0.9));
            note.setStyle("-fx-text-fill: #666;");
            note.setWrapText(true);
            section.getChildren().add(note);
        });
        HBox members = new HBox(6);
        members.setPadding(new Insets(0, 0, 0, 12));
        members.setAlignment(Pos.CENTER_LEFT);
        for (Ref member : uniform.members()) {
            members.getChildren().add(chip(member));
        }
        section.getChildren().add(members);
        return section;
    }

    private Node list(String heading, List<String> entries) {
        VBox box = new VBox(2);
        Label title = new Label(heading);
        title.setFont(Font.font(null, FontWeight.BOLD, base * 0.9));
        box.getChildren().add(title);
        for (String entry : entries) {
            Label line = new Label("• " + entry);
            line.setFont(Font.font(base * 0.9));
            line.setWrapText(true);
            box.getChildren().add(line);
        }
        return box;
    }

    private Label head(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(null, FontWeight.BOLD, base * 0.8));
        label.setStyle("-fx-text-fill: #666;");
        return label;
    }

    private Node proposeBar(Spec spec) {
        Button propose = new Button("Propose");
        propose.setFont(Font.font(base * 0.9));
        propose.setTooltip(new Tooltip("Writes the concept and its specification to the knowledge base, uncommitted"));
        Label result = new Label();
        result.setFont(Font.font(base * 0.9));
        result.setWrapText(true);
        propose.setOnAction(e -> {
            try {
                ComplexConceptWrites.Proposed proposed = ComplexConceptWrites.propose(spec, Coordinates.Edit.Default());
                result.setStyle("-fx-text-fill: #2a6;");
                result.setText(proposed.message());
                propose.setDisable(true);
            } catch (ComplexConceptRefusal | IllegalStateException refused) {
                result.setStyle("-fx-text-fill: #a33;");
                result.setText(refused.getMessage());
            }
        });
        HBox bar = new HBox(8, propose, result);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    // ── Chips ───────────────────────────────────────────────────────────

    private Node chip(Ref ref) {
        if (ref.local()) {
            return localConcept(ref.label());
        }
        Optional<PublicId> pid = ComponentText.publicId(ref.nid());
        if (pid.isEmpty()) {
            return localConcept(ref.label());
        }
        String sctid = ComponentText.sctid(viewCalc, ref.nid()).orElse(null);
        return viewProperties != null
                ? KonceptChips.chip(pid.get(), sctid, viewProperties, base)
                : KonceptChips.chip(pid.get(), sctid, viewCalc, base);
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
