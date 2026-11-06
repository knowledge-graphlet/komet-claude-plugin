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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionTreeEdits;
import network.ike.komet.claude.decision.render.DecisionFigureText;

import java.util.Optional;

/**
 * The control that opens on a click on a value's name in the tornado: the value's lower and upper
 * bound, a slider between them, and the value the tree is drawn at. Moving the slider or typing a
 * value previews the tree at that value, live and uncommitted; the button makes it a what-if. The
 * bounds are the slider's range, the swing's by default. Typing other bounds moves the range, and
 * typing a value outside it widens the range to take it in, never the block.
 */
final class MeasureControl extends VBox {

    /** The popover's width, in ems of the document's text. */
    static final double WIDTH_EMS = 26;

    /** What the control asks of the chart it opens from. */
    interface Listener {
        /** The tree is to be drawn at this value, uncommitted. */
        void preview(double value);

        /** This value is to be tried as a what-if. */
        void commit(double value);
    }

    final TextField lower;
    final TextField upper;
    final Slider slider;
    final TextField shown;
    final Button apply;
    private final Listener listener;
    /** The slider's resolution: one tidy step per whole pixel of its track, never finer. */
    private double step = 1;
    private boolean snapping;
    /** The value being tried: where the slider stands, or exactly what was typed. */
    private double current;
    /** Whether the value has moved from where the tree stands: until it has, the step does not touch it. */
    private boolean moved;
    /** The value field's text as this control last wrote it: any other text is a person's. */
    private String written = "";

    /**
     * @param name   what the value is
     * @param range  the slider's range to begin with
     * @param value  where the tree stands now
     * @param stated whether the block stated the range, or the sweep assumed it
     */
    MeasureControl(String name, Bounds range, double value, boolean stated, double base, Listener listener) {
        super(6);
        this.listener = listener;
        setId("measure-control");
        setPadding(new Insets(8, 10, 8, 10));
        Label title = new Label(name);
        title.setFont(Font.font(null, FontWeight.BOLD, base * 0.9));
        title.setTextFill(MeasureChip.INK);
        title.setWrapText(true);
        Label how = new Label(stated ? "the block's stated interval" : "an assumed range; type other bounds to move it");
        how.setFont(Font.font(base * 0.75));
        how.setTextFill(javafx.scene.paint.Color.web("#666"));

        lower = field(range.lower(), base);
        lower.setId("measure-control-lower");
        upper = field(range.upper(), base);
        upper.setId("measure-control-upper");
        Label from = new Label("from");
        from.setFont(Font.font(base * 0.85));
        Label to = new Label("to");
        to.setFont(Font.font(base * 0.85));
        HBox bounds = new HBox(6, from, lower, to, upper);
        bounds.setAlignment(Pos.CENTER_LEFT);

        slider = new Slider(range.lower(), range.upper(), Math.max(range.lower(), Math.min(range.upper(), value)));
        slider.setId("measure-control-slider");
        slider.setPrefWidth(Math.max(220, base * 18));
        slider.setShowTickMarks(true);
        slider.setMajorTickUnit(Math.max((range.upper() - range.lower()) / 4, 1e-9));
        slider.setMinorTickCount(1);

        shown = new TextField();
        shown.setId("measure-control-value");
        shown.setFont(Font.font(null, FontWeight.BOLD, base * 0.9));
        shown.setPrefColumnCount(8);
        shown.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        apply = new Button("Use this value");
        apply.setId("measure-control-apply");
        apply.setFont(Font.font(base * 0.85));
        apply.setTooltip(new Tooltip("Tries it as a what-if, which the block can then take back or write"));
        HBox foot = new HBox(10, new Label("value"), shown, apply);
        foot.setAlignment(Pos.CENTER_LEFT);
        ((Label) foot.getChildren().get(0)).setFont(Font.font(base * 0.85));

        getChildren().addAll(title, how, bounds, slider, foot);
        restep();
        current = slider.getValue();
        write(TornadoChart.value(current));
        slider.widthProperty().addListener((obs, was, now) -> restep());
        slider.valueProperty().addListener((obs, was, now) -> {
            if (snapping) {
                return;
            }
            double snapped = snap(now.doubleValue());
            if (snapped != now.doubleValue()) {
                snapping = true;
                try {
                    slider.setValue(snapped);
                } finally {
                    snapping = false;
                }
            }
            moved = true;
            show(snapped);
            listener.preview(snapped);
        });
        lower.setOnAction(e -> rerange());
        upper.setOnAction(e -> rerange());
        shown.setOnAction(e -> typeValue());
        apply.setOnAction(e -> {
            if (typeValue()) {
                listener.commit(current);
            }
        });
    }

