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

import network.ike.komet.claude.stamp.print.LayoutOracle.Geometry;
import network.ike.komet.claude.stamp.print.LayoutOracle.Kind;
import network.ike.komet.claude.stamp.print.LayoutOracle.Layout;
import network.ike.komet.claude.stamp.print.LayoutOracle.Line;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Inline spans through typst itself, no JavaFX: inline code prints in the mono font at the raw
 * size, a span breaks Typst's labels without losing the alignment with the drawn text, and a
 * note's words are read as the note's, at the foot of its page, whatever they interrupt.
 */
class InlinePrintIT {

    private static final Geometry LETTER = new Geometry(612, 792, 72, 72, 468, 648);
    /** Noto Sans Mono's advance, in ems. */
    private static final double MONO_ADVANCE = 0.6;
    private static final String FILLER = "Lease daemon synced record the fence reads the record and the holder renews it again. ";

    private static List<TypstOracle.Item> items(DocumentModel model, Path folder) throws Exception {
        assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        Path source = folder.resolve("inline.typ");
        Files.writeString(source, TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT, TypstWriter.Labels.WORDS), StandardCharsets.UTF_8);
        TypstCompiler.Result result = TypstCompiler.evaluate(folder, source, TypstOracle.QUERY, DocumentFonts.extractTo(folder.resolve("fonts")));
        assertTrue(result.ok(), result.output());
        return TypstOracle.parse(result.output());
    }

    private static Layout layout(DocumentModel model, Path folder, List<Unit> units) throws Exception {
        LayoutOracle.Measure width = (text, unit, mono, bold, italic) -> text.length() * (mono ? MONO_ADVANCE * TypstWriter.RAW_SCALE : 0.5) * unit.fontSize();
        return TypstOracle.align(items(model, folder), units, LETTER, width);
    }

    @Test
    void inlineCodePrintsMonoAtTheRawSize(@TempDir Path folder) throws Exception {
        DocumentModel model = new DocumentModel("", List.of(new DocumentModel.Paragraph("The `measurementBasis` field.")));
        List<TypstOracle.Item> items = items(model, folder);
        for (int i = 0; i < items.size(); i++) {
            if ("measurementBasis".equals(items.get(i).value())) {
                double width = items.get(i + 1).x() - items.get(i).x();
                assertEquals("measurementBasis".length() * MONO_ADVANCE * 12 * TypstWriter.RAW_SCALE, width, 0.1, "16 advances at the raw size");
                return;
            }
        }
        throw new AssertionError("no word 'measurementBasis' among typst's labels: " + items);
    }

    @Test
    void wordsAcrossSpansAlignWithTheDrawnText(@TempDir Path folder) throws Exception {
        DocumentModel model = new DocumentModel("", List.of(new DocumentModel.Paragraph(
                FILLER.repeat(2) + "A `raw`-joined word, foo*bold*baz and *strong* _words_ here. " + FILLER.repeat(2))));
        List<Unit> units = ModelUnits.of(model, 12);
        assertFalse(units.getFirst().text().contains("`"));
        Layout layout = layout(model, folder, units);
        assertTrue(layout.notes().stream().noneMatch(n -> n.contains("not placed")), layout.notes().toString());
        List<Line> lines = layout.ofUnit(0);
        assertEquals(units.getFirst().text().strip().length(), lines.getLast().to());
        for (int k = 1; k < lines.size(); k++) {
            assertTrue(lines.get(k).from() >= lines.get(k - 1).to(), "lines stay in order: " + lines);
        }
    }

    @Test
    void aNotesWordsAreTheNotesAndStandAtThePagesFoot(@TempDir Path folder) throws Exception {
        List<DocumentModel.Block> blocks = new ArrayList<>();
        blocks.add(new DocumentModel.Paragraph(FILLER.repeat(3) + "Said so. footnote:[With `code` in it.] " + FILLER.repeat(2)));
        blocks.add(new DocumentModel.Paragraph("Next. footnote:[" + FILLER.repeat(3) + "] The end."));
        DocumentModel model = new DocumentModel("", blocks);
        List<Unit> units = ModelUnits.of(model, 12);
        assertEquals(List.of(Kind.PARAGRAPH, Kind.FOOTNOTE, Kind.PARAGRAPH, Kind.FOOTNOTE), units.stream().map(Unit::kind).toList());
        Layout layout = layout(model, folder, units);
        assertTrue(layout.notes().stream().noneMatch(n -> n.contains("not placed")), layout.notes().toString());

        Unit body = units.getFirst();
        Line first = layout.ofUnit(1).getFirst();
        assertEquals("¹With code in it.", first.text(units.get(1)));
        assertTrue(first.x() > LETTER.contentLeft() + 5, "a note's first line is indented");
        Line last = layout.ofUnit(3).getLast();
        assertEquals(LETTER.contentBottom(), last.baseline(), 1, "the last note's last baseline stands on the content's bottom edge");
        double bodyEnd = layout.ofUnit(2).getLast().baseline();
        assertTrue(first.baseline() > bodyEnd, "the notes stand under the text");
        assertTrue(layout.ofUnit(3).size() > 1, "a long note wraps");
        assertEquals(body.text().strip().length(), layout.ofUnit(0).getLast().to(), "the words after a mark are the block's");
    }
}
