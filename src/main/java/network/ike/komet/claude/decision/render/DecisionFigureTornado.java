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

import network.ike.komet.claude.decision.render.DecisionFigure.Mark;
import network.ike.komet.claude.decision.render.DecisionFigure.Polyline;
import network.ike.komet.claude.decision.render.DecisionFigure.Rect;
import network.ike.komet.claude.decision.render.DecisionFigure.Stroke;
import network.ike.komet.claude.decision.render.DecisionFigure.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * A tornado diagram of a decision tree's one-way sensitivity as a {@link DecisionFigure}, drawn
 * as the live {@code TornadoChart} draws it: one bar per swept measure, longest first, on one
 * axis, which is the expected cost at the root, with a dashed line at the base case. A bar spans
 * the root's cost from the measure's lower end to its upper end; it is solid when the block
 * stated that range and hollow when the sweep assumed it. Where the root's chosen alternative
 * flips inside the range, the bar changes colour beyond the flip and a tick carries the
 * threshold. The builder reads a {@link Tornado}, which is the sweep as numbers and names, so a
 * sweep over the stored tree and a sweep over a block's text draw the same bars.
 *
 * <p>Kept word for word in step with its twin in {@code ike-docs/koncept-asciidoc-extension}.
 */
public final class DecisionFigureTornado {

    private DecisionFigureTornado() {
    }

    /** What the root chose along a stretch of a bar, against what it chose at the base. */
    public enum Choice { BASE, OTHER, OPEN }

    /** A stretch of a bar between two cuts, by the root's cost at each cut. */
    public record Segment(double rootFrom, double rootTo, Choice choice) {
    }

    /** A flip, by the root's cost where it happens; {@code threshold} is the measure's value there, for a root flip. */
    public record Tick(double root, boolean atRoot, String threshold) {
    }

    /**
     * One measure's bar.
     *
     * @param name         how the measure is named
     * @param low          the measure's lower end
     * @param high         the measure's upper end
     * @param stated       whether the block stated the range, which draws the bar solid
     * @param rootAtLow    the root's cost at the lower end
     * @param rootAtHigh   the root's cost at the upper end
     * @param leastRoot    the least root cost anywhere in the range
     * @param greatestRoot the greatest root cost anywhere in the range
     * @param segments     the stretches between the root's flips, in rising order of the measure
     * @param ticks        every flip, at any decision
     */
    public record Bar(String name, double low, double high, boolean stated, double rootAtLow, double rootAtHigh,
                      double leastRoot, double greatestRoot, List<Segment> segments, List<Tick> ticks) {
    }

    /**
     * The sweep as the tornado reads it.
     *
     * @param baseRoot the root's cost with every measure at its base
     * @param least    the least root cost over every bar, the base included
     * @param greatest the greatest root cost over every bar, the base included
     * @param bars     the bars, longest first, each with some length
     * @param notes    sentences for the footnote after the standard ones: what does not move the cost, what was not swept
     */
    public record Tornado(double baseRoot, double least, double greatest, List<Bar> bars, List<String> notes) {
    }

    /** The surface's base font size, from which every other size follows as it does on the live chart. */
    public static final double BASE = 12;
    private static final double CAPTION_ROOM = 64;

    /** The footnote's first sentences, which every tornado carries. */
    public static final String FOOTNOTE = "One value at a time, the rest at base. A solid bar is the interval the block"
            + " states; a hollow bar an assumed one, half to double, or zero to one for a share.";

    /** How the root's cost is printed on the axis. */
    public interface Money {
        String print(double root);
    }

