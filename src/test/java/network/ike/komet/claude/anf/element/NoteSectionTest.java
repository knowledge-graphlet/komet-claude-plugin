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
package network.ike.komet.claude.anf.element;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A note's sections, the top level of the lift region's element tree. */
class NoteSectionTest {

    private static List<String> titles(String note) {
        return NoteSection.of(note).stream().map(NoteSection::title).toList();
    }

    @Test
    void headingsTitleTheTextUpToTheNextHeading() {
        String note = "Vitals\n\nBP 120/80 sitting. Pulse 72.\n\nTemperature 37.1.\n\nAssessment\n\nWell controlled hypertension.";
        List<NoteSection> sections = NoteSection.of(note);
        assertEquals(List.of("Vitals", "Assessment"), sections.stream().map(NoteSection::title).toList());
        assertTrue(sections.get(0).holds(note.indexOf("Temperature")), "a heading's section runs to the next heading");
        assertTrue(sections.get(1).holds(note.indexOf("hypertension")));
        assertEquals(note.length(), sections.get(1).range().end());
    }

    @Test
    void aShortFirstLineEndingInAColonIsAHeading() {
        assertEquals(List.of("History", "Plan"),
                titles("History:\nCough for three days.\n\nPlan:\nRest and fluids."));
    }

    @Test
    void aNoteWithoutHeadingsIsSectionedByItsParagraphsTitledByTheirFirstWords() {
        assertEquals(List.of("A 62-year-old man presented to his…", "He was asked to return."),
                titles("A 62-year-old man presented to his doctor's office.\n\nHe was asked to return."));
    }

    @Test
    void textBeforeTheFirstHeadingIsASectionOfItsOwn() {
        assertEquals(List.of("Seen today.", "Plan"), titles("Seen today.\n\nPlan\n\nReturn in a week."));
    }

    @Test
    void anEmptyNoteHasNoSections() {
        assertEquals(List.of(), NoteSection.of(""));
    }
}
