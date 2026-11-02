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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A tornado diagram of a decision tree's one-way sensitivity as a {@link DecisionFigure}, drawn
 * as the live {@code TornadoChart} draws it: one bar per swept measure, longest first, on one
 * axis. The axis is the <em>net value</em> of the alternative the base chose, what the next best
 * alternative would cost less what the chosen one costs, so zero is break-even, a bar is long
 * only where its measure can change the answer, and every flip of the root's choice sits on the
 * zero line; when the root did not decide at the base, the axis is the expected cost at the root
 * instead. Round ticks with light gridlines cross the bars, a dashed line marks the base case and
 * is named at its foot, and a title above the axis says what it measures. A bar spans the axis from the measure's lower
 * end to its upper end, with each end's value printed in its own unit; it is solid when the block
 * stated that range and hollow when the sweep assumed it. Where the root's chosen alternative
 * flips inside the range, the bar changes colour beyond the flip and a tick carries the threshold.
 * A legend names the colours. The builder reads a {@link Tornado}, which is the sweep as numbers
 * and names, so a sweep over the stored tree and a sweep over a block's text draw the same bars.
 *
 * <p>Kept word for word in step with its twin in {@code ike-docs/koncept-asciidoc-extension}.
 */
public final class DecisionFigureTornado {

    private DecisionFigureTornado() {
    }

    /** What the root chose along a stretch of a bar, against what it chose at the base. */
    public enum Choice { BASE, OTHER, OPEN }

    /** A stretch of a bar between two cuts, by where each cut falls on the axis. */
    public record Segment(double from, double to, Choice choice) {
    }

    /**
     * A flip, by where it falls on the axis; {@code threshold} is the measure's value there,
     * printed with its unit, for a root flip.
     */
    public record Tick(double at, boolean atRoot, String threshold) {
    }

    /**
     * One measure's bar.
     *
     * @param name        how the measure is named
     * @param lowCaption  the measure's lower end, printed with its unit
     * @param highCaption the measure's upper end, printed with its unit
     * @param stated      whether the block stated the range, which draws the bar solid
     * @param atLow       where the axis stands with the measure at its lower end
     * @param atHigh      where the axis stands with the measure at its upper end
     * @param least       the least the axis reaches anywhere in the range
     * @param greatest    the greatest the axis reaches anywhere in the range
     * @param segments    the stretches between the root's flips, in rising order of the measure
     * @param ticks       every flip, at any decision
     */
    public record Bar(String name, String lowCaption, String highCaption, boolean stated, double atLow, double atHigh,
                      double least, double greatest, List<Segment> segments, List<Tick> ticks) {
    }

    /**
     * The sweep as the tornado reads it.
     *
     * @param title    what the axis measures, printed above it
     * @param net      whether the axis is net value, so that zero is break-even and is drawn
     * @param chosen   how the alternative the base chose is named, for the legend; {@code null} when the base did not decide
     * @param base     where the axis stands with every measure at its base
     * @param least    the least the axis reaches over every bar, the base included
     * @param greatest the greatest the axis reaches over every bar, the base included
     * @param bars     the bars, longest first, each with some length
     * @param notes    sentences for the footnote after the standard ones: what does not move the answer, what was not swept
     */
    public record Tornado(String title, boolean net, String chosen, double base, double least, double greatest,
                          List<Bar> bars, List<String> notes) {
    }

    /** The surface's base font size, from which every other size follows as it does on the live chart. */
    public static final double BASE = 12;
    private static final double CAPTION_ROOM = 64;
    private static final String GRID = "#e4e4e4";

    /** The footnote's first sentences, which every tornado carries. */
    public static final String FOOTNOTE = "One value at a time, the rest at base. A solid bar is the interval the block"
            + " states; a hollow bar an assumed one, half to double, a share no higher than one.";

    /** How an amount on the axis is printed. */
    public interface Money {
        String print(double amount);
    }

