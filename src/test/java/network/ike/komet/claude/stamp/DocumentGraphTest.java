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
import design.knowledge.stamp.adoc.dialect.Reference;
import dev.ikm.tinkar.common.id.PublicIds;
import network.ike.komet.claude.stamp.DocumentGraph.Kind;
import network.ike.komet.claude.stamp.DocumentGraph.Node;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredReference;
import dev.ikm.tinkar.terms.ConceptFacade;
import network.ike.foundation.ike.bindings.IkeConcept;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The graph of a stored document: the tree the Graph view shows, and the ordinals its nodes span. */
class DocumentGraphTest {

    /** Nids minted across every document of a test, so two members never share one. */
    private static long nextNid = 10_000;

    /** A document parsed by the dialect and stored as the materializer stores it: lines, then blocks over them. */
    static StoredDocument stored(String text, Map<Integer, List<StoredReference>> referencesByLineNumber) {
        Document parsed = IkeDialectParser.parse(text);
        List<StoredLine> lines = new ArrayList<>();
        Map<Integer, Long> nidByLine = new LinkedHashMap<>();
        for (int i = 0; i < parsed.lines().size(); i++) {
            long nid = nextNid++;
            nidByLine.put(i + 1, nid);
            lines.add(new StoredLine(nid, parsed.lines().get(i), null, null, List.of()));
        }
        Map<Long, StoredBlock> blocks = new LinkedHashMap<>();
        StoredBlock root = store(parsed.root(), nidByLine, blocks);
        Map<Long, List<StoredReference>> references = new LinkedHashMap<>();
        referencesByLineNumber.forEach((number, refs) -> references.put(nidByLine.get(number),
                refs.stream().map(r -> new StoredReference(r.nid(), r.kind(), r.target(), r.text(), nidByLine.get(number))).toList()));
        return new StoredDocument(PublicIds.of(UUID.randomUUID()), "0000000", lines, 1, List.of(parsed.title() == null ? "" : parsed.title()),
                root, blocks, references);
    }

    static StoredDocument stored(String text) {
        return stored(text, Map.of());
    }

    private static StoredBlock store(Block block, Map<Integer, Long> nidByLine, Map<Long, StoredBlock> blocks) {
        long nid = nextNid++;
        List<long[]> members = new ArrayList<>();
        for (int line : block.lines()) {
            members.add(new long[] {line, nidByLine.get(line)});
        }
        for (Block child : block.children()) {
            StoredBlock stored = store(child, nidByLine, blocks);
            members.add(new long[] {child.firstLine(), stored.nid()});
        }
        members.sort((a, b) -> Long.compare(a[0], b[0]));
        StoredBlock stored = new StoredBlock(nid, block.kind(), block.level(), block.title() == null ? "" : block.title(),
                block.parameters() == null ? "" : block.parameters(), members.stream().map(m -> m[1]).toList(), null);
        blocks.put(nid, stored);
        return stored;
    }

    private static Node child(Node node, Kind kind, int index) {
        List<Node> of = node.children().stream().filter(n -> n.kind() == kind).toList();
        return of.get(index);
    }

    private static List<String> labels(Node node) {
        return node.children().stream().map(Node::label).toList();
    }

