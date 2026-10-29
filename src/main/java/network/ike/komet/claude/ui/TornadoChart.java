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
import javafx.stage.Popup;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionTreeSensitivity;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Flip;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Swing;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Unswept;
import network.ike.komet.claude.decision.render.DecisionFigureText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A tornado diagram of a decision tree's one-way sensitivity, drawn beneath the tree: one bar
 * per swept measure, longest first, on one axis, which is the expected cost at the root, with a
 * dashed line at the base case. A bar spans the root's cost from the measure's lower end to its
 * upper end; it is solid when the block stated that range and hollow when the sweep assumed it.
 * Where the root's chosen alternative flips inside the range, the bar changes colour beyond the
 * flip and a tick carries the threshold. Resting on a bar lights the measure on the tree through
 * the {@link Host}; a click on a bar tries the value under the pointer as a what-if.
 */
final class TornadoChart extends Region {

    /** What the chart asks of the surface it is drawn on. */
    interface Host {
        /** How a swept measure is named: its quantity, or its kind under the branch that carries it. */
        String nameOf(int measureVertex);

        /** How an alternative is named, for a flip. */
        String alternativeName(int alternativeVertex);

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
    private static final double CAPTION_ROOM = 64;

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
            lowCaption = caption(swing.range().lower());
            highCaption = caption(swing.range().upper());
        }

