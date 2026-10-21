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

import network.ike.komet.claude.stamp.print.LayoutOracle.Kind;
import network.ike.komet.claude.stamp.print.LayoutOracle.Layout;
import network.ike.komet.claude.stamp.print.LayoutOracle.Line;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Golden layouts round-trip through their text form and pin what they were made from. */
class GoldenLayoutTest {

    @Test
    void aGoldenRoundTrips() {
        Layout layout = new Layout(2, List.of(new Line(0, 0, 72, 85.5, 0, 9, false), new Line(1, 1, 84.25, 101.56, 10, 22, true)), true, 0, List.of());
        GoldenLayout golden = new GoldenLayout("0.15.1", "abc", "def", 2, layout);

        GoldenLayout back = GoldenLayout.read(golden.write());

        assertEquals("0.15.1", back.typstVersion());
        assertEquals("abc", back.sourceHash());
        assertEquals("def", back.unitsHash());
        assertEquals(2, back.units());
        assertEquals(2, back.layout().pages());
        assertEquals(layout.lines(), back.layout().lines());
        assertTrue(golden.write().startsWith("typst: 0.15.1\n"));
    }

    @Test
    void theHashesChangeWithTheSourceAndTheUnits() {
        Unit a = new Unit(0, 0, Kind.PARAGRAPH, "one", 12, false, 0, 0, 0, false);
        Unit b = new Unit(0, 0, Kind.PARAGRAPH, "two", 12, false, 0, 0, 0, false);

        assertEquals(64, GoldenLayout.sha256("x").length());
        assertNotEquals(GoldenLayout.sha256("x"), GoldenLayout.sha256("y"));
        assertNotEquals(GoldenLayout.unitsHash(List.of(a)), GoldenLayout.unitsHash(List.of(b)));
        assertEquals(GoldenLayout.unitsHash(List.of(a)), GoldenLayout.unitsHash(List.of(a)));
    }

    @Test
    void thePinnedSourceCarriesBlockLabelsButNoWordLabels() {
        DocumentModel model = new DocumentModel("T", List.of(new DocumentModel.Paragraph("Some words here.")));

        String pinned = GoldenLayout.pinnedSource(model, TypstWriter.PageSetup.DEFAULT);

        assertTrue(pinned.contains("<" + TypstWriter.BLOCK_LABEL + ">"));
        assertTrue(!pinned.contains(TypstOracle.WORD_RULE));
        assertTrue(!pinned.contains("#h(0pt)"), "chunking belongs to the word-labelled form only");
    }
}
