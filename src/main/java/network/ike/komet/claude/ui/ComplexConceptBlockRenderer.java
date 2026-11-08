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

import network.ike.komet.claude.stamp.KonceptChips;

import network.ike.komet.claude.stamp.BlockRewriter;

import dev.ikm.komet.framework.Identicon;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.kview.controls.ComponentItem;
import dev.ikm.komet.kview.controls.KLDiTreeControlFactory;
import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import dev.ikm.komet.framework.controls.KonceptBadge;
import dev.ikm.komet.framework.controls.KonceptStatus;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
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
import network.ike.komet.claude.complexconcept.editor.ComplexConceptControl;
import network.ike.komet.claude.complexconcept.model.ComplexConceptBuilder;
import network.ike.komet.claude.stamp.ComponentText;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A {@code complex-concept} fenced block: a complex concept's specification stated in the house
 * grammar, drawn as a card beneath the prose (ike-lab-documents topic
 * {@code dev-decision-point-knowledge}; IKE-Network/ike-issues#812). The card shows the concept
 * and, over the specification tree the block builds, the complex concept editor control: the
 * definition as the axiom control, the expansion as clause rows, each prerequisite as the ANF
 * statement control, so a value, a topic or an association is edited in place and a prerequisite
 * added from the clause's menu. An edit reads back to the block's grammar; <b>Write back to the
 * block</b> restates the block in the document, as the decision tree's what-if does. Beneath the
 * editor the card lists what the specification still lacks and the block's findings.
 *
 * <p>The block registers its concept and its card on the shared {@link ProfileBlocks}, so a
 * decision-tree block of the same document whose token names the same symbol draws a chip that
 * says what the concept is and peeks at this card. A complete specification whose concept the
 * store lacks offers <b>Propose</b>, which writes the concept and its specification as one
 * uncommitted change ({@link ComplexConceptWrites}).
 *
 * <p>Parsing is pure string work, done eagerly to decide whether the block is well formed; the
 * card is built lazily on the JavaFX application thread. A block whose root is not a concept
 * declines, and renders as preformatted text. A block the builder refuses, or a surface with no
 * view, shows the prerequisites as a read-only table instead of the editor.
 */
final class ComplexConceptBlockRenderer implements network.ike.komet.claude.stamp.StructuredBlockKind.FenceRenderer {

    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private final ProfileBlocks context;
    private BlockRewriter rewriter;

    ComplexConceptBlockRenderer(ViewCalculator viewCalc, double base, ProfileBlocks context) {
        this(viewCalc, null, base, context);
    }

    ComplexConceptBlockRenderer(ViewProperties viewProperties, double base, ProfileBlocks context) {
        this(viewProperties == null ? null : viewProperties.calculator(), viewProperties, base, context);
    }

    ComplexConceptBlockRenderer(ViewCalculator viewCalc, ViewProperties viewProperties, double base,
                                        ProfileBlocks context) {
        this.viewCalc = viewCalc;
        this.viewProperties = viewProperties;
        this.base = base;
        this.context = context == null ? new ProfileBlocks() : context;
    }

    /** Where an edited specification goes when the reader writes it back; null offers the edit alone. */
    public void setRewriter(BlockRewriter rewriter) {
        this.rewriter = rewriter;
    }

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        if (viewCalc == null || !ComplexConceptText.isTagged(info) || !rootIsConcept(literal)) {
            return List.of();
        }
        String body = literal == null ? "" : literal;
        return List.of(new BlockPiece(() -> new Card(info, body).build(), body.stripTrailing()));
    }

    private static boolean rootIsConcept(String literal) {
        BlockLines.Parsed lines = BlockLines.parse(literal == null ? "" : literal);
        return lines.roots().size() == 1
                && lines.roots().get(0).form() == BlockLines.Form.VERTEX
                && "concept".equals(lines.roots().get(0).word());
    }

    // ── The card (JavaFX application thread) ────────────────────────────

    /** One rendering of the block: the parse, the editor over it, and what the editor last said. */
    private final class Card {
        private final String info;
        private final String body;
        private final Identities ids;
        private final Parsed parsed;
        /** The spec as the editor last left it; the parsed one until an edit. */
        private Spec spec;
        private final Label result = new Label();
        private final VBox still = new VBox(2);
        private Button writeBack;
        /** Builds the editor, when drawing the block left it unbuilt; null otherwise. */
        private Button edit;

        Card(String info, String body) {
            this.info = info;
            this.body = body;
            this.ids = context.identities(viewCalc);
            this.parsed = ComplexConceptText.parse(body, ids);
            this.spec = parsed.spec().orElse(null);
        }

        Node build() {
            VBox column = new VBox(6);
            column.getStyleClass().add("complex-concept");
            column.setCursor(Cursor.DEFAULT);
            column.setFillWidth(true);
            column.setPadding(new Insets(4, 0, 4, 0));

            if (spec != null) {
                context.defineComplexConcept(spec.concept().nid(), spec, () -> new Card(info, body).build());
                column.getChildren().add(header(spec));
                boolean mints = ComplexConceptText.buildingMints(spec);
                Node editor = mints ? null : editor();
                Node shown = editor != null ? editor : readOnly();
                column.getChildren().add(shown);
                if (mints) {
                    edit = editButton(column, shown);
                }
                column.getChildren().add(still);
                refreshStill();
                column.getChildren().add(bar());
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

        /** The complex concept editor over the tree the spec builds, or null when the builder refuses it. */
        private Node editor() {
            ComplexConceptText.Proxies proxies = new ComplexConceptText.Proxies();
            DiTreeEntity start;
            try {
                ComplexConceptBuilder builder = ComplexConceptText.builder(spec, proxies);
                start = builder.asDiTree();
            } catch (RuntimeException refused) {
                result.setStyle("-fx-text-fill: #a33;");
                result.setText(refused.getMessage());
                return null;
            }
            ComplexConceptControl control = KLDiTreeControlFactory.configure(new ComplexConceptControl(), viewCalc);
            var items = control.getComponentItemResolver();
            control.setComponentItemResolver(nid -> proxies.ref(nid).filter(Ref::local)
                    .map(ref -> {
                        PublicId publicId = PublicIds.of(ComplexConceptText.localPublicId(ref));
                        return new ComponentItem(ref.label(), Identicon.generateIdenticonImage(publicId), publicId, true);
                    })
                    .orElseGet(() -> items.apply(nid)));
            var names = control.getDescriptionResolver();
            control.setDescriptionResolver(nid -> proxies.ref(nid).filter(Ref::local).map(Ref::label)
                    .orElseGet(() -> names.apply(nid)));
            control.setCompactMode(true);
            control.setMaxWidth(Double.MAX_VALUE);
            control.setPadding(new Insets(2, 0, 2, 0));
            control.setValue(start);
            control.valueProperty().subscribe((was, now) -> {
                if (now == null || now == start || now.equals(was)) {
                    return;
                }
                Spec edited;
                try {
                    edited = ComplexConceptText.spec(now, spec, ids, proxies);
                } catch (RuntimeException e) {
                    result.setStyle("-fx-text-fill: #a33;");
                    result.setText("The edit could not be read back to the block: " + e.getMessage());
                    return;
                }
                spec = edited;
                context.defineComplexConcept(spec.concept().nid(), spec, null);
                refreshStill();
                result.setText("");
                if (writeBack != null) {
                    writeBack.setDisable(rewriter == null || ComplexConceptText.write(spec).equals(canonical(body)));
                }
            });
            control.lastRefusalProperty().subscribe(message -> {
                if (message != null) {
                    result.setStyle("-fx-text-fill: #a33;");
                    result.setText(message);
                }
            });
            return control;
        }

        /**
         * The button that puts the editor in place of what is {@code shown}. Editing is the
         * reader's choice to work on the block, so here its new concepts may take their nids.
         */
        private Button editButton(VBox column, Node shown) {
            Button button = new Button("Edit");
            button.setId("complexConceptEdit");
            button.setFont(Font.font(base * 0.9));
            button.setTooltip(new Tooltip("Opens the editor, and enters the concepts new to the knowledge base in its identity map"));
            button.setOnAction(e -> {
                Node editor = editor();
                if (editor != null) {
                    column.getChildren().set(column.getChildren().indexOf(shown), editor);
                    ((Pane) button.getParent()).getChildren().remove(button);
                }
            });
            return button;
        }

        private void refreshStill() {
            still.getChildren().clear();
            List<String> missing = ComplexConceptText.completeness(spec);
            if (!missing.isEmpty()) {
                still.getChildren().add(list("Still needs", missing));
            }
        }

        /** Write back and Propose, with the last message beside them. */
        private Node bar() {
            HBox bar = new HBox(8);
            bar.setAlignment(Pos.CENTER_LEFT);
            if (edit != null) {
                bar.getChildren().add(edit);
            }
            if (rewriter != null) {
                writeBack = new Button("Write back to the block");
                writeBack.setFont(Font.font(base * 0.9));
                writeBack.setTooltip(new Tooltip("Restates the block in the document as the editor has it"));
                writeBack.setDisable(true);
                writeBack.setOnAction(e -> {
                    String restated = ComplexConceptText.write(spec);
                    if (!restated.equals(canonical(body))) {
                        rewriter.rewrite(info, body, info, restated);
                    }
                });
                bar.getChildren().add(writeBack);
            }
            if (spec.concept().local() && viewProperties != null) {
                Button propose = new Button("Propose");
                propose.setFont(Font.font(base * 0.9));
                propose.setTooltip(new Tooltip("Writes the concept and its specification to the knowledge base, uncommitted"));
                propose.setOnAction(e -> {
                    ComplexConceptWrites.Written written = ComplexConceptWrites.write(spec, viewCalc, Coordinates.Edit.Default());
                    result.setStyle(written.wrote() ? "-fx-text-fill: #2a6;" : "-fx-text-fill: #a33;");
                    result.setText(written.message());
                    propose.setDisable(written.wrote());
                });
                bar.getChildren().add(propose);
            }
            result.setFont(Font.font(base * 0.9));
            result.setWrapText(true);
            bar.getChildren().add(result);
            return bar;
        }

        private String canonical(String text) {
            return parsed.spec().map(ComplexConceptText::write).orElse(text);
        }

        // ── The read-only form ──────────────────────────────────────────

        /** The block with no tree under it: built from the spec's labels, it asks the store for no nid. */
        private Node readOnly() {
            VBox section = new VBox(6);
            spec.definitionParent().ifPresent(parent -> section.getChildren().add(row("a kind of", chip(parent))));
            spec.shape().ifPresent(shape -> {
                switch (shape) {
                    case Focused focused -> section.getChildren().add(focused(focused));
                    case Uniform uniform -> section.getChildren().add(uniform(uniform));
                }
            });
            return section;
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
            Node domain = uniform.domain().map(ComplexConceptBlockRenderer.this::chip).orElseGet(() -> {
                Label none = new Label("(no measurement domain)");
                none.setFont(Font.font(base * 0.95));
                none.setStyle("-fx-text-fill: #a33;");
                return none;
            });
            section.getChildren().add(row(words, domain));
            HBox members = new HBox(6);
            members.setPadding(new Insets(0, 0, 0, 12));
            members.setAlignment(Pos.CENTER_LEFT);
            for (Ref member : uniform.members()) {
                members.getChildren().add(chip(member));
            }
            section.getChildren().add(members);
            return section;
        }
    }

    private Node header(Spec spec) {
        Label title = new Label("Complex concept");
        title.setFont(Font.font(null, FontWeight.BOLD, base));
        Node defined = chip(spec.concept());
        if (defined instanceof KonceptBadge badge) {
            badge.setStatus(KonceptStatus.COMPLEX);      // this is the card that mark opens, so a click has nothing further to show
            badge.setOnDisclose(() -> { });
        }
        HBox row = new HBox(8, title, defined);
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

    private Node list(String heading, List<String> entries) {
        VBox box = new VBox(2);
        Label title = new Label(heading);
        title.setFont(Font.font(null, FontWeight.BOLD, base * 0.9));
        box.getChildren().add(title);
        List<Label> lines = new ArrayList<>();
        for (String entry : entries) {
            Label line = new Label("• " + entry);
            line.setFont(Font.font(base * 0.9));
            line.setWrapText(true);
            lines.add(line);
        }
        box.getChildren().addAll(lines);
        return box;
    }

    private Label head(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(null, FontWeight.BOLD, base * 0.8));
        label.setStyle("-fx-text-fill: #666;");
        return label;
    }

    // ── Chips ───────────────────────────────────────────────────────────

    private Node chip(Ref ref) {
        Optional<PublicId> pid = ref.local() ? Optional.empty() : ComponentText.publicId(ref.nid());
        if (pid.isEmpty()) {
            return KonceptChips.capped(KonceptChips.blockConcept(ref.label(), viewProperties, viewCalc, base, null), base * 30);
        }
        String sctid = ComponentText.sctid(viewCalc, ref.nid()).orElse(null);
        return KonceptChips.capped(viewProperties != null
                ? KonceptChips.chip(pid.get(), sctid, viewProperties, base)
                : KonceptChips.chip(pid.get(), sctid, viewCalc, base), base * 30);
    }
}
