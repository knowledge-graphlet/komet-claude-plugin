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
package network.ike.komet.claude.anf.editor;

import javafx.scene.Node;
import javafx.scene.layout.Pane;
import dev.ikm.komet.framework.controls.MarkTile;
import network.ike.komet.document.DecisionFigure.Mark;
import network.ike.komet.document.DecisionFigure.Rect;

import java.util.List;

/**
 * The sign of a statement: the assertion sign, an upright stroke with a stroke out of its middle,
 * which logic writes before what is asserted, in white on a tile of the statement's ink
 * (IKE-Network/ike-issues#812). A statement carries it wherever it is named, on its line in a
 * document, at the head of its form, beside a tree's node and beside a finding. A tile is heavier
 * than a kind sigil or a status mark and of a shape neither has, so a statement is seen before its
 * words are read. It is drawn, not set as a glyph, so it is the same on the screen and on a page,
 * whatever face the text is in.
 */
public final class StatementSign {

    /** The tile's ink. */
    public static final String INK = "#2E3240";
    /** The ink of the empty place where an identicon would stand. */
    public static final String UNASSIGNED = "#8a93a6";
    /** The sign as a character, for text that is set and cannot be drawn in. */
    public static final String GLYPH = "\u22A2";
    /** The sign's two strokes, in parts of the tile's edge: the upright, and the stroke out of its middle. */
    private static final double[][] STROKES = {{0.33, 0.22, 0.33, 0.78}, {0.33, 0.5, 0.76, 0.5}};

    private StatementSign() {
    }

    /**
     * The sign for the screen, beside text of a size.
     *
     * @param size the size of the text it stands beside
     * @return the sign, which says what it is when the pointer rests on it
     */
    public static Node node(double size) {
        Node sign = plain(size);
        MarkMeaning.install(sign, MarkMeaning.STATEMENT);
        return sign;
    }

    /**
     * The sign with nothing to say for itself, for a label that says what the sign is and more.
     *
     * @param size the size of the text it stands beside
     * @return the sign
     */
    public static Node plain(double size) {
        Pane sign = MarkTile.of(Math.rint(size * 1.1), INK, STROKES);
        sign.getStyleClass().add("anf-statement-sign");
        return sign;
    }

    /**
     * A statement's identicon, which follows its sign as a STAMP's follows its pentagon: the same
     * picture wherever the statement is named, so a tree's node says which statement it cites.
     *
     * @param statement the statement's identity in the knowledge base
     * @param size      the size of the text it stands beside
     * @return the identicon
     */
    public static Node identicon(dev.ikm.tinkar.common.id.PublicId statement, double size) {
        javafx.scene.image.ImageView picture = new javafx.scene.image.ImageView(
                dev.ikm.komet.framework.Identicon.generateIdenticonImage(statement));
        picture.setFitWidth(Math.rint(size));
        picture.setFitHeight(Math.rint(size));
        picture.getStyleClass().add("anf-statement-identicon");
        return picture;
    }

    /**
     * The empty place where a statement's identicon would stand, for a statement the knowledge
     * base does not have: a square of the identicon's size, hollow, its edge dashed. It is in the
     * language of the marks, open where what the knowledge base has is filled, and nothing in it
     * can be taken for an identicon's pattern.
     *
     * @param size the size of the text it stands beside
     * @return the empty place
     */
    public static Node unassigned(double size) {
        double edge = Math.rint(size);
        javafx.scene.shape.Rectangle slot = new javafx.scene.shape.Rectangle(0.5, 0.5, edge - 1, edge - 1);
        slot.setFill(javafx.scene.paint.Color.TRANSPARENT);
        slot.setStroke(javafx.scene.paint.Color.web(UNASSIGNED));
        slot.setStrokeWidth(1);
        slot.getStrokeDashArray().setAll(2.0, 2.0);
        slot.setArcWidth(3);
        slot.setArcHeight(3);
        Pane place = new Pane(slot);
        place.getStyleClass().add("anf-statement-unassigned");
        place.setMinSize(edge, edge);
        place.setPrefSize(edge, edge);
        place.setMaxSize(edge, edge);
        return place;
    }

    /**
     * What follows a statement's sign: its identicon, or the empty place of one not assigned, each
     * saying what it is when the pointer rests on it.
     *
     * @param statement the statement's identity in the knowledge base, or empty when it has none
     * @param size      the size of the text it stands beside
     * @return the identicon or the empty place
     */
    public static Node identity(java.util.Optional<dev.ikm.tinkar.common.id.PublicId> statement, double size) {
        Node shown = identityPlain(statement, size);
        MarkMeaning.install(shown, statement.isPresent() ? MarkMeaning.STATEMENT_IDENTITY : MarkMeaning.NO_IDENTITY);
        return shown;
    }

    /**
     * What follows a statement's sign, with nothing to say for itself, for a label that says more.
     *
     * @param statement the statement's identity in the knowledge base, or empty when it has none
     * @param size      the size of the text it stands beside
     * @return the identicon or the empty place
     */
    public static Node identityPlain(java.util.Optional<dev.ikm.tinkar.common.id.PublicId> statement, double size) {
        return statement.map(identity -> identicon(identity, size)).orElseGet(() -> unassigned(size));
    }

    /**
     * Draws the sign on a page, at the head of a line of text of a size.
     *
     * @param marks the figure's marks, which the tile and the sign's two strokes join
     * @param x     the tile's left edge
     * @param top   the top of the line of text it stands beside
     * @param size  the size of that text
     * @param role  the role its marks take in the figure
     */
    public static void draw(List<Mark> marks, double x, double top, double size, String role) {
        double edge = size * 0.92;
        double stroke = Math.max(0.9, edge * 0.13);
        // the middle of the line's letters is a little under half the text's size beneath its top
        double y = top + size * 0.5 - edge / 2 + 0.4;
        marks.add(new Rect(x, y, edge, edge, INK, 1, null, role));
        double upright = x + STROKES[0][0] * edge;
        marks.add(new Rect(upright - stroke / 2, y + STROKES[0][1] * edge, stroke, (STROKES[0][3] - STROKES[0][1]) * edge, "#FFFFFF", 1, null, role));
        marks.add(new Rect(upright, y + edge / 2 - stroke / 2, (STROKES[1][2] - STROKES[1][0]) * edge, stroke, "#FFFFFF", 1, null, role));
    }
}
