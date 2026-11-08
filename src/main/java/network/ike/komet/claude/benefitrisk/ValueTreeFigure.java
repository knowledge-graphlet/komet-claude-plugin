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
package network.ike.komet.claude.benefitrisk;

import network.ike.komet.claude.benefitrisk.ValueTree.Kind;
import network.ike.komet.claude.benefitrisk.ValueTree.Part;
import network.ike.komet.claude.stamp.DecisionFigure;
import network.ike.komet.claude.stamp.DecisionFigure.Mark;
import network.ike.komet.claude.stamp.DecisionFigure.Polyline;
import network.ike.komet.claude.stamp.DecisionFigure.Rect;
import network.ike.komet.claude.stamp.DecisionFigure.Stroke;
import network.ike.komet.claude.stamp.DecisionFigure.Text;
import network.ike.komet.claude.stamp.DecisionFigureText;

import java.util.ArrayList;
import java.util.List;

/**
 * A value tree as marks, for the page: the same shape the surface draws, left to right, each level
 * a column and each parent centered on its children, the names as plain text since a badge's
 * identicon means nothing on paper. What a part says beyond its name stands under the name.
 */
public final class ValueTreeFigure {

    /** The gap between two columns, which the elbows of the connecting lines stand in. */
    private static final double COLUMN_GAP = 22;
    private static final double ROW_GAP = 5;
    private static final double INSET = 4;
    /** How much wider the last column is than the others, where the criteria's longer names stand. */
    private static final double LAST_COLUMN = 1.7;

    private ValueTreeFigure() {
    }

    /** A part placed on the figure. */
    private static final class Box {
        final Part part;
        final int depth;
        final List<Box> children = new ArrayList<>();
        List<String> name = List.of();
        double height;
        double center;

        Box(Part part, int depth) {
            this.part = part;
            this.depth = depth;
            part.parts().forEach(child -> children.add(new Box(child, depth + 1)));
        }
    }

    /**
     * Builds the figure.
     *
     * @param goal  the tree's goal
     * @param width the figure's width
     * @param size  the size of the names
     * @return the figure
     */
    public static DecisionFigure build(Part goal, double width, double size) {
        Box root = new Box(goal, 0);
        int levels = depth(root) + 1;
        double unit = (width - (levels - 1) * COLUMN_GAP) / (levels - 1 + LAST_COLUMN);
        double line = DecisionFigureText.lineHeight(size);
        double tagLine = DecisionFigureText.lineHeight(size * 0.85);
        measure(root, levels, unit, size, line, tagLine);
        double height = place(root, 0);
        List<Mark> lines = new ArrayList<>();
        List<Mark> boxes = new ArrayList<>();
        draw(root, levels, unit, size, line, lines, boxes);
        lines.addAll(boxes);
        return new DecisionFigure(width, height + 1, "value-tree", lines);
    }

    private static int depth(Box box) {
        int deepest = box.depth;
        for (Box child : box.children) {
            deepest = Math.max(deepest, depth(child));
        }
        return deepest;
    }

    private static double columnWidth(int depth, int levels, double unit) {
        return depth == levels - 1 ? unit * LAST_COLUMN : unit;
    }

    private static double columnLeft(int depth, double unit) {
        return depth * (unit + COLUMN_GAP);
    }

    private static void measure(Box box, int levels, double unit, double size, double line, double tagLine) {
        box.name = DecisionFigureText.wrap(box.part.name(), size, columnWidth(box.depth, levels, unit) - 2 * INSET);
        box.height = box.name.size() * line + (box.part.reading().isEmpty() ? 0 : tagLine) + 2 * INSET;
        box.children.forEach(child -> measure(child, levels, unit, size, line, tagLine));
    }

    /** Places a box and all beneath it from a top edge; a parent's center falls midway over its children. */
    private static double place(Box box, double top) {
        if (box.children.isEmpty()) {
            box.center = top + box.height / 2;
            return top + box.height;
        }
        double y = top;
        for (int i = 0; i < box.children.size(); i++) {
            y = place(box.children.get(i), y + (i == 0 ? 0 : ROW_GAP));
        }
        box.center = (box.children.getFirst().center + box.children.getLast().center) / 2;
        // a parent taller than what stands beneath it would reach above its own top edge, into the box before it
        double over = top - (box.center - box.height / 2);
        if (over > 0) {
            shift(box, over);
            y += over;
        }
        return Math.max(y, box.center + box.height / 2);
    }

    private static void shift(Box box, double by) {
        box.center += by;
        box.children.forEach(child -> shift(child, by));
    }

    private static void draw(Box box, int levels, double unit, double size, double line, List<Mark> lines, List<Mark> boxes) {
        double left = columnLeft(box.depth, unit);
        double wide = columnWidth(box.depth, levels, unit);
        double top = box.center - box.height / 2;
        boolean gate = box.part.kind() == Kind.GATE;
        boxes.add(new Rect(left, top, wide, box.height, gate ? DecisionFigureText.CHANCE_FILL : DecisionFigureText.DECISION_FILL, 1, null, "value-tree-part"));
        double y = top + INSET;
        for (String name : box.name) {
            boxes.add(new Text(left + INSET, y, name, size, box.part.kind() == Kind.GOAL, false, DecisionFigureText.INK, "value-tree-name"));
            y += line;
        }
        if (!box.part.reading().isEmpty()) {
            boxes.add(new Text(left + INSET, y, box.part.reading(), size * 0.85, false, false,
                    gate ? DecisionFigureText.GAP : DecisionFigureText.CAPTION, "value-tree-reading"));
        }
        double out = left + wide;
        double elbow = out + COLUMN_GAP / 2;
        for (Box child : box.children) {
            lines.add(new Polyline(new double[]{out, box.center, elbow, box.center, elbow, child.center, columnLeft(child.depth, unit), child.center},
                    Stroke.solid(DecisionFigureText.AXIS, 0.8), "value-tree-edge"));
            draw(child, levels, unit, size, line, lines, boxes);
        }
    }
}
