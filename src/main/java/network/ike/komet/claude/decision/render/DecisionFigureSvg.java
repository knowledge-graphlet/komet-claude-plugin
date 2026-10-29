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
package network.ike.komet.claude.decision.render;

import network.ike.komet.claude.decision.render.DecisionFigure.Circle;
import network.ike.komet.claude.decision.render.DecisionFigure.Mark;
import network.ike.komet.claude.decision.render.DecisionFigure.Polygon;
import network.ike.komet.claude.decision.render.DecisionFigure.Polyline;
import network.ike.komet.claude.decision.render.DecisionFigure.Rect;
import network.ike.komet.claude.decision.render.DecisionFigure.Stroke;
import network.ike.komet.claude.decision.render.DecisionFigure.Text;

/**
 * A {@link DecisionFigure} as one self-contained SVG document: a {@code viewBox} the figure's
 * size, every mark an element whose {@code class} is the mark's role, text in the sans face the
 * width estimate is of. Nothing in the document refers to anything outside it, so the SVG is at
 * home in a page, an image tag, a data URI or a file.
 *
 * <p>Kept word for word in step with its twin in {@code ike-docs/koncept-asciidoc-extension}.
 */
public final class DecisionFigureSvg {

    private DecisionFigureSvg() {
    }

    /** The figure as an SVG document, without an XML declaration. */
    public static String write(DecisionFigure figure) {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"").append(escape(figure.role()))
                .append("\" width=\"").append(n(figure.width())).append("\" height=\"").append(n(figure.height()))
                .append("\" viewBox=\"0 0 ").append(n(figure.width())).append(' ').append(n(figure.height()))
                .append("\" font-family=\"").append(DecisionFigureText.FAMILY).append("\">\n");
        for (Mark mark : figure.marks()) {
            sb.append("  ");
            switch (mark) {
                case Rect r -> {
                    sb.append("<rect class=\"").append(escape(r.role())).append("\" x=\"").append(n(r.x()))
                            .append("\" y=\"").append(n(r.y())).append("\" width=\"").append(n(r.w()))
                            .append("\" height=\"").append(n(r.h())).append('"');
                    fill(sb, r.fill());
                    if (r.opacity() < 1) {
                        sb.append(" fill-opacity=\"").append(n(r.opacity())).append('"');
                    }
                    stroke(sb, r.stroke());
                    sb.append("/>");
                }
                case Circle c -> {
                    sb.append("<circle class=\"").append(escape(c.role())).append("\" cx=\"").append(n(c.cx()))
                            .append("\" cy=\"").append(n(c.cy())).append("\" r=\"").append(n(c.r())).append('"');
                    fill(sb, c.fill());
                    stroke(sb, c.stroke());
                    sb.append("/>");
                }
                case Polygon p -> {
                    sb.append("<polygon class=\"").append(escape(p.role())).append("\" points=\"").append(points(p.points()))
                            .append('"');
                    fill(sb, p.fill());
                    stroke(sb, p.stroke());
                    sb.append("/>");
                }
                case Polyline p -> {
                    sb.append("<polyline class=\"").append(escape(p.role())).append("\" points=\"").append(points(p.points()))
                            .append("\" fill=\"none\" stroke-linecap=\"round\" stroke-linejoin=\"round\"");
                    stroke(sb, p.stroke());
                    sb.append("/>");
                }
                case Text t -> {
                    sb.append("<text class=\"").append(escape(t.role())).append("\" x=\"").append(n(t.x()))
                            .append("\" y=\"").append(n(t.top() + DecisionFigureText.ascent(t.size())))
                            .append("\" font-size=\"").append(n(t.size())).append('"');
                    if (t.bold()) {
                        sb.append(" font-weight=\"bold\"");
                    }
                    if (t.italic()) {
                        sb.append(" font-style=\"italic\"");
                    }
                    sb.append(" fill=\"").append(t.colour()).append("\">").append(escape(t.text())).append("</text>");
                }
            }
            sb.append('\n');
        }
        return sb.append("</svg>\n").toString();
    }

    private static void fill(StringBuilder sb, String fill) {
        sb.append(" fill=\"").append(fill == null ? "none" : fill).append('"');
    }

    private static void stroke(StringBuilder sb, Stroke stroke) {
        if (stroke == null) {
            return;
        }
        sb.append(" stroke=\"").append(stroke.colour()).append("\" stroke-width=\"").append(n(stroke.width())).append('"');
        if (stroke.dashed()) {
            sb.append(" stroke-dasharray=\"3 3\"");
        }
    }

    private static String points(double[] points) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < points.length; i += 2) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(n(points[i])).append(',').append(n(points[i + 1]));
        }
        return sb.toString();
    }

    private static String n(double value) {
        return DecisionFigureText.length(value);
    }

    /** Text safe in an attribute or as content. */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
