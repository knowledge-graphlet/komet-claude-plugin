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

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.ConceptFacade;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.stamp.StoredNodeFacts.NodeFacts;
import network.ike.komet.claude.stamp.StoredNodeFacts.Property;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Graph view over a document stored as semantic vertices (IKE-Network/ike-issues#1313): each
 * node is read as the vertex it is, its meaning the vertex's and its properties the vertex's,
 * keyed by concept, and the tree is the store's, blank lines included.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VertexGraphIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    @TempDir
    Path root;

    private ViewCalculator view;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "vertex");
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        view = Calculators.View.Default();
    }

    @AfterAll
    void teardownDatabase() {
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    @Test
    void theGraphShowsTheVerticesAsStored() throws Exception {
        Path dir = Files.createDirectories(root.resolve("graph"));
        git(dir, "init", "-q", "-b", "main");
        Path file = dir.resolve("topic.adoc");
        Files.writeString(file, """
                :topic-id: dev-vertex-graph

                = Graph Title

                First claim, see xref:other-topic[].

                == Details

                ----
                x

                y
                ----
                """);
        git(dir, "add", "topic.adoc");
        git(dir, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-04-01T00:00:00Z", "-m", "topic");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(anchor).orElseThrow();
        DocumentGraph.Node graph = DocumentGraph.of(DocumentAssembly.single(document, "topic.adoc"));

        // The root: the document's meaning, referencing the document's own pattern, named for the topic.
        NodeFacts rootFacts = facts(graph);
        assertEquals(name(IkeConcept.ASCIIDOC_DOCUMENT), rootFacts.meaning(), "the vertex's meaning, not the pattern's");
        assertEquals("dev-vertex-graph", rootFacts.patternName());
        assertEquals(EntityHandle.get(graph.nid()).expectSemantic().patternNid(), rootFacts.referencedNid());
        assertEquals(name(IkeConcept.DOCUMENT_ELEMENT_TITLE), rootFacts.properties().getFirst().key());
        assertEquals("Graph Title", rootFacts.properties().getFirst().value());
        assertEquals(name(IkeConcept.DOCUMENT_ELEMENT_MEMBERS), rootFacts.properties().getLast().key(), "Members last");

        // The tree is the store's: the blank lines the root holds are its members in the graph.
        assertTrue(graph.children().stream().anyMatch(n -> n.kind() == DocumentGraph.Kind.LINE && n.line().text().isEmpty()
                && facts(n).meaning().equals(name(IkeConcept.BLANK_LINE))), "a blank line is a node of the graph");

        // A paragraph, its prose line previewing its Line source, the reference hanging from it.
        DocumentGraph.Node paragraph = find(graph, n -> n.kind() == DocumentGraph.Kind.BLOCK && facts(n).meaning().equals(name(IkeConcept.DOCUMENT_PARAGRAPH)));
        DocumentGraph.Node claim = paragraph.children().getFirst();
        NodeFacts claimFacts = facts(claim);
        assertEquals(name(IkeConcept.PROSE_LINE), claimFacts.meaning());
        Property source = claimFacts.primary().orElseThrow();
        assertEquals(name(IkeConcept.LINE_SOURCE), source.key(), "a line previews its Line source");
        assertEquals("First claim, see xref:other-topic[].", source.value());
        DocumentGraph.Node xref = claim.children().stream().filter(n -> n.kind() == DocumentGraph.Kind.REFERENCE).findFirst().orElseThrow();
        assertTrue(facts(xref).properties().stream().anyMatch(p -> p.value().equals("other-topic")), "the reference carries its target");
        assertEquals(document.root().nid(), facts(xref).referencedNid(), "every vertex references the root");

        // A heading line carries its title; a listing holds its interior blank line.
        DocumentGraph.Node heading = find(graph, n -> n.kind() == DocumentGraph.Kind.LINE && n.line().text().equals("== Details"));
        assertEquals(name(IkeConcept.HEADING_LINE), facts(heading).meaning());
        assertTrue(facts(heading).properties().stream().anyMatch(p -> p.value().equals("Details")));
        DocumentGraph.Node listing = find(graph, n -> n.kind() == DocumentGraph.Kind.BLOCK && facts(n).meaning().equals(name(IkeConcept.DOCUMENT_LISTING)));
        assertEquals(List.of("----", "x", "", "y", "----"), listing.children().stream().map(n -> n.line().text()).toList());
        assertEquals(List.of(name(IkeConcept.DELIMITER_LINE), name(IkeConcept.VERBATIM_LINE), name(IkeConcept.BLANK_LINE),
                        name(IkeConcept.VERBATIM_LINE), name(IkeConcept.DELIMITER_LINE)),
                listing.children().stream().map(n -> facts(n).meaning()).toList());
        assertEquals(4, document.blocks().get(listing.nid()).members().size(), "the other views read the listing's content without it");
    }

    @Test
    void theDocBookProjectionReadsTheVertexTree() throws Exception {
        Path dir = Files.createDirectories(root.resolve("docbook"));
        git(dir, "init", "-q", "-b", "main");
        Path file = dir.resolve("topic.adoc");
        Files.writeString(file, """
                :topic-id: dev-vertex-docbook

                = DocBook Title

                First claim, see xref:other-topic[] and *this*.

                == Details

                [source,text]
                ----
                x

                y
                ----
                """);
        commit(dir, "topic.adoc");
        GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(GitDocumentMaterializer.materialize(dir, file).anchor()).orElseThrow();
        DocumentGraph.Node graph = DocumentGraph.docbook(DocumentAssembly.single(document, "topic.adoc"));
        DocumentGraphTest.assertNoMarkupLineLeft(graph, document);

        assertEquals("dev-vertex-docbook", graph.markup().attributes().get("topic-id"));
        DocumentGraph.Node paragraph = find(graph, n -> n.blockKind() == design.knowledge.stamp.adoc.dialect.Block.Kind.PARAGRAPH);
        assertEquals(name(IkeConcept.DOCUMENT_PARAGRAPH), facts(paragraph).meaning(), "a block is still its vertex");
        assertEquals(List.of("Sentence"), paragraph.children().stream().map(n -> n.markup().element()).toList(),
                "the paragraph's one stored sentence is a node of its own");
        DocumentGraph.Node sentence = paragraph.children().getFirst();
        assertEquals(name(IkeConcept.SENTENCE), facts(sentence).meaning(), "and is its vertex");
        assertEquals(List.of("text", "Cross reference", "text", "Strong", "text"),
                sentence.children().stream().map(n -> n.markup().element()).toList());
        assertEquals("other-topic", sentence.children().get(1).markup().attributes().get("linkend"));
        assertEquals(name(IkeConcept.ASCIIDOC_CROSS_REFERENCE), facts(sentence.children().get(1)).meaning(), "an inline element is its vertex");
        DocumentGraph.Node flat = find(DocumentGraph.docbook(DocumentAssembly.single(document, "topic.adoc"), false),
                n -> n.blockKind() == design.knowledge.stamp.adoc.dialect.Block.Kind.PARAGRAPH);
        assertEquals(List.of("text", "Cross reference", "text", "Strong", "text"), flat.children().stream().map(n -> n.markup().element()).toList(),
                "flattened, the paragraph holds the sentence's mixed content");
        DocumentGraph.Node listing = find(graph, n -> n.blockKind() == design.knowledge.stamp.adoc.dialect.Block.Kind.LISTING);
        assertEquals("text", listing.markup().attributes().get("language"));
        assertEquals(List.of("x\n\ny"), listing.children().stream().map(n -> n.markup().text()).toList(),
                "the body, the blank line the vertex tree holds in it kept");
    }

    /**
     * A sentence two people wrote parts of (refinement (b) of design-2026-10-21-stored-text-segments):
     * its node carries its own STAMP, the version that wrote its words, and the STAMPs of the lines it
     * spans, so who wrote which clause shows in DocBook mode.
     */
    @Test
    void aSentenceShowsTheStampsOfTheLinesItSpans() throws Exception {
        Path dir = Files.createDirectories(root.resolve("fragments"));
        git(dir, "init", "-q", "-b", "main");
        Path file = dir.resolve("topic.adoc");
        Files.writeString(file, "= Fragments\n\nA machine takes it,\nrenews it while working,\nand returns it.\n");
        commitAs(dir, "topic.adoc", "alice", "2026-04-01T00:00:00Z");
        Files.writeString(file, "= Fragments\n\nA machine takes it,\nrenews it while working,\nand returns it when done.\n");
        commitAs(dir, "topic.adoc", "bob", "2026-04-02T00:00:00Z");
        GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(GitDocumentMaterializer.materialize(dir, file).anchor()).orElseThrow();
        DocumentGraph.Node graph = DocumentGraph.docbook(DocumentAssembly.single(document, "topic.adoc"));
        DocumentGraph.Node sentence = find(graph, n -> n.markup() != null && "Sentence".equals(n.markup().element()));
        assertEquals(2, sentence.commits().size(), "alice's two lines and bob's one: two STAMPs");
        assertTrue(sentence.commits().get(0).time() < sentence.commits().get(1).time(), "oldest first");
        assertEquals(sentence.commits().get(1).nid(), sentence.stamp().nid(), "bob's revision wrote the sentence's current words");
    }

    @Test
    @Tag("external") // the lab and ANF checkouts beside this one; run with -Pexternal
    void theFiveRealDocumentsProjectFromTheirVertices() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(DocumentGraphTest.REAL_DOCUMENTS.stream().allMatch(Files::exists),
                "the lab and ANF checkouts are on this machine");
        Path dir = Files.createDirectories(root.resolve("real"));
        git(dir, "init", "-q", "-b", "main");
        for (Path source : DocumentGraphTest.REAL_DOCUMENTS) {
            Path file = dir.resolve(source.getFileName());
            Files.copy(source, file);
            commit(dir, file.getFileName().toString());
            GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(GitDocumentMaterializer.materialize(dir, file).anchor()).orElseThrow();
            DocumentAssembly assembly = DocumentAssembly.of(document, file.getFileName().toString(), path -> java.util.Optional.empty());
            DocumentGraph.Node graph = DocumentGraph.docbook(assembly);
            DocumentGraphTest.assertNoMarkupLineLeft(graph, document);
            assertTrue(DocumentGraphTest.all(graph).stream().anyMatch(n -> n.blockKind() == design.knowledge.stamp.adoc.dialect.Block.Kind.SECTION),
                    source + ": its sections");
        }
    }

    private static void commitAs(Path dir, String path, String author, String date) throws Exception {
        git(dir, "add", path);
        git(dir, "-c", "user.name=" + author, "-c", "user.email=" + author + "@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=" + date, "-m", path);
    }

    private static void commit(Path dir, String path) throws Exception {
        git(dir, "add", path);
        git(dir, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-04-01T00:00:00Z", "-m", path);
    }

    private NodeFacts facts(DocumentGraph.Node node) {
        return StoredNodeFacts.read(node.nid(), view, false).orElseThrow();
    }

    private String name(ConceptFacade concept) {
        return StampNames.name(view, concept.nid());
    }

    private static DocumentGraph.Node find(DocumentGraph.Node from, java.util.function.Predicate<DocumentGraph.Node> test) {
        List<DocumentGraph.Node> stack = new ArrayList<>(List.of(from));
        while (!stack.isEmpty()) {
            DocumentGraph.Node node = stack.removeLast();
            if (node.nid() != 0 && test.test(node)) {
                return node;
            }
            stack.addAll(node.children());
        }
        throw new AssertionError("no such node");
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
