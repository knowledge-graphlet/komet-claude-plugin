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

import java.util.Collections;
import java.util.List;

/**
 * A decision-tree figure as marks on a page: rectangles, circles, polygons, polylines and text,
 * each at a place in the figure's own units, each with its paint and a role naming what it stands
 * for. A figure is the whole of what a writer needs: {@link DecisionFigureSvg} and
 * {@link DecisionFigureTypst} translate the marks and decide nothing, so the two media draw the
 * same picture, and a test reads the roles back from the SVG.
 *
 * <p>This class, the text metrics, the writers and the tornado builder are kept word for word in
 * step with their twins in {@code ike-docs/koncept-asciidoc-extension}, where the documentation
 * build draws the same figures store-free; only the package line and the adapters differ.
 *
 * @param width  the figure's width, in its units
 * @param height the figure's height
 * @param role   what the figure is: {@code decision-tree} or {@code decision-tornado}
 * @param marks  the marks, in drawing order: what comes later is drawn over what comes before
 */
public record DecisionFigure(double width, double height, String role, List<Mark> marks) {

    public DecisionFigure {
        marks = Collections.unmodifiableList(List.copyOf(marks));
    }

    /** How a mark's outline is drawn; {@code null} for no outline. */
    public record Stroke(String colour, double width, boolean dashed) {
        public static Stroke solid(String colour, double width) {
            return new Stroke(colour, width, false);
        }

        public static Stroke dashed(String colour, double width) {
            return new Stroke(colour, width, true);
        }
    }

    /** One mark of the figure. Every colour is a CSS hex string; a {@code null} fill is none. */
    public sealed interface Mark permits Rect, Circle, Polygon, Polyline, Text {
        /** What the mark stands for: the SVG class, which a test reads back. */
        String role();
    }

    /** An axis-aligned rectangle from its top-left corner. */
    public record Rect(double x, double y, double w, double h, String fill, double opacity, Stroke stroke, String role)
            implements Mark {
    }

    /** A circle about its centre. */
    public record Circle(double cx, double cy, double r, String fill, Stroke stroke, String role) implements Mark {
    }

    /** A closed polygon through its points, as {@code x0, y0, x1, y1, …}. */
    public record Polygon(double[] points, String fill, Stroke stroke, String role) implements Mark {
    }

    /** An open polyline through its points, as {@code x0, y0, x1, y1, …}, round-capped and round-joined. */
    public record Polyline(double[] points, Stroke stroke, String role) implements Mark {
    }

    /**
     * A run of text whose top-left corner is at {@code (x, top)}: the writers set the baseline
     * from the size, so a label sits where the builder put its box.
     */
    public record Text(double x, double top, String text, double size, boolean bold, boolean italic, String colour,
                       String role) implements Mark {
    }
}
