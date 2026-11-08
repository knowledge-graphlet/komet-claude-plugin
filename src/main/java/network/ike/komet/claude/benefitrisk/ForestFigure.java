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

import network.ike.komet.claude.benefitrisk.EffectsReading.Row;
import network.ike.komet.claude.benefitrisk.EffectsReading.Verdict;
import network.ike.komet.claude.benefitrisk.ValueTree.Kind;
import network.ike.komet.document.DecisionFigure;
import network.ike.komet.document.DecisionFigure.Mark;
import network.ike.komet.document.DecisionFigure.Polyline;
import network.ike.komet.document.DecisionFigure.Rect;
import network.ike.komet.document.DecisionFigure.Stroke;
import network.ike.komet.document.DecisionFigure.Text;
import network.ike.komet.document.DecisionFigureText;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A forest plot as marks: one row per estimate, a square at its value and a line across its
 * interval, on one axis with a reference marked. It holds nothing of its own. A row the data do not
 * separate draws a hollow square, and a row with nothing to draw says so.
 */
public final class ForestFigure {

    private static final double GROUP_GAP = 6;
    private static final double SQUARE = 8;
    private static final double PAD = 8;

    private ForestFigure() {
    }

    /**
     * One row of a plot.
     *
     * @param group   the heading it stands under, or empty for none
     * @param name    its name
     * @param central the value, or empty when there is nothing to draw
     * @param lower   its lower limit
     * @param upper   its upper limit
     * @param open    whether its square is hollow
     * @param words   the value in words, or what the row says in its place
     */
    public record Line(String group, String name, Optional<Double> central, double lower, double upper, boolean open, String words) {
    }

    /**
     * The scale of a plot.
     *
     * @param lowest    its left end
     * @param highest   its right end
     * @param step      the distance between ticks
     * @param reference the value the darker line stands at
     * @param header    the heading of the value column
     * @param below     what is printed beneath the left and right of the reference
     * @param tick      how a tick reads
     */
    public record Axis(double lowest, double highest, double step, double reference, String header, List<String> below,
                       java.util.function.DoubleFunction<String> tick) {
    }

    /**
     * Builds the plot of an effects table: gates are not effects, so they are not drawn.
     *
     * @param reading the effects table, read
     * @param width   the figure's width
     * @param size    the size of its row names
     * @return the figure
     */
    public static DecisionFigure build(EffectsReading reading, double width, double size) {
        List<Line> lines = new ArrayList<>();
        for (Row row : reading.rows()) {
            if (row.leaf().part().kind() == Kind.GATE) {
                continue;
            }
            String group = row.leaf().domain().map(d -> d.polarity().map(ForestFigure::capitalized).orElse(d.name())).orElse("");
            lines.add(row.effect()
                    .map(e -> new Line(group, row.leaf().part().name(), Optional.of(e.central()), e.lower(), e.upper(),
                            row.verdict() == Verdict.NOT_SEPARATED, e.words()))
                    .orElseGet(() -> new Line(group, row.leaf().part().name(), Optional.empty(), 0, 0, false, "Nothing to compute from")));
        }
        double reach = reach(lines);
        return build(lines, new Axis(-reach, reach, 1, 0, "Effect (95% CI)",
                List.of("← Favors " + reading.second().name(), "Favors " + reading.first().name() + " →"),
                tick -> tick == 0 ? "0" : EffectSize.signed(tick).replace(".00", "")), width, size);
    }

