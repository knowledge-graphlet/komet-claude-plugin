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

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionTreeSensitivity;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Flip;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Swing;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Unswept;
import network.ike.komet.claude.decision.render.DecisionFigureText;
import network.ike.komet.claude.decision.render.DecisionFigureTornado;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A tornado diagram of a decision tree's one-way sensitivity, drawn beneath the tree: one bar
 * per swept measure, longest first, on one axis. The axis is the net value of the alternative the
 * base chose, what the next best alternative would cost less what the chosen one costs, so zero
 * is break-even and every flip of the root's choice sits on the zero line; when the root did not
 * decide at the base, the axis is the expected cost at the root. Round ticks with light gridlines
 * cross the bars, a dashed line marks the base case, and a title across the chart says what the axis measures. A
 * bar spans the axis from the measure's lower end to its upper end, each end's value printed in
 * its own unit; it is solid when the block stated that range and hollow when the sweep assumed it.
 * Where the root's chosen alternative flips inside the range, the bar changes colour beyond the
 * flip and a tick carries the threshold; a legend names the colours. Resting on a bar lights the
 * measure on the tree through the {@link Host}; a click on a bar tries the value under the
 * pointer as a what-if, and a click on a name opens the value's control. Drawn as the shared {@code DecisionFigureTornado} draws the figure.
 */
final class TornadoChart extends Region {

    /** What the chart asks of the surface it is drawn on. */
    interface Host {
        /** How a swept measure is named: its quantity, or its kind under the branch that carries it. */
        String nameOf(int measureVertex);

        /** How an alternative is named, for a flip. */
        String alternativeName(int alternativeVertex);

        /** A value of a swept measure printed with its unit: a share as a percentage, money to the whole unit. */
        String quantity(int measureVertex, double value);

        /** The name of the money every amount is in, for the axis title; empty when the block names none. */
        String moneyUnit();

        /** Lights or unlights a measure on the tree as the pointer rests on its bar. */
        void light(int measureVertex, boolean on);

        /** Tries a value of a measure as a what-if. */
        void tryValue(int measureVertex, double value);

        /** Draws the tree at a value of a measure, uncommitted; empty draws it where it stands. */
        void preview(int measureVertex, OptionalDouble value);
    }

    static final Color BASE_CHOICE = Color.web("#1f3b73");
    static final Color OTHER_CHOICE = Color.web("#b5541a");
    static final Color OPEN_CHOICE = Color.web("#9a9a9a");
    static final Color HOT = Color.web("#d98b1a");
    private static final Color AXIS = Color.web("#777");
    private static final Color GRID = Color.web("#e4e4e4");
    private static final double CAPTION_ROOM = 64;
    /** The widest the chart grows, in ems of the base size: the documentation figure's 640 px at its 12 px type, a little over. */
    static final double WIDTH_EMS = 56;

    /** One measure's row: its name, the band the pointer rests on, the bar's segments and ticks, and the captions at its ends. */
    final class Row {
        final Swing swing;
        final Label name;
        final Rectangle band = new Rectangle();
        final List<Rectangle> segments = new ArrayList<>();
        final List<Line> ticks = new ArrayList<>();
        final List<Label> tickLabels = new ArrayList<>();
        final Label lowCaption;
        final Label highCaption;
        double y;

        Row(Swing swing) {
            this.swing = swing;
            name = new Label(host.nameOf(swing.measureVertex()));
            name.setFont(Font.font(base * 0.85));
            name.setTextFill(MeasureChip.INK);
            lowCaption = caption(host.quantity(swing.measureVertex(), swing.range().lower()));
            highCaption = caption(host.quantity(swing.measureVertex(), swing.range().upper()));
        }

        private Label caption(String text) {
            Label caption = new Label(text);
            caption.setFont(Font.font(base * 0.72));
            caption.setTextFill(Color.web("#666"));
            return caption;
        }

        boolean hollow() {
            return !swing.stated();
        }
    }

