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

import network.ike.komet.claude.stamp.DocumentAssembly.Include;
import network.ike.komet.claude.stamp.DocumentAssembly.Located;
import network.ike.komet.claude.stamp.DocumentAssembly.Member;
import network.ike.komet.claude.stamp.DocumentAssembly.Part;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An assembly on the surface: the host's lines with each resolved include line followed by
 * its child's, every line keeping its member, so a selection locates to one file's own line
 * numbers (ike-lab-documents topic {@code dev-document-surface-pages};
 * IKE-Network/ike-issues#1307).
 */
class DocumentAssemblyTest {

    private static long nextNid = 1000;

    /** A document of those lines, nids minted in order, no structure. */
    private static StoredDocument document(String... texts) {
        List<StoredLine> lines = new ArrayList<>();
        for (String text : texts) {
            lines.add(new StoredLine(nextNid++, text, null, null, List.of()));
        }
        return new StoredDocument(null, "abc1234", lines, 1, List.of(), null, Map.of(), Map.of());
    }

    private static List<String> texts(List<StoredLine> lines) {
        return lines.stream().map(StoredLine::text).toList();
    }

    /** The ANF shape: an aggregator whose {@code :topics:} attribute the build settles, resolved through the convention. */
    private static DocumentAssembly compendium(Map<String, StoredDocument> store) {
        StoredDocument host = document(
                "= Compendium",
                ":topics: {generated}/topics-asciidoc",
                "",
                "== Introduction",
                "",
                "include::{topics}/topics/anf/first.adoc[leveloffset=+2]",
                "",
                "include::{topics}/topics/anf/second.adoc[leveloffset=+2]",
                "",
                "include::{topics}/topics/anf/absent.adoc[leveloffset=+2]",
                "",
                "== Closing",
                "",
                "The end.");
        return DocumentAssembly.of(host, "compendium/src/docs/asciidoc/compendium.adoc", path -> Optional.ofNullable(store.get(path)));
    }

    @Test
    void childrenFollowTheirIncludeLinesInIncludeOrder() {
        StoredDocument first = document(":topic-id: anf-first", "= First", "", "One claim.", "Another claim.");
        StoredDocument second = document(":topic-id: anf-second", "= Second", "", "A claim that cites xref:anf-first[].");
        DocumentAssembly assembly = compendium(Map.of(
                "topics/src/docs/asciidoc/topics/anf/first.adoc", first,
                "topics/src/docs/asciidoc/topics/anf/second.adoc", second));

        assertEquals(List.of("compendium/src/docs/asciidoc/compendium.adoc", "topics/src/docs/asciidoc/topics/anf/first.adoc",
                "topics/src/docs/asciidoc/topics/anf/second.adoc"), assembly.members().stream().map(Member::path).toList(),
                "the host first, then the children in include order");
        assertEquals(List.of(
                "= Compendium", ":topics: {generated}/topics-asciidoc", "", "== Introduction", "",
                "include::{topics}/topics/anf/first.adoc[leveloffset=+2]",
                ":topic-id: anf-first", "= First", "", "One claim.", "Another claim.",
                "",
                "include::{topics}/topics/anf/second.adoc[leveloffset=+2]",
                ":topic-id: anf-second", "= Second", "", "A claim that cites xref:anf-first[].",
                "",
                "include::{topics}/topics/anf/absent.adoc[leveloffset=+2]",
                "", "== Closing", "", "The end."), texts(assembly.lines()),
                "the surface order: each resolved include line followed by its child's lines, an unresolved one left as a line");

        List<Include> includes = assembly.includes();
        assertEquals(3, includes.size());
        assertTrue(includes.get(0).resolved() && includes.get(1).resolved());
        assertSame(first, includes.get(0).child().document());
        assertEquals(1, includes.get(0).child().depth());
        assertSame(assembly.host(), includes.get(0).child().includedBy());
        assertFalse(includes.get(2).resolved(), "nothing in the store answers the third include");
        assertTrue(includes.get(2).note().startsWith("not in the store"), includes.get(2).note());
        assertEquals(List.of(includes.get(2)), assembly.unresolved());

        // Every line keeps its member and its index in that member.
        StoredLine oneClaim = first.lines().get(3);
        assertEquals(9, assembly.ordinal(oneClaim.nid()));
        assertSame(first, assembly.memberAt(9).orElseThrow().document());
        assertEquals(3, assembly.indexInMember(9), "'One claim.' is the fourth line of first.adoc");
        assertSame(assembly.host(), assembly.memberAt(5).orElseThrow(), "the include line itself is the host's");
        assertSame(assembly.host(), assembly.memberAt(11).orElseThrow(), "the host resumes after the child");
        assertEquals(0, assembly.indexInMember(0));
        assertEquals(6, assembly.indexInMember(11));
        assertTrue(assembly.memberAt(99).isEmpty());
        assertEquals(oneClaim, assembly.line(oneClaim.nid()).orElseThrow());
        assertEquals(first.lines().get(0), assembly.lines().get(assembly.ordinalIn(includes.get(0).child(), 0)));
    }

    @Test
    void aBlocksLinesSplitAtTheIncludesShownInPlace() {
        StoredDocument first = document("= First", "", "One claim.");
        DocumentAssembly assembly = compendium(Map.of("topics/src/docs/asciidoc/topics/anf/first.adoc", first));
        List<StoredLine> host = assembly.host().document().lines();

        List<Part> alone = assembly.parts(List.of(host.get(5)));
        assertEquals(1, alone.size());
        assertTrue(alone.getFirst().included(), "a block that is the include line alone stands for the child");
        assertSame(first, alone.getFirst().include().child().document());

        List<Part> mixed = assembly.parts(List.of(host.get(3), host.get(4), host.get(5), host.get(6), host.get(9)));
        assertEquals(3, mixed.size(), "the lines before, the child, the lines after");
        assertEquals(List.of("== Introduction", ""), texts(mixed.get(0).lines()));
        assertTrue(mixed.get(1).included());
        assertEquals(List.of("", "include::{topics}/topics/anf/absent.adoc[leveloffset=+2]"), texts(mixed.get(2).lines()),
                "an include the store cannot answer stays in its run of lines");

        List<Part> plain = assembly.parts(List.of(host.get(0), host.get(1)));
        assertEquals(1, plain.size());
        assertFalse(plain.getFirst().included());
        assertEquals(1, assembly.parts(List.of()).size(), "no lines: one empty text part, never none");
    }

    @Test
    void aSelectionLocatesToOneMemberAndItsOwnLineNumbers() {
        StoredDocument first = document(":topic-id: anf-first", "= First", "", "One claim.", "Another claim.");
        DocumentAssembly assembly = compendium(Map.of("topics/src/docs/asciidoc/topics/anf/first.adoc", first));
        Member child = assembly.children().getFirst();

        // A drag over the child's two claims, caret before anchor.
        Located inside = assembly.locate(new LineSelection(10, 9)).orElseThrow();
        assertSame(child, inside.member());
        assertEquals(new LineSelection(4, 3), inside.local(), "ordinals into first.adoc's own lines, the gesture's direction kept");
        List<int[]> ranges = inside.local().ranges(index -> index + 1);               // every line committed: number = index + 1
        assertEquals(1, ranges.size());
        assertEquals(4, ranges.getFirst()[0], "the trailer names first.adoc's lines 4-5");
        assertEquals(5, ranges.getFirst()[1]);

        Located hostOnly = assembly.locate(new LineSelection(3, 5)).orElseThrow();
        assertSame(assembly.host(), hostOnly.member());
        assertEquals(new LineSelection(3, 5), hostOnly.local(), "the host's own indexes are its surface ordinals until the first child");
        Located afterChild = assembly.locate(new LineSelection(11, 12)).orElseThrow();
        assertEquals(new LineSelection(6, 7), afterChild.local(), "after the child, the host's indexes fall behind the surface ordinals");

        assertTrue(assembly.locate(new LineSelection(5, 9)).isEmpty(), "a selection from the include line into the child spans two files");
        assertEquals(2, assembly.membersTouched(new LineSelection(5, 9)));
        assertTrue(assembly.locate(new LineSelection(20, 40)).isEmpty(), "out of range");
        assertTrue(assembly.locate(null).isEmpty());
    }

    @Test
    void aFileReadsOnceAndACycleEndsWhereItBegan() {
        StoredDocument leaf = document(":topic-id: leaf", "= Leaf", "", "A claim.");
        StoredDocument part = document(":topic-id: part", "= Part", "", "include::leaf.adoc[]", "", "include::../book.adoc[]");
        StoredDocument book = document("= Book", "", "include::parts/part.adoc[]", "", "include::parts/leaf.adoc[]", "", "include::parts/part.adoc[]");
        Map<String, StoredDocument> store = Map.of("docs/book.adoc", book, "docs/parts/part.adoc", part, "docs/parts/leaf.adoc", leaf);

        DocumentAssembly assembly = DocumentAssembly.of(book, "docs/book.adoc", path -> Optional.ofNullable(store.get(path)));

        assertEquals(List.of("docs/book.adoc", "docs/parts/part.adoc", "docs/parts/leaf.adoc"), assembly.members().stream().map(Member::path).toList(),
                "a nested aggregator is followed; the leaf reads once, where the part first names it");
        assertEquals(List.of(
                "= Book", "", "include::parts/part.adoc[]",
                ":topic-id: part", "= Part", "", "include::leaf.adoc[]",
                ":topic-id: leaf", "= Leaf", "", "A claim.",
                "", "include::../book.adoc[]",
                "", "include::parts/leaf.adoc[]", "", "include::parts/part.adoc[]"), texts(assembly.lines()));
        assertEquals(2, assembly.children().getLast().depth());
        List<Include> left = assembly.unresolved();
        assertEquals(3, left.size(), "the cycle back to the book, the leaf a second time, the part a second time");
        for (Include include : left) {
            assertTrue(include.note().startsWith("shown once"), include.note());
            assertNull(include.child());
        }
        assertSame(part, left.getFirst().in().document(), "the cycle is the part's include");
    }

    @Test
    void aDocumentAloneOrWithoutAPathKeepsItsIncludeLines() {
        StoredDocument first = document("= First");
        StoredDocument host = document("= Book", "", "include::first.adoc[]");

        DocumentAssembly alone = DocumentAssembly.single(host, "book.adoc");
        assertEquals(1, alone.members().size());
        assertEquals(texts(host.lines()), texts(alone.lines()));
        assertFalse(alone.includes().getFirst().resolved());

        DocumentAssembly unplaced = DocumentAssembly.of(host, null, path -> Optional.of(first));
        assertEquals(1, unplaced.members().size(), "without the host's own path nothing resolves relative to it");
        assertTrue(unplaced.includes().isEmpty());
        assertEquals("", unplaced.host().path());
        assertEquals("book.adoc", alone.host().fileName());
    }
}
