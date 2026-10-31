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
import java.util.function.Consumer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
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
import javafx.scene.shape.Circle;
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
import network.ike.komet.claude.anf.element.ClinicalElement;
import network.ike.komet.claude.anf.element.ElementLevel;
import network.ike.komet.claude.anf.element.ElementText;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.koncept.ComponentText;
import network.ike.komet.claude.stamp.SegmentedControl;
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
        if (viewCalc != null && isNotClinical(info)) {
            return List.of(new BlockPiece(() -> new NotClinical(info).block, ""));
        }
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

    /** Whether a fence marks narrative text as carrying no clinical fact, {@code anf-not-clinical from="…"}. */
    static boolean isNotClinical(String info) {
        return info != null && Tokens.words(info).stream().findFirst().orElse("").startsWith("anf-not-clinical");
    }

    /** A not-clinical mark: one muted line naming the words it covers, shown with the elements. */
    private final class NotClinical {
        final HBox row = new HBox(6);
        /** The block node: the surface may bind its visibility to {@link #shown}, so the row inside is what this mark hides. */
        final VBox block = new VBox(row);
        final Consumer<ElementLevel> levelListener = this::show;
        final BooleanProperty shown = new SimpleBooleanProperty(true);

        NotClinical(String info) {
            Label word = stateTag("not clinical", "#f1efe8", "#5f5e5a");
            Label quote = new Label("“" + String.join(" … ", ElementText.quotes(attribute(info, "from").orElse(""))) + "”");
            quote.setFont(Font.font(null, FontPosture.ITALIC, base * 0.9));
            quote.setTextFill(Color.web("#888"));
            row.getChildren().addAll(word, quote);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(1, 0, 1, base));
            block.getProperties().put(MarkdownRichText.BLOCK_SHOWN, shown);
            block.getProperties().put(MarkdownRichText.BLOCK_NO_EXPAND, Boolean.TRUE);
            // the block is all that is kept of this mark: it holds the mark, and so its level listener
            block.getProperties().put(NotClinical.class, this);
            context.listenLevel(levelListener);
            show(context.elementLevel());
        }

        private void show(ElementLevel level) {
            boolean visible = level.shows(ElementLevel.ELEMENTS);
            row.setVisible(visible);
            row.setManaged(visible);
            shown.set(visible);
        }
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
        return attribute(info, "narrative");
    }

    private static Optional<String> attribute(String info, String name) {
        if (info == null) {
            return Optional.empty();
        }
        String value = ElementText.attributes(info).get(name);
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** The element sentence the fence names, {@code element="…"} (dev-anf-element-isolation). */
    static Optional<String> elementOf(String info) {
        return attribute(info, "element");
    }

    /** The narrative words the fence quotes: {@code narrative="…"}, or the quotes of its {@code from} anchors. */
    static Optional<String> wordsOf(String info) {
        Optional<String> narrative = narrativeOf(info);
        if (narrative.isPresent()) {
            return narrative;
        }
        List<String> quotes = ElementText.quotes(attribute(info, "from").orElse(""));
        return quotes.isEmpty() ? Optional.empty() : Optional.of(String.join(" … ", quotes));
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

    /** One finding: folded to its line, or unfolded to the editable tree; an element's row follows the document's level. */
    private final class Finding {
        final String info;
        final String originalBody;
        final VBox column = new VBox(2);
        final HBox line = new HBox(8);
        final Label fold = new Label("▸");
        final boolean element;
        final List<Node> spanNodes = new ArrayList<>();
        final List<Node> elementNodes = new ArrayList<>();
        final List<Node> anfNodes = new ArrayList<>();
        AnfStatement statement;
        AnfStatementControl control;
        SegmentedControl<ElementLevel> levelSwitch;
        boolean open;
        /** Held here so the context's weak reference lives as long as this finding. */
        final Consumer<ElementLevel> levelListener = this::show;
        /** Whether the block shows at all: an element row hides at the Narrative level, unless it carries the switch. */
        final BooleanProperty shown = new SimpleBooleanProperty(true);

        Finding(String info, String body) {
            this.info = info;
            this.originalBody = body;
            column.getStyleClass().add("anf-block");
            column.setPadding(new Insets(1, 0, 1, 0));
            column.setFillWidth(true);
            element = elementOf(info).isPresent();
            statement = element && body.isBlank() ? null : parse(body);
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
            if (element) {
                Circle dot = new Circle(base * 0.32,
                        Color.web(context.elementSpans().colour(info).orElse("#888888")));
                spanNodes.add(dot);
            }
            wordsOf(info).ifPresent(span -> {
                Label narrative = new Label("“" + span + "”");
                narrative.setFont(Font.font(null, FontPosture.ITALIC, base * 0.95));
                narrative.setTextFill(Color.web("#333"));
                spanNodes.add(narrative);
                spanNodes.add(arrow());
            });
            elementOf(info).ifPresent(sentence -> {
                Label fact = new Label(sentence);
                fact.setFont(Font.font(base * 0.95));
                fact.setTextFill(Color.web("#282040"));
                elementNodes.add(fact);
            });
            buildAnf();
            if (statement != null) {
                Tooltip tip = new Tooltip(AnfAdoc.toAdoc(statement).strip());
                tip.setFont(Font.font("Menlo", base * 0.85));
                Tooltip.install(line, tip);
            }
            line.setOnMouseClicked(e -> toggle());
            if (element && context.elementSpans().isSwitchHost(info)) {
                column.getChildren().add(levelSwitch());
            }
            column.getChildren().add(line);
            if (element) {
                column.getProperties().put(MarkdownRichText.BLOCK_SHOWN, shown);
                column.getProperties().put(MarkdownRichText.BLOCK_NO_EXPAND, Boolean.TRUE);
                column.getProperties().put(Finding.class, this);
                context.listenLevel(levelListener);
                show(context.elementLevel());
            } else {
                show(ElementLevel.ANF);
            }
        }

        /** The ANF part of the row: the statement's topic and result, its name, or why there is none. */
        private void buildAnf() {
            anfNodes.clear();
            if (statement == null && element) {
                anfNodes.add(stateTag("not lifted", "#ece8f2", "#5e4b7e"));
                return;
            }
            if (statement == null) {
                Label gap = new Label("not read as an ANF statement");
                gap.setFont(Font.font(base * 0.9));
                gap.setStyle("-fx-text-fill: #8a5a00;");
                anfNodes.add(gap);
                return;
            }
            anfNodes.addAll(summary(statement));
            if (stale()) {
                anfNodes.add(stateTag("stale", "#fcebeb", "#a32d2d"));
            } else if (element && ClinicalElement.needsAttention(statement)) {
                anfNodes.add(stateTag("needs attention", "#faeeda", "#854f0b"));
            }
            idOf(info).ifPresent(name -> {
                Label named = new Label(name);
                named.setFont(Font.font(base * 0.78));
                named.setTextFill(Color.web("#999"));
                anfNodes.add(named);
            });
        }

        private boolean stale() {
            Optional<String> lifted = attribute(info, "lifted");
            return element && lifted.isPresent() && !lifted.equals(elementOf(info));
        }

        /** The element's state in a word, as the Elements level shows it beside the sentence. */
        private Label stateWord() {
            if (statement == null) {
                return stateTag("not lifted", "#ece8f2", "#5e4b7e");
            }
            if (stale()) {
                return stateTag("stale", "#fcebeb", "#a32d2d");
            }
            if (ClinicalElement.needsAttention(statement)) {
                return stateTag("needs attention", "#faeeda", "#854f0b");
            }
            return stateTag("lifted", "#e1f5ee", "#0f6e56");
        }

        /** The one switch over the note: Narrative, Elements, ANF, carried by its first element. */
        private Node levelSwitch() {
            levelSwitch = new SegmentedControl<>(List.of(ElementLevel.values()), ElementLevel::label,
                    ElementLevel::description, level -> "element-level-" + level.name().toLowerCase(Locale.ROOT))
                    .light(base * 0.8);
            levelSwitch.setId("element-level-switch");
            levelSwitch.select(context.elementLevel());
            levelSwitch.valueProperty().subscribe(level -> context.setElementLevel(level));
            Label show = new Label("Show");
            show.setFont(Font.font(base * 0.8));
            show.setTextFill(Color.web("#666"));
            HBox row = new HBox(6, show, levelSwitch);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(2, 0, 4, 0));
            return row;
        }

        /** Lays the row out for a level: nothing at Narrative, the sentence and state at Elements, the ANF at ANF. */
        private void show(ElementLevel level) {
            if (levelSwitch != null && levelSwitch.getValue() != level) {
                levelSwitch.select(level);
            }
            boolean visible = !element || level.shows(ElementLevel.ELEMENTS);
            line.setVisible(visible);
            line.setManaged(visible);
            shown.set(visible || levelSwitch != null);
            boolean anf = !element || level.shows(ElementLevel.ANF);
            if (!anf && open) {
                toggle();
            }
            List<Node> nodes = new ArrayList<>();
            fold.setVisible(anf && statement != null);
            nodes.add(fold);
            nodes.addAll(spanNodes);
            nodes.addAll(elementNodes);
            if (anf) {
                if (!elementNodes.isEmpty()) {
                    nodes.add(arrow());
                }
                nodes.addAll(anfNodes);
            } else {
                nodes.add(stateWord());
            }
            Region spring = new Region();
            HBox.setHgrow(spring, Priority.ALWAYS);
            nodes.add(spring);
            line.getChildren().setAll(nodes);
            line.setCursor(anf && statement != null ? Cursor.HAND : Cursor.DEFAULT);
        }

        private Label arrow() {
            Label arrow = new Label("→");
            arrow.setFont(Font.font(base * 0.9));
            arrow.setTextFill(Color.web("#888"));
            return arrow;
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
            if (!open && element && !context.elementLevel().shows(ElementLevel.ANF)) {
                return;
            }
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
            buildAnf();
            show(element ? context.elementLevel() : ElementLevel.ANF);
        }
    }

    private Label stateTag(String word, String fill, String ink) {
        Label tag = new Label(word);
        tag.setFont(Font.font(base * 0.78));
        tag.setStyle("-fx-background-color: " + fill + "; -fx-text-fill: " + ink + "; -fx-background-radius: 4; -fx-padding: 1 6 1 6;");
        return tag;
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
