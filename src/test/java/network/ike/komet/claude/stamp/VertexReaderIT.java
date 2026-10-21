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

import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.LineRange;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The readers over a document materialized as semantic vertices (IKE-Network/ike-issues#1313):
 * one history materialized both ways into one store reads back the same through the regular
 * loader, so the card, the Graph view, print, export and the assistant's tools see one document
 * whichever model holds it. Lines, their authored and latest STAMPs, the annotations in force,
 * the block tree, the references, the attributes, the commit and the history are compared; the
 * nids differ, so everything is compared by what it says and who said it when.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VertexReaderIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    @TempDir
    Path root;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
    }

    @AfterAll
    void teardownDatabase() {
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    /**
     * alice writes a topic; bob revises a claim and adds a section; Rita affirms both claims;
     * carol drops the listing and revises the first claim's words, which unseats Rita's
     * affirmation of it. Both models read back alike.
     */
    @Test
    void bothModelsReadBackAlike() throws Exception {
        Path dir = Files.createDirectories(root.resolve("parity"));
        git(dir, "init", "-q", "-b", "main");
        Path file = dir.resolve("topic.adoc");
        String listing = "[source,yaml]\n----\nkey: value\n----\n";
        String v1 = """
                :topic-id: dev-vertex-parity

                = Parity Title

                First claim, see xref:other-topic[] and ((a term)).
                Second claim.

                == Details

                """ + listing;
        commit(dir, file, v1, "alice", "2026-03-01T00:00:00Z", "topic");
        String v2 = v1.replace("Second claim.", "Second claim, revised.") + "\n== More\n\n* one\n* two\n";
        commit(dir, file, v2, "bob", "2026-03-02T00:00:00Z", "revise and extend");
        affirm(dir, v2, List.of("First claim, see xref:other-topic[] and ((a term)).", "Second claim, revised."),
                "2026-03-03T00:00:00Z");
        String v3 = v2.replace(listing, "").replace("First claim, see", "The first claim, see");
        commit(dir, file, v3, "carol", "2026-03-04T00:00:00Z", "drop the listing, revise the first claim");

        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "prose");
        PublicId proseAnchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);           // the default model
        PublicId vertexAnchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        assertNotEquals(proseAnchor, vertexAnchor);
        assertFalse(VertexReader.isVertexDocument(proseAnchor));
        assertTrue(VertexReader.isVertexDocument(vertexAnchor));

        StoredDocument prose = GitDocumentMaterializer.load(proseAnchor).orElseThrow();
        StoredDocument vertex = GitDocumentMaterializer.load(vertexAnchor).orElseThrow();

        assertEquals(lines(prose), lines(vertex), "the same lines, authored and revised by the same people at the same times");
        assertEquals(prose.commit(), vertex.commit());
        assertEquals(prose.manifestVersions(), vertex.manifestVersions(), "one version per commit that changed the file");
        assertEquals(prose.attributes(), vertex.attributes());
        assertEquals(tree(prose, prose.root()), tree(vertex, vertex.root()), "the same block tree over the same lines");
        assertEquals(references(prose), references(vertex));

        StoredLine first = line(vertex, "The first claim, see xref:other-topic[] and ((a term)).");
        StoredLine second = line(vertex, "Second claim, revised.");
        assertTrue(first.affirmers().isEmpty(), "carol's revision of the words unseats Rita's affirmation");
        assertEquals(1, second.affirmers().size(), "the unrevised line keeps it");
        assertEquals(time("2026-03-04T00:00:00Z"), first.introduced().time(), "the revision is the authored version");

        assertEquals(history(prose), history(vertex), "the same history, line by line");
        assertTrue(history(vertex).contains("retired key: value"), "the dropped listing is a retired line");

        assertEquals(vertexAnchor, GitDocumentMaterializer.documentAnchor(dir, "topic.adoc"),
                "a file materialized as vertices is found at its root vertex");
        assertEquals(List.of(vertexAnchor), GitDocumentMaterializer.storedDocumentsByPath().get("topic.adoc"),
                "the prose-element document of the same name and path is superseded");
        assertEquals(vertexAnchor, GitDocumentMaterializer.storedDocuments().get("dev-vertex-parity"),
                "a name finds the vertex document");

        // The superseded prose-element document is deleted as a scope; the vertex document is untouched.
        assertEquals(List.of(proseAnchor), GitDocumentMaterializer.superseded());
        assertTrue(DocumentScopes.of(proseAnchor).closed(), String.valueOf(DocumentScopes.of(proseAnchor).incoming()));
        assertTrue(DocumentScopes.of(vertexAnchor).closed(), String.valueOf(DocumentScopes.of(vertexAnchor).incoming()));
        DocumentScopes.Cleared cleared = DocumentScopes.deleteSuperseded();
        assertEquals(1, cleared.deleted(), String.valueOf(cleared.skipped()));
        assertTrue(cleared.components() > prose.lines().size(), "its lines, blocks, references, manifest, git source and annotations");
        assertTrue(!dev.ikm.tinkar.common.service.PrimitiveData.get().hasPublicId(proseAnchor), "the prose-element document is gone");
        assertEquals(List.of(), GitDocumentMaterializer.superseded());
        StoredDocument after = GitDocumentMaterializer.load(vertexAnchor).orElseThrow();
        assertEquals(lines(vertex), lines(after), "the vertex document reads as before, its shared STAMPs kept");
        assertEquals(history(vertex), history(after));
        assertEquals(List.of(vertexAnchor), GitDocumentMaterializer.storedDocumentsByPath().get("topic.adoc"));
    }

    /** A vertex document is listed under its topic id, the name of its own pattern. */
    @Test
    void aVertexDocumentIsListedByName() throws Exception {
        Path dir = Files.createDirectories(root.resolve("listed"));
        git(dir, "init", "-q", "-b", "main");
        Path file = dir.resolve("listed.adoc");
        commit(dir, file, ":topic-id: dev-vertex-listed\n\n= Listed\n\nA line.\n", "alice", "2026-03-05T00:00:00Z", "listed");
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "vertex");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        assertEquals(anchor, GitDocumentMaterializer.storedDocuments().get("dev-vertex-listed"));
    }

    /** A clone's prose-element document is another working tree's: its origin's vertex document does not supersede it. */
    @Test
    void aClonesDocumentIsNotSupersededByItsOrigins() throws Exception {
        PublicId origin = null;
        PublicId clone = null;
        for (String tree : new String[] {"origin", "origin-clone"}) {
            Path dir = Files.createDirectories(root.resolve(tree));
            git(dir, "init", "-q", "-b", "main");
            Path file = dir.resolve("clone.adoc");
            commit(dir, file, ":topic-id: dev-vertex-clone\n\n= Clone\n\nA line.\n", "alice", "2026-03-06T00:00:00Z", "clone");
            if (tree.equals("origin")) {
                origin = GitDocumentMaterializer.materialize(dir, file).anchor();
            } else {
                System.setProperty(VertexMaterializer.MODEL_PROPERTY, "prose");
                clone = GitDocumentMaterializer.materialize(dir, file).anchor();
                System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
            }
        }
        assertFalse(GitDocumentMaterializer.superseded().contains(clone), "the clone's document is its own");
        List<PublicId> byPath = GitDocumentMaterializer.storedDocumentsByPath().get("clone.adoc");
        assertTrue(byPath.contains(origin) && byPath.contains(clone), "both are listed under the path: " + byPath);
    }

    // ── comparable renderings ─────────────────────────────────────────────────

    private static List<String> lines(StoredDocument document) {
        List<String> out = new ArrayList<>();
        for (StoredLine line : document.lines()) {
            StringBuilder b = new StringBuilder(line.text()).append(" | ").append(who(line.introduced())).append(" | ").append(who(line.latest()));
            line.annotations().forEach(a -> b.append(" | ").append(a.kind()).append(' ').append(who(a.stamp())).append(' ').append(a.text()));
            out.add(b.toString());
        }
        return out;
    }

    private static String tree(StoredDocument document, StoredBlock block) {
        StringBuilder b = new StringBuilder().append(block.kind()).append('/').append(block.level()).append('/').append(block.title())
                .append('/').append(block.parameters()).append('[');
        for (long member : block.members()) {
            StoredBlock child = document.blocks().get(member);
            b.append(child != null ? tree(document, child) : document.line(member).map(StoredLine::text).orElse("?" + member)).append(';');
        }
        return b.append(']').toString();
    }

    private static List<String> references(StoredDocument document) {
        List<String> out = new ArrayList<>();
        for (StoredLine line : document.lines()) {
            for (StoredReference r : document.references().getOrDefault(line.nid(), List.of())) {
                out.add(line.text() + " -> " + r.kind() + " " + r.target() + " " + r.text());
            }
        }
        out.sort(null);
        return out;
    }

    private static String history(StoredDocument document) {
        DocumentHistory history = DocumentHistory.load(document, false);
        StringBuilder b = new StringBuilder();
        for (StoredLine line : document.lines()) {
            b.append(line.text()).append(':');
            history.versions(line.nid()).forEach(v -> b.append(' ').append(v.word()).append(' ').append(who(v.stamp())).append(" \"").append(v.text()).append('"'));
            b.append('\n');
        }
        for (DocumentHistory.RetiredLine r : history.retired()) {
            String after = r.afterNid() == 0 ? "" : document.line(r.afterNid()).map(StoredLine::text).orElse("?");
            b.append("retired ").append(r.text()).append(" after ").append(after).append(' ').append(who(r.retired())).append('\n');
        }
        return b.toString();
    }

    private static StoredLine line(StoredDocument document, String text) {
        return document.lines().stream().filter(l -> l.text().equals(text)).findFirst().orElseThrow(() -> new AssertionError("no line " + text));
    }

    private static String who(StampEntity<?> stamp) {
        return stamp == null ? "-" : stamp.authorNid() + "@" + stamp.time();
    }

    private static long time(String instant) {
        return java.time.Instant.parse(instant).toEpochMilli();
    }

    // ── git ───────────────────────────────────────────────────────────────────

    private void affirm(Path dir, String content, List<String> texts, String date) throws Exception {
        String head;
        try (GitRepo repo = GitRepo.open(dir)) {
            head = repo.headRevision();
        }
        List<String> lines = List.of(content.split("\n", -1)).subList(0, content.split("\n", -1).length - 1);
        List<String> trailers = new ArrayList<>(List.of("-c", "user.name=Rita Reviewer", "-c", "user.email=rita@example.test", "commit", "-q",
                "--no-gpg-sign", "--allow-empty", "--date=" + date, "-m", "Affirm the claims"));
        for (String text : texts) {
            int line = lines.indexOf(text) + 1;
            assertTrue(line > 0, "no line " + text);
            trailers.add("--trailer");
            trailers.add(Annotation.of(AnnotationKind.AFFIRMATION, LineRange.of(Path.of("topic.adoc"), line, line, head, lines)).trailer());
        }
        git(dir, trailers.toArray(String[]::new));
    }

    private void commit(Path dir, Path file, String text, String author, String date, String message) throws Exception {
        Files.writeString(file, text);
        git(dir, "add", file.getFileName().toString());
        git(dir, "-c", "user.name=" + author, "-c", "user.email=" + author + "@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=" + date, "-m", message);
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
