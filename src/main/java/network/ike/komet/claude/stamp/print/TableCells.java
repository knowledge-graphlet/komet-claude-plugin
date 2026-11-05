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
package network.ike.komet.claude.stamp.print;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the body of an AsciiDoc table the way Asciidoctor does. A bar opens a cell, a cell runs on
 * to the next bar across any number of lines, and rows are cut from the cells by the column count:
 * neither a source line nor a blank line ends a row. The count comes from {@code cols}, or else from
 * the cells on the table's first line.
 *
 * <p>The print model has no spanned cells, so a {@code 2+|} or {@code .2+|} cell takes its one place
 * and leaves the places it spans empty, which keeps every later cell in its column.
 */
public final class TableCells {

    /** What stands between two cells of a row in the text a page draws for it. */
    public static final String BAR = "  │  ";

    /** A cell spec: duplication or spans, alignment, style. */
    private static final String CELL_SPEC = "(?:(\\d+)?(?:\\.(\\d+))?([*+]))?[<^>]?(?:\\.[<^>])?[adehlmsv]?$";

    /** A spec that is all of a line before its first bar. */
    private static final Pattern LINE_SPEC = Pattern.compile("^[ \\t]*" + CELL_SPEC);

    /** A spec that ends a cell's text: it stands off from the text by a space, or it is the text. */
    private static final Pattern TEXT_SPEC = Pattern.compile("(?<=[ \\t])" + CELL_SPEC);

    private TableCells() {
    }

    /**
     * A stretch of a cell's text.
     *
     * @param line the index of the source line it comes from
     * @param text its text, stripped, escaped bars plain
     */
    public record Piece(int line, String text) { }

    /**
     * One cell.
     *
     * @param pieces its text, a piece for each source line it reaches; none for a place a span leaves empty
     */
    public record Cell(List<Piece> pieces) {

        /** The empty cell a span leaves. */
        private static final Cell EMPTY = new Cell(List.of());

        /** @return the cell's text, its lines joined by a space */
        public String text() {
            return String.join(" ", pieces.stream().map(Piece::text).filter(t -> !t.isEmpty()).toList());
        }
    }

    /** A cell as written, before it is placed. */
    private static final class Written {
        final List<Piece> pieces = new ArrayList<>();
        final int line;
        final int repeat;
        final int columnSpan;
        final int rowSpan;

        Written(int line, int repeat, int columnSpan, int rowSpan) {
            this.line = line;
            this.repeat = repeat;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
        }

        void add(int at, String text) {
            String t = text.strip().replace("\\|", "|");
            if (!t.isEmpty()) {
                pieces.add(new Piece(at, t));
            }
        }

        /** @return the cell, opening on its line even when empty */
        Cell cell() {
            return new Cell(pieces.isEmpty() ? List.of(new Piece(line, "")) : List.copyOf(pieces));
        }

        /** @return how many columns it fills, its copies counted */
        int places() {
            return repeat * columnSpan;
        }
    }

