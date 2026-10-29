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
 * A {@link DecisionFigure} as Typst code: one {@code box} the figure's size, in points, and
 * inside it every mark {@code place}d at its coordinates, so the print pipeline draws the figure
 * natively, with the document's own text, and never through an embedded image. The code is a
 * markup-mode fragment (it begins with {@code #box}) that an AsciiDoc-to-Typst converter can
 * paste where the block stood, or a reader can compile on its own in a page.
 *
 * <p>Kept word for word in step with its twin in {@code ike-docs/koncept-asciidoc-extension}.
 */
public final class DecisionFigureTypst {

    private DecisionFigureTypst() {
    }

    /** The figure as a Typst markup fragment. One figure unit is one point. */
    public static String write(DecisionFigure figure) {
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(figure.role()).append('\n');
        sb.append("#box(width: ").append(pt(figure.width())).append(", height: ").append(pt(figure.height()))
                .append(", {\n");
        for (Mark mark : figure.marks()) {
            sb.append("  ");
            switch (mark) {
                case Rect r -> sb.append("place(dx: ").append(pt(r.x())).append(", dy: ").append(pt(r.y()))
                        .append(", rect(width: ").append(pt(r.w())).append(", height: ").append(pt(r.h()))
                        .append(", fill: ").append(fill(r.fill(), r.opacity()))
                        .append(", stroke: ").append(stroke(r.stroke(), false)).append("))");
                case Circle c -> sb.append("place(dx: ").append(pt(c.cx() - c.r())).append(", dy: ").append(pt(c.cy() - c.r()))
                        .append(", circle(radius: ").append(pt(c.r()))
                        .append(", fill: ").append(fill(c.fill(), 1))
                        .append(", stroke: ").append(stroke(c.stroke(), false)).append("))");
                case Polygon p -> {
                    sb.append("place(polygon(fill: ").append(fill(p.fill(), 1))
                            .append(", stroke: ").append(stroke(p.stroke(), false));
                    for (int i = 0; i + 1 < p.points().length; i += 2) {
                        sb.append(", (").append(pt(p.points()[i])).append(", ").append(pt(p.points()[i + 1])).append(')');
                    }
                    sb.append("))");
                }
                case Polyline p -> {
                    sb.append("place(curve(stroke: ").append(stroke(p.stroke(), true));
                    for (int i = 0; i + 1 < p.points().length; i += 2) {
                        sb.append(", curve.").append(i == 0 ? "move" : "line").append("((")
                                .append(pt(p.points()[i])).append(", ").append(pt(p.points()[i + 1])).append("))");
                    }
                    sb.append("))");
                }
                case Text t -> {
                    sb.append("place(dx: ").append(pt(t.x())).append(", dy: ").append(pt(t.top()))
                            .append(", text(size: ").append(pt(t.size()));
                    if (t.bold()) {
                        sb.append(", weight: \"bold\"");
                    }
                    if (t.italic()) {
                        sb.append(", style: \"italic\"");
                    }
                    sb.append(", fill: ").append(rgb(t.colour())).append(", \"").append(escape(t.text())).append("\"))");
                }
            }
            sb.append('\n');
        }
        return sb.append("})\n").toString();
    }

    private static String fill(String fill, double opacity) {
        if (fill == null) {
            return "none";
        }
        if (opacity < 1) {
            return rgb(fill) + ".transparentize(" + DecisionFigureText.length((1 - opacity) * 100) + "%)";
        }
        return rgb(fill);
    }

    private static String stroke(Stroke stroke, boolean round) {
        if (stroke == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder("(paint: ").append(rgb(stroke.colour()))
                .append(", thickness: ").append(pt(stroke.width()));
        if (stroke.dashed()) {
            sb.append(", dash: (array: (3pt, 3pt), phase: 0pt)");
        }
        if (round) {
            sb.append(", cap: \"round\", join: \"round\"");
        }
        return sb.append(')').toString();
    }

    private static String rgb(String colour) {
        return "rgb(\"" + colour + "\")";
    }

    private static String pt(double value) {
        return DecisionFigureText.length(value) + "pt";
    }

    /** Text safe inside a Typst string literal. */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