    /**
     * Builds the figure.
     *
     * @param tornado the sweep
     * @param width   the figure's width, which the bars share after the names and the captions
     * @param money   how the axis prints an amount
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
        // on a net-value axis zero is always in view: it is what every bar is read against
        double least = tornado.net() ? Math.min(0, tornado.least()) : tornado.least();
        double greatest = tornado.net() ? Math.max(0, tornado.greatest()) : tornado.greatest();
        double span = greatest - least;
        // the rooms beside the bars grow until every caption and threshold fits inside the figure
        double leftRoom = CAPTION_ROOM;
        double rightRoom = CAPTION_ROOM;
        double barLeft = names + 10 + leftRoom;
        double barWidth = Math.max(40, width - barLeft - rightRoom - 4);
        for (int pass = 0; pass < 3; pass++) {
            double left0 = barLeft;
            double width0 = barWidth;
            Axis trial = at -> span <= 0 ? left0 + width0 / 2 : left0 + (at - least) / span * width0;
            double overLeft = 0;
            double overRight = 0;
            for (Bar bar : tornado.bars()) {
                Ends ends = ends(bar, trial, captionSize);
                overLeft = Math.max(overLeft, (barLeft - leftRoom) - ends.left());
                overRight = Math.max(overRight, ends.right() - (barLeft + barWidth + rightRoom));
            }
            if (overLeft <= 0 && overRight <= 0) {
                break;
            }
            leftRoom += Math.max(0, overLeft);
            rightRoom += Math.max(0, overRight);
            barLeft = names + 10 + leftRoom;
            barWidth = Math.max(40, width - barLeft - rightRoom - 4);
        }
        double finalLeft = barLeft;
        double finalWidth = barWidth;
        Axis axis = at -> span <= 0 ? finalLeft + finalWidth / 2 : finalLeft + (at - least) / span * finalWidth;

        // the title: what the axis measures
        double y = 0;
        double axisLine = DecisionFigureText.lineHeight(axisSize);
        for (String line : DecisionFigureText.wrap(tornado.title(), axisSize, barWidth + CAPTION_ROOM)) {
            marks.add(new Text(barLeft, y, line, axisSize, false, true, DecisionFigureText.AXIS, "axis-title"));
            y += axisLine;
        }

        // the axis: round ticks above the bars, each labelled where it has room
        List<Double> ticks = ticks(least, greatest, barWidth);
        double lastEnd = Double.NEGATIVE_INFINITY;
        for (double tick : ticks) {
            String label = money.print(tick);
            double w = DecisionFigureText.width(label, axisSize, false);
            double lx = axis.x(tick) - w / 2;
            if (lx < lastEnd + 6) {
                continue;
            }
            marks.add(new Text(lx, y, label, axisSize, false, false, DecisionFigureText.AXIS, "axis"));
            lastEnd = lx + w;
        }
        y += lineHeight;
        double baseX = axis.x(tornado.base());

        double barsTop = y;
        double barsBottom = y + tornado.bars().size() * rowHeight;
        for (double tick : ticks) {
            double tx = axis.x(tick);
            boolean zero = tornado.net() && tick == 0;
            marks.add(new Polyline(new double[] {tx, barsTop, tx, barsBottom},
                    Stroke.solid(zero ? DecisionFigureText.AXIS : GRID, 1), zero ? "grid zero" : "grid"));
        }
        marks.add(new Polyline(new double[] {baseX, barsTop, baseX, barsBottom},
                Stroke.dashed(DecisionFigureText.AXIS, 1), "base-line"));

        boolean anyOther = false;
        boolean anyOpen = false;
        boolean anyHollow = false;
        boolean anyFlip = false;
        for (Bar bar : tornado.bars()) {
            String name = DecisionFigureText.fit(bar.name(), nameSize, false, names);
            marks.add(new Text(0, y + (rowHeight - lineHeight) / 2, name, nameSize, false, false, DecisionFigureText.INK,
                    "bar-name"));
            double barTop = y + 5;
            double barHeight = rowHeight - 10;
            anyHollow |= !bar.stated();
            for (Segment segment : bar.segments()) {
                double x0 = axis.x(Math.min(segment.from(), segment.to()));
                double x1 = axis.x(Math.max(segment.from(), segment.to()));
                String colour = switch (segment.choice()) {
                    case BASE -> DecisionFigureText.CHOSEN;
                    case OTHER -> DecisionFigureText.GAP;
                    case OPEN -> DecisionFigureText.OPEN;
                };
                anyOther |= segment.choice() == Choice.OTHER;
                anyOpen |= segment.choice() == Choice.OPEN;
                String role = "bar " + (bar.stated() ? "stated" : "assumed") + " " + segment.choice().name().toLowerCase();
                if (bar.stated()) {
                    marks.add(new Rect(x0, barTop, Math.max(1, x1 - x0), barHeight, colour, 0.85, null, role));
                } else {
                    marks.add(new Rect(x0, barTop, Math.max(1, x1 - x0), barHeight, null, 1, Stroke.solid(colour, 1.3), role));
                }
            }
            for (Tick tick : bar.ticks()) {
                double tx = axis.x(tick.at());
                anyFlip |= tick.atRoot();
                marks.add(new Polyline(new double[] {tx, barTop - 3, tx, barTop + barHeight + 3},
                        Stroke.solid(tick.atRoot() ? DecisionFigureText.HOT : DecisionFigureText.OPEN, tick.atRoot() ? 2 : 1),
                        tick.atRoot() ? "tick root" : "tick"));
            }
            // the measure's own values at the bar's ends, and beyond them each root flip's threshold
            double captionTop = barTop + (barHeight - lineHeight) / 2 + 1;
            for (Placed placed : placed(bar, axis, captionSize)) {
                marks.add(new Text(placed.x(), captionTop, placed.text(), captionSize, placed.threshold(), false,
                        placed.threshold() ? DecisionFigureText.HOT : DecisionFigureText.CAPTION,
                        placed.threshold() ? "threshold" : "caption"));
            }
            y += rowHeight;
        }

        // the base, named at the foot of its line
        String baseText = "base " + money.print(tornado.base());
        double baseWidth = DecisionFigureText.width(baseText, axisSize, true);
        double baseLabelX = Math.max(0, Math.min(baseX - baseWidth / 2, width - baseWidth));
        marks.add(new Text(baseLabelX, y + 2, baseText, axisSize, true, false, DecisionFigureText.AXIS, "axis base"));
        y += lineHeight;

        // the legend: what the colours and the hollow bars say
        y += 4;
        List<Mark> legend = new ArrayList<>();
        double lx = 0;
        double swatchW = 14;
        double swatchH = 8;
        List<Object[]> items = new ArrayList<>();
        if (tornado.net() || tornado.chosen() != null) {
            items.add(new Object[] {DecisionFigureText.CHOSEN, true,
                    tornado.chosen() == null ? "the base choice holds" : "“" + tornado.chosen() + "” still best"});
        }
        if (anyOther) {
            items.add(new Object[] {DecisionFigureText.GAP, true, "another alternative wins"});
        }
        if (anyOpen) {
            items.add(new Object[] {DecisionFigureText.OPEN, true, "choice left open"});
        }
        if (anyHollow) {
            items.add(new Object[] {DecisionFigureText.INK, false, "hollow: range assumed"});
        }
        if (anyFlip) {
            items.add(new Object[] {DecisionFigureText.HOT, null, "flip of the choice"});
        }
        for (Object[] item : items) {
            String text = (String) item[2];
            double w = swatchW + 5 + DecisionFigureText.width(text, captionSize, false);
            if (lx > 0 && lx + w > width - 4) {
                lx = 0;
                y += lineHeight;
            }
            double swatchTop = y + (lineHeight - swatchH) / 2;
            if (item[1] == null) {
                double tx = lx + swatchW / 2;
                legend.add(new Polyline(new double[] {tx, swatchTop - 2, tx, swatchTop + swatchH + 2},
                        Stroke.solid((String) item[0], 2), "legend-swatch"));
            } else if ((Boolean) item[1]) {
                legend.add(new Rect(lx, swatchTop, swatchW, swatchH, (String) item[0], 0.85, null, "legend-swatch"));
            } else {
                legend.add(new Rect(lx, swatchTop, swatchW, swatchH, null, 1, Stroke.solid((String) item[0], 1.3), "legend-swatch"));
            }
            legend.add(new Text(lx + swatchW + 5, y + (lineHeight - DecisionFigureText.lineHeight(captionSize)) / 2, text,
                    captionSize, false, false, DecisionFigureText.CAPTION, "legend"));
            lx += w + 16;
        }
        marks.addAll(legend);
        y += lineHeight;

        StringBuilder footnote = new StringBuilder(FOOTNOTE);
        for (String note : tornado.notes()) {
            footnote.append(' ').append(note);
        }
        double footnoteSize = BASE * 0.78;
        double footnoteLine = DecisionFigureText.lineHeight(footnoteSize);
        double top = y + 4;
        for (String line : DecisionFigureText.wrap(footnote.toString(), footnoteSize, width - 4)) {
            marks.add(new Text(0, top, line, footnoteSize, false, false, DecisionFigureText.CAPTION, "footnote"));
            top += footnoteLine;
        }
        return new DecisionFigure(width, top + 4, "decision-tornado", marks);
    }

    /** A caption or a threshold label, where it goes. */
    private record Placed(double x, String text, boolean threshold) {
    }

