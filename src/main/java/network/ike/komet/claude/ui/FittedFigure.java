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
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;
import network.ike.komet.document.surface.DocumentSurface;

import java.util.function.DoubleConsumer;

/**
 * A figure that fits its region: drawn at its natural size when that fits, scaled down
 * uniformly when it is wider, and never scrolled sideways, since a surface has one scroll axis
 * (ike-lab-documents {@code dev-interaction-surface-family}, "Scroll discipline";
 * {@code dev-decision-document}). In a block of a document with margins a wider figure first
 * stands out into them, the grid's {@code full-bleed}, and only then scales. A figure says when it
 * is {@linkplain #reducedProperty() reduced}, so the block it is in can offer to expand it; the
 * figure has no mark of its own for that ({@code dev-grow-model-review}).
 *
 * <p>The figure is told each width it is given, once laid out, so it may first draw itself more
 * compactly before it is scaled.
 */
final class FittedFigure extends Region {
    private final Region figure;
    private final Scale scale = new Scale(1, 1, 0, 0);
    private final DoubleConsumer widthGiven;
    /** The narrowest the figure reflows to before it scales; a figure that does not reflow has its natural width. */
    private final double least;
    /** Whether the figure is drawn smaller than full size, or stands in for a fuller one. */
    private final ReadOnlyBooleanWrapper reduced = new ReadOnlyBooleanWrapper(false);
    /** Whether the figure stands in for a fuller one, so that it is reduced at any scale. */
    private boolean standsIn;
    private double toldWidth = -1;

    /**
     * @param figure     the figure, at its natural size
     * @param widthGiven told each new width the figure is given, after layout
     */
    FittedFigure(Region figure, DoubleConsumer widthGiven) {
        this(figure, widthGiven, Double.MAX_VALUE);
    }

    /**
     * A figure that lays itself out at any width down to a least one, as a chart or a table does,
     * and is scaled only below that.
     *
     * @param figure the figure
     * @param least  the narrowest it is laid out, in points
     */
    FittedFigure(Region figure, double least) {
        this(figure, width -> { }, least);
    }

    private FittedFigure(Region figure, DoubleConsumer widthGiven, double least) {
        this.figure = figure;
        this.widthGiven = widthGiven;
        this.least = least;
        figure.getTransforms().add(scale);
        getChildren().add(figure);
        setMinWidth(0);
    }

    /**
     * Says that the figure stands in for a fuller one, as a tree's outline does for the tree drawn
     * left to right: it is {@linkplain #reducedProperty() reduced} whether or not it is scaled.
     */
    void standsInForFuller() {
        standsIn = true;
        reduced.set(true);
    }

    /**
     * Whether the figure is drawn smaller than full size or stands in for a fuller one: a reader
     * of it gains from seeing it expanded.
     *
     * @return the property, set as the figure is laid out
     */
    ReadOnlyBooleanProperty reducedProperty() {
        return reduced.getReadOnlyProperty();
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

    /** The width the figure is laid out at for a region's: what there is, within its least and natural widths. */
    private double laidOut(double width) {
        double natural = natural();
        double floor = Math.min(Math.max(least, figure.minWidth(-1)), natural);
        return width > 0 ? Math.max(Math.min(natural, available(width)), floor) : natural;
    }

    /** The figure's width at a region's: the region's, and what it may take from the margins beside it. */
    private double available(double width) {
        Insets bleed = DocumentSurface.bleedBeside(this, width);
        return width + bleed.getLeft() + bleed.getRight();
    }

    private double scaleAt(double width) {
        return width > 0 ? Math.min(1, available(width) / laidOut(width)) : 1;
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
        return figure.prefHeight(laidOut(width)) * scaleAt(width);
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double natural = laidOut(width);
        double s = scaleAt(width);
        Insets bleed = DocumentSurface.bleedBeside(this, width);
        // a figure that fits stays with the column; a wider one stands into the margins evenly
        double over = natural * s - width;
        double x = over > 0 ? -Math.min(bleed.getLeft(), over / 2) : 0;
        figure.resizeRelocate(x, 0, natural, figure.prefHeight(natural));
        scale.setX(s);
        scale.setY(s);
        reduced.set(s < 0.999 || standsIn);
        double given = available(width);
        if (width > 0 && Math.abs(given - toldWidth) > 0.5) {
            toldWidth = given;
            Platform.runLater(() -> widthGiven.accept(given));
        }
    }
}
