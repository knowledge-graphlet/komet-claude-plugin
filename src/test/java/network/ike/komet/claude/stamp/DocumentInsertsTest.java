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
import design.knowledge.stamp.adoc.dialect.Document;
import design.knowledge.stamp.adoc.dialect.IkeDialectParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The standard entries of the Insert menu form one leaf block each (ike-lab-documents topic dev-document-edit-menu). */
class DocumentInsertsTest {

    @Test
    void everyStandardEntryFormsOneLeafBlockOfItsKind() {
        List<DocumentInserts.Insertion> entries = new ArrayList<>(DocumentInserts.standard());
        entries.addAll(DocumentInserts.admonitions());
        assertEquals(3 + 5, entries.size());
        Set<String> ids = new HashSet<>();
        for (DocumentInserts.Insertion entry : entries) {
            assertTrue(ids.add(entry.id()), "ids are distinct: " + entry.id());
            assertTrue(DocumentEditor.isLeafKind(entry.kind()), entry.label() + " is a leaf kind");
            List<String> lines = new ArrayList<>(List.of("= Title", "", "Lead.", ""));
            lines.addAll(entry.text().lines().toList());
            Document parsed = IkeDialectParser.parse(lines);
            List<Block> added = parsed.blocks().stream().filter(b -> b.firstLine() == 5).toList();
            assertEquals(1, added.size(), entry.label() + " forms one block");
            assertEquals(entry.kind(), added.getFirst().kind(), entry.label());
            assertTrue(added.getFirst().children().isEmpty(), entry.label() + " holds no other block");
            assertEquals(lines.size(), added.getFirst().lastLine(), entry.label() + " runs to its last line");
            assertTrue(entry.opensForEditing(), entry.label() + " opens for editing, since it is a placeholder");
        }
    }

    @Test
    void theParagraphAndListingKeepTheIdsOfTheVerbsTheyReplace() {
        List<DocumentInserts.Insertion> standard = DocumentInserts.standard();
        assertEquals("stampedDocumentAddParagraph", standard.get(0).id());
        assertEquals("stampedDocumentAddListing", standard.get(1).id());
        assertEquals("stampedDocumentInsertNote", DocumentInserts.admonitions().getFirst().id());
    }

    @Test
    void aKindsStyleBecomesACamelCasedId() {
        assertEquals("DecisionTree", DocumentInserts.camel("decision-tree"));
        assertEquals("Anf", DocumentInserts.camel("anf"));
        assertEquals("PageLayout", DocumentInserts.camel("page_layout"));
    }

    @Test
    void containersAreNotLeafKinds() {
        for (Block.Kind kind : List.of(Block.Kind.LIST, Block.Kind.TABLE, Block.Kind.SECTION, Block.Kind.EXAMPLE, Block.Kind.SIDEBAR, Block.Kind.QUOTE)) {
            assertFalse(DocumentEditor.isLeafKind(kind), kind.name());
        }
        for (Block.Kind kind : List.of(Block.Kind.PARAGRAPH, Block.Kind.LISTING, Block.Kind.LITERAL, Block.Kind.ADMONITION, Block.Kind.IMAGE, Block.Kind.OPEN)) {
            assertTrue(DocumentEditor.isLeafKind(kind), kind.name());
        }
    }
}