    /**
     * Builds a plot.
     *
     * @param lines its rows
     * @param axis  its scale
     * @param width the figure's width
     * @param size  the size of its row names
     * @return the figure
     */
    public static DecisionFigure build(List<Line> lines, Axis axis, double width, double size) {
        double textColumn = width * 0.21;
        double nameColumn = width * 0.33;
        double left = nameColumn + PAD;
        double right = width - textColumn - PAD;
        double line = DecisionFigureText.lineHeight(size);
        List<Mark> marks = new ArrayList<>();
        List<Mark> ink = new ArrayList<>();
        double y = 2;
        ink.add(new Text(right + PAD, y, axis.header(), size * 0.9, false, true, DecisionFigureText.CAPTION, "forest-header"));
        y += line + 4;
        double top = y;
        String shownGroup = "";
        for (Line row : lines) {
            if (!row.group().isEmpty() && !row.group().equals(shownGroup)) {
                y += shownGroup.isEmpty() ? 0 : GROUP_GAP;
                shownGroup = row.group();
                ink.add(new Text(0, y, row.group(), size * 0.9, true, false, DecisionFigureText.INK, "forest-domain"));
                y += line;
            }
            List<String> name = DecisionFigureText.wrap(row.name(), size, nameColumn - PAD);
            double height = Math.max(name.size(), 1) * line + 5;
            for (int i = 0; i < name.size(); i++) {
                ink.add(new Text(PAD, y + i * line, name.get(i), size, false, false, DecisionFigureText.INK, "forest-name"));
            }
            double mid = y + height / 2 - 2;
            if (row.central().isPresent()) {
                ink.add(new Polyline(new double[]{at(row.lower(), left, right, axis), mid, at(row.upper(), left, right, axis), mid},
                        Stroke.solid(DecisionFigureText.INK, 1.2), "forest-interval"));
                double x = at(row.central().get(), left, right, axis);
                ink.add(new Rect(x - SQUARE / 2, mid - SQUARE / 2, SQUARE, SQUARE, row.open() ? "#ffffff" : DecisionFigureText.CHOSEN, 1,
                        Stroke.solid(DecisionFigureText.CHOSEN, 1.2), row.open() ? "forest-point-open" : "forest-point"));
                ink.add(new Text(right + PAD, mid - line / 2, row.words(), size * 0.95, false, false, DecisionFigureText.INK, "forest-value"));
            } else {
                ink.add(new Text(left + PAD, mid - line / 2, row.words(), size * 0.9, false, true, DecisionFigureText.CAPTION, "forest-none"));
            }
            y += height;
        }
        double bottom = y + 2;
        // the grid first, so a row's marks stand over it
        for (double tick = axis.lowest(); tick <= axis.highest() + 1e-9; tick += axis.step()) {
            double x = at(tick, left, right, axis);
            boolean reference = Math.abs(tick - axis.reference()) < 1e-9;
            marks.add(new Polyline(new double[]{x, top, x, bottom},
                    reference ? Stroke.solid(DecisionFigureText.AXIS, 1) : Stroke.solid("#e4e4e4", 0.6), reference ? "forest-zero" : "forest-grid"));
            String label = axis.tick().apply(tick);
            marks.add(new Text(x - DecisionFigureText.width(label, size * 0.8, false) / 2, bottom + 3, label, size * 0.8, false, false,
                    DecisionFigureText.CAPTION, "forest-tick"));
        }
        marks.add(new Polyline(new double[]{left, bottom, right, bottom}, Stroke.solid(DecisionFigureText.AXIS, 1), "forest-axis"));
        marks.addAll(ink);
        double captions = bottom + 3 + line;
        double zero = at(axis.reference(), left, right, axis);
        if (axis.below().size() == 2) {
            String toLeft = axis.below().get(0);
            marks.add(new Text(zero - 6 - DecisionFigureText.width(toLeft, size * 0.85, false), captions, toLeft, size * 0.85, false, false,
                    DecisionFigureText.INK, "forest-favors"));
            marks.add(new Text(zero + 6, captions, axis.below().get(1), size * 0.85, false, false, DecisionFigureText.INK, "forest-favors"));
        } else if (axis.below().size() == 1) {
            marks.add(new Text(left, captions, axis.below().getFirst(), size * 0.85, false, false, DecisionFigureText.INK, "forest-favors"));
        }
        return new DecisionFigure(width, captions + line + 2, "forest-plot", marks);
    }

    /** The whole number the axis reaches on each side, enough for the widest interval. */
    private static double reach(List<Line> lines) {
        double widest = 1;
        for (Line line : lines) {
            if (line.central().isPresent()) {
                widest = Math.max(widest, Math.max(Math.abs(line.lower()), Math.abs(line.upper())));
            }
        }
        return Math.ceil(widest);
    }

    private static double at(double value, double left, double right, Axis axis) {
        return left + (value - axis.lowest()) / (axis.highest() - axis.lowest()) * (right - left);
    }

    private static String capitalized(String word) {
        return word.isEmpty() ? word : word.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + word.substring(1);
    }
}
