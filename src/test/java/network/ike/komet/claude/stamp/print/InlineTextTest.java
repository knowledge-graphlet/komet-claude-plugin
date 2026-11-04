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

import network.ike.komet.claude.stamp.print.InlineText.Run;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prose as a page prints it: the markup gone, the spans over the drawn text. */
class InlineTextTest {

    private static Run run(int from, int to, boolean mono, boolean bold, boolean italic) {
        return new Run(from, to, mono, bold, italic, null);
    }

    @Test
    void theMarkupGoesAndTheSpansStay() {
        InlineText read = InlineText.of("The `measurementBasis` is *firm*, _for now_.");
        assertEquals("The measurementBasis is firm, for now.", read.text());
        assertEquals(List.of(run(4, 20, true, false, false), run(24, 28, false, true, false), run(30, 37, false, false, true)), read.runs());
    }

    @Test
    void nestedSpansCompound() {
        assertEquals(List.of(run(0, 4, false, true, true)), InlineText.of("*_both_*").runs());
        assertEquals(List.of(run(0, 4, true, true, false)), InlineText.of("*`code`*").runs());
    }

    @Test
    void whatIsNotMarkupStaysText() {
        for (String text : List.of("a 2 * 3 * 4 sum", "snake_case_name", "an {attribute} left as written", "it's `unclosed")) {
            assertEquals(text, InlineText.of(text).text());
        }
        assertEquals("*not bold*", InlineText.of("\\*not bold*").text());
        assertTrue(InlineText.of("\\*not bold*").runs().isEmpty());
    }

    @Test
    void aReferencePrintsItsTextAndABareOneItsTargetsWords() {
        assertEquals("See the types and anf data types.", InlineText.of("See xref:anf-data-types[the types] and <<anf-data-types>>.").text());
        assertEquals("See kinds of data.", InlineText.of("See <<kinds-of-data.adoc#instance>>.").text());
    }

    @Test
    void aLinkKeepsItsTarget() {
        InlineText read = InlineText.of("At https://ike.network/a[the site].");
        assertEquals("At the site.", read.text());
        assertEquals("https://ike.network/a", read.runs().getFirst().href());
    }

    @Test
    void quotationsCurveAndAnchorsAndConcealedTermsPrintNothing() {
        assertEquals("A “quoted” word.", InlineText.of("A \"`quoted`\" word.").text());
        assertEquals("Here and there.", InlineText.of("Here [[mark]]and (((concealed)))there.").text());
        assertEquals("A visible term.", InlineText.of("A ((visible)) term.").text());
    }

    @Test
    void aFootnoteLeavesItsMarkTightAgainstTheWordBefore() {
        InlineText read = InlineText.of("Said so. footnote:[With `code` in it.] Then more.");
        assertEquals("Said so.¹ Then more.", read.text());
        InlineText.Note note = read.notes().getFirst();
        assertEquals(1, note.number());
        assertEquals("With code in it.", note.body().text());
        assertEquals(List.of(run(5, 9, true, false, false)), note.body().runs());
        assertEquals("¹With code in it.", note.drawn().text());
        assertEquals(List.of(run(6, 10, true, false, false)), note.drawn().runs(), "the span moves with the mark before it");
        assertEquals(new InlineText.Mark(8, 1), read.marks(0).getFirst());
    }

    @Test
    void aDocumentCountsItsNotesAcrossItsProse() {
        InlineText.Numbering numbering = new InlineText.Numbering();
        assertEquals("One.¹", InlineText.of("One. footnote:[A.]", numbering).text());
        assertEquals("Two.²", InlineText.of("Two. footnote:[B.]", numbering).text());
        assertEquals("⁴²", InlineText.superscript(42));
    }

    @Test
    void aNamedNoteIsRepeatedByItsName() {
        InlineText read = InlineText.of("One. footnote:shared[The note.] Two. footnote:shared[]");
        assertEquals("One.¹ Two.¹", read.text());
        assertFalse(read.notes().getFirst().repeat());
        assertTrue(read.notes().getLast().repeat());
        assertEquals("shared", read.notes().getLast().id());
    }

    @Test
    void aNameNeverGivenIsANoteOfItsOwn() {
        assertFalse(InlineText.of("One. footnote:lost[]").notes().getFirst().repeat());
    }

    @Test
    void sourceOffsetsLandOnTheDrawnText() {
        InlineText read = InlineText.of("a *bold* b");
        assertEquals(0, read.drawnAt(0));
        assertEquals(2, read.drawnAt(2), "the opening mark draws nothing");
        assertEquals(2, read.drawnAt(3));
        assertEquals(6, read.drawnAt(8));
        assertEquals(8, read.drawnAt(10));
    }

    @Test
    void aSpanCrossesTheLinesOfAParagraph() {
        InlineText read = InlineText.of("one *two\nthree* four");
        assertEquals("one two\nthree four", read.text());
        assertEquals(List.of(run(4, 13, false, true, false)), read.runs());
        assertEquals(7, read.drawnAt(8), "the first line ends where its span pauses");
    }

    @Test
    void aHardBreakStaysAsItsMark() {
        assertEquals("one +\ntwo", InlineText.of("one +\ntwo").text());
    }

    @Test
    void literalTextIsNotRead() {
        InlineText read = InlineText.literal("`kept` *as is*");
        assertEquals("`kept` *as is*", read.text());
        assertTrue(read.runs().isEmpty());
    }

    @Test
    void plainTextHasNoRunAtAnyOffset() {
        LayoutOracle.Unit unit = new LayoutOracle.Unit(0, 0, LayoutOracle.Kind.PARAGRAPH, "plain", 12, false, 0, 0, 0, false);
        assertNull(unit.runAt(2));
        assertFalse(unit.monoAt(2));
    }
}
