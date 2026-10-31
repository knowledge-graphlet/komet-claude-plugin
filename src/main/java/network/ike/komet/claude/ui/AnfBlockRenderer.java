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
import dev.ikm.komet.kview.controls.KLDiTreeControlFactory;
import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.anf.AnfProjection;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.anf.AnfStatementView;
import network.ike.komet.claude.anf.AnfValue;
import network.ike.komet.claude.anf.Circumstance;
import network.ike.komet.claude.anf.editor.AnfStatementControl;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.koncept.ComponentText;
import network.ike.komet.claude.tools.GraphTools;

/**
 * The live rendering of an {@code anf} fenced block on the assistant surface
 * ({@code dev-anf-adoc-representation}, {@code dev-calculator-blocks}): a finding of the note,
 * inline with the narrative it was lifted from. Folded, it is one line: the narrative span the
 * fence names ({@code narrative="A 62-year-old man"}), the statement's topic as a Koncept chip,
 * and its result; resting on it shows the whole statement, and a click unfolds the statement as
 * the editable ANF tree control the lift's editor uses, so the reader sees the narrative, the ANF
 * it became, and can correct the ANF in place. An edit is projected back to the block's text
 * through the surface's {@link BlockRewriter}, and the statement is re-published to the blocks
 * that read it, so a calculation beneath recomputes. A result the record does not determine is
 * written as the interval that says so, {@code [0,1]} on the Presence scale or an unbounded
 * measure, and renders as that, never as a guess. The fence names the statement for the document,
 * {@code anf type=performance id=stmt-sbp}, and a {@code calculate} block binds to it by that name
 * through the shared {@link BlockContext}.
 */
final class AnfBlockRenderer implements BlockRenderer {

    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private final BlockContext context;
    private KonceptLabelTypography typography = KonceptLabelTypography.DEFAULT;
    private BlockRewriter rewriter;

    AnfBlockRenderer(ViewCalculator viewCalc, double base, BlockContext context) {
        this(viewCalc, null, base, context);
    }

    AnfBlockRenderer(ViewProperties viewProperties, double base, BlockContext context) {
        this(viewProperties == null ? null : viewProperties.calculator(), viewProperties, base, context);
    }

    private AnfBlockRenderer(ViewCalculator viewCalc, ViewProperties viewProperties, double base, BlockContext context) {
        this.viewCalc = viewCalc;
        this.viewProperties = viewProperties;
        this.base = base;
        this.context = context;
    }

    void setChipTypography(KonceptLabelTypography typography) {
        this.typography = typography == null ? KonceptLabelTypography.DEFAULT : typography;
    }

