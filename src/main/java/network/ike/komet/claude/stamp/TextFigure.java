/*
 * Copyright © 2025 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.stamp;

import network.ike.komet.claude.stamp.DecisionFigure;

import network.ike.komet.claude.stamp.DecisionFigure.Mark;
import network.ike.komet.claude.stamp.DecisionFigure.Rect;
import network.ike.komet.claude.stamp.DecisionFigure.Stroke;
import network.ike.komet.claude.stamp.DecisionFigure.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A block of words as a figure: a heading, sentences and a table, for a block a page draws from
 * what it says rather than from the control the surface shows. Its width is a page's measure, so
 * it is drawn at its own size; a sentence takes the whole width, and a cell wraps between words.
 */
public final class TextFigure {

    /** The figure's width: about a page's measure at the print theme's size. */
    public static final double WIDTH = 460;
    private static final double SIZE = 10;
    private static final double LINE = DecisionFigureText.lineHeight(SIZE);
    private static final double CELL_GAP = 14;
    private static final double INSET = 6;

    private TextFigure() {
    }

    /**
     * Builds a figure.
     *
     * @param role      what it is, as {@link DecisionFigure#role()} says
     * @param heading   the name it goes by, bold, when it has one
     * @param sentences what it says, each a paragraph
     * @param table     its table, the first row the header, or none
     * @return the figure
     */
    public static DecisionFigure of(String role, Optional<String> heading, List<String> sentences, List<List<String>> table) {
        List<Mark> marks = new ArrayList<>();
        double y = INSET;
        if (heading.isPresent()) {
            for (String line : DecisionFigureText.wrap(heading.get(), SIZE * 1.05, WIDTH - 2 * INSET)) {
                marks.add(new Text(INSET, y, line, SIZE * 1.05, true, false, DecisionFigureText.INK, "name"));
                y += LINE;
            }
            y += 2;
        }
        for (String sentence : sentences) {
            for (String line : DecisionFigureText.wrap(sentence, SIZE, WIDTH - 2 * INSET)) {
                marks.add(new Text(INSET, y, line, SIZE, false, false, DecisionFigureText.INK, "sentence"));
                y += LINE;
            }
            y += 3;
        }
        if (!table.isEmpty()) {
            y = table(marks, table, y + 2);
        }
        marks.addFirst(new Rect(0, 0, WIDTH, y + INSET, "#F7F7F7", 1, null, "ground"));
        return new DecisionFigure(WIDTH, y + INSET, role, marks);
    }

    /**
     * A figure moved down beneath a title of its own.
     *
     * @param title  the title
     * @param figure the figure
     * @return the figure with the title above it
     */
    public static DecisionFigure titled(String title, DecisionFigure figure) {
        List<Mark> marks = new ArrayList<>();
        double y = 0;
        for (String line : DecisionFigureText.wrap(title, SIZE, figure.width())) {
            marks.add(new Text(0, y, line, SIZE, true, false, DecisionFigureText.INK, "title"));
            y += LINE;
        }
        y += 4;
        for (Mark mark : figure.marks()) {
            marks.add(moved(mark, y));
        }
        return new DecisionFigure(figure.width(), figure.height() + y, figure.role(), marks);
    }

    private static Mark moved(Mark mark, double dy) {
        return switch (mark) {
            case Rect r -> new Rect(r.x(), r.y() + dy, r.w(), r.h(), r.fill(), r.opacity(), r.stroke(), r.role());
            case DecisionFigure.Circle c -> new DecisionFigure.Circle(c.cx(), c.cy() + dy, c.r(), c.fill(), c.stroke(), c.role());
            case DecisionFigure.Polygon p -> new DecisionFigure.Polygon(down(p.points(), dy), p.fill(), p.stroke(), p.role());
            case DecisionFigure.Polyline p -> new DecisionFigure.Polyline(down(p.points(), dy), p.stroke(), p.role());
            case Text t -> new Text(t.x(), t.top() + dy, t.text(), t.size(), t.bold(), t.italic(), t.colour(), t.role());
        };
    }

    private static double[] down(double[] points, double dy) {
        double[] moved = points.clone();
        for (int i = 1; i < moved.length; i += 2) {
            moved[i] += dy;
        }
        return moved;
    }

    private static double table(List<Mark> marks, List<List<String>> table, double top) {
        double[] widths = widths(table);
        double y = top;
        for (int r = 0; r < table.size(); r++) {
            List<String> row = table.get(r);
            boolean header = r == 0;
            double x = INSET;
            int lines = 1;
            for (int c = 0; c < row.size(); c++) {
                List<String> wrapped = DecisionFigureText.wrap(row.get(c), SIZE, widths[c]);
                lines = Math.max(lines, wrapped.size());
                double at = y;
                for (String line : wrapped) {
                    marks.add(new Text(x, at, line, SIZE, header, false,
                            header ? DecisionFigureText.CAPTION : DecisionFigureText.INK, header ? "table-head" : "table"));
                    at += LINE;
                }
                x += widths[c] + CELL_GAP;
            }
            y += lines * LINE + 2;
            if (header) {
                marks.add(new Rect(INSET, y - 1, WIDTH - 2 * INSET, 0.5, DecisionFigureText.CAPTION, 1, (Stroke) null, "table-rule"));
                y += 2;
            }
        }
        return y;
    }

    /** Each column's width: its natural one, the widest given up first and no column narrower than its widest word. */
    private static double[] widths(List<List<String>> table) {
        int columns = table.stream().mapToInt(List::size).max().orElse(0);
        double[] natural = new double[columns];
        double[] least = new double[columns];
        for (List<String> row : table) {
            for (int c = 0; c < row.size(); c++) {
                natural[c] = Math.max(natural[c], DecisionFigureText.width(row.get(c), SIZE, true));
                for (String word : row.get(c).split("\\s+")) {
                    least[c] = Math.max(least[c], DecisionFigureText.width(word, SIZE, true));
                }
            }
        }
        double room = WIDTH - 2 * INSET - (columns - 1) * CELL_GAP;
        double[] widths = natural.clone();
        double over = Arrays.stream(widths).sum() - room;
        while (over > 0.5) {
            int widest = 0;
            for (int c = 1; c < columns; c++) {
                if (widths[c] - least[c] > widths[widest] - least[widest]) {
                    widest = c;
                }
            }
            double cut = Math.min(over, Math.max(1, (widths[widest] - least[widest]) / 2));
            if (widths[widest] <= least[widest]) {
                break;
            }
            widths[widest] = Math.max(least[widest], widths[widest] - cut);
            over -= cut;
        }
        return widths;
    }
}
