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
import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.dialect.Reference;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;

import java.time.Instant;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.LineRange;
import design.knowledge.stamp.adoc.model.StampFile;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.State;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Git to graph against an ephemeral store seeded with the IKE starter set: a three-commit
 * history with an affirmation materializes into the journal-shaped document, reads back in
 * order with the commits' own STAMPs, converges when materialized twice, and agrees with the
 * core's {@code asOf} view commit for commit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitDocumentMaterializerIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    @TempDir
    Path root;

    /** The last commit that changed the file (the affirmation after it is empty). */
    private String lastContentCommit;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertTrue(count > 0, "Should load entities from the starter-data protobuf file");
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void materializesReadsBackAndConverges() throws Exception {
        Path file = seedRepository();

        GitDocumentMaterializer.Result first = GitDocumentMaterializer.materialize(root, file);
        GitDocumentMaterializer.StoredDocument stored = GitDocumentMaterializer.load(first.anchor()).orElseThrow();

        assertEquals("anf-demo-topic", first.name(), "the anchor is named by the file's :topic-id:");
        assertEquals(3, first.commits());
        assertEquals(5, first.lines(), "three lines at creation, then a title and an edited middle line");
        assertEquals(1, first.annotations());
        assertEquals(List.of(":topic-id: anf-demo-topic", "one", "two-b", "three"), stored.lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList());
        assertEquals(2, stored.manifestVersions(), "one manifest version per commit that changed the file");
        try (GitRepo repo = GitRepo.open(root)) {
            assertEquals(lastContentCommit, stored.commit(), "the git source carries the last commit that changed the file, not the empty affirmation after it");
            StampFile head = repo.stampHistoryOf(Path.of("doc.adoc")).atHead();
            // The store's introducer STAMP time is the commit's author time, line for line with the core view.
            for (int i = 0; i < stored.lines().size(); i++) {
                int line = i + 1;
                var introducer = head.stamps().values().stream().filter(r -> r.introduced().contains(line)).findFirst().orElseThrow();
                assertEquals(introducer.time().toEpochMilli(), stored.lines().get(i).introduced().time(), "line " + line);
                assertEquals(State.ACTIVE, stored.lines().get(i).latest().state());
            }
        }
        GitDocumentMaterializer.StoredLine affirmed = stored.lines().get(2);
        assertEquals(1, affirmed.affirmers().size(), "the edited middle line was affirmed after the edit");
        assertEquals("Rita Reviewer", affirmed.affirmers().get(0).author().description());
        assertTrue(stored.lines().get(0).affirmers().isEmpty());
        GitDocumentMaterializer.StoredAnnotation annotation = affirmed.annotations().get(0);
        assertEquals(AnnotationKind.AFFIRMATION, annotation.kind());
        assertEquals(affirmed.introduced().nid(), annotation.reviewedStamp(), "the reviewed version is bob's, the one Rita saw");
        assertEquals("", annotation.text());

        GitDocumentMaterializer.Result second = GitDocumentMaterializer.materialize(root, file);
        GitDocumentMaterializer.StoredDocument again = GitDocumentMaterializer.load(second.anchor()).orElseThrow();

        assertEquals(first.anchor(), second.anchor());
        assertEquals(0, second.commits(), "every commit was already recorded");
        assertEquals(stored.manifestVersions(), again.manifestVersions(), "re-materializing adds no versions");

        GitDocumentMaterializer.Result third = GitDocumentMaterializer.materialize(root, file, false);
        GitDocumentMaterializer.StoredDocument rewritten = GitDocumentMaterializer.load(third.anchor()).orElseThrow();

        assertEquals(3, third.commits(), "with the gate off every commit is written again");
        assertEquals(stored.manifestVersions(), rewritten.manifestVersions(),
                "and the store still converges: a complete STAMP tuple is one stamp, identical versions merge to one");
        assertEquals(stored.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList(),
                again.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList(), "identities are deterministic");
    }

    @Test
    void aLaterRefutationSupersedesAndCommentsCarryTheirText() throws Exception {
        // Its own repository with its own history: the class shares one store, and a line element's
        // identity is its introducing commit's SHA and position. Seeded the same way in the same
        // second, this repository's commits would share the first test's SHAs, and with them its line
        // elements — so whichever test ran first would stamp the other's lines.
        Path repoRoot = java.nio.file.Files.createDirectories(root.resolve("refutation"));
        gitIn(repoRoot, "init", "-q", "-b", "main");
        gitIn(repoRoot, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign", "--allow-empty",
                "--date=2025-12-31T00:00:00Z", "-m", "start the refutation repository");
        Path file = seedRepository(repoRoot);
        List<String> content = List.of(":topic-id: anf-demo-topic", "one", "two-b", "three");
        Annotation refutation = Annotation.of(AnnotationKind.REFUTATION, LineRange.of(Path.of("doc.adoc"), 3, 3, lastContentCommit, content));
        gitIn(repoRoot, "-c", "user.name=Rita Reviewer", "-c", "user.email=rita@example.test", "commit", "-q", "--no-gpg-sign", "--allow-empty",
                "--date=2026-01-04T00:00:00Z", "-m", "Refute doc.adoc 3-3", "-m", "Two-b overstates the finding.", "--trailer", refutation.trailer());
        Annotation comment = Annotation.of(AnnotationKind.COMMENT, LineRange.of(Path.of("doc.adoc"), 3, 4, lastContentCommit, content));
        gitIn(repoRoot, "-c", "user.name=sam", "-c", "user.email=sam@example.test", "commit", "-q", "--no-gpg-sign", "--allow-empty",
                "--date=2026-01-05T00:00:00Z", "-m", "Comment on doc.adoc 3-4", "-m", "See the 2025 review.", "--trailer", comment.trailer());

        GitDocumentMaterializer.Result result = GitDocumentMaterializer.materialize(repoRoot, file);
        GitDocumentMaterializer.StoredDocument stored = GitDocumentMaterializer.load(result.anchor()).orElseThrow();

        assertEquals(3, result.annotations());
        GitDocumentMaterializer.StoredLine middle = stored.lines().get(2);
        assertTrue(middle.affirmers().isEmpty(), "Rita's refutation replaces her affirmation");
        assertEquals(1, middle.refuters().size());
        assertEquals(List.of(AnnotationKind.REFUTATION, AnnotationKind.COMMENT), middle.annotations().stream().map(GitDocumentMaterializer.StoredAnnotation::kind).toList());
        assertEquals("Two-b overstates the finding.", middle.annotations().get(0).text());
        assertEquals("See the 2025 review.", stored.lines().get(3).annotations().get(0).text());
    }

    /** alice creates three lines; bob inserts a topic-id line and edits the middle; Rita affirms lines 2–3 of bob's version. */
    @Test
    void structureReferencesAndRetirementsAreMaterialized() throws Exception {
        Path dir = Files.createDirectories(root.resolve("structured"));
        gitIn(dir, "init", "-q", "-b", "main");
        Path file = dir.resolve("topic.adoc");
        String listing = "[source,yaml]\n----\nkey: value\n----\n";
        String v1 = """
                // anf-demo-structure
                :topic-id: anf-demo-structure
                :topic-type: concept

                [[anf-demo-structure]]
                = Demo Title

                First claim, see xref:other-topic[] and ((a term)).
                Second claim.

                == Details

                """ + listing;
        Files.writeString(file, v1);
        gitIn(dir, "add", "topic.adoc");
        gitIn(dir, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign", "--date=2026-02-01T00:00:00Z", "-m", "topic");
        String v2 = v1.replace("Second claim.", "Second claim, revised.") + "\n== More\n\n* one\n* two\n";
        Files.writeString(file, v2);
        gitIn(dir, "add", "topic.adoc");
        gitIn(dir, "-c", "user.name=bob", "-c", "user.email=bob@example.test", "commit", "-q", "--no-gpg-sign", "--date=2026-02-02T00:00:00Z", "-m", "revise and extend");
        Files.writeString(file, v2.replace(listing, ""));
        gitIn(dir, "add", "topic.adoc");
        gitIn(dir, "-c", "user.name=carol", "-c", "user.email=carol@example.test", "commit", "-q", "--no-gpg-sign", "--date=2026-02-03T00:00:00Z", "-m", "drop the listing");

        GitDocumentMaterializer.Result result = GitDocumentMaterializer.materialize(dir, file);
        assertEquals(3, result.commits());
        assertTrue(result.blocks() > 0 && result.references() > 0, "structure and references were written");
        GitDocumentMaterializer.StoredDocument stored = GitDocumentMaterializer.load(result.anchor()).orElseThrow();
        assertEquals(List.of("Demo Title", "anf-demo-structure", "concept", "", "", "", "", "", ""), stored.attributes(),
                "the attributes pattern: title, then the topic attributes, empty when absent");
        assertNotNull(stored.root());
        assertEquals(Block.Kind.DOCUMENT, stored.root().kind());
        List<GitDocumentMaterializer.StoredBlock> sections = stored.blocks().values().stream()
                .filter(b -> b.kind() == Block.Kind.SECTION).sorted(java.util.Comparator.comparing(GitDocumentMaterializer.StoredBlock::title)).toList();
        assertEquals(List.of("Details", "More"), sections.stream().map(GitDocumentMaterializer.StoredBlock::title).toList());
        assertTrue(stored.blocks().values().stream().noneMatch(b -> b.kind() == Block.Kind.LISTING), "the listing dropped in the third commit is inactive");
        GitDocumentMaterializer.StoredBlock paragraph = stored.blocks().values().stream().filter(b -> b.kind() == Block.Kind.PARAGRAPH).findFirst().orElseThrow();
        assertEquals(2, paragraph.members().size(), "the paragraph's members are its two lines");
        assertEquals("First claim, see xref:other-topic[] and ((a term)).", stored.line(paragraph.members().get(0)).orElseThrow().text());
        assertEquals("Second claim, revised.", stored.line(paragraph.members().get(1)).orElseThrow().text(), "the revised line's latest text");
        List<GitDocumentMaterializer.StoredReference> refs = stored.references().get(paragraph.members().get(0));
        assertEquals(List.of(Reference.Kind.CROSS_REFERENCE, Reference.Kind.INDEX_TERM),
                refs.stream().sorted(java.util.Comparator.comparing(r -> r.kind().name())).map(GitDocumentMaterializer.StoredReference::kind).toList());
        assertEquals("other-topic", refs.stream().filter(r -> r.kind() == Reference.Kind.CROSS_REFERENCE).findFirst().orElseThrow().target());
        assertEquals("a term", refs.stream().filter(r -> r.kind() == Reference.Kind.INDEX_TERM).findFirst().orElseThrow().target());
        GitDocumentMaterializer.StoredBlock list = stored.blocks().values().stream().filter(b -> b.kind() == Block.Kind.LIST).findFirst().orElseThrow();
        assertEquals(2, list.members().size());
        assertTrue(list.members().stream().allMatch(nid -> stored.blocks().get(nid) != null && stored.blocks().get(nid).kind() == Block.Kind.LIST_ITEM));
        GitDocumentMaterializer.StoredBlock more = sections.get(1);
        assertEquals(Instant.parse("2026-02-02T00:00:00Z").toEpochMilli(), more.stamp().time(), "a block is STAMPed by the commit that introduced it");
        assertEquals(Instant.parse("2026-02-03T00:00:00Z").toEpochMilli(), sections.get(0).stamp().time(),
                "a section whose members changed (the listing left it) is re-STAMPed by that commit");
        assertEquals(Instant.parse("2026-02-02T00:00:00Z").toEpochMilli(), paragraph.stamp().time(),
                "a revised line is a new line under git blame, so the paragraph's membership changed under the revising commit");
        assertEquals(Instant.parse("2026-02-01T00:00:00Z").toEpochMilli(), stored.line(paragraph.members().get(0)).orElseThrow().introduced().time(),
                "the untouched first line keeps the introducing commit's STAMP");

        long anchorNid = EntityService.get().nidForPublicId(result.anchor());
        SemanticEntity<SemanticEntityVersion> listingBlock = EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, IkePattern.DOCUMENT_BLOCK_PATTERN.nid()).toList().stream()
                .filter(s -> s.versions().stream().anyMatch(v -> v.state() == State.INACTIVE)).findFirst().orElseThrow();
        SemanticEntityVersion retired = listingBlock.versions().stream().filter(v -> v.state() == State.INACTIVE).findFirst().orElseThrow();
        assertEquals(Instant.parse("2026-02-03T00:00:00Z").toEpochMilli(), retired.time(), "the listing's withdrawal is STAMPed by the commit that dropped it");
        assertEquals(IkeConcept.DOCUMENT_LISTING.nid(), ((dev.ikm.tinkar.terms.EntityProxy) retired.fieldValues().get(0)).nid());

        int before = blockVersions(anchorNid);
        GitDocumentMaterializer.materialize(dir, file, false);
        assertEquals(before, blockVersions(anchorNid), "a full rewrite converges: no new block versions");
        GitDocumentMaterializer.StoredDocument again = GitDocumentMaterializer.load(result.anchor()).orElseThrow();
        assertEquals(stored.blocks().keySet(), again.blocks().keySet());
    }

    @Test
    void exportGivesTheCommittedTextBackUnchanged() throws Exception {
        Path dir = Files.createDirectories(root.resolve("exported"));
        gitIn(dir, "init", "-q", "-b", "main");
        Path file = dir.resolve("docs/round-trip.adoc");
        Files.createDirectories(file.getParent());
        String text = """
                :topic-id: anf-round-trip
                [[anf-round-trip]]
                = Round Trip

                A claim with trailing spaces\s\s
                	a tab-indented line

                ----
                  code keeps its indentation
                ----
                * one
                * two
                """;
        Files.writeString(file, text);
        gitIn(dir, "add", "docs/round-trip.adoc");
        gitIn(dir, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign", "--date=2026-03-01T00:00:00Z", "-m", "round trip");
        GitDocumentMaterializer.Result result = GitDocumentMaterializer.materialize(dir, file);
        GitDocumentExporter.Exported exported = GitDocumentExporter.export(result.anchor()).orElseThrow();
        assertEquals("docs/round-trip.adoc", exported.path());
        assertEquals(text, exported.text(), "the store gives the committed text back byte for byte");
        assertEquals(GitDocumentExporter.Relation.IDENTICAL_TO_HEAD, GitDocumentExporter.compare(exported, dir).relation());
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        Path written = GitDocumentExporter.writeTo(exported, elsewhere);
        assertEquals(elsewhere.resolve("docs/round-trip.adoc"), written);
        assertEquals(text, Files.readString(written));
        Files.writeString(file, text.replace("A claim", "An edited claim"));
        gitIn(dir, "add", "docs/round-trip.adoc");
        gitIn(dir, "-c", "user.name=bob", "-c", "user.email=bob@example.test", "commit", "-q", "--no-gpg-sign", "--date=2026-03-02T00:00:00Z", "-m", "edit");
        GitDocumentExporter.Comparison stale = GitDocumentExporter.compare(exported, dir);
        assertEquals(GitDocumentExporter.Relation.DIFFERS_FROM_HEAD, stale.relation(), "HEAD moved on after the materialization");
        assertEquals(5, stale.firstDifference());
    }

    private static int blockVersions(long anchorNid) {
        int count = 0;
        for (SemanticEntity<SemanticEntityVersion> s : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, IkePattern.DOCUMENT_BLOCK_PATTERN.nid()).toList()) {
            for (SemanticEntityVersion v : s.versions()) {
                if (v.time() != Long.MAX_VALUE) {
                    count++;
                }
            }
        }
        return count;
    }

    private void gitIn(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }

    private Path seedRepository() throws Exception {
        return seedRepository(root);
    }

    private Path seedRepository(Path dir) throws Exception {
        gitIn(dir, "init", "-q", "-b", "main");
        Path file = dir.resolve("doc.adoc");
        Files.writeString(file, "one\ntwo\nthree\n");
        gitIn(dir, "add", "doc.adoc");
        gitIn(dir, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign", "--date=2026-01-01T00:00:00Z", "-m", "add doc");
        Files.writeString(file, ":topic-id: anf-demo-topic\none\ntwo-b\nthree\n");
        gitIn(dir, "add", "doc.adoc");
        gitIn(dir, "-c", "user.name=bob", "-c", "user.email=bob@example.test", "commit", "-q", "--no-gpg-sign", "--date=2026-01-02T00:00:00Z", "-m", "title and edit");
        String head;
        try (GitRepo repo = GitRepo.open(dir)) {
            head = repo.headRevision();
        }
        lastContentCommit = head;
        Annotation affirmation = Annotation.of(AnnotationKind.AFFIRMATION,
                LineRange.of(Path.of("doc.adoc"), 3, 3, head, List.of(":topic-id: anf-demo-topic", "one", "two-b", "three")));
        gitIn(dir, "-c", "user.name=Rita Reviewer", "-c", "user.email=rita@example.test", "commit", "-q", "--no-gpg-sign", "--allow-empty",
                "--date=2026-01-03T00:00:00Z", "-m", "Affirm doc.adoc 3-3", "--trailer", affirmation.trailer());
        return file;
    }

    private void git(String... args) throws Exception {
        gitIn(root, args);
    }
}
