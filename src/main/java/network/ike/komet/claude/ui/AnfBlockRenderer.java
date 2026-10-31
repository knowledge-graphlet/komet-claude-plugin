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
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.anf.AnfStatementView;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.tools.GraphTools;

/**
 * The live rendering of an {@code anf} fenced block on the assistant surface
 * ({@code dev-anf-adoc-representation}, {@code dev-calculator-blocks}): the ANF statement the
 * block writes, drawn as the statement card the narrative lift shows, its grounded concepts as
 * Koncept chips and its result as an interval with its measure semantic. A result the record does
 * not determine is written as the interval that says so, {@code [0,1]} on the Presence scale or an
 * unbounded measure, and renders as that, never as a guess. The fence names the statement for the
 * document, {@code anf type=performance id=stmt-sbp}, and a {@code calculate} block binds to it by
 * that name through the shared {@link BlockContext}: the note's data gathering, read by its
 * assessment.
 */
final class AnfBlockRenderer implements BlockRenderer {

    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private final BlockContext context;

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

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        if (viewCalc == null || !isTagged(info)) {
            return List.of();
        }
        String body = literal == null ? "" : literal;
        return List.of(new BlockPiece(() -> card(info, body), body.stripTrailing()));
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
        for (String word : Tokens.words(info.replace(',', ' '))) {
            if (word.startsWith("type=")) {
                return word.substring("type=".length()).toLowerCase(Locale.ROOT);
            }
        }
        return "performance";
    }

    /** The name the fence gives the statement for the document, {@code id=…}; empty when it gives none. */
    static Optional<String> idOf(String info) {
        for (String word : Tokens.words(info.replace(',', ' '))) {
            if (word.startsWith("id=")) {
                return Optional.of(word.substring("id=".length()));
            }
        }
        return Optional.empty();
    }

    /** The block as the lift's parser reads it: the header line, the body between its delimiters. */
    static String asAdoc(String info, String body) {
        return "[anf,type=" + typeOf(info) + "]\n--\n" + body + (body.endsWith("\n") ? "" : "\n") + "--\n";
    }

    private AnfStatement parse(String info, String body) {
        AnfAdoc.ConceptResolver resolver = id -> GraphTools.resolveConcept(id, viewCalc);
        try {
            return AnfAdoc.parse(asAdoc(info, body), resolver);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Node card(String info, String body) {
        AnfStatement statement = parse(info, body);
        VBox column = new VBox(3);
        column.getStyleClass().add("anf-block");
        column.setPadding(new Insets(2, 0, 2, 0));
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("ANF statement");
        title.setFont(Font.font(null, FontWeight.BOLD, base * 0.9));
        header.getChildren().add(title);
        Label kind = new Label(typeOf(info));
        kind.setFont(Font.font(base * 0.85));
        kind.setTextFill(Color.web("#666"));
        header.getChildren().add(kind);
        Optional<String> id = idOf(info);
        id.ifPresent(name -> {
            Label named = new Label("· " + name);
            named.setFont(Font.font(base * 0.85));
            named.setTextFill(Color.web("#666"));
            header.getChildren().add(named);
        });
        column.getChildren().add(header);
        if (statement == null) {
            Label gap = new Label("The block did not read as an ANF statement.");
            gap.setFont(Font.font(base * 0.9));
            gap.setStyle("-fx-text-fill: #8a5a00;");
            column.getChildren().add(gap);
            return column;
        }
        id.ifPresent(name -> context.defineStatement(name, statement));
        Node view = viewProperties != null ? AnfStatementView.render(statement, viewProperties, null)
                : AnfStatementView.render(statement);
        column.getChildren().add(view);
        return column;
    }
}