    private final DecisionTreeSensitivity sweep;
    private final double base;
    private final Host host;
    private final double lineHeight;
    private final double rowHeight;
    private final Map<Integer, Row> rows = new LinkedHashMap<>();
    /** Whether the axis is the net value of the base's choice rather than the root's cost. */
    private final boolean net;
    private final double least;
    private final double greatest;
    private final Label axisTitle;
    private final List<Label> tickLabels = new ArrayList<>();
    private final List<Line> gridLines = new ArrayList<>();
    private final Label axisBase;
    private final Line baseLine = new Line();
    private final List<Node> legend = new ArrayList<>();
    private final Label probe = new Label();
    private final Label footnote;
    private final Line marker = new Line();
    private final MeasurePeek peek = new MeasurePeek();
    private MeasureControl control;
    private Row controlRow;
    private double barLeft;
    private double barWidth;

    TornadoChart(DecisionTreeSensitivity sweep, double base, Host host) {
        this.sweep = sweep;
        this.base = base;
        this.host = host;
        this.lineHeight = Math.ceil(base * 1.35);
        this.rowHeight = lineHeight + 10;
        getStyleClass().add("decision-tornado");
        // as wide as the document's figure in proportion to the type, never the whole card: a card widened
        // for a deep tree would otherwise stretch every bar across it
        setMaxWidth(base * WIDTH_EMS);
        net = sweep.netValued();
        // on a net-value axis zero is always in view: it is what every bar is read against
        least = net ? Math.min(0, sweep.leastNet()) : sweep.least();
        greatest = net ? Math.max(0, sweep.greatestNet()) : sweep.greatest();
        String unit = host.moneyUnit() == null || host.moneyUnit().isBlank() ? "" : ", in " + host.moneyUnit();
        axisTitle = axisLabel(net
                ? "Net value of \u201C" + host.alternativeName(sweep.baseChosen().orElse(-1))
                + "\u201D: what the next best alternative would cost, less what it costs" + unit + ". Zero is break-even."
                : "Expected cost at the root" + unit);
        axisTitle.setStyle("-fx-font-style: italic;");
        axisTitle.setWrapText(true);
        axisBase = axisLabel("base " + money(net ? sweep.baseNet() : sweep.baseRoot()));
        axisBase.setFont(Font.font(null, FontWeight.BOLD, base * 0.78));
        baseLine.setStroke(AXIS);
        baseLine.getStrokeDashArray().setAll(3.0, 3.0);
        for (double tick : DecisionFigureTornado.ticks(least, greatest, 400)) {
            Label label = axisLabel(money(tick));
            label.setUserData(tick);
            tickLabels.add(label);
            Line grid = new Line();
            boolean zero = net && tick == 0;
            grid.setStroke(zero ? AXIS : GRID);
            grid.setUserData(tick);
            gridLines.add(grid);
        }
        probe.setFont(Font.font(base * 0.78));
        probe.setStyle("-fx-background-color: #fffbe8; -fx-border-color: #e6c87a; -fx-padding: 1 5 1 5;");
        probe.setVisible(false);
        probe.setMouseTransparent(true);
        marker.setStroke(HOT);
        marker.setStrokeWidth(2.5);
        marker.setVisible(false);
        marker.setMouseTransparent(true);
        sceneProperty().addListener((obs, was, now) -> {
            if (now == null) {
                peek.close();
            }
        });
        getChildren().add(axisTitle);
        getChildren().addAll(gridLines);
        getChildren().addAll(tickLabels);
        getChildren().addAll(axisBase, baseLine, marker);
        for (Swing swing : sweep.swings()) {
            if ((net ? swing.netWidth() : swing.width()) <= 0) {
                continue;
            }
            Row row = new Row(swing);
            rows.put(swing.measureVertex(), row);
            build(row);
        }
        buildLegend();
        footnote = new Label(footnote());
        footnote.setFont(Font.font(base * 0.78));
        footnote.setTextFill(Color.web("#666"));
        footnote.setWrapText(true);
        getChildren().addAll(footnote, probe);
    }

