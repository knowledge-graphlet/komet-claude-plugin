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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import network.ike.komet.claude.stamp.print.TableCells.Cell;
import org.junit.jupiter.api.Test;

/** A table's body reads as Asciidoctor reads it, whatever its source lines. */
class TableCellsTest {

    private static List<List<String>> read(String parameters, String... lines) {
        return TableCells.rows(List.of(lines), parameters).stream()
                .map(row -> row.stream().map(Cell::text).toList()).toList();
    }

    @Test
    void aCellRunsOnAcrossLinesToTheNextBar() {
        assertEquals(List.of(List.of("Source", "The document as lines: one row per line."),
                        List.of("Reading", "One scroll axis.")),
                read("cols=\"1,3\"", "| Source", "| The document as lines:", "one row per line.", "| Reading", "| One scroll axis."));
    }

    @Test
    void rowsAreCutByTheColumnCountNotByLines() {
        assertEquals(List.of(List.of("a", "b"), List.of("c", "d"), List.of("e", "f")),
                read("cols=\"1,1\"", "| a | b | c", "| d", "| e | f"));
    }

    @Test
    void withoutColsTheFirstLineCountsTheColumns() {
        assertEquals(List.of(List.of("a", "b", "c"), List.of("d", "e", "f")),
                read("", "| a | b | c", "| d", "| e", "| f"));
    }

    @Test
    void aBareNumberInColsIsThatManyColumns() {
        assertEquals(List.of(List.of("a", "b", "c")), read("cols=\"3\"", "| a", "| b", "| c"));
    }

    @Test
    void aSpecBeforeABarStaysOutOfTheCellBeforeIt() {
        assertEquals(List.of(List.of("Case 2", "385", "Modest")), read("cols=\"3\"", "| Case 2 | 385 a| Modest"));
        assertEquals(List.of(List.of("x", "y"), List.of("z", "w")), read("cols=\"2\"", "a| x | y", ">.^s| z", "| w"));
    }

    @Test
    void aWordOfTheTextIsNoSpec() {
        assertEquals(List.of(List.of("a", "b")), read("cols=\"2\"", "|a|b"));
        assertEquals(List.of(List.of("see table 2", "b")), read("cols=\"2\"", "| see table 2 | b"));
    }

    @Test
    void anEscapedBarIsText() {
        assertEquals(List.of(List.of("a|b", "c")), read("cols=\"2\"", "| a\\|b | c"));
    }

    @Test
    void aDuplicatedCellFillsItsPlaces() {
        assertEquals(List.of(List.of("x", "x"), List.of("x", "y")), read("cols=\"2\"", "3*| x", "| y"));
    }

    @Test
    void aSpanKeepsTheCellsAfterItInTheirColumns() {
        assertEquals(List.of(List.of("wide", "", "c"), List.of("d", "e", "f")), read("cols=\"3\"", "2+| wide | c", "| d | e | f"));
        assertEquals(List.of(List.of("tall", "b"), List.of("", "c"), List.of("d", "e")), read("cols=\"2\"", ".2+| tall | b", "| c", "| d | e"));
    }

    @Test
    void aShortLastRowKeepsItsCells() {
        assertEquals(List.of(List.of("a", "b"), List.of("c")), read("cols=\"2\"", "| a | b", "| c"));
        assertEquals(List.of(List.of("a", "b")), read("cols=\"2\"", "| a | b |"));
    }

    @Test
    void aTableThatOpensWithoutABarStillReads() {
        assertEquals(List.of(List.of("first line", "b")), read("cols=\"2\"", "first", "line", "| b"));
    }

    @Test
    void anEmptyCellKeepsItsPlace() {
        assertEquals(List.of(List.of("", "b"), List.of("c", "")), read("cols=\"2\"", "| | b", "| c |"));
    }

    @Test
    void theDrawnLinesSpellTheRowAsTheModelDoes() {
        List<List<String>> tables = List.of(
                List.of("| one", "two", "| three | four", "five"),
                List.of("| | b", "| | d"),
                List.of("| a |", "| | d"),
                List.of("| a | b"));
        for (List<String> lines : tables) {
            for (List<Cell> row : TableCells.rows(lines, "cols=\"2\"")) {
                String spelled = String.join(" ", TableCells.drawn(row).stream().map(TableCells.Piece::text).toList());
                assertEquals(String.join(TableCells.BAR, row.stream().map(Cell::text).toList()), spelled);
            }
        }
    }

    @Test
    void aDrawnLineKnowsItsSource() {
        List<TableCells.Piece> drawn = TableCells.drawn(TableCells.rows(List.of("| a", "b | c", "| d"), "cols=\"2\"").getFirst());
        assertEquals(List.of(0, 1), drawn.stream().map(TableCells.Piece::line).toList());
    }
}