    /**
     * Reads the value field when its text differs from the value being tried, and previews what it
     * reads. A value outside the range widens the range to take it in.
     *
     * @return false when the field holds nothing that reads as one value
     */
    boolean typeValue() {
        if (shown.getText().equals(written)) {
            return true;
        }
        Optional<Bounds> typed = DecisionTreeEdits.typed(shown.getText()).filter(b -> b.lower() == b.upper());
        if (typed.isEmpty()) {
            shown.setStyle("-fx-border-color: #c0392b;");
            return false;
        }
        shown.setStyle("");
        double value = typed.get().lower();
        if (value < slider.getMin() || value > slider.getMax()) {
            double min = Math.min(value, slider.getMin());
            double max = Math.max(value, slider.getMax());
            slider.setMin(min);
            slider.setMax(max);
            lower.setText(TornadoChart.value(min).replace(",", ""));
            upper.setText(TornadoChart.value(max).replace(",", ""));
            slider.setMajorTickUnit(Math.max((max - min) / 4, 1e-9));
            restep();
        }
        snapping = true;
        try {
            slider.setValue(value);     // as typed: only the pointer is held to the slider's step
        } finally {
            snapping = false;
        }
        moved = true;
        current = value;
        written = shown.getText();
        listener.preview(value);
        return true;
    }

    /**
     * The step the slider moves by: the range over the track's whole pixels, rounded up to one,
     * two or five of a power of ten. A slider two hundred pixels wide over 350..1,400 steps by
     * ten; a value it hands on is never finer than the pointer could have set.
     */
    private void restep() {
        double width = slider.getWidth() > 0 ? slider.getWidth() : slider.getPrefWidth();
        double pixels = Math.max(1, width - 16);
        step = niceStep((slider.getMax() - slider.getMin()) / pixels);
        slider.setBlockIncrement(step);
        if (moved && !snapping) {
            double snapped = snap(slider.getValue());
            if (snapped != slider.getValue()) {
                snapping = true;
                try {
                    slider.setValue(snapped);
                } finally {
                    snapping = false;
                }
                show(snapped);
            }
        }
    }

    /** One, two or five of a power of ten, the least no smaller than the raw step. */
    static double niceStep(double raw) {
        if (!(raw > 0) || Double.isInfinite(raw)) {
            return 1;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double mantissa = raw / magnitude;
        double nice = mantissa <= 1 ? 1 : mantissa <= 2 ? 2 : mantissa <= 5 ? 5 : 10;
        return nice * magnitude;
    }

    /** The value at the nearest multiple of the step, within the slider's range, free of binary noise. */
    double snap(double value) {
        double min = slider.getMin();
        double max = slider.getMax();
        double at = Math.round(value / step) * step;
        at = Math.max(min, Math.min(max, at));
        return Double.parseDouble(DecisionFigureText.atStep(at, step).replace(",", ""));
    }

    /** The slider's step; for a test. */
    double step() {
        return step;
    }

    private static TextField field(double value, double base) {
        TextField field = new TextField(TornadoChart.value(value).replace(",", ""));
        field.setFont(Font.font(base * 0.85));
        field.setPrefColumnCount(7);
        // never squeezed below its text, which would lay the card out on every pulse
        field.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return field;
    }

    private void show(double value) {
        current = value;
        write(DecisionFigureText.atStep(value, step));
    }

    private void write(String text) {
        written = text;
        shown.setStyle("");
        shown.setText(text);
    }

    /** Reads the two bound fields and moves the slider's range to them, holding its value within. */
    void rerange() {
        Optional<Bounds> lo = DecisionTreeEdits.typed(lower.getText());
        Optional<Bounds> hi = DecisionTreeEdits.typed(upper.getText());
        if (lo.isEmpty() || hi.isEmpty() || lo.get().lower() >= hi.get().upper()) {
            lower.setStyle("-fx-border-color: #c0392b;");
            upper.setStyle("-fx-border-color: #c0392b;");
            return;
        }
        lower.setStyle("");
        upper.setStyle("");
        double min = lo.get().lower();
        double max = hi.get().upper();
        double value = Math.max(min, Math.min(max, slider.getValue()));
        slider.setMin(min);
        slider.setMax(max);
        slider.setMajorTickUnit(Math.max((max - min) / 4, 1e-9));
        restep();
        slider.setValue(snap(value));
        show(slider.getValue());
    }

    /** The slider's range, as typed or as it began. */
    Bounds range() {
        return Bounds.of(slider.getMin(), slider.getMax());
    }
}