    /** Where an edit of the statement goes when it is written back to the document; null leaves the edit on the view. */
    void setRewriter(BlockRewriter rewriter) {
        this.rewriter = rewriter;
    }

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        if (viewCalc == null || !isTagged(info)) {
            return List.of();
        }
        String body = literal == null ? "" : literal;
        return List.of(new BlockPiece(() -> new Finding(info, body).column, body.stripTrailing()));
    }

    /** Whether a fence is an ANF block: its first word is {@code anf}, with or without a type after a comma or space. */
    static boolean isTagged(String info) {
        if (info == null) {
            return false;
        }
        String first = Tokens.words(info).stream().findFirst().orElse("");
        return first.equals("anf") || first.startsWith("anf,");
    }

    /** The statement type the fence names, {@code type=performance|request|narrative}; performance when none. */
    static String typeOf(String info) {
        return attribute(info, "type").map(t -> t.toLowerCase(Locale.ROOT)).orElse("performance");
    }

    /** The name the fence gives the statement for the document, {@code id=…}; empty when it gives none. */
    static Optional<String> idOf(String info) {
        return attribute(info, "id");
    }

    /** The narrative span the statement was lifted from, {@code narrative="…"}; empty when the fence names none. */
    static Optional<String> narrativeOf(String info) {
        return attribute(info, "narrative").map(Tokens::unquote);
    }

    private static Optional<String> attribute(String info, String name) {
        if (info == null) {
            return Optional.empty();
        }
        for (String word : Tokens.words(info.replace(',', ' '))) {
            if (word.startsWith(name + "=")) {
                return Optional.of(word.substring(name.length() + 1));
            }
        }
        return Optional.empty();
    }

    /** The block as the lift's parser reads it: the header line, the body between its delimiters. */
    static String asAdoc(String info, String body) {
        return "[anf,type=" + typeOf(info) + "]\n--\n" + body + (body.endsWith("\n") ? "" : "\n") + "--\n";
    }

    /** The body of a block the lift's writer produced: what lies between its delimiters. */
    static String bodyOf(String adoc) {
        List<String> lines = new ArrayList<>(List.of(adoc.split("\n", -1)));
        List<String> body = new ArrayList<>();
        boolean in = false;
        for (String line : lines) {
            if (line.strip().equals("--")) {
                in = !in;
                continue;
            }
            if (in) {
                body.add(line);
            }
        }
        return String.join("\n", body) + "\n";
    }

    /** One finding: folded to its line, or unfolded to the editable tree. */
    private final class Finding {
        final String info;
        final String originalBody;
        final VBox column = new VBox(2);
        final HBox line = new HBox(8);
        final Label fold = new Label("▸");
        AnfStatement statement;
        AnfStatementControl control;
        boolean open;

        Finding(String info, String body) {
            this.info = info;
            this.originalBody = body;
            column.getStyleClass().add("anf-block");
            column.setPadding(new Insets(1, 0, 1, 0));
            column.setFillWidth(true);
            statement = parse(body);
            idOf(info).ifPresent(name -> {
                if (statement != null) {
                    context.defineStatement(name, statement);
                }
            });
            line.setAlignment(Pos.CENTER_LEFT);
            line.setCursor(Cursor.HAND);
            line.setId(idOf(info).map(name -> "anf-" + name).orElse(null));
            fold.setFont(Font.font(base * 0.9));
            fold.setTextFill(Color.web("#666"));
            fold.setMinWidth(base);
            line.getChildren().add(fold);
            narrativeOf(info).ifPresent(span -> {
                Label narrative = new Label("“" + span + "”");
                narrative.setFont(Font.font(null, FontPosture.ITALIC, base * 0.95));
                narrative.setTextFill(Color.web("#333"));
                line.getChildren().add(narrative);
                Label arrow = new Label("→");
                arrow.setFont(Font.font(base * 0.9));
                arrow.setTextFill(Color.web("#888"));
                line.getChildren().add(arrow);
            });
            if (statement == null) {
                Label gap = new Label("not read as an ANF statement");
                gap.setFont(Font.font(base * 0.9));
                gap.setStyle("-fx-text-fill: #8a5a00;");
                line.getChildren().add(gap);
            } else {
                summary(statement).forEach(line.getChildren()::add);
                idOf(info).ifPresent(name -> {
                    Label named = new Label(name);
                    named.setFont(Font.font(base * 0.78));
                    named.setTextFill(Color.web("#999"));
                    line.getChildren().add(named);
                });
                Tooltip tip = new Tooltip(AnfAdoc.toAdoc(statement).strip());
                tip.setFont(Font.font("Menlo", base * 0.85));
                Tooltip.install(line, tip);
            }
            Region spring = new Region();
            HBox.setHgrow(spring, Priority.ALWAYS);
            line.getChildren().add(spring);
            line.setOnMouseClicked(e -> toggle());
            column.getChildren().add(line);
        }

        private AnfStatement parse(String body) {
            AnfAdoc.ConceptResolver resolver = id -> GraphTools.resolveConcept(id, viewCalc);
            try {
                return AnfAdoc.parse(asAdoc(info, body), resolver);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** The folded line's pieces: the topic as a chip, then the result or the request. */
        private List<Node> summary(AnfStatement s) {
            List<Node> nodes = new ArrayList<>();
            nodes.add(value(s.topic()));
            switch (s.circumstance()) {
                case Circumstance.Performance p -> {
                    if (p.result() != null) {
                        nodes.add(plain(result(p.result())));
                    }
                    if (p.bodySite() != null) {
                        nodes.add(muted("at"));
                        nodes.add(value(p.bodySite()));
                    }
                }
                case Circumstance.Request r -> {
                    nodes.add(muted("requested"));
                    if (r.requestedResult() != null) {
                        nodes.add(plain(result(r.requestedResult())));
                    }
                }
                case Circumstance.Narrative n -> nodes.add(muted("“" + n.text() + "”"));
            }
            return nodes;
        }

        private String result(AnfStatement.Result r) {
            String unit = r.measureSemantic() instanceof AnfValue.Grounded g ? g.label()
                    : r.measureSemantic() instanceof AnfValue.Candidate c ? c.provisionalLabel() : "";
            boolean unbounded = r.lowerBound() == null && r.upperBound() == null;
            String interval = unbounded ? "indeterminate"
                    : Tokens.interval(r.lowerBound(), r.upperBound(), r.includeLowerBound(), r.includeUpperBound());
            return unit.isBlank() ? interval : interval + " " + unit;
        }

        private Node value(AnfValue v) {
            return switch (v) {
                case null -> muted("?");
                case AnfValue.Grounded g -> chip(g);
                case AnfValue.Candidate c -> localConcept(c.provisionalLabel());
                case AnfValue.Clarify c -> localConcept("clarify " + c.field());
            };
        }

        private Node chip(AnfValue.Grounded g) {
            Optional<PublicId> pid = ComponentText.publicId(g.nid());
            if (pid.isEmpty()) {
                return plain(g.label());
            }
            String sctid = ComponentText.sctid(viewCalc, g.nid()).orElse(null);
            Node chip = viewProperties != null
                    ? KonceptChips.chip(pid.get(), sctid, viewProperties, base, typography)
                    : KonceptChips.chip(pid.get(), sctid, viewCalc, base, typography);
            if (chip instanceof KonceptBadge badge) {
                badge.setStatusSlotReserved(true);
            }
            return chip;
        }

        /** Unfolds the statement as the editable tree, or folds it again. */
        private void toggle() {
            open = !open;
            fold.setText(open ? "▾" : "▸");
            if (!open) {
                if (control != null) {
                    column.getChildren().remove(control);
                }
                return;
            }
            if (statement == null) {
                return;
            }
            if (control == null) {
                control = tree();
            }
            if (!column.getChildren().contains(control)) {
                column.getChildren().add(control);
            }
        }

        /** The editable ANF tree over this statement, writing each edit back to the block. */
        private AnfStatementControl tree() {
            AnfStatementControl tree = KLDiTreeControlFactory.configure(new AnfStatementControl(), viewCalc);
            tree.setCompactMode(true);
            tree.setShowsMissing(false);
            tree.setMaxWidth(Double.MAX_VALUE);
            tree.setPadding(new Insets(2, 0, 4, base * 1.5));
            DiTreeEntity start = AnfProjection.toTree(statement);
            tree.setValue(start);
            tree.valueProperty().subscribe((was, now) -> {
                if (now == null || now == start || now.equals(was)) {
                    return;
                }
                AnfStatement edited;
                try {
                    edited = AnfProjection.fromTree(now, AnfProjection.grounding(viewCalc));
                } catch (RuntimeException e) {
                    return;
                }
                statement = edited;
                idOf(info).ifPresent(name -> context.defineStatement(name, edited));
                refold(edited);
                if (rewriter != null) {
                    String body = bodyOf(AnfAdoc.toAdoc(edited));
                    if (!body.equals(originalBody) && !body.equals(originalBody + "\n")) {
                        rewriter.rewrite(info, originalBody, info, body);
                    }
                }
            });
            return tree;
        }

        /** Redraws the folded line after an edit. */
        private void refold(AnfStatement edited) {
            List<Node> keep = new ArrayList<>();
            for (Node node : line.getChildren()) {
                keep.add(node);
                if (node instanceof Label label && "→".equals(label.getText())) {
                    break;
                }
            }
            if (keep.size() == line.getChildren().size()) {
                keep = new ArrayList<>(line.getChildren().subList(0, 1));
            }
            line.getChildren().setAll(keep);
            summary(edited).forEach(line.getChildren()::add);
            idOf(info).ifPresent(name -> {
                Label named = new Label(name);
                named.setFont(Font.font(base * 0.78));
                named.setTextFill(Color.web("#999"));
                line.getChildren().add(named);
            });
            Region spring = new Region();
            HBox.setHgrow(spring, Priority.ALWAYS);
            line.getChildren().add(spring);
        }
    }

    private Label plain(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(base * 0.95));
        return label;
    }

    private Label muted(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(base * 0.85));
        label.setTextFill(Color.web("#666"));
        return label;
    }

    private Label localConcept(String text) {
        Label chip = new Label(text);
        chip.setFont(Font.font(base * 0.95));
        chip.setStyle("-fx-background-color: #f4f2ec; -fx-background-radius: 9; -fx-padding: 1 7 1 7;"
                + " -fx-text-fill: #333; -fx-border-color: #b9b3a3; -fx-border-radius: 9; -fx-border-style: dashed;"
                + " -fx-border-width: 1;");
        return chip;
    }

    /** The read-only card, for a surface with no view to edit against; kept for the lift's own history. */
    static Node card(AnfStatement statement) {
        return AnfStatementView.render(statement);
    }
}