        private Label caption(double value) {
            Label caption = new Label(value(value));
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
    private final Label axisLow;
    private final Label axisHigh;
    private final Label axisBase;
    private final Line baseLine = new Line();
    private final Label probe = new Label();
    private final Label footnote;
    private final Line marker = new Line();
    private final Popup popup = new Popup();
    private final PauseTransition hideDelay = new PauseTransition(Duration.millis(600));
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
        setMaxWidth(Double.MAX_VALUE);
        axisLow = axisLabel(money(sweep.least()));
        axisHigh = axisLabel(money(sweep.greatest()));
        axisBase = axisLabel("base " + money(sweep.baseRoot()));
        axisBase.setFont(Font.font(null, FontWeight.BOLD, base * 0.78));
        baseLine.setStroke(AXIS);
        baseLine.getStrokeDashArray().setAll(3.0, 3.0);
        probe.setFont(Font.font(base * 0.78));
        probe.setStyle("-fx-background-color: #fffbe8; -fx-border-color: #e6c87a; -fx-padding: 1 5 1 5;");
        probe.setVisible(false);
        probe.setMouseTransparent(true);
        marker.setStroke(HOT);
        marker.setStrokeWidth(2.5);
        marker.setVisible(false);
        marker.setMouseTransparent(true);
        popup.setAutoHide(true);
        popup.setAutoFix(true);
        popup.setOnAutoHide(e -> closeControl());
        hideDelay.setOnFinished(e -> closeControl());
        sceneProperty().addListener((obs, was, now) -> {
            if (now == null && popup.isShowing()) {
                popup.hide();
            }
        });
        getChildren().addAll(axisLow, axisHigh, axisBase, baseLine, marker);
        for (Swing swing : sweep.swings()) {
            if (swing.width() <= 0) {
                continue;
            }
            Row row = new Row(swing);
            rows.put(swing.measureVertex(), row);
            build(row);
        }
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
        StringBuilder sb = new StringBuilder("One value at a time, the rest at base. A solid bar is the interval the block states;"
                + " a hollow bar an assumed one, half to double, or zero to one for a share. Click a bar to try the value under the pointer.");
        int still = (int) sweep.swings().stream().filter(s -> s.width() <= 0).count();
        if (still > 0) {
            sb.append(' ').append(still == 1 ? "One value does not move the expected cost." : still + " values do not move the expected cost.");
        }
        for (Unswept unswept : sweep.unswept()) {
            sb.append(" Not swept: ").append(host.nameOf(unswept.measureVertex())).append(", ").append(unswept.why()).append('.');
        }
        return sb.toString();
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
                Label at = new Label("flips at " + value(flip.at()));
                at.setFont(Font.font(null, FontWeight.BOLD, base * 0.72));
                at.setTextFill(HOT);
                at.setMouseTransparent(true);
                row.tickLabels.add(at);
                getChildren().add(at);
            }
        }
        StringBuilder tip = new StringBuilder(host.nameOf(vertex)).append(": ")
                .append(value(swing.range().lower())).append(" to ").append(value(swing.range().upper()))
                .append(row.hollow() ? " (assumed)" : " (as stated)")
                .append("\nexpected cost ").append(money(swing.rootAtLow())).append(" to ").append(money(swing.rootAtHigh()));
        for (Flip flip : swing.flips()) {
            tip.append("\nat ").append(value(flip.at())).append(flip.decisionVertex() == rootDecision ? " the choice" : " a later choice")
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
            hideDelay.stop();
            if (controlRow != row) {
                openControl(vertex);
            }
        });
        row.name.setOnMouseExited(e -> {
            highlight(vertex, false);
            host.light(vertex, false);
            hideDelay.playFromStart();
        });
        row.band.setOnMouseMoved(e -> valueAt(row, e.getX()).ifPresent(value -> {
            probe.setText(value(value) + " → " + money(swing.rootAtValue(value).orElse(Double.NaN)));
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
        double root = sweep.least() + (x - barLeft) / barWidth * (sweep.greatest() - sweep.least());
        double clipped = Math.max(row.swing.least(), Math.min(row.swing.greatest(), root));
        return row.swing.valueAtRoot(clipped);
    }

    // ── The value's control ──────────────────────────────────────────────────

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
                        popup.hide();
                        control = null;
                        controlRow = null;
                        marker.setVisible(false);
                        host.tryValue(measureVertex, value);
                    }
                });
        control.setOnMouseEntered(e -> hideDelay.stop());
        control.setOnMouseExited(e -> hideDelay.playFromStart());
        popup.getContent().setAll(control);
        if (getScene() != null && getScene().getWindow() != null) {
            javafx.geometry.Point2D at = row.name.localToScreen(0, row.name.getHeight() + 2);
            popup.show(getScene().getWindow(), at.getX(), at.getY());
        }
        return control;
    }

    /** Closes the control, and takes the tree back to where it stands. */
    void closeControl() {
        hideDelay.stop();
        if (control == null) {
            return;
        }
        Row row = controlRow;
        control = null;
        controlRow = null;
        marker.setVisible(false);
        if (popup.isShowing()) {
            popup.hide();
        }
        host.preview(row.swing.measureVertex(), OptionalDouble.empty());
    }

    /** The control while one is open; for a test. */
    Optional<MeasureControl> control() {
        return Optional.ofNullable(control);
    }

    /** A marker on the row's bar at the root's cost for a value being previewed. */
    private void mark(Row row, double value) {
        Optional<Double> root = row.swing.rootAtValue(value);
        if (root.isEmpty()) {
            marker.setVisible(false);
            return;
        }
        double x = x(root.get());
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

    private double x(double root) {
        double span = sweep.greatest() - sweep.least();
        return span <= 0 ? barLeft + barWidth / 2 : barLeft + (root - sweep.least()) / span * barWidth;
    }

    @Override
    protected double computePrefHeight(double width) {
        double w = width < 0 ? Math.max(getWidth(), 400) : width;
        return lineHeight + rows.size() * rowHeight + 6 + footnote.prefHeight(w - 4) + 4;
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefWidth(double height) {
        return 400;
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double names = nameWidth();
        barLeft = names + 10 + CAPTION_ROOM;
        barWidth = Math.max(40, width - barLeft - CAPTION_ROOM - 4);
        double axisY = 0;
        place(axisLow, barLeft, axisY);
        place(axisHigh, barLeft + barWidth - axisHigh.prefWidth(-1), axisY);
        double baseX = x(sweep.baseRoot());
        place(axisBase, Math.max(barLeft + axisLow.prefWidth(-1) + 6,
                Math.min(baseX - axisBase.prefWidth(-1) / 2, barLeft + barWidth - axisHigh.prefWidth(-1) - axisBase.prefWidth(-1) - 6)), axisY);
        double y = lineHeight;
        baseLine.setStartX(baseX);
        baseLine.setStartY(y);
        baseLine.setEndX(baseX);
        baseLine.setEndY(y + rows.size() * rowHeight);
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
                double a = row.swing.rootAtValue(cut[0]).orElse(sweep.baseRoot());
                double b = row.swing.rootAtValue(cut[1]).orElse(sweep.baseRoot());
                double x0 = x(Math.min(a, b));
                double x1 = x(Math.max(a, b));
                segment.setX(x0);
                segment.setY(barTop);
                segment.setWidth(Math.max(1, x1 - x0));
                segment.setHeight(barHeight);
            }
            for (Line tick : row.ticks) {
                Flip flip = (Flip) tick.getUserData();
                double tx = x(row.swing.rootAtValue(flip.at()).orElse(sweep.baseRoot()));
                tick.setStartX(tx);
                tick.setEndX(tx);
                tick.setStartY(barTop - 3);
                tick.setEndY(barTop + barHeight + 3);
            }
            // the measure's own values at the bar's ends: low where the root is least, high where it is greatest
            boolean rising = row.swing.rootAtHigh() >= row.swing.rootAtLow();
            Label leftCaption = rising ? row.lowCaption : row.highCaption;
            Label rightCaption = rising ? row.highCaption : row.lowCaption;
            double left = x(row.swing.least());
            double right = x(row.swing.greatest());
            double captionLeft = left - 4 - leftCaption.prefWidth(-1);
            double captionRight = right + 4;
            int shown = 0;
            for (Flip flip : row.swing.flips()) {
                if (flip.decisionVertex() != sweep.tree().rootIndex()) {
                    continue;
                }
                Label at = row.tickLabels.get(shown++);
                double tx = x(row.swing.rootAtValue(flip.at()).orElse(sweep.baseRoot()));
                double w = at.prefWidth(-1);
                // the label goes on the wider side of the tick, and a caption it would cover moves outward
                boolean toTheRight = right - tx >= tx - left;
                double lx = toTheRight ? tx + 4 : tx - 4 - w;
                place(at, lx, barTop + (barHeight - at.prefHeight(-1)) / 2);
                if (toTheRight) {
                    captionRight = Math.max(captionRight, lx + w + 6);
                } else {
                    captionLeft = Math.min(captionLeft, lx - 6 - leftCaption.prefWidth(-1));
                }
            }
            place(leftCaption, captionLeft, barTop + (barHeight - lineHeight) / 2 + 1);
            place(rightCaption, captionRight, barTop + (barHeight - lineHeight) / 2 + 1);
            y += rowHeight;
        }
        footnote.resizeRelocate(0, y + 6, width - 4, footnote.prefHeight(width - 4));
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

    private static String money(double root) {
        return Double.isNaN(root) ? "?" : DecisionTreeBlockRenderer.money(Bounds.point(root));
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
