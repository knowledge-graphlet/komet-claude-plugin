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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fidelity comparison of the two passes, and its parsing of typst's answer, without typst. */
class PageFidelityTest {

    @TempDir
    Path folder;

    @Test
    void parsesTypstQueryLocations() {
        String json = "[{\"page\":1,\"x\":\"72pt\",\"y\":\"108.5pt\"},{\"page\":1,\"x\":\"72pt\",\"y\":\"201pt\"},{\"page\":2,\"x\":\"72pt\",\"y\":\"72pt\"}]";

        List<PageFidelity.Placed> placed = PageFidelity.parse(json);

        assertEquals(List.of(new PageFidelity.Placed(1, 108.5), new PageFidelity.Placed(1, 201), new PageFidelity.Placed(2, 72)), placed);
        assertEquals(List.of(), PageFidelity.parse(""));
    }

    @Test
    void comparesTheGreedyPassWithTypstsLines() {
        Unit heading = new Unit(0, 0, Kind.HEADING, "A heading", 18, true, 0, 0, 9, true);
        Unit prose = new Unit(1, 1, Kind.PARAGRAPH, "one two three four five six", 12, false, 0, 13, 0, false);
        // Greedy: the paragraph as two lines on page 1, breaking after "three"; Typst: breaking after "four", the second line on page 2.
        Layout greedy = new Layout(1, List.of(
                new Line(0, 0, 72, 85, 0, 9, false),
                new Line(1, 0, 72, 110, 0, 13, false),
                new Line(1, 0, 72, 126, 14, 27, false)), false, 2, List.of());
        Layout typst = new Layout(2, List.of(
                new Line(0, 0, 72, 85, 0, 9, false),
                new Line(1, 0, 72, 110, 0, 18, false),
                new Line(1, 1, 72, 81, 19, 27, true)), true, 48, List.of("a note"));

        PageFidelity.Report report = PageFidelity.compare(greedy, typst, List.of(heading, prose));

        assertEquals(1, report.greedyPages());
        assertEquals(2, report.typstPages());
        assertEquals(List.of(), report.greedyBreaks());
        assertEquals(List.of(-1), report.typstBreaks(), "Typst's page 2 starts inside the paragraph");
        assertEquals(2, report.samePage(), "both units start on page 1 in both passes");
        assertEquals(2, report.sameLineCount());
        assertEquals(1, report.sameLines(), "only the heading's line breaks the same way");
        assertEquals(1, report.hyphenated());
        assertEquals(List.of("a note"), report.notes());
        assertTrue(report.text().contains("pages: greedy JavaFX 1, Typst 2"));
        assertTrue(report.text().contains("note: a note"));
    }

    @Test
    void theBundledFontsExtractForTypst() throws Exception {
        Path fonts = DocumentFonts.extractTo(folder.resolve("fonts"));

        assertTrue(Files.isRegularFile(fonts.resolve("NotoSerif-Regular.ttf")));
        assertTrue(Files.isRegularFile(fonts.resolve("NotoSansMono-Regular.ttf")));
        assertTrue(Files.readString(fonts.resolve("OFL.txt")).contains("SIL Open Font License"));
        assertTrue(Files.size(fonts.resolve("NotoSerif-Regular.ttf")) > 100_000);
    }
}
