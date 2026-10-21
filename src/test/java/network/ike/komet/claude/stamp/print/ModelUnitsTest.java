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

import network.ike.komet.claude.stamp.print.DocumentModel.Heading;
import network.ike.komet.claude.stamp.print.DocumentModel.Item;
import network.ike.komet.claude.stamp.print.DocumentModel.ListBlock;
import network.ike.komet.claude.stamp.print.DocumentModel.Paragraph;
import network.ike.komet.claude.stamp.print.DocumentModel.Table;
import network.ike.komet.claude.stamp.print.DocumentModel.Verbatim;
import network.ike.komet.claude.stamp.print.LayoutOracle.Kind;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Units from a print model: numbered as the writer labels blocks, with Typst's markers and spacing. */
class ModelUnitsTest {

    @Test
    void theTitleAndEveryTopLevelBlockAreNumberedAsTheWriterLabelsThem() {
        DocumentModel model = new DocumentModel("Title", List.of(
                new Heading(1, "Section"),
                new Paragraph("Prose here."),
                new ListBlock(ListBlock.Kind.UNORDERED, "", List.of(
                        new Item("", "first", List.of(new ListBlock(ListBlock.Kind.UNORDERED, "", List.of(new Item("", "nested", List.of()))))),
                        new Item("", "second", List.of()))),
                new Table("Caption", List.of(1, 1), 1, List.of(List.of("A", "B"), List.of("c", "d"))),
                new Verbatim("", "raw one\nraw two")));

        List<Unit> units = ModelUnits.of(model, 12);

        assertEquals(List.of(0, 1, 2, 3, 3, 3, 4, 4, 4, 5), units.stream().map(Unit::block).toList());
        assertEquals(Kind.TITLE, units.get(0).kind());
        assertEquals(21.6, units.get(0).fontSize(), 0.001);
        assertEquals(Kind.HEADING, units.get(1).kind());
        assertEquals(18, units.get(1).fontSize(), 0.001);
        assertTrue(units.get(1).keepWithNext());
        assertEquals("• first", units.get(3).text());
        assertEquals("‣ nested", units.get(4).text(), "a nested item carries Typst's second-level marker");
        assertEquals(24, units.get(4).indent(), 0.001);
        assertEquals("• second", units.get(5).text());
        assertEquals(Kind.OTHER, units.get(6).kind());
        assertEquals("Caption", units.get(6).text());
        assertEquals("A  │  B", units.get(7).text());
        assertTrue(units.get(7).bold(), "a header row is bold");
        assertEquals(Kind.VERBATIM, units.get(9).kind());
        assertEquals(10.2, units.get(9).fontSize(), 0.001);
        assertEquals("raw one\nraw two", units.get(9).text());
        assertEquals(ModelUnits.bullet(0), ModelUnits.bullet(3));
    }

    @Test
    void spacingFollowsTypstsRules() {
        Unit title = new Unit(0, 0, Kind.TITLE, "T", 21.6, true, 0, 0, 14.4, false);
        Unit heading = new Unit(1, 1, Kind.HEADING, "H", 18, true, 0, 16.2, 9, true);
        Unit prose = new Unit(2, 2, Kind.PARAGRAPH, "p", 12, false, 0, 13.2, 0, false);
        Unit item = new Unit(3, 3, Kind.LIST_ITEM, "i", 12, false, 12, 7.8, 0, false);
        Unit raw = new Unit(4, 4, Kind.VERBATIM, "r", 10.2, false, 0, 13.2, 0, false);

        assertEquals(14.4, UnitSpacing.gap(title, prose), 0.001, "the title's gap is 1.2em of the body");
        assertEquals(9, UnitSpacing.gap(heading, prose), 0.001, "a heading's weak spacing replaces the paragraph gap");
        assertEquals(16.2, UnitSpacing.gap(prose, heading), 0.001);
        assertEquals(13.2, UnitSpacing.gap(prose, prose), 0.001);
        assertEquals(7.8, UnitSpacing.gap(item, item), 0.001, "tight items a leading apart");
        assertEquals(13.2 + UnitSpacing.VERBATIM_INSET, UnitSpacing.gap(prose, raw), 0.001);
        assertEquals(13.2 + UnitSpacing.VERBATIM_INSET, UnitSpacing.gap(raw, prose), 0.001);
        assertEquals(UnitSpacing.VERBATIM_PITCH * 10.2, UnitSpacing.pitch(raw), 0.001);
        assertEquals(LayoutOracle.PITCH * 12, UnitSpacing.pitch(prose), 0.001);
        assertEquals(0.65 * 12, UnitSpacing.of(Kind.LIST_ITEM, Kind.LIST_ITEM, 12, 12)[0], 0.001);
        assertEquals(1.1 * 12 + UnitSpacing.CELL_INSET - 0.4, UnitSpacing.of(Kind.OTHER, Kind.PARAGRAPH, 12, 12, true)[1], 0.001);
    }
}
