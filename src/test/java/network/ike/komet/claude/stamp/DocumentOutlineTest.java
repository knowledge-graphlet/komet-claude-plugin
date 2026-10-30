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
package network.ike.komet.claude.stamp;

import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import dev.ikm.tinkar.common.id.PublicIds;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredAnnotation;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The document outline the assistant reads: paths, ordinals, pages, search and the store's provenance, without a store. */
class DocumentOutlineTest {

    private static final DocumentOutline.Describe DESCRIBE = stamp -> "—";

    /**
     * = Title / :topic-id: t / == Section (a paragraph of two lines, a listing) / == Second (one paragraph);
     * line 4 is affirmed and commented on.
     */
    static StoredDocument fixture() {
        List<StoredLine> lines = List.of(
                new StoredLine(101, "= Title", null, null, List.of()),
                new StoredLine(102, ":topic-id: t", null, null, List.of()),
                new StoredLine(103, "== Section", null, null, List.of()),
                new StoredLine(104, "The first line of prose.", null, null, List.of(
                        new StoredAnnotation(AnnotationKind.AFFIRMATION, null, 0, ""),
                        new StoredAnnotation(AnnotationKind.COMMENT, null, 0, "Worth a second look."))),
                new StoredLine(105, "The second line of prose.", null, null, List.of()),
                new StoredLine(106, "----", null, null, List.of()),
                new StoredLine(107, "code here", null, null, List.of()),
                new StoredLine(108, "----", null, null, List.of()),
                new StoredLine(109, "== Second", null, null, List.of()),
                new StoredLine(110, "Closing words.", null, null, List.of()));
        Map<Long, StoredBlock> blocks = new HashMap<>();
        StoredBlock paragraph = new StoredBlock(203, Block.Kind.PARAGRAPH, 0, "", "", List.of(104L, 105L), null);
        StoredBlock listing = new StoredBlock(204, Block.Kind.LISTING, 0, "Example", "[source,java]", List.of(106L, 107L, 108L), null);
        StoredBlock section = new StoredBlock(202, Block.Kind.SECTION, 1, "Section", "", List.of(103L, 203L, 204L), null);
        StoredBlock closing = new StoredBlock(206, Block.Kind.PARAGRAPH, 0, "", "", List.of(110L), null);
        StoredBlock second = new StoredBlock(205, Block.Kind.SECTION, 1, "Second", "", List.of(109L, 206L), null);
        StoredBlock root = new StoredBlock(201, Block.Kind.DOCUMENT, 0, "Title", "", List.of(101L, 102L, 202L, 205L), null);
        for (StoredBlock b : List.of(root, section, paragraph, listing, second, closing)) {
            blocks.put(b.nid(), b);
        }
        return new StoredDocument(PublicIds.newRandom(), "abcdef0123", lines, 1, List.of("Title"), root, blocks, Map.of());
    }

    @Test
    void blocksAreNamedByPathAndLinesByOrdinal() {
        DocumentOutline outline = new DocumentOutline(fixture());

        assertEquals(List.of("0", "1", "1.1", "1.2", "2", "2.1"), outline.nodes().stream().map(DocumentOutline.Node::path).toList());
        assertEquals("1.1", outline.find("§1.1").orElseThrow().path());
        assertEquals("1.2", outline.find("1.2").orElseThrow().path());
        assertEquals("1.2", outline.find("L7").orElseThrow().path(), "a line names the block holding it");
        assertEquals("1", outline.find("l3").orElseThrow().path(), "a heading line belongs to its section");
        assertTrue(outline.find("L99").isEmpty());
        assertTrue(outline.find("§9").isEmpty());
        assertTrue(outline.find("1.1").orElseThrow().editable());
        assertFalse(outline.find("1").orElseThrow().editable(), "a section holds blocks; its children are edited");
        assertEquals("Title", outline.title());
    }

    @Test
    void theOutlinePagesAndShowsLinesWithTheirMarks() {
        DocumentOutline outline = new DocumentOutline(fixture());

        DocumentOutline.Page all = outline.outline(0, 100, 10_000, true, DESCRIBE);
        assertEquals(6, all.next());
        assertFalse(all.more());
        assertTrue(all.text().contains("§1 SECTION level 1 \"Section\" L3, 2 blocks\n"), all.text());
        assertTrue(all.text().contains("§1.2 LISTING \"Example\" [source,java] L6-8, editable"), all.text());
        assertTrue(all.text().contains("L4  — ✓ …  The first line of prose."), all.text());

        DocumentOutline.Page first = outline.outline(0, 2, 10_000, false, DESCRIBE);
        assertEquals(2, first.next());
        assertTrue(first.more());
        assertFalse(first.text().contains("L4  "), "an outline without lines");
        DocumentOutline.Page tiny = outline.outline(3, 100, 10, true, DESCRIBE);
        assertEquals(4, tiny.next(), "one block always fits, however small the budget");
    }

    @Test
    void searchNamesTheBlockAndTheLine() {
        DocumentOutline outline = new DocumentOutline(fixture());

        DocumentOutline.Matches prose = outline.search("PROSE", 10, DESCRIBE);
        assertEquals(2, prose.count());
        assertTrue(prose.text().startsWith("§1.1 PARAGRAPH  L4  "), prose.text());
        assertEquals(0, outline.search("absent", 10, DESCRIBE).count());
        assertEquals(1, outline.search("prose", 1, DESCRIBE).text().lines().count(), "at most max shown");
    }

    @Test
    void aFullBlockCarriesEveryAnnotationsText() {
        DocumentOutline outline = new DocumentOutline(fixture());
        StringBuilder out = new StringBuilder();

        outline.block(outline.find("1.1").orElseThrow(), true, true, DESCRIBE, out);

        assertTrue(out.toString().contains("affirmed by —"), out.toString());
        assertTrue(out.toString().contains("commented on by —: Worth a second look."), out.toString());
    }

    @Test
    void theStoresProvenanceCountsTheQueueAndListsARange() {
        DocumentOutline outline = new DocumentOutline(fixture());

        String text = outline.provenance(4, 5, DESCRIBE);

        assertTrue(text.startsWith("10 lines, 0 introducing STAMPs, 1 lines affirmed, 0 refuted, 0 in the review queue\n"), text);
        assertTrue(text.contains("Lines 4-5"), text);
        assertTrue(text.contains("… 5 more line(s)"), text);
        assertTrue(outline.provenance(40, 50, DESCRIBE).contains("beyond the document's 10 lines"));
    }

    @Test
    void theToolsDeclareTheirInputs() {
        List<AnthropicTool> tools = DocumentGraphTools.tools(() -> null, () -> null);

        assertEquals(List.of("read_document", "read_block", "find_in_document", "propose_block_edit", "propose_sentence_edit",
                "propose_block_insert", "propose_block_delete"), tools.stream().map(AnthropicTool::name).toList());
        assertEquals(List.of("after", "kind", "text"), tools.get(5).inputSchema().get("required"));
        assertEquals(List.of("block"), tools.get(6).inputSchema().get("required"));
        assertEquals(List.of(), tools.get(0).inputSchema().get("required"));
        assertEquals(List.of("block"), tools.get(1).inputSchema().get("required"));
        assertEquals(List.of("query"), tools.get(2).inputSchema().get("required"));
        assertEquals(List.of("block", "text"), tools.get(3).inputSchema().get("required"));
        assertTrue(tools.get(3).description().contains("uncommitted"));
        assertTrue(tools.get(0).description().contains("no working tree"));
        assertTrue(tools.get(2).execute(Map.of("query", " ")).startsWith("Error: query is empty"));
    }
}