    /** The leftmost and rightmost edge a bar's captions and threshold labels reach. */
    private record Ends(double left, double right) {
    }

    /**
     * The captions at the bar's ends, low where the axis is least and high where it is greatest,
     * and beyond the caption on the bar's wider side each root flip's threshold, clear of the bar
     * itself.
     */
    private static List<Placed> placed(Bar bar, Axis axis, double captionSize) {
        boolean rising = bar.atHigh() >= bar.atLow();
        String leftCaption = rising ? bar.lowCaption() : bar.highCaption();
        String rightCaption = rising ? bar.highCaption() : bar.lowCaption();
        double left = axis.x(bar.least());
        double right = axis.x(bar.greatest());
        double leftWidth = DecisionFigureText.width(leftCaption, captionSize, false);
        double rightWidth = DecisionFigureText.width(rightCaption, captionSize, false);
        List<Placed> placed = new ArrayList<>();
        placed.add(new Placed(left - 4 - leftWidth, leftCaption, false));
        placed.add(new Placed(right + 4, rightCaption, false));
        double afterRight = right + 4 + rightWidth + 8;
        double beforeLeft = left - 4 - leftWidth - 8;
        for (Tick tick : bar.ticks()) {
            if (!tick.atRoot()) {
                continue;
            }
            String label = "flips at " + tick.threshold();
            double tx = axis.x(tick.at());
            double w = DecisionFigureText.width(label, captionSize, true);
            boolean toTheRight = right - tx >= tx - left;
            placed.add(new Placed(toTheRight ? afterRight : beforeLeft - w, label, true));
            if (toTheRight) {
                afterRight += w + 8;
            } else {
                beforeLeft -= w + 8;
            }
        }
        return placed;
    }

