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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.stage.Popup;
import javafx.util.Duration;
import network.ike.komet.claude.calculator.CalculatorSensitivity;
import network.ike.komet.claude.decision.Bounds;

/**
 * The tornado beneath a calculator ({@code dev-calculator-blocks}): one bar per numeric input,
 * widest first, on one axis, which is the result at the output's scale, with a hairline at the
 * result for every input at its midpoint. A bar is solid when the input's own bounds are an
 * interval, an observed resolution or a missing input's plausible range, and hollow when the
 * sweep assumed a swing for a point reading. Resting on a bar lights the input's row through the
 * {@link Host}; resting on its name opens the value control, whose slider previews the result and
 * whose button writes an assumption.
 */
final class CalculatorTornado extends VBox {

    /** What the chart asks of the card it is drawn on. */
    interface Host {
        /** How an input is named. */
        String nameOf(long inputQuantity);

        /** Lights or unlights an input's row as the pointer rests on its bar. */
        void light(long inputQuantity, boolean on);

        /** Draws the result at a value of an input, uncommitted; empty draws it where it stands. */
        void preview(long inputQuantity, OptionalDouble value);

        /** Writes an assumption for an input as a what-if. */
        void assume(long inputQuantity, double value);

        /** A result as the card prints it. */
        String shown(Bounds result);
    }

    private static final Color SOLID = Color.web("#7f77dd");
    private static final Color AXIS = Color.web("#777");
    private static final double NAME_WIDTH = 160;
    private static final double CAPTION_WIDTH = 96;
    private static final double BAR_HEIGHT = 12;

    private final Map<Long, Row> rows = new LinkedHashMap<>();
    private final Popup popup = new Popup();
    private final PauseTransition hideDelay = new PauseTransition(Duration.millis(600));
    private Row controlRow;

    private final class Row {
        final CalculatorSensitivity.Row swept;
        final HBox line = new HBox(8);
        final Label name;
        final Pane track = new Pane();
        final Rectangle bar = new Rectangle();
        final Label caption;

        Row(CalculatorSensitivity.Row swept, double base, double lo, double hi, double mid) {
            this.swept = swept;
            name = new Label(host.nameOf(swept.input().quantity()));
            name.setFont(Font.font(base * 0.85));
            name.setTextFill(MeasureChip.INK);
            name.setMinWidth(NAME_WIDTH);
            name.setPrefWidth(NAME_WIDTH);
            name.setTooltip(new Tooltip(swept.solid()
                    ? "Swung across the input's own interval; rest here for a slider"
                    : "A point reading swung across an assumed range; rest here for a slider"));
            track.setPrefHeight(BAR_HEIGHT + 6);
            track.setMinHeight(BAR_HEIGHT + 6);
            HBox.setHgrow(track, javafx.scene.layout.Priority.ALWAYS);
            Rectangle rail = new Rectangle();
            rail.setFill(Color.web("#f1efe8"));
            rail.setArcWidth(4);
            rail.setArcHeight(4);
            rail.setHeight(BAR_HEIGHT);
            rail.setY(3);
            rail.widthProperty().bind(track.widthProperty());
            bar.setHeight(BAR_HEIGHT);
            bar.setY(3);
            bar.setArcWidth(4);
            bar.setArcHeight(4);
            if (swept.solid()) {
                bar.setFill(SOLID);
            } else {
                bar.setFill(Color.TRANSPARENT);
                bar.setStroke(SOLID);
                bar.setStrokeWidth(1.5);
            }
            Line hair = new Line();
            hair.setStroke(AXIS);
            hair.setStartY(0);
            hair.setEndY(BAR_HEIGHT + 6);
            Bounds span = swept.span();
            double range = Math.max(hi - lo, 1e-9);
            track.widthProperty().addListener((obs, was, now) -> {
                double w = now.doubleValue();
                double x0 = (span.lower() - lo) / range * w;
                double x1 = (span.upper() - lo) / range * w;
                bar.setX(x0);
                bar.setWidth(Math.max(1.5, x1 - x0));
                double hx = (mid - lo) / range * w;
                hair.setStartX(hx);
                hair.setEndX(hx);
            });
            track.getChildren().addAll(rail, bar, hair);
            caption = new Label(host.shown(span));
            caption.setFont(Font.font(base * 0.78));
            caption.setTextFill(Color.web("#666"));
            caption.setMinWidth(CAPTION_WIDTH);
            line.setAlignment(Pos.CENTER_LEFT);
            line.getChildren().addAll(name, track, caption);
            line.setId("calculator-tornado-row-" + swept.input().quantity());
            long quantity = swept.input().quantity();
            line.setOnMouseEntered(e -> host.light(quantity, true));
            line.setOnMouseExited(e -> host.light(quantity, false));
            name.setOnMouseEntered(e -> open(this, base));
            name.setOnMouseExited(e -> scheduleHide());
            track.setOnMouseClicked(e -> {
                double w = track.getWidth();
                if (w <= 0) {
                    return;
                }
                double at = lo + e.getX() / w * range;
                Optional<Double> input = inputAt(this, at);
                input.ifPresent(v -> host.assume(quantity, v));
            });
        }
    }