    /**
     * Builds the figure.
     *
     * @param tornado the sweep
     * @param width   the figure's width, which the bars share after the names and the captions
     * @param money   how the axis prints a cost
     */
    public static DecisionFigure build(Tornado tornado, double width, Money money) {
        double lineHeight = DecisionFigureText.lineHeight(BASE);
        double rowHeight = lineHeight + 10;
        double nameSize = BASE * 0.85;
        double captionSize = BASE * 0.72;
        double axisSize = BASE * 0.78;
        List<Mark> marks = new ArrayList<>();

        double names = 0;
        for (Bar bar : tornado.bars()) {
            names = Math.max(names, DecisionFigureText.width(bar.name(), nameSize, false));
        }
        names = Math.min(names, 220);
        double barLeft = names + 10 + CAPTION_ROOM;
        double barWidth = Math.max(40, width - barLeft - CAPTION_ROOM - 4);
        double span = tornado.greatest() - tornado.least();
        Axis axis = root -> span <= 0 ? barLeft + barWidth / 2 : barLeft + (root - tornado.least()) / span * barWidth;

        // the axis: least and greatest at the ends, the base between them
        String lowText = money.print(tornado.least());
        String highText = money.print(tornado.greatest());
        String baseText = "base " + money.print(tornado.baseRoot());
        double lowWidth = DecisionFigureText.width(lowText, axisSize, false);
        double highWidth = DecisionFigureText.width(highText, axisSize, false);
        double baseWidth = DecisionFigureText.width(baseText, axisSize, true);
        marks.add(new Text(barLeft, 0, lowText, axisSize, false, false, DecisionFigureText.AXIS, "axis"));
        marks.add(new Text(barLeft + barWidth - highWidth, 0, highText, axisSize, false, false, DecisionFigureText.AXIS, "axis"));
        double baseX = axis.x(tornado.baseRoot());
        double baseLabelX = Math.max(barLeft + lowWidth + 6,
                Math.min(baseX - baseWidth / 2, barLeft + barWidth - highWidth - baseWidth - 6));
        marks.add(new Text(baseLabelX, 0, baseText, axisSize, true, false, DecisionFigureText.AXIS, "axis base"));
        double y = lineHeight;
        marks.add(new Polyline(new double[] {baseX, y, baseX, y + tornado.bars().size() * rowHeight},
                Stroke.dashed(DecisionFigureText.AXIS, 1), "base-line"));

        for (Bar bar : tornado.bars()) {
            String name = DecisionFigureText.fit(bar.name(), nameSize, false, names);
            marks.add(new Text(0, y + (rowHeight - lineHeight) / 2, name, nameSize, false, false, DecisionFigureText.INK,
                    "bar-name"));
            double barTop = y + 5;
            double barHeight = rowHeight - 10;
            for (Segment segment : bar.segments()) {
                double x0 = axis.x(Math.min(segment.rootFrom(), segment.rootTo()));
                double x1 = axis.x(Math.max(segment.rootFrom(), segment.rootTo()));
                String colour = switch (segment.choice()) {
                    case BASE -> DecisionFigureText.CHOSEN;
                    case OTHER -> DecisionFigureText.GAP;
                    case OPEN -> DecisionFigureText.OPEN;
                };
                String role = "bar " + (bar.stated() ? "stated" : "assumed") + " " + segment.choice().name().toLowerCase();
                if (bar.stated()) {
                    marks.add(new Rect(x0, barTop, Math.max(1, x1 - x0), barHeight, colour, 0.85, null, role));
                } else {
                    marks.add(new Rect(x0, barTop, Math.max(1, x1 - x0), barHeight, null, 1, Stroke.solid(colour, 1.3), role));
                }
            }
            for (Tick tick : bar.ticks()) {
                double tx = axis.x(tick.root());
                marks.add(new Polyline(new double[] {tx, barTop - 3, tx, barTop + barHeight + 3},
                        Stroke.solid(tick.atRoot() ? DecisionFigureText.HOT : DecisionFigureText.OPEN, tick.atRoot() ? 2 : 1),
                        tick.atRoot() ? "tick root" : "tick"));
            }
            // the measure's own values at the bar's ends: low where the root is least, high where it is greatest
            boolean rising = bar.rootAtHigh() >= bar.rootAtLow();
            String leftCaption = DecisionFigureText.value(rising ? bar.low() : bar.high());
            String rightCaption = DecisionFigureText.value(rising ? bar.high() : bar.low());
            double left = axis.x(bar.leastRoot());
            double right = axis.x(bar.greatestRoot());
            double leftWidth = DecisionFigureText.width(leftCaption, captionSize, false);
            double captionLeft = left - 4 - leftWidth;
            double captionRight = right + 4;
            double captionTop = barTop + (barHeight - lineHeight) / 2 + 1;
            for (Tick tick : bar.ticks()) {
                if (!tick.atRoot()) {
                    continue;
                }
                String label = "flips at " + tick.threshold();
                double tx = axis.x(tick.root());
                double w = DecisionFigureText.width(label, captionSize, true);
                // the label goes on the wider side of the tick, and a caption it would cover moves outward
                boolean toTheRight = right - tx >= tx - left;
                double lx = toTheRight ? tx + 4 : tx - 4 - w;
                marks.add(new Text(lx, barTop + (barHeight - lineHeight) / 2 + 1, label, captionSize, true, false,
                        DecisionFigureText.HOT, "threshold"));
                if (toTheRight) {
                    captionRight = Math.max(captionRight, lx + w + 6);
                } else {
                    captionLeft = Math.min(captionLeft, lx - 6 - leftWidth);
                }
            }
            marks.add(new Text(captionLeft, captionTop, leftCaption, captionSize, false, false, DecisionFigureText.CAPTION,
                    "caption"));
            marks.add(new Text(captionRight, captionTop, rightCaption, captionSize, false, false, DecisionFigureText.CAPTION,
                    "caption"));
            y += rowHeight;
        }

        StringBuilder footnote = new StringBuilder(FOOTNOTE);
        for (String note : tornado.notes()) {
            footnote.append(' ').append(note);
        }
        double footnoteSize = BASE * 0.78;
        double footnoteLine = DecisionFigureText.lineHeight(footnoteSize);
        double top = y + 6;
        for (String line : DecisionFigureText.wrap(footnote.toString(), footnoteSize, width - 4)) {
            marks.add(new Text(0, top, line, footnoteSize, false, false, DecisionFigureText.CAPTION, "footnote"));
            top += footnoteLine;
        }
        return new DecisionFigure(width, top + 4, "decision-tornado", marks);
    }

    private interface Axis {
        double x(double root);
    }
}