    private Label axisLabel(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(base * 0.78));
        label.setTextFill(AXIS);
        return label;
    }

    private String footnote() {
        StringBuilder sb = new StringBuilder(DecisionFigureTornado.FOOTNOTE + " Click a bar to try the value under the pointer, or a name to set its value.");
        int still = (int) sweep.swings().stream().filter(s -> (net ? s.netWidth() : s.width()) <= 0).count();
        if (still > 0) {
            sb.append(' ').append(still == 1 ? "One value does not move the answer." : still + " values do not move the answer.");
        }
        for (Unswept unswept : sweep.unswept()) {
            sb.append(" Not swept: ").append(host.nameOf(unswept.measureVertex())).append(", ").append(unswept.why()).append('.');
        }
        return sb.toString();
    }

    /** The legend: a swatch and a few words for each colour the bars use, and for a hollow bar and a flip. */
    private void buildLegend() {
        boolean anyOther = false;
        boolean anyOpen = false;
        boolean anyHollow = false;
        boolean anyFlip = false;
        for (Row row : rows.values()) {
            anyHollow |= row.hollow();
            anyFlip |= !row.tickLabels.isEmpty();
            for (Rectangle segment : row.segments) {
                javafx.scene.paint.Paint colour = row.hollow() ? segment.getStroke() : segment.getFill();
                anyOther |= OTHER_CHOICE.equals(colour) || OTHER_CHOICE.deriveColor(0, 1, 1, 0.85).equals(colour);
                anyOpen |= OPEN_CHOICE.equals(colour) || OPEN_CHOICE.deriveColor(0, 1, 1, 0.85).equals(colour);
            }
        }
        String chosen = sweep.baseChosen().isPresent() ? host.alternativeName(sweep.baseChosen().getAsInt()) : null;
        legendItem(swatch(BASE_CHOICE, true), chosen == null || !net ? "the base choice holds" : "\u201C" + chosen + "\u201D still best");
        if (anyOther) {
            legendItem(swatch(OTHER_CHOICE, true), "another alternative wins");
        }
        if (anyOpen) {
            legendItem(swatch(OPEN_CHOICE, true), "choice left open");
        }
        if (anyHollow) {
            legendItem(swatch(MeasureChip.INK, false), "hollow: range assumed");
        }
        if (anyFlip) {
            Line tick = new Line(0, 0, 0, 12);
            tick.setStroke(HOT);
            tick.setStrokeWidth(2);
            legendItem(tick, "flip of the choice");
        }
        getChildren().addAll(legend);
    }

    private static Rectangle swatch(Color colour, boolean filled) {
        Rectangle swatch = new Rectangle(14, 8);
        if (filled) {
            swatch.setFill(colour.deriveColor(0, 1, 1, 0.85));
            swatch.setStroke(null);
        } else {
            swatch.setFill(Color.TRANSPARENT);
            swatch.setStroke(colour);
            swatch.setStrokeWidth(1.3);
        }
        return swatch;
    }

    private void legendItem(Node swatch, String text) {
        Label label = new Label(text);
        label.setFont(Font.font(base * 0.72));
        label.setTextFill(Color.web("#666"));
        label.getStyleClass().add("tornado-legend");
        swatch.setMouseTransparent(true);
        legend.add(swatch);
        legend.add(label);
    }

    // ── Building a row ───────────────────────────────────────────────────────

    private void build(Row row) {
        Swing swing = row.swing;
        int vertex = swing.measureVertex();
        row.band.setFill(Color.TRANSPARENT);
        row.band.setCursor(Cursor.HAND);
        row.band.setId("tornado-row-" + vertex);
        getChildren().addAll(row.band, row.name, row.lowCaption, row.highCaption);
        int rootDecision = sweep.tree().rootIndex();
        List<Flip> rootFlips = swing.flipsAt(rootDecision).castToList();
        int baseChosen = sweep.baseChosen().orElse(-1);
        // segments between the root's flips, each coloured by what the root chose there
        List<Double> cuts = new ArrayList<>();
        cuts.add(swing.range().lower());
        rootFlips.forEach(flip -> cuts.add(flip.at()));
        cuts.add(swing.range().upper());
        for (int i = 1; i < cuts.size(); i++) {
            int chosen = i == 1
                    ? (rootFlips.isEmpty() ? baseChosen : rootFlips.get(0).chosenBelow())
                    : rootFlips.get(i - 2).chosenAbove();
            Color colour = chosen < 0 ? OPEN_CHOICE : chosen == baseChosen ? BASE_CHOICE : OTHER_CHOICE;
            Rectangle segment = new Rectangle();
            if (row.hollow()) {
                segment.setFill(Color.TRANSPARENT);
                segment.setStroke(colour);
                segment.setStrokeWidth(1.3);
            } else {
                segment.setFill(colour.deriveColor(0, 1, 1, 0.85));
                segment.setStroke(null);
            }
            segment.setMouseTransparent(true);
            segment.setUserData(new double[] {cuts.get(i - 1), cuts.get(i)});
            row.segments.add(segment);
            getChildren().add(segment);
        }
        for (Flip flip : swing.flips()) {
            boolean atRoot = flip.decisionVertex() == rootDecision;
            Line tick = new Line();
            tick.setStroke(atRoot ? HOT : OPEN_CHOICE);
            tick.setStrokeWidth(atRoot ? 2 : 1);
            tick.setUserData(flip);
            tick.setMouseTransparent(true);
            row.ticks.add(tick);
            getChildren().add(tick);
            if (atRoot) {
                Label at = new Label("flips at " + host.quantity(vertex, flip.at()));
                at.setFont(Font.font(null, FontWeight.BOLD, base * 0.72));
                at.setTextFill(HOT);
                at.setMouseTransparent(true);
                row.tickLabels.add(at);
                getChildren().add(at);
            }
        }
        StringBuilder tip = new StringBuilder(host.nameOf(vertex)).append(": ")
                .append(host.quantity(vertex, swing.range().lower())).append(" to ").append(host.quantity(vertex, swing.range().upper()))
                .append(row.hollow() ? " (assumed)" : " (as stated)")
                .append("\nexpected cost ").append(money(swing.rootAtLow())).append(" to ").append(money(swing.rootAtHigh()));
        if (net) {
            tip.append("\nnet value ").append(money(swing.netAtLow())).append(" to ").append(money(swing.netAtHigh()));
        }
        for (Flip flip : swing.flips()) {
            tip.append("\nat ").append(host.quantity(vertex, flip.at())).append(flip.decisionVertex() == rootDecision ? " the choice" : " a later choice")
                    .append(" flips from ").append(host.alternativeName(flip.chosenBelow()))
                    .append(" to ").append(host.alternativeName(flip.chosenAbove()));
        }
        Tooltip.install(row.band, new Tooltip(tip.toString()));
        row.band.setOnMouseEntered(e -> {
            highlight(vertex, true);
            host.light(vertex, true);
        });
        row.band.setOnMouseExited(e -> {
            highlight(vertex, false);
            host.light(vertex, false);
            probe.setVisible(false);
        });
        row.name.setOnMouseEntered(e -> {
            highlight(vertex, true);
            host.light(vertex, true);
        });
        row.name.setOnMouseExited(e -> {
            highlight(vertex, false);
            host.light(vertex, false);
        });
        row.name.setCursor(Cursor.HAND);
        row.name.setOnMouseClicked(e -> nameClicked(row));
        row.band.setOnMouseMoved(e -> valueAt(row, e.getX()).ifPresent(value -> {
            probe.setText(host.quantity(vertex, value) + " → " + (net
                    ? "net " + money(swing.netAtValue(value).orElse(Double.NaN)) + ", expected cost "
                    + money(swing.rootAtValue(value).orElse(Double.NaN))
                    : money(swing.rootAtValue(value).orElse(Double.NaN))));
            probe.setVisible(true);
            probe.autosize();
            probe.relocate(Math.min(e.getX() + 10, getWidth() - probe.getWidth() - 2), row.y - lineHeight * 0.9);
        }));
        row.band.setOnMouseClicked(e -> valueAt(row, e.getX()).ifPresent(value -> host.tryValue(vertex, rounded(value))));
    }

    /** The measure's value under an x on a row, read off the bar. */
    Optional<Double> valueAt(Row row, double x) {
        if (barWidth <= 0) {
            return Optional.empty();
        }
        double at = least + (x - barLeft) / barWidth * (greatest - least);
        if (net) {
            double clipped = Math.max(row.swing.leastNet(), Math.min(row.swing.greatestNet(), at));
            return row.swing.valueAtNet(clipped);
        }
        double clipped = Math.max(row.swing.least(), Math.min(row.swing.greatest(), at));
        return row.swing.valueAtRoot(clipped);
    }

    /** Where the axis stands when a row's measure is a value: the net value, or the root's cost. */
    private double at(Swing swing, double value) {
        return net ? swing.netAtValue(value).orElse(sweep.baseNet()) : swing.rootAtValue(value).orElse(sweep.baseRoot());
    }

    // ── The value's control ──────────────────────────────────────────────────

    /** A click on a value's name opens its control, or closes it when it is the one open. */
    void nameClicked(Row row) {
        if (controlRow == row) {
            closeControl();
        } else if (peek.opens(row)) {
            openControl(row.swing.measureVertex());
        }
    }

    /**
     * Opens the control for a value beneath its name: its bounds, a slider, and the value the
     * tree is drawn at. Any control already open closes first, and its preview with it.
     */
    MeasureControl openControl(int measureVertex) {
        Row row = rows.get(measureVertex);
        if (row == null) {
            return null;
        }
        closeControl();
        Swing swing = row.swing;
        controlRow = row;
        control = new MeasureControl(host.nameOf(measureVertex), swing.range(), swing.base(), swing.stated(), base,
                new MeasureControl.Listener() {
                    @Override
                    public void preview(double value) {
                        mark(row, value);
                        host.preview(measureVertex, OptionalDouble.of(value));
                    }

                    @Override
                    public void commit(double value) {
                        peek.close();
                        control = null;
                        controlRow = null;
                        marker.setVisible(false);
                        host.tryValue(measureVertex, value);
                    }
                });
        if (getScene() != null && getScene().getWindow() != null) {
            peek.show(row, control, row.name, base * MeasureControl.WIDTH_EMS, this::closeControl);
        }
        return control;
    }

    /** Closes the control, and takes the tree back to where it stands. */
    void closeControl() {
        if (control == null) {
            return;
        }
        Row row = controlRow;
        control = null;
        controlRow = null;
        marker.setVisible(false);
        peek.close();
        host.preview(row.swing.measureVertex(), OptionalDouble.empty());
    }

    /** The control while one is open; for a test. */
    Optional<MeasureControl> control() {
        return Optional.ofNullable(control);
    }

    /** A marker on the row's bar where the axis stands for a value being previewed. */
    private void mark(Row row, double value) {
        Optional<Double> at = net ? row.swing.netAtValue(value) : row.swing.rootAtValue(value);
        if (at.isEmpty()) {
            marker.setVisible(false);
            return;
        }
        double x = x(at.get());
        marker.setStartX(x);
        marker.setEndX(x);
        marker.setStartY(row.y + 2);
        marker.setEndY(row.y + rowHeight - 2);
        marker.setVisible(true);
        marker.toFront();
    }

    /** Lights a measure's row, as the pointer resting on its chip in the tree asks. */
    void highlight(int measureVertex, boolean on) {
        Row row = rows.get(measureVertex);
        if (row == null) {
            return;
        }
        row.band.setFill(on ? Color.web("#fff3d6") : Color.TRANSPARENT);
        row.name.setTextFill(on ? HOT : MeasureChip.INK);
        row.name.setFont(Font.font(null, on ? FontWeight.BOLD : FontWeight.NORMAL, base * 0.85));
    }

    // ── Laying the rows out ──────────────────────────────────────────────────

    private double nameWidth() {
        double widest = 0;
        for (Row row : rows.values()) {
            widest = Math.max(widest, row.name.prefWidth(-1));
        }
        return Math.min(widest, 220);
    }

    private double x(double at) {
        double span = greatest - least;
        return span <= 0 ? barLeft + barWidth / 2 : barLeft + (at - least) / span * barWidth;
    }

    /** The height of the title above the axis at a width, and of the legend beneath the bars. */
    private double titleHeight(double width) {
        return axisTitle.prefHeight(Math.max(40, width - 4));
    }

    private double legendHeight(double width) {
        double x = 0;
        int lines = legend.isEmpty() ? 0 : 1;
        for (int i = 0; i + 1 < legend.size(); i += 2) {
            double w = 14 + 5 + legend.get(i + 1).prefWidth(-1);
            if (x > 0 && x + w > width - 4) {
                x = 0;
                lines++;
            }
            x += w + 16;
        }
        return lines * lineHeight;
    }

    @Override
    protected double computePrefHeight(double width) {
        double w = width < 0 ? Math.max(getWidth(), 400) : width;
        return titleHeight(w) + lineHeight + rows.size() * rowHeight + lineHeight + 4 + legendHeight(w) + 4 + footnote.prefHeight(w - 4) + 4;
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefWidth(double height) {
        return base * WIDTH_EMS;
    }

    /** A caption or a threshold label, where it goes on its row. */
    private record Placed(Label label, double x) {
    }

    /**
     * The captions at a row's ends, low where the axis is least and high where it is greatest,
     * and beyond the caption on the bar's wider side each root flip's threshold, clear of the bar.
     */
    private List<Placed> placed(Row row) {
        boolean rising = net ? row.swing.netAtHigh() >= row.swing.netAtLow() : row.swing.rootAtHigh() >= row.swing.rootAtLow();
        Label leftCaption = rising ? row.lowCaption : row.highCaption;
        Label rightCaption = rising ? row.highCaption : row.lowCaption;
        double left = x(net ? row.swing.leastNet() : row.swing.least());
        double right = x(net ? row.swing.greatestNet() : row.swing.greatest());
        List<Placed> placed = new ArrayList<>();
        placed.add(new Placed(leftCaption, left - 4 - leftCaption.prefWidth(-1)));
        placed.add(new Placed(rightCaption, right + 4));
        double afterRight = right + 4 + rightCaption.prefWidth(-1) + 8;
        double beforeLeft = left - 4 - leftCaption.prefWidth(-1) - 8;
        int shown = 0;
        for (Flip flip : row.swing.flips()) {
            if (flip.decisionVertex() != sweep.tree().rootIndex()) {
                continue;
            }
            Label at = row.tickLabels.get(shown++);
            double tx = x(at(row.swing, flip.at()));
            double w = at.prefWidth(-1);
            boolean toTheRight = right - tx >= tx - left;
            placed.add(new Placed(at, toTheRight ? afterRight : beforeLeft - w));
            if (toTheRight) {
                afterRight += w + 8;
            } else {
                beforeLeft -= w + 8;
            }
        }
        return placed;
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double names = nameWidth();
        // the rooms beside the bars grow until every caption and threshold fits inside the chart
        double leftRoom = CAPTION_ROOM;
        double rightRoom = CAPTION_ROOM;
        for (int pass = 0; pass < 5; pass++) {
            barLeft = names + 10 + leftRoom;
            barWidth = Math.max(40, width - barLeft - rightRoom - 4);
            double overLeft = 0;
            double overRight = 0;
            for (Row row : rows.values()) {
                for (Placed placed : placed(row)) {
                    overLeft = Math.max(overLeft, (names + 10) - placed.x());
                    overRight = Math.max(overRight, placed.x() + placed.label().prefWidth(-1) - (width - 4));
                }
            }
            if (overLeft <= 0 && overRight <= 0) {
                break;
            }
            leftRoom += Math.max(0, overLeft);
            rightRoom += Math.max(0, overRight);
        }
        // a sentence, so it takes the chart's whole width: over the bars alone it stands in a thin column
        double titleWidth = Math.max(40, width - 4);
        double titleH = axisTitle.prefHeight(titleWidth);
        axisTitle.resizeRelocate(0, 0, titleWidth, titleH);
        double axisY = titleH;
        double baseX = x(net ? sweep.baseNet() : sweep.baseRoot());
        // round ticks, skipping any label an earlier one would cover
        double lastEnd = Double.NEGATIVE_INFINITY;
        for (Label label : tickLabels) {
            double w = label.prefWidth(-1);
            double lx = x((Double) label.getUserData()) - w / 2;
            boolean shown = lx >= lastEnd + 6;
            label.setVisible(shown);
            if (shown) {
                place(label, lx, axisY);
                lastEnd = lx + w;
            }
        }
        double y = axisY + lineHeight;
        double barsBottom = y + rows.size() * rowHeight;
        for (Line grid : gridLines) {
            double gx = x((Double) grid.getUserData());
            grid.setStartX(gx);
            grid.setEndX(gx);
            grid.setStartY(y);
            grid.setEndY(barsBottom);
        }
        baseLine.setStartX(baseX);
        baseLine.setStartY(y);
        baseLine.setEndX(baseX);
        baseLine.setEndY(barsBottom);
        for (Row row : rows.values()) {
            row.y = y;
            row.band.setX(0);
            row.band.setY(y);
            row.band.setWidth(width);
            row.band.setHeight(rowHeight);
            row.name.setMaxWidth(names);
            row.name.resizeRelocate(0, y + (rowHeight - lineHeight) / 2, names, lineHeight);
            double barTop = y + 5;
            double barHeight = rowHeight - 10;
            for (Rectangle segment : row.segments) {
                double[] cut = (double[]) segment.getUserData();
                double a = at(row.swing, cut[0]);
                double b = at(row.swing, cut[1]);
                double x0 = x(Math.min(a, b));
                double x1 = x(Math.max(a, b));
                segment.setX(x0);
                segment.setY(barTop);
                segment.setWidth(Math.max(1, x1 - x0));
                segment.setHeight(barHeight);
            }
            for (Line tick : row.ticks) {
                Flip flip = (Flip) tick.getUserData();
                double tx = x(at(row.swing, flip.at()));
                tick.setStartX(tx);
                tick.setEndX(tx);
                tick.setStartY(barTop - 3);
                tick.setEndY(barTop + barHeight + 3);
            }
            // the measure's own values at the bar's ends, and beyond them each root flip's threshold
            for (Placed placed : placed(row)) {
                place(placed.label(), placed.x(), barTop + (barHeight - placed.label().prefHeight(-1)) / 2);
            }
            y += rowHeight;
        }
        double baseW = axisBase.prefWidth(-1);
        place(axisBase, Math.max(0, Math.min(baseX - baseW / 2, width - baseW)), y + 2);
        y += lineHeight + 4;
        double lx = 0;
        for (int i = 0; i + 1 < legend.size(); i += 2) {
            Node swatch = legend.get(i);
            Label label = (Label) legend.get(i + 1);
            double w = 14 + 5 + label.prefWidth(-1);
            if (lx > 0 && lx + w > width - 4) {
                lx = 0;
                y += lineHeight;
            }
            swatch.relocate(lx, y + (lineHeight - swatch.prefHeight(-1)) / 2);
            place(label, lx + 14 + 5, y + (lineHeight - label.prefHeight(-1)) / 2);
            lx += w + 16;
        }
        y += legend.isEmpty() ? 0 : lineHeight;
        footnote.resizeRelocate(0, y + 4, width - 4, footnote.prefHeight(width - 4));
    }

    private static void place(Node node, double x, double y) {
        double w = node.prefWidth(-1);
        node.resizeRelocate(x, y, w, node.prefHeight(w));
    }

    /**
     * A measure's value as a threshold or a caption reads it: whole above a hundred, two decimals
     * above one, four beneath, since a flip found by bisection is only as exact as its range.
     */
    static String value(double value) {
        return DecisionFigureText.value(value);
    }

    /** A value as the chart prints it, so a what-if tried from a bar says what the reader saw. */
    static double rounded(double value) {
        double magnitude = Math.abs(value);
        double unit = magnitude >= 100 ? 1 : magnitude >= 1 ? 0.01 : 0.0001;
        return Math.round(value / unit) * unit;
    }

    /** An amount as the chart prints it: after the block's money sign when its unit has one, the sign of a negative first. */
    private String money(double amount) {
        if (Double.isNaN(amount)) {
            return "?";
        }
        return (amount < 0 ? "\u2212" : "") + DecisionFigureText.symbolFor(host.moneyUnit())
                + DecisionTreeBlockRenderer.money(Bounds.point(Math.abs(amount)));
    }

    /** The rows, longest swing first, for a test. */
    List<Row> rows() {
        return new ArrayList<>(rows.values());
    }

    Optional<Row> row(int measureVertex) {
        return Optional.ofNullable(rows.get(measureVertex));
    }

    DecisionTreeSensitivity sweep() {
        return sweep;
    }
}
