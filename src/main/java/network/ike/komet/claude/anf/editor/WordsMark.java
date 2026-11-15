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
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import network.ike.docs.konceptcore.KonceptKind;
import network.ike.komet.document.DecisionFigure;
import network.ike.komet.document.DecisionFigure.Mark;
import network.ike.komet.document.DecisionFigureText;

import java.util.List;

/**
 * The mark of words the knowledge base has nothing for yet: a question mark in a ring
 * (IKE-Network/ike-issues#812). It is one mark, whatever the words turn out to be. It does not
 * say that a concept, a complex concept or a statement is wanted, since that is settled when the
 * words are resolved, and the mark then gives way to what they became: a concept's badge, a
 * complex concept's tile, a statement's.
 *
 * <p>The ring is open where a tile is filled: a tile marks what the knowledge base has, the ring
 * what it does not have. Its question mark and its colour are those of the sigil a badge carries
 * for a component the knowledge base does not have ({@link KonceptKind#UNKNOWN}), so the question
 * mark looks one way wherever it means that.
 */
public final class WordsMark {

    /** The mark's style class on the screen. */
    public static final String STYLE_CLASS = "anf-words-mark";

    private WordsMark() {
    }

    /**
     * The mark for the screen, beside text of a size.
     *
     * @param size the size of the text it stands beside
     * @param more what else is to be said of the mark where it stands, after what it means
     * @return the mark, which says what it means when the pointer rests on it
     */
    public static Node node(double size, String... more) {
        Color ink = Color.web(KonceptKind.UNKNOWN.colorHex());
        double edge = Math.rint(size * 1.1);
        double stroke = Math.max(1.1, size * 0.09);
        Circle ring = new Circle(edge / 2 - stroke / 2, Color.TRANSPARENT);
        ring.setStroke(ink);
        ring.setStrokeWidth(stroke);
        Text question = new Text(KonceptKind.UNKNOWN.glyph());
        question.setFont(Font.font(null, FontWeight.BOLD, size * 0.78));
        question.setFill(ink);
        StackPane mark = new StackPane(ring, question);
        mark.getStyleClass().add(STYLE_CLASS);
        mark.setMinSize(edge, edge);
        mark.setPrefSize(edge, edge);
        mark.setMaxSize(edge, edge);
        MarkMeaning.install(mark, MarkMeaning.WORDS_ONLY, more);
        return mark;
    }

    /**
     * Draws the mark on a page, at the head of a line of text of a size.
     *
     * @param marks the figure's marks, which the ring and its question mark join
     * @param x     the mark's left edge
     * @param top   the top of the line of text it stands beside
     * @param size  the size of that text
     * @param role  the role its marks take in the figure
     * @return where the words begin after the mark
     */
    public static double draw(List<Mark> marks, double x, double top, double size, String role) {
        String ink = KonceptKind.UNKNOWN.colorHex();
        double edge = size * 0.92;
        double radius = edge / 2;
        // the middle of the line's letters is a little under half the text's size beneath its top
        double middle = top + size * 0.5 + 0.4;
        marks.add(new DecisionFigure.Circle(x + radius, middle, radius - 0.4, null, DecisionFigure.Stroke.solid(ink, 0.8), role));
        String glyph = KonceptKind.UNKNOWN.glyph();
        double letter = size * 0.72;
        // a figure's text has the middle of its letters about 0.44 of its size beneath its top
        marks.add(new DecisionFigure.Text(x + radius - DecisionFigureText.width(glyph, letter, true) / 2, middle - letter * 0.44 - 0.2,
                glyph, letter, true, false, ink, role));
        return x + edge + 3.5;
    }
}
