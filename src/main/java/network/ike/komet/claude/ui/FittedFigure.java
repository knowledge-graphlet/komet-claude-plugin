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

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.transform.Scale;

import java.util.function.DoubleConsumer;

/**
 * A figure that fits its region: drawn at its natural size when that fits, scaled down
 * uniformly when it is wider, and never scrolled sideways, since a surface has one scroll axis
 * (ike-lab-documents {@code dev-interaction-surface-family}, "Scroll discipline";
 * {@code dev-decision-document}). A scaled figure shows a mark at its top right that opens it
 * whole, the full-surface escape.
 *
 * <p>The figure is told each width it is given, once laid out, so it may first draw itself more
 * compactly before it is scaled.
 */
final class FittedFigure extends Region {
    private final Region figure;
    private final Scale scale = new Scale(1, 1, 0, 0);
    private final Label expand;
    private final DoubleConsumer widthGiven;
    private double toldWidth = -1;

    /**
     * @param figure     the figure, at its natural size
     * @param base       the surface's base font size
     * @param widthGiven told each new width the figure is given, after layout
     * @param escape     opens the figure whole
     */
    FittedFigure(Region figure, double base, DoubleConsumer widthGiven, Runnable escape) {
        this.figure = figure;
        this.widthGiven = widthGiven;
        figure.getTransforms().add(scale);
        expand = new Label("⤢");
        expand.setId("decision-tree-expand");
        expand.setFont(Font.font(base * 1.1));
        expand.setStyle("-fx-text-fill: #1f5fa8; -fx-background-color: rgba(255,255,255,0.85); -fx-padding: 0 4 0 4;");
        expand.setCursor(Cursor.HAND);
        expand.setTooltip(new Tooltip("Open the whole figure, at full size"));
        expand.setOnMouseClicked(e -> {
            escape.run();
            e.consume();
        });
        expand.setVisible(false);
        getChildren().addAll(figure, expand);
        setMinWidth(0);
    }

    /** How much the figure is scaled down to fit, 1 when it is drawn at its natural size. */
    double scale() {
        return scale.getX();
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    private double natural() {
        return Math.max(1, figure.prefWidth(-1));
    }

    private double scaleAt(double width) {
        return width > 0 ? Math.min(1, width / natural()) : 1;
    }

    @Override
    protected double computePrefWidth(double height) {
        return natural();
    }

    @Override
    protected double computeMinWidth(double height) {
        return 0;
    }

    @Override
    protected double computePrefHeight(double width) {
        return figure.prefHeight(natural()) * scaleAt(width);
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double natural = natural();
        double s = scaleAt(width);
        figure.resizeRelocate(0, 0, natural, figure.prefHeight(natural));
        scale.setX(s);
        scale.setY(s);
        expand.setVisible(s < 0.999);
        double w = expand.prefWidth(-1);
        expand.resizeRelocate(Math.max(0, width - w), 0, w, expand.prefHeight(w));
        if (width > 0 && Math.abs(width - toldWidth) > 0.5) {
            toldWidth = width;
            Platform.runLater(() -> widthGiven.accept(width));
        }
    }
}
