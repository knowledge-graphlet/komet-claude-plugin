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

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * A row of pieces, each kept whole. On one line a row short of room shortens a piece to a few
 * letters, and the piece it shortens is the one that says most. Here the pieces that do not fit
 * go to a line beneath, so the row grows taller where its region is narrow. A piece wider than
 * the whole row is given the row's width, and wraps inside it when it can.
 *
 * <p>A lead, a fold mark for one, stands at the start of the first line. The lines beneath begin
 * past it, under the first piece, so the mark stays alone in its column.
 */
final class WrappedRow extends Pane {

    private final double gap;
    private final double lineGap;
    private Node lead;

    /**
     * @param gap     the space between two pieces on a line
     * @param lineGap the space between two lines
     */
    WrappedRow(double gap, double lineGap) {
        this.gap = gap;
        this.lineGap = lineGap;
        setMinWidth(0);
    }

    /**
     * Sets what the row shows.
     *
     * @param lead   what stands at the start of the first line, or {@code null} for nothing
     * @param pieces the pieces, in reading order
     */
    void show(Node lead, List<? extends Node> pieces) {
        this.lead = lead;
        List<Node> all = new ArrayList<>();
        if (lead != null) {
            all.add(lead);
        }
        all.addAll(pieces);
        getChildren().setAll(all);
    }

    /** How many lines the row took when it was last laid out. */
    int lines() {
        return lines(getWidth()).size();
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computeMinWidth(double height) {
        return 0;
    }

    /** The width of the row on one line. */
    @Override
    protected double computePrefWidth(double height) {
        Insets insets = getInsets();
        double width = insets.getLeft() + insets.getRight() + indent();
        boolean first = true;
        for (Node piece : pieces()) {
            width += (first ? 0 : gap) + snapSizeX(piece.prefWidth(-1));
            first = false;
        }
        return width;
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets insets = getInsets();
        double height = 0;
        List<Line> lines = lines(width);
        for (Line line : lines) {
            height += line.height;
        }
        height += lineGap * Math.max(0, lines.size() - 1);
        if (lead != null && lead.isManaged()) {
            height = Math.max(height, snapSizeY(lead.prefHeight(-1)));
        }
        return insets.getTop() + height + insets.getBottom();
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        Insets insets = getInsets();
        List<Line> lines = lines(getWidth());
        double left = insets.getLeft() + indent();
        double top = insets.getTop();
        boolean first = true;
        for (Line line : lines) {
            if (first && lead != null && lead.isManaged()) {
                double height = snapSizeY(lead.prefHeight(-1));
                lead.resizeRelocate(insets.getLeft(), snapPositionY(top + (line.height - height) / 2),
                        snapSizeX(lead.prefWidth(-1)), height);
            }
            first = false;
            double x = left;
            for (int i = 0; i < line.pieces.size(); i++) {
                double width = line.widths.get(i);
                double height = line.heights.get(i);
                line.pieces.get(i).resizeRelocate(snapPositionX(x), snapPositionY(top + (line.height - height) / 2), width, height);
                x += width + gap;
            }
            top += line.height + lineGap;
        }
        if (lines.isEmpty() && lead != null && lead.isManaged()) {
            lead.resizeRelocate(insets.getLeft(), insets.getTop(), snapSizeX(lead.prefWidth(-1)), snapSizeY(lead.prefHeight(-1)));
        }
    }

    /** The pieces a line takes, each with the size it is given. */
    private static final class Line {
        final List<Node> pieces = new ArrayList<>();
        final List<Double> widths = new ArrayList<>();
        final List<Double> heights = new ArrayList<>();
        double width;
        double height;
    }

    /** The lines the pieces take in a width; one line when no width is given yet. */
    private List<Line> lines(double width) {
        Insets insets = getInsets();
        double room = width > 0 ? Math.max(1, width - insets.getLeft() - insets.getRight() - indent()) : Double.MAX_VALUE;
        List<Line> lines = new ArrayList<>();
        Line line = null;
        for (Node piece : pieces()) {
            double w = Math.min(snapSizeX(piece.prefWidth(-1)), room);
            double h = snapSizeY(piece.getContentBias() == Orientation.HORIZONTAL ? piece.prefHeight(w) : piece.prefHeight(-1));
            if (line == null || line.width + gap + w > room) {
                line = new Line();
                lines.add(line);
            } else {
                line.width += gap;
            }
            line.pieces.add(piece);
            line.widths.add(w);
            line.heights.add(h);
            line.width += w;
            line.height = Math.max(line.height, h);
        }
        return lines;
    }

    /** How far the pieces stand in from the row's start: the lead and the space after it. */
    private double indent() {
        return lead != null && lead.isManaged() ? snapSizeX(lead.prefWidth(-1)) + gap : 0;
    }

    private List<Node> pieces() {
        List<Node> pieces = new ArrayList<>();
        for (Node child : getChildren()) {
            if (child != lead && child.isManaged()) {
                pieces.add(child);
            }
        }
        return pieces;
    }
}
