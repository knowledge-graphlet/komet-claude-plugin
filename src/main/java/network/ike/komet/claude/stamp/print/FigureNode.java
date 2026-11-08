/*
 * Copyright © 2025 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.stamp.print;

import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;
import network.ike.komet.claude.stamp.DecisionFigure;

/**
 * A {@link DecisionFigure} as a JavaFX node: the marks the Typst writer draws, at the same places,
 * so the Page layout shows what the PDF prints.
 */
public final class FigureNode {

    private FigureNode() {
    }

    /**
     * Draws a figure.
     *
     * @param figure the figure
     * @param scale  the scale it is drawn at, 1 for its natural size
     * @return a pane of the scaled size
     */
    public static Pane of(DecisionFigure figure, double scale) {
        return of(figure, scale, DocumentFonts.SERIF);
    }

    /**
     * Draws a figure in a named face, for a figure on the reading surface beside the surface's own text.
     *
     * @param figure the figure
     * @param scale  the scale it is drawn at, 1 for its natural size
     * @param family the font family of its text
     * @return a pane of the scaled size
     */
    public static Pane of(DecisionFigure figure, double scale, String family) {
        Group marks = new Group();
        for (DecisionFigure.Mark mark : figure.marks()) {
            marks.getChildren().add(node(mark, family));
        }
        marks.getTransforms().add(new Scale(scale, scale, 0, 0));
        Pane pane = new Pane(marks);
        pane.setMouseTransparent(false);
        pane.setPrefSize(figure.width() * scale, figure.height() * scale);
        pane.setMinSize(figure.width() * scale, figure.height() * scale);
        pane.setMaxSize(figure.width() * scale, figure.height() * scale);
        return pane;
    }

    private static Node node(DecisionFigure.Mark mark, String family) {
        return switch (mark) {
            case DecisionFigure.Rect r -> {
                Rectangle shape = new Rectangle(r.x(), r.y(), r.w(), r.h());
                shape.setOpacity(r.opacity());
                yield styled(shape, r.fill(), r.stroke());
            }
            case DecisionFigure.Circle c -> styled(new Circle(c.cx(), c.cy(), c.r()), c.fill(), c.stroke());
            case DecisionFigure.Polygon p -> styled(new Polygon(p.points()), p.fill(), p.stroke());
            case DecisionFigure.Polyline p -> {
                Polyline shape = new Polyline(p.points());
                shape.setStrokeLineCap(StrokeLineCap.ROUND);
                shape.setStrokeLineJoin(StrokeLineJoin.ROUND);
                yield styled(shape, null, p.stroke());
            }
            case DecisionFigure.Text t -> {
                Text text = new Text(t.x(), t.top(), t.text());
                text.setTextOrigin(VPos.TOP);
                text.setFont(Font.font(family, t.bold() ? FontWeight.BOLD : FontWeight.NORMAL,
                        t.italic() ? FontPosture.ITALIC : FontPosture.REGULAR, t.size()));
                text.setFill(paint(t.colour()));
                yield text;
            }
        };
    }

    private static Shape styled(Shape shape, String fill, DecisionFigure.Stroke stroke) {
        shape.setFill(fill == null ? Color.TRANSPARENT : paint(fill));
        shape.setStroke(stroke == null ? null : paint(stroke.colour()));
        if (stroke != null) {
            shape.setStrokeWidth(stroke.width());
            if (stroke.dashed()) {
                shape.getStrokeDashArray().setAll(3.0, 3.0);
            }
        }
        return shape;
    }

    private static Paint paint(String colour) {
        return colour == null ? Color.BLACK : Color.web(colour);
    }
}