    private static Ends ends(Bar bar, Axis axis, double captionSize) {
        double left = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        for (Placed placed : placed(bar, axis, captionSize)) {
            left = Math.min(left, placed.x());
            right = Math.max(right, placed.x() + DecisionFigureText.width(placed.text(), captionSize, placed.threshold()));
        }
        return new Ends(left, right);
    }

    /**
     * Round values for the axis: a step of one, two or five times a power of ten, the one nearest
     * to what puts the labels about ninety units apart, from the first multiple at or above the
     * least to the last at or below the greatest.
     */
    public static List<Double> ticks(double least, double greatest, double barWidth) {
        double span = greatest - least;
        if (!(span > 0)) {
            return List.of(least);
        }
        int wanted = Math.max(2, (int) Math.floor(barWidth / 90));
        double raw = span / wanted;
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double step = magnitude;
        for (double candidate : new double[] {magnitude, 2 * magnitude, 5 * magnitude, 10 * magnitude}) {
            if (Math.abs(Math.log(candidate / raw)) < Math.abs(Math.log(step / raw))) {
                step = candidate;
            }
        }
        List<Double> ticks = new ArrayList<>();
        long first = (long) Math.ceil(least / step - 1e-9);
        long last = (long) Math.floor(greatest / step + 1e-9);
        for (long i = first; i <= last; i++) {
            // multiplied as decimals, so three steps of 0.2 read 0.6 and never 0.6000000000000001
            ticks.add(i == 0 ? 0.0 : BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(i)).doubleValue());
        }
        return ticks;
    }

    private interface Axis {
        double x(double at);
    }
}