    /**
     * Reads a table's rows.
     *
     * @param lines      the table's body lines, in order
     * @param parameters the table's block parameters, for {@code cols}
     * @return the rows; a last row left short keeps its cells, unless they are all empty, as a closing bar leaves one
     */
    public static List<List<Cell>> rows(List<String> lines, String parameters) {
        List<Written> written = written(lines);
        int columns = StoredDocumentModel.namedColumns(parameters);
        if (columns == 0 && !written.isEmpty()) {
            int first = written.getFirst().line;
            columns = written.stream().filter(c -> c.line == first).mapToInt(Written::places).sum();
        }
        columns = Math.max(1, columns);
        List<List<Cell>> rows = new ArrayList<>();
        List<Cell> row = new ArrayList<>();
        int[] held = new int[columns];                           // how many more rows a row span keeps each column
        for (Written cell : written) {
            for (int copy = 0; copy < cell.repeat; copy++) {
                while (row.size() < columns && held[row.size()] > 0) {
                    held[row.size()]--;
                    row.add(Cell.EMPTY);
                }
                if (row.size() == columns) {
                    rows.add(row);
                    row = new ArrayList<>();
                    while (row.size() < columns && held[row.size()] > 0) {
                        held[row.size()]--;
                        row.add(Cell.EMPTY);
                    }
                }
                int at = row.size();
                int end = Math.min(columns, at + cell.columnSpan);
                row.add(cell.cell());
                Arrays.fill(held, at, end, cell.rowSpan - 1);
                while (row.size() < end) {
                    row.add(Cell.EMPTY);
                }
                if (row.size() == columns) {
                    rows.add(row);
                    row = new ArrayList<>();
                }
            }
        }
        if (row.stream().anyMatch(cell -> !cell.text().isEmpty())) {
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    /** The cells as written, in order, specs read and taken out of the text they end. */
    private static List<Written> written(List<String> lines) {
        List<Written> cells = new ArrayList<>();
        Written open = null;
        for (int l = 0; l < lines.size(); l++) {
            String text = lines.get(l);
            if (text.isBlank()) {
                continue;
            }
            int at = 0;
            for (int bar = text.indexOf('|'); bar >= 0; bar = text.indexOf('|', bar + 1)) {
                if (bar > 0 && text.charAt(bar - 1) == '\\') {
                    continue;
                }
                String before = text.substring(at, bar);
                Matcher spec = at == 0 ? LINE_SPEC.matcher(before) : TEXT_SPEC.matcher(before);
                boolean found = spec.find();
                if (!found && at == 0) {
                    spec = TEXT_SPEC.matcher(before);
                    found = spec.find();
                }
                if (open != null) {
                    open.add(l, found ? before.substring(0, spec.start()) : before);
                } else if (found ? spec.start() > 0 : !before.isBlank()) {
                    Written lead = new Written(l, 1, 1, 1);      // a table that opens with text, not a bar
                    lead.add(l, found ? before.substring(0, spec.start()) : before);
                    cells.add(lead);
                }
                open = found ? opened(spec, l) : new Written(l, 1, 1, 1);
                cells.add(open);
                at = bar + 1;
            }
            if (open == null) {
                open = new Written(l, 1, 1, 1);                  // a table that opens without a bar
                cells.add(open);
            }
            open.add(l, text.substring(at));
        }
        return cells;
    }

    private static Written opened(Matcher spec, int line) {
        boolean repeats = "*".equals(spec.group(3));
        boolean spans = "+".equals(spec.group(3));
        int first = spec.group(1) == null ? 1 : Math.max(1, Integer.parseInt(spec.group(1)));
        int second = spec.group(2) == null ? 1 : Math.max(1, Integer.parseInt(spec.group(2)));
        return new Written(line, repeats ? first : 1, spans ? first : 1, spans ? second : 1);
    }

    /**
     * A row's text by source line, for a renderer that keeps each line's provenance.
     *
     * @param row a row's cells
     * @return a piece for each source line it reaches, which joined by a space spell the row's
     *         cells joined by {@link #BAR}
     */
    public static List<Piece> drawn(List<Cell> row) {
        List<Piece> lines = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int line = -1;
        for (int c = 0; c < row.size(); c++) {
            if (c > 0) {
                text.append(BAR);
            }
            for (Piece piece : row.get(c).pieces()) {
                if (line >= 0 && piece.line() != line) {
                    lines.add(new Piece(line, flushed(text)));
                    text.setLength(0);
                }
                line = piece.line();
                text.append(piece.text());
            }
        }
        if (line >= 0) {
            lines.add(new Piece(line, text.toString()));
        }
        return List.copyOf(lines);
    }

    /** A line's text, less the space the join to the next line will put back. */
    private static String flushed(StringBuilder text) {
        int end = text.length();
        return end > 0 && text.charAt(end - 1) == ' ' ? text.substring(0, end - 1) : text.toString();
    }

    /** @return the rows' width, the longest row's */
    public static int columns(List<List<Cell>> rows) {
        return rows.stream().mapToInt(List::size).max().orElse(1);
    }
}