    private final Host host;

    CalculatorTornado(CalculatorSensitivity sensitivity, double base, Host host) {
        super(2);
        this.host = host;
        setId("calculator-tornado");
        setPadding(new Insets(4, 0, 2, 0));
        Optional<Bounds> midpoint = sensitivity.midpoint();
        double mid = midpoint.map(Bounds::midpoint).orElse(0.0);
        double lo = mid;
        double hi = mid;
        for (CalculatorSensitivity.Row row : sensitivity.rows()) {
            lo = Math.min(lo, row.span().lower());
            hi = Math.max(hi, row.span().upper());
        }
        double pad = Math.max((hi - lo) * 0.05, 1e-6);
        lo -= pad;
        hi += pad;
        for (CalculatorSensitivity.Row row : sensitivity.rows()) {
            Row drawn = new Row(row, base, lo, hi, mid);
            rows.put(row.input().quantity(), drawn);
            getChildren().add(drawn.line);
        }
        Label legend = new Label("solid: an interval that enters the computation · hollow: the assumed swing of a point reading"
                + " · the hairline is the result with every input at its midpoint, " + host.shown(Bounds.point(mid)));
        legend.setFont(Font.font(base * 0.75));
        legend.setTextFill(Color.web("#666"));
        legend.setWrapText(true);
        getChildren().add(legend);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        hideDelay.setOnFinished(e -> {
            popup.hide();
            controlRow = null;
            host.preview(0, OptionalDouble.empty());
        });
    }

    /** Lights an input's row from outside, as the rail's hover lights the bar. */
    void highlight(long inputQuantity, boolean on) {
        Row row = rows.get(inputQuantity);
        if (row != null) {
            row.bar.setOpacity(on ? 1.0 : 0.85);
            row.name.setStyle(on ? "-fx-font-weight: bold;" : "");
        }
    }

    /** The input value a point on the result axis corresponds to, by linear interpolation across the swing. */
    private Optional<Double> inputAt(Row row, double result) {
        Bounds low = row.swept.low();
        Bounds high = row.swept.high();
        double r0 = low.midpoint();
        double r1 = high.midpoint();
        if (Math.abs(r1 - r0) < 1e-12) {
            return Optional.empty();
        }
        double t = (result - r0) / (r1 - r0);
        t = Math.max(0, Math.min(1, t));
        Bounds swing = row.swept.swing();
        return Optional.of(swing.lower() + t * (swing.upper() - swing.lower()));
    }

    private void open(Row row, double base) {
        hideDelay.stop();
        if (controlRow == row && popup.isShowing()) {
            return;
        }
        controlRow = row;
        long quantity = row.swept.input().quantity();
        double current = row.swept.swing().midpoint();
        MeasureControl control = new MeasureControl(host.nameOf(quantity), row.swept.swing(), current, row.swept.solid(), base,
                new MeasureControl.Listener() {
                    @Override
                    public void preview(double value) {
                        host.preview(quantity, OptionalDouble.of(value));
                    }

                    @Override
                    public void commit(double value) {
                        popup.hide();
                        controlRow = null;
                        host.preview(quantity, OptionalDouble.empty());
                        host.assume(quantity, value);
                    }
                });
        control.setOnMouseEntered(e -> hideDelay.stop());
        control.setOnMouseExited(e -> scheduleHide());
        popup.getContent().setAll(control);
        javafx.geometry.Point2D at = row.name.localToScreen(0, row.name.getHeight());
        if (at != null) {
            popup.show(row.name, at.getX(), at.getY() + 2);
        }
    }

    private void scheduleHide() {
        hideDelay.playFromStart();
    }

    /** The rows drawn, for a test. */
    int rowCount() {
        return rows.size();
    }

    /** The region, for a host that sizes it. */
    Region asRegion() {
        return this;
    }
}
