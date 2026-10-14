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
package network.ike.komet.claude.doc;

import dev.ikm.tinkar.terms.EntityProxy.Concept;
import network.ike.komet.claude.doc.MarkdownStructure.Child;
import network.ike.komet.claude.doc.MarkdownStructure.Element;
import network.ike.komet.claude.doc.MarkdownStructure.Line;
import network.ike.komet.claude.doc.MarkdownStructure.Member;
import network.ike.komet.claude.doc.MarkdownStructure.Turn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks how a turn's Markdown splits into the shared block model: every line belongs to exactly one
 * element, and flattening the elements gives the Markdown back unchanged.
 */
class MarkdownStructureTest {

    @Test
    void everyLineBelongsToExactlyOneElementAndTheTurnReadsBack() {
        for (String markdown : List.of("", "one line", "trailing newline\n", "a\n\n\nb",
                "# Title\n\nPara one\nstill one\n\n## Sub\n\n- x\n- y\n\n  continued\n\n# Next\ntext",
                "```\nunclosed fence\nstill code", "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |",
                "> quote\n> more\n\n***\n\n<div>html</div>", "    indented code\n\nafter", "\n\n")) {
            Turn turn = MarkdownStructure.parse(markdown);
            List<Integer> owned = new ArrayList<>();
            collect(turn.turn(), owned);
            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < turn.lines().size(); i++) {
                expected.add(i);
            }
            assertEquals(expected, owned, "each line once, in order: " + markdown.replace("\n", "\\n"));
            assertEquals(markdown, String.join("\n", turn.lines()), "reads back");
        }
    }

    @Test
    void headingsNestSectionsByLevel() {
        Turn turn = MarkdownStructure.parse("Intro\n\n# A\n\nbody\n\n## A.1\n\nmore\n\n# B\n\nend");
        List<Member> top = turn.turn().members();
        assertEquals(DocumentModelTerms.PARAGRAPH, kind(top.get(0)));
        Element a = ((Child) top.get(2)).element();
        Element b = ((Child) top.get(3)).element();
        assertEquals(DocumentModelTerms.SECTION, a.kind());
        assertEquals("A", a.title());
        assertEquals(1, a.level());
        assertEquals("B", b.title());
        Element a1 = a.members().stream().filter(m -> m instanceof Child).map(m -> ((Child) m).element())
                .filter(e -> e.kind() == DocumentModelTerms.SECTION).findFirst().orElseThrow();
        assertEquals("A.1", a1.title());
        assertEquals(2, a1.level());
        assertEquals(9, a.lastLine(), "a section runs to the line before the next heading of its level");
    }

    @Test
    void fencesListsAndTablesCarryTheirStructure() {
        Turn turn = MarkdownStructure.parse("```koncept-tree\nAortic stenosis\n```\n\n1. one\n2. two\n\n| a |\n|---|\n| 1 |");
        List<Member> top = turn.turn().members();
        Element listing = ((Child) top.get(0)).element();
        assertEquals(DocumentModelTerms.LISTING, listing.kind());
        assertEquals("koncept-tree", listing.parameters());
        assertEquals(3, listing.members().size(), "both fences and the code line");
        Element list = ((Child) top.get(2)).element();
        assertEquals(DocumentModelTerms.LIST, list.kind());
        assertEquals("ordered", list.parameters());
        assertEquals(2, list.members().size(), "two items");
        assertEquals(DocumentModelTerms.LIST_ITEM, kind(list.members().get(0)));
        Element table = ((Child) top.get(4)).element();
        assertEquals(DocumentModelTerms.TABLE, table.kind());
        assertEquals(java.util.Arrays.asList(DocumentModelTerms.TABLE_ROW, null, DocumentModelTerms.TABLE_ROW),
                table.members().stream().map(MarkdownStructureTest::kind).toList(),
                "header row, the delimiter line the table owns, a body row");
    }

    private static Concept kind(Member member) {
        return member instanceof Child child ? child.element().kind() : null;
    }

    private static void collect(Element element, List<Integer> owned) {
        for (Member member : element.members()) {
            switch (member) {
                case Line line -> owned.add(line.index());
                case Child child -> collect(child.element(), owned);
            }
        }
    }
}