    /** The first line node, anywhere beneath, whose text starts so; the dialect keeps an include line inside a block. */
    private static Node lineStartingWith(Node node, String prefix) {
        if (node.kind() == Kind.LINE && node.line().text().startsWith(prefix)) {
            return node;
        }
        for (Node child : node.children()) {
            Node found = lineStartingWith(child, prefix);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void sectionsHoldTheirBlocksAndBlocksTheirLines() {
        StoredDocument document = stored("""
                = The Title

                == One

                First claim.
                Second claim.

                == Two

                * an item
                * another
                """);
        DocumentAssembly assembly = DocumentAssembly.single(document, "docs/one.adoc");

        Node root = DocumentGraph.of(assembly);

        assertEquals(Kind.DOCUMENT, root.kind());
        assertEquals("The Title", root.label());
        assertEquals("docs/one.adoc", root.detail());
        assertEquals(0, root.first());
        assertEquals(assembly.lines().size() - 1, root.last(), "the root spans every line");
        assertTrue(root.structural());

        Node one = child(root, Kind.BLOCK, 0);
        assertEquals(Block.Kind.SECTION, one.blockKind());
        assertEquals("One", one.label(), "a section's label is its title");
        assertTrue(one.structural());
        Node paragraph = child(one, Kind.BLOCK, 0);
        assertEquals(Block.Kind.PARAGRAPH, paragraph.blockKind());
        assertEquals("Paragraph: First claim.", paragraph.label(), "a block without a title shows its first line");
        assertFalse(paragraph.structural(), "a block opens collapsed");
        assertEquals(List.of("First claim.", "Second claim."), labels(paragraph), "a line's label is its text");
        assertEquals("line 5 of one.adoc", paragraph.children().getFirst().detail(), "its number is a detail, not a label");
        assertEquals(assembly.ordinal(paragraph.children().get(0).nid()), paragraph.first());
        assertEquals(assembly.ordinal(paragraph.children().get(1).nid()), paragraph.last());
        assertEquals(2, paragraph.lineCount());
        assertEquals(Optional.of(new LineSelection(paragraph.first(), paragraph.last())), paragraph.selection());
        assertTrue(one.spans(paragraph.first()) && one.first() < paragraph.first(), "the section spans its heading and its paragraph");

        Node two = child(root, Kind.BLOCK, 1);
        assertEquals("Two", two.label());
        Node list = child(two, Kind.BLOCK, 0);
        assertEquals(Block.Kind.LIST, list.blockKind());
        assertEquals(2, list.children().stream().filter(n -> n.blockKind() == Block.Kind.LIST_ITEM).count(), "items under the list");
        assertEquals(assembly.lines().size() - 1, two.last(), "the last section ends where the document ends");
    }

    @Test
    void anIncludeLineCarriesTheChildDocumentBeneathIt() {
        StoredDocument child = stored("""
                = Child

                A claim of the child.
                """);
        StoredDocument host = stored("""
                = Host

                include::child.adoc[]

                After the include.
                """);
        DocumentAssembly assembly = DocumentAssembly.of(host, "docs/host.adoc",
                path -> path.equals("docs/child.adoc") ? Optional.of(child) : Optional.empty());
        assertEquals(2, assembly.members().size(), "the child is shown in place");

        Node root = DocumentGraph.of(assembly);
        Node include = lineStartingWith(root, "include::");
        assertNotNull(include);
        assertEquals(1, include.children().size(), "the include line has the child's document beneath it");
        Node childRoot = include.children().getFirst();
        assertEquals(Kind.DOCUMENT, childRoot.kind());
        assertEquals("Child", childRoot.label());
        assertEquals("docs/child.adoc", childRoot.detail());
        assertSame(assembly.children().getFirst(), childRoot.member());
        int includeOrdinal = assembly.ordinal(include.nid());
        assertEquals(includeOrdinal, include.first());
        assertEquals(includeOrdinal + child.lines().size(), include.last(), "the include spans its line and every line of the child");
        assertEquals(includeOrdinal + 1, childRoot.first(), "the child's lines follow the include line in surface order");
        assertEquals(assembly.lines().size() - 1, root.last());

        Node after = lineStartingWith(root, "After the include.");
        assertNotNull(after, "the host's lines after the include stay the host's");
        assertSame(assembly.host(), after.member());
        assertTrue(after.first() > include.last(), "and follow the child in surface order");
    }

    @Test
    void anUnresolvedIncludeStaysALineWithItsNote() {
        StoredDocument host = stored("""
                = Host

                include::missing.adoc[]
                """);
        DocumentAssembly assembly = DocumentAssembly.of(host, "docs/host.adoc", path -> Optional.empty());

        Node root = DocumentGraph.of(assembly);
        Node include = lineStartingWith(root, "include::");
        assertNotNull(include);
        assertTrue(include.children().isEmpty(), "nothing beneath it");
        assertTrue(include.detail().startsWith("not in the store"), include.detail());
        assertEquals(include.first(), include.last());
    }

    @Test
    void aDocumentWithoutStructureListsItsLinesFlat() {
        List<StoredLine> lines = List.of(new StoredLine(nextNid++, "= Bare", null, null, List.of()),
                new StoredLine(nextNid++, "", null, null, List.of()),
                new StoredLine(nextNid++, "A line.", null, null, List.of()));
        StoredDocument bare = new StoredDocument(PublicIds.of(UUID.randomUUID()), "abc1234", lines, 1, List.of(), null, Map.of(), Map.of());
        DocumentAssembly assembly = DocumentAssembly.single(bare, "bare.adoc");

        Node root = DocumentGraph.of(assembly);
        assertEquals("bare.adoc", root.label(), "no title: the file name");
        assertTrue(root.detail().contains("re-materialize"), root.detail());
        assertEquals(0, root.nid());
        assertNull(root.block());
        assertEquals(3, root.children().size());
        assertTrue(root.children().stream().allMatch(n -> n.kind() == Kind.LINE));
        assertEquals(0, root.first());
        assertEquals(2, root.last());
    }

    @Test
    void referencesHangUnderTheirLines() {
        StoredDocument document = stored("""
                = Refs

                See xref:other[the other] and <<anchor>>.
                """, Map.of(3, List.of(
                new StoredReference(nextNid++, Reference.Kind.CROSS_REFERENCE, "other", "the other", 0),
                new StoredReference(nextNid++, Reference.Kind.CROSS_REFERENCE, "anchor", "", 0))));
        DocumentAssembly assembly = DocumentAssembly.single(document, "refs.adoc");

        Node root = DocumentGraph.of(assembly);
        Node line = lineStartingWith(root, "See ");
        assertNotNull(line);
        assertEquals(List.of("cross reference → other", "cross reference → anchor"), labels(line));
        Node reference = line.children().getFirst();
        assertEquals(Kind.REFERENCE, reference.kind());
        assertEquals("the other", reference.detail());
        assertFalse(reference.spansLines(), "a reference spans no line of its own");
        assertEquals(line.first(), line.last(), "references do not widen the line");
    }

    @Test
    void withHistoryVersionsHangUnderLinesAndRetiredLinesStandWhereTheyStood() {
        StoredDocument document = stored("""
                = The Title

                == One

                First claim.
                Second claim.
                """);
        DocumentAssembly assembly = DocumentAssembly.single(document, "docs/one.adoc");
        long first = document.lines().get(4).nid();                 // "First claim."
        long second = document.lines().get(5).nid();                // "Second claim."
        long gone = nextNid++;                                       // a line commit 2 removed, between them
        DocumentHistory history = DocumentHistory.from(
                List.of(List.of(first, gone, second), List.of(first, second)),
                Map.of(first, List.of(new DocumentHistory.Raw(null, "First claim, as written.", true), new DocumentHistory.Raw(null, "First claim.", true)),
                        gone, List.of(new DocumentHistory.Raw(null, "A claim since withdrawn.", true), new DocumentHistory.Raw(null, "A claim since withdrawn.", false)),
                        second, List.of(new DocumentHistory.Raw(null, "Second claim.", true))));

        Node root = DocumentGraph.of(assembly, member -> history);
        Node paragraph = child(child(root, Kind.BLOCK, 0), Kind.BLOCK, 0);
        assertEquals(List.of(Kind.LINE, Kind.RETIRED, Kind.LINE), paragraph.children().stream().map(Node::kind).toList(),
                "the retired line stands after the line it followed");
        Node firstLine = paragraph.children().get(0);
        assertEquals(List.of("First claim, as written.", "First claim."), labels(firstLine), "a line's versions beneath it, oldest first");
        assertEquals(Kind.VERSION, firstLine.children().getFirst().kind());
        assertEquals("introduced", firstLine.children().get(0).detail());
        assertEquals("retexted", firstLine.children().get(1).detail());
        assertEquals(firstLine.first(), firstLine.children().get(1).first(), "a version spans its line");
        assertEquals(firstLine.first(), firstLine.last(), "versions do not widen the line");
        Node retired = paragraph.children().get(1);
        assertEquals("A claim since withdrawn.", retired.label());
        assertEquals(gone, retired.nid());
        assertFalse(retired.spansLines(), "the document as it stands does not hold it");
        assertEquals(paragraph.children().get(2).first(), paragraph.last(), "nor does it widen the block");
        assertFalse(retired.uncommitted());
        assertTrue(paragraph.commits().isEmpty(), "no STAMPs in the fixture: no commits on the strip");

        Node latest = DocumentGraph.of(assembly);
        Node latestParagraph = child(child(latest, Kind.BLOCK, 0), Kind.BLOCK, 0);
        assertEquals(2, latestParagraph.children().size(), "as it stands: no retired line");
        assertTrue(latestParagraph.children().getFirst().children().isEmpty(), "and no versions");
    }

    @Test
    void aRetiredLineThatLedTheDocumentStandsFirst() {
        StoredDocument document = stored("""
                = The Title

                A claim.
                """);
        DocumentAssembly assembly = DocumentAssembly.single(document, "docs/one.adoc");
        long title = document.lines().get(0).nid();
        long gone = nextNid++;
        DocumentHistory history = DocumentHistory.from(
                List.of(List.of(gone, title), List.of(title)),
                Map.of(gone, List.of(new DocumentHistory.Raw(null, "// a comment once first", true), new DocumentHistory.Raw(null, "// a comment once first", false)),
                        title, List.of(new DocumentHistory.Raw(null, "= The Title", true))));

        Node root = DocumentGraph.of(assembly, member -> history);
        assertEquals(Kind.RETIRED, root.children().getFirst().kind());
        assertEquals("// a comment once first", root.children().getFirst().label());
    }

    // ── the DocBook projection ────────────────────────────────────────────────

    private static final String DOCBOOK = """
            // a-topic
            :topic-id: dev-docbook
            :topic-scope-note: One note \\
              carried over two lines.

            [[dev-docbook]]
            = DocBook Title

            A *strong* claim and an _emphasis
            over two lines_, with `mono`, <<target,the target>>,
            xref:other[], https://example.org[a link] and ((a term)).
            indexterm:[primary, secondary]
            A footnote:[A note.] and [[here]]an anchor.

            [[config]]
            .Config
            [source,yaml,role=wide]
            ----
            key: value

            other: 1
            ----

            [NOTE]
            ====
            Inside the note.
            ====

            TIP: Mind the gap.

            [cols="1,2"]
            |===
            | Name | Meaning
            | a | the first
            |===

            Term one:: Its description.

            include::missing.adoc[]
            """;

    /** Every node beneath, the root first, in document order. */
    static List<Node> all(Node root) {
        List<Node> out = new ArrayList<>();
        out.add(root);
        for (Node child : root.children()) {
            out.addAll(all(child));
        }
        return out;
    }

    private static Node element(Node root, String name) {
        return all(root).stream().filter(n -> n.markup() != null && n.markup().element().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name));
    }

    private static List<String> elements(List<Node> nodes) {
        return nodes.stream().map(n -> n.markup().element()).toList();
    }

    @Test
    void theDocBookProjectionFoldsMarkupLinesIntoTheirBlocks() {
        StoredDocument document = stored(DOCBOOK);
        DocumentAssembly assembly = DocumentAssembly.of(document, "docs/a.adoc", path -> Optional.empty());

        Node root = DocumentGraph.docbook(assembly);
        assertNoMarkupLineLeft(root, document);

        assertEquals(Kind.DOCUMENT, root.kind());
        assertEquals("DocBook Title", root.label());
        assertEquals("dev-docbook", root.markup().attributes().get("id"), "the anchor line is the document's id");
        assertEquals("dev-docbook", root.markup().attributes().get("topic-id"), "a header attribute entry is the document's attribute");
        assertEquals("One note carried over two lines.", root.markup().attributes().get("topic-scope-note"), "a continued value, joined");
        assertEquals(Kind.ELEMENT, root.children().getFirst().kind(), "the title first, as DocBook places it");
        assertEquals("Title", root.children().getFirst().markup().element());
        assertEquals("DocBook Title", root.children().getFirst().markup().text());
        assertEquals("a-topic", element(root, "Comment").markup().text());

        Node listing = all(root).stream().filter(n -> n.blockKind() == Block.Kind.LISTING).findFirst().orElseThrow();
        assertEquals(Map.of("id", "config", "style", "source", "language", "yaml", "role", "wide"), listing.markup().attributes(),
                "the anchor and attribute lines are the listing's attributes");
        assertEquals(List.of("Title", "text"), elements(listing.children()));
        assertEquals("Config", listing.children().getFirst().markup().text(), "the block title line is its title");
        assertEquals("key: value\n\nother: 1", listing.children().get(1).markup().text(), "the body is one text, its blank line kept");
        assertEquals(assembly.ordinal(document.lines().get(18).nid()), listing.children().get(1).first(), "the body starts after the delimiter");
        assertEquals(assembly.ordinal(document.lines().get(14).nid()), listing.first(), "the listing spans its anchor line");
        assertEquals(assembly.ordinal(document.lines().get(21).nid()), listing.last(), "and its closing delimiter");

        Node note = all(root).stream().filter(n -> n.blockKind() == Block.Kind.ADMONITION).findFirst().orElseThrow();
        assertEquals("NOTE", note.markup().attributes().get("style"));
        assertEquals(List.of("Paragraph"), elements(note.children()), "the delimiters are not shown");
        Node tip = all(root).stream().filter(n -> n.blockKind() == Block.Kind.ADMONITION).skip(1).findFirst().orElseThrow();
        assertEquals("TIP", tip.markup().attributes().get("style"));
        assertEquals("Mind the gap.", tip.children().getFirst().markup().text(), "the label is the style, not text");

        Node table = all(root).stream().filter(n -> n.blockKind() == Block.Kind.TABLE).findFirst().orElseThrow();
        assertEquals("1,2", table.markup().attributes().get("cols"));
        assertEquals(List.of("Row", "Row"), elements(table.children()), "four cells in two columns are two rows");
        assertEquals(List.of("Name", "Meaning"), table.children().getFirst().children().stream().map(c -> c.markup().text()).toList());
        Node cell = table.children().get(1).children().get(1);
        assertEquals("the first", cell.markup().text());
        assertEquals(assembly.ordinal(document.lines().get(33).nid()), cell.first(), "a cell spans the line it is written on");

        Node list = all(root).stream().filter(n -> n.blockKind() == Block.Kind.LIST).findFirst().orElseThrow();
        assertEquals("description", list.markup().attributes().get("style"));
        Node item = list.children().getFirst();
        assertEquals(List.of("Term", "text"), elements(item.children()));
        assertEquals("Term one", item.children().getFirst().markup().text());
        assertEquals("Its description.", item.children().get(1).markup().text(), "the marker is not text");

        Node include = element(root, "Include");
        assertEquals("missing.adoc", include.markup().attributes().get("href"));
        assertTrue(include.detail().startsWith("not in the store"), include.detail());
        assertEquals(include.first(), include.last());
    }

    @Test
    void theDocBookProjectionJoinsAParagraphIntoTextAndInlineElements() {
        StoredDocument document = stored(DOCBOOK);
        DocumentAssembly assembly = DocumentAssembly.of(document, "docs/a.adoc", path -> Optional.empty());
        Node root = DocumentGraph.docbook(assembly);

        Node paragraph = all(root).stream().filter(n -> n.blockKind() == Block.Kind.PARAGRAPH).findFirst().orElseThrow();
        assertTrue(paragraph.children().stream().noneMatch(n -> n.kind() == Kind.LINE), "lines are not nodes");
        assertEquals(List.of("Strong", "Emphasis", "Monospace", "Cross reference", "Cross reference", "Link", "Index term", "Index term",
                        "Footnote", "Anchor"),
                elements(paragraph.children().stream().filter(n -> n.kind() == Kind.INLINE).toList()));
        assertEquals("A ", paragraph.children().getFirst().markup().text(), "text runs interleave the elements");
        assertTrue(paragraph.label().startsWith("Paragraph: A strong claim and an emphasis over two lines, with mono, the target"), paragraph.label());

        int line9 = assembly.ordinal(document.lines().get(8).nid());
        Node strong = element(paragraph, "Strong");
        assertEquals("strong", strong.markup().text());
        assertEquals(line9, strong.first());
        assertEquals(line9, strong.last(), "an element spans the lines its characters came from");
        Node emphasis = element(paragraph, "Emphasis");
        assertEquals("emphasis over two lines", emphasis.markup().text());
        assertEquals(line9, emphasis.first());
        assertEquals(line9 + 1, emphasis.last(), "across a line break, both lines");
        assertEquals(List.of(document.lines().get(8), document.lines().get(9)), emphasis.markup().lines(), "the line vertices it came from");
        assertEquals(Optional.of(new LineSelection(line9, line9 + 1)), emphasis.selection(), "selecting it selects its lines");

        List<Node> references = paragraph.children().stream().filter(n -> "Cross reference".equals(n.markup().element())).toList();
        assertEquals(Map.of("linkend", "target"), references.get(0).markup().attributes());
        assertEquals("the target", references.get(0).markup().text());
        assertEquals(Map.of("linkend", "other"), references.get(1).markup().attributes());
        assertTrue(references.get(1).children().isEmpty(), "no text of its own");
        assertEquals("Cross reference → other", references.get(1).label());
        assertEquals(Map.of("href", "https://example.org"), element(paragraph, "Link").markup().attributes());
        assertEquals("a link", element(paragraph, "Link").markup().text());

        List<Node> terms = paragraph.children().stream().filter(n -> "Index term".equals(n.markup().element())).toList();
        assertEquals("yes", terms.get(0).markup().attributes().get("shown"));
        assertEquals("a term", terms.get(0).markup().text());
        assertEquals("primary", terms.get(1).markup().attributes().get("primary"));
        assertEquals("secondary", terms.get(1).markup().attributes().get("secondary"));
        assertEquals("no", terms.get(1).markup().attributes().get("shown"));
        assertTrue(terms.get(1).children().isEmpty(), "a concealed term shows no text");

        Node footnote = element(paragraph, "Footnote");
        assertEquals("A note.", footnote.markup().text());
        assertEquals(line9 + 4, footnote.first());
        assertEquals(Map.of("id", "here"), element(paragraph, "Anchor").markup().attributes());
        assertEquals("an anchor.", paragraph.children().getLast().markup().text());
        assertEquals(line9, paragraph.first());
        assertEquals(line9 + 4, paragraph.last());
    }

    @Test
    void storedSentencesAreNodesBoundToTheirEntities() {
        StoredDocument plain = stored("""
                = Sentences

                The lease is a loan. A machine *takes* it, renews
                it while working, and returns it.
                """);
        StoredBlock paragraph = plain.blocks().values().stream().filter(b -> b.kind() == Block.Kind.PARAGRAPH).findFirst().orElseThrow();
        GitDocumentMaterializer.StoredInline strong = new GitDocumentMaterializer.StoredInline(901, 0, "*takes*", null, null, List.of(), List.of());
        List<GitDocumentMaterializer.StoredSentence> sentences = List.of(
                new GitDocumentMaterializer.StoredSentence(801, "The lease is a loan.", null, null, List.of()),
                new GitDocumentMaterializer.StoredSentence(802, "A machine *takes* it, renews it while working, and returns it.", null, null,
                        List.of(strong)));
        StoredDocument document = new StoredDocument(plain.anchor(), plain.commit(), plain.lines(), 1, plain.attributes(), plain.root(),
                plain.blocks(), plain.references(), Map.of(paragraph.nid(), sentences));
        DocumentAssembly assembly = DocumentAssembly.of(document, "docs/a.adoc", path -> Optional.empty());

        Node root = DocumentGraph.docbook(assembly, true);
        Node para = all(root).stream().filter(n -> n.blockKind() == Block.Kind.PARAGRAPH).findFirst().orElseThrow();
        List<Node> nodes = para.children().stream().filter(n -> n.markup() != null && n.markup().element().equals("Sentence")).toList();
        assertEquals(2, nodes.size(), "each stored sentence is a node");
        assertEquals(801, nodes.get(0).nid());
        assertEquals(802, nodes.get(1).nid());
        int line3 = assembly.ordinal(document.lines().get(2).nid());
        assertEquals(line3, nodes.get(1).first(), "the second sentence starts on the line the first ends on");
        assertEquals(line3 + 1, nodes.get(1).last());
        Node takes = element(nodes.get(1), "Strong");
        assertEquals(901, takes.nid(), "an inline element is bound to its own entity");
        assertTrue(para.label().startsWith("Paragraph: The lease is a loan. A machine takes it"), para.label());

        Node flat = all(DocumentGraph.docbook(assembly, false)).stream().filter(n -> n.blockKind() == Block.Kind.PARAGRAPH).findFirst().orElseThrow();
        assertTrue(flat.children().stream().noneMatch(n -> n.markup() != null && n.markup().element().equals("Sentence")), "flattened");
        assertEquals(901, element(flat, "Strong").nid(), "flattened, an element keeps its entity");

        StoredDocument stale = new StoredDocument(plain.anchor(), plain.commit(), plain.lines(), 1, plain.attributes(), plain.root(),
                plain.blocks(), plain.references(), Map.of(paragraph.nid(),
                        List.of(new GitDocumentMaterializer.StoredSentence(803, "Words the lines no longer say.", null, null, List.of()))));
        Node asRead = all(DocumentGraph.docbook(DocumentAssembly.of(stale, "docs/a.adoc", path -> Optional.empty()), true)).stream()
                .filter(n -> n.blockKind() == Block.Kind.PARAGRAPH).findFirst().orElseThrow();
        assertTrue(asRead.children().stream().noneMatch(n -> n.markup() != null && n.markup().element().equals("Sentence")),
                "a sentence the lines no longer say is not shown; the flow is projected as read");
        assertEquals("Strong", element(asRead, "Strong").markup().element());
    }

    @Test
    void theDocBookProjectionCarriesAnIncludedDocumentInPlace() {
        StoredDocument child = stored("""
                = Child

                A claim of the child.
                """);
        StoredDocument host = stored("""
                = Host

                include::child.adoc[leveloffset=+1]

                After the include.
                """);
        DocumentAssembly assembly = DocumentAssembly.of(host, "docs/host.adoc",
                path -> path.equals("docs/child.adoc") ? Optional.of(child) : Optional.empty());

        Node root = DocumentGraph.docbook(assembly);
        Node included = root.children().stream().filter(n -> n.kind() == Kind.DOCUMENT).findFirst().orElseThrow();
        assertEquals("Child", included.label());
        assertEquals("child.adoc", included.markup().attributes().get("href"));
        int includeOrdinal = assembly.ordinal(host.lines().get(2).nid());
        assertEquals(includeOrdinal, included.first(), "it stands where the include line stood, and spans it");
        assertEquals(includeOrdinal + child.lines().size(), included.last());
        assertEquals("A claim of the child.", element(included, "Paragraph").children().getFirst().markup().text());
        assertNoMarkupLineLeft(root, host);
    }

    @Test
    void theDocBookProjectionCarriesMetadataAfterAParagraphToTheNextBlock() {
        StoredDocument document = stored("""
                == Grammar

                indexterm:[Annotation trailer, grammar]
                [source,text]
                ----
                Annotation: <kind> <target>
                ----
                """);
        DocumentAssembly assembly = DocumentAssembly.of(document, "docs/a.adoc", path -> Optional.empty());

        Node root = DocumentGraph.docbook(assembly);
        assertNoMarkupLineLeft(root, document);

        Node paragraph = all(root).stream().filter(n -> n.blockKind() == Block.Kind.PARAGRAPH).findFirst().orElseThrow();
        assertEquals(Map.of(), paragraph.markup().attributes(), "the attribute line is not the paragraph's");
        assertEquals(List.of("Index term"), elements(paragraph.children()), "the paragraph keeps only its index term");
        assertEquals(List.of(document.lines().get(2)), paragraph.markup().lines());
        assertEquals(assembly.ordinal(document.lines().get(2).nid()), paragraph.last());

        Node listing = all(root).stream().filter(n -> n.blockKind() == Block.Kind.LISTING).findFirst().orElseThrow();
        assertEquals(Map.of("style", "source", "language", "text"), listing.markup().attributes(), "the attribute line is the listing's");
        assertEquals(document.lines().get(3), listing.markup().lines().getFirst(), "the listing came from the attribute line");
        assertEquals(assembly.ordinal(document.lines().get(3).nid()), listing.first(), "and spans it");
        assertEquals("Annotation: <kind> <target>", listing.children().getFirst().markup().text());
    }

    @Test
    void metadataAfterTheLastBlockStaysWhereItIs() {
        StoredDocument document = stored("""
                A claim.
                [source,text]
                """);
        DocumentAssembly assembly = DocumentAssembly.of(document, "docs/a.adoc", path -> Optional.empty());

        Node root = DocumentGraph.docbook(assembly);
        assertNoMarkupLineLeft(root, document);
        Node paragraph = all(root).stream().filter(n -> n.blockKind() == Block.Kind.PARAGRAPH).findFirst().orElseThrow();
        assertEquals(List.of(document.lines().get(0), document.lines().get(1)), paragraph.markup().lines(), "no block to carry it to");
    }

    /** The five documents the projection was built against: a topic of the lab, its surface topic, two ANF topics, the ANF compendium. */
    static final List<Path> REAL_DOCUMENTS = List.of(
            Path.of(System.getProperty("user.home"), "ike-dev/ike-lab-documents/topics/src/docs/asciidoc/topics/dev/working-set-lease.adoc"),
            Path.of(System.getProperty("user.home"), "ike-dev/ike-lab-documents/topics/src/docs/asciidoc/topics/dev/stamped-document-surface.adoc"),
            Path.of(System.getProperty("user.home"), "ike-dev/anf/topics/src/docs/asciidoc/topics/anf/example-medication.adoc"),
            Path.of(System.getProperty("user.home"), "ike-dev/anf/topics/src/docs/asciidoc/topics/anf/data-types.adoc"),
            Path.of(System.getProperty("user.home"), "ike-dev/anf/compendium/src/docs/asciidoc/compendium.adoc"));

    @Test
    void theFiveRealDocumentsProjectWithNoMarkupLineLeft() throws Exception {
        Assumptions.assumeTrue(REAL_DOCUMENTS.stream().allMatch(Files::exists), "the lab and ANF checkouts are on this machine");
        for (Path file : REAL_DOCUMENTS) {
            StoredDocument document = stored(Files.readString(file));
            Node root = DocumentGraph.docbook(DocumentAssembly.of(document, file.getFileName().toString(), path -> Optional.empty()));
            assertNoMarkupLineLeft(root, document);
            assertTrue(all(root).stream().anyMatch(n -> n.blockKind() == Block.Kind.SECTION), file + ": its sections");
        }
    }

    private static final Set<ConceptFacade> FOLDED = Set.of(IkeConcept.ANCHOR_LINE, IkeConcept.BLOCK_ATTRIBUTE_LINE, IkeConcept.DELIMITER_LINE,
            IkeConcept.ATTRIBUTE_ENTRY_LINE);

    private static final Set<ConceptFacade> CONTENT = Set.of(IkeConcept.PROSE_LINE, IkeConcept.ITEM_LINE, IkeConcept.VERBATIM_LINE,
            IkeConcept.HEADING_LINE, IkeConcept.BLOCK_TITLE_LINE, IkeConcept.COMMENT_LINE, IkeConcept.ASCIIDOC_INCLUDE);

    /**
     * No line is a node, and no markup line survives as text: an anchor, attribute, delimiter or
     * attribute-entry line is in no text or inline node, a heading or block title only as the text
     * of its title, a blank line only inside a verbatim body; no node is labelled with a markup
     * line as written; and no content is lost: every line of text, heading, title, comment or
     * include is a line some node came from.
     */
    static void assertNoMarkupLineLeft(Node root, StoredDocument document) {
        List<LineRoles.Role> roles = LineRoles.of(IkeDialectParser.parse(document.lines().stream().map(StoredLine::text).toList()));
        Map<Long, LineRoles.Role> byNid = new HashMap<>();
        for (int i = 0; i < document.lines().size(); i++) {
            byNid.put(document.lines().get(i).nid(), roles.get(i));
        }
        Set<Long> shown = new java.util.HashSet<>();
        for (Node node : all(root)) {
            node.markup().lines().forEach(line -> shown.add(line.nid()));
        }
        for (StoredLine line : document.lines()) {
            if (CONTENT.contains(byNid.get(line.nid()).meaning())) {
                assertTrue(shown.contains(line.nid()), "a line of content no node came from: " + line.text());
            }
        }
        for (Node node : all(root)) {
            assertNotEquals(Kind.LINE, node.kind(), "a line is a node: " + node.label());
            assertNotNull(node.markup(), "every node is an element: " + node.label());
            boolean content = node.kind() == Kind.TEXT || node.kind() == Kind.INLINE;
            for (StoredLine line : node.markup().lines()) {
                LineRoles.Role role = byNid.get(line.nid());
                if (role == null) {
                    continue;                                    // a line of an included document
                }
                if (FOLDED.contains(role.meaning()) && line.text().strip().length() > 2) {
                    assertNotEquals(line.text().strip(), node.label().strip(), "a node labelled with its markup line as written");
                }
                if (!content) {
                    continue;
                }
                assertFalse(FOLDED.contains(role.meaning()), "a markup line left as text: " + line.text());
                if (role.meaning() == IkeConcept.HEADING_LINE || role.meaning() == IkeConcept.BLOCK_TITLE_LINE) {
                    assertFalse(node.markup().text().contains(line.text()), "a title line left as written: " + line.text());
                }
                if (role.meaning() == IkeConcept.BLANK_LINE) {
                    assertTrue(node.markup().text().contains("\n"), "a blank line outside a verbatim body: " + node.label());
                }
            }
        }
    }

    @Test
    void kindsReadAsWords() {
        assertEquals("Open block", DocumentGraph.kindName(Block.Kind.OPEN));
        assertEquals("Bibliography entry", DocumentGraph.kindName(Block.Kind.BIBLIOGRAPHY_ENTRY));
        assertEquals("Row", DocumentGraph.kindName(Block.Kind.TABLE_ROW));
    }
}
