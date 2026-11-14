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

import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.StrokeLineCap;
import network.ike.komet.document.DecisionFigure.Mark;
import network.ike.komet.document.DecisionFigure.Rect;

import java.util.List;

/**
 * The sign of a statement: the assertion sign, an upright stroke with a stroke out of its middle,
 * which logic writes before what is asserted (IKE-Network/ike-issues#812). A statement carries it
 * wherever it is named, on its line in a document, beside a tree's node and beside a finding, as
 * a concept carries its badge. It is drawn, not set as a glyph, so it is the same two strokes on
 * the screen and on a page, whatever face the text is in.
 */
final class StatementSign {

    /** The sign's ink, the ink of the bar a statement's form begins with. */
    static final String INK = "#2E3240";
    /** The sign as a character, for text that is set and cannot be drawn in. */
    static final String GLYPH = "⊢";

    private StatementSign() {
    }

    /**
     * The sign for the screen, beside text of a size.
     *
     * @param size the size of the text it stands beside
     * @return the sign, with a tooltip that names it
     */
    static Node node(double size) {
        double height = size * 0.74;
        double width = size * 0.56;
        double stroke = Math.max(1.3, size * 0.11);
        Line upright = new Line(stroke / 2, stroke / 2, stroke / 2, height + stroke / 2);
        Line out = new Line(stroke / 2, height / 2 + stroke / 2, width + stroke / 2, height / 2 + stroke / 2);
        for (Line line : List.of(upright, out)) {
            line.setStroke(Color.web(INK));
            line.setStrokeWidth(stroke);
            line.setStrokeLineCap(StrokeLineCap.BUTT);
        }
        Pane sign = new Pane(upright, out);
        sign.getStyleClass().add("anf-statement-sign");
        sign.setMinSize(width + stroke, height + stroke);
        sign.setPrefSize(width + stroke, height + stroke);
        sign.setMaxSize(width + stroke, height + stroke);
        Tooltip.install(sign, new Tooltip("A statement"));
        return sign;
    }

    /**
     * Draws the sign on a page, at the head of a line of text of a size.
     *
     * @param marks the figure's marks, which the sign's two strokes join
     * @param x     the sign's left edge
     * @param top   the top of the line of text it stands beside
     * @param size  the size of that text
     * @param role  the role its strokes take in the figure
     */
    static void draw(List<Mark> marks, double x, double top, double size, String role) {
        double height = size * 0.74;
        double width = size * 0.56;
        double stroke = Math.max(0.9, size * 0.11);
        // the middle of the line's letters is a little under half the text's size beneath its top
        double y = top + size * 0.5 - height / 2 + 0.4;
        marks.add(new Rect(x, y, stroke, height, INK, 1, null, role));
        marks.add(new Rect(x, y + height / 2 - stroke / 2, width, stroke, INK, 1, null, role));
    }
}
