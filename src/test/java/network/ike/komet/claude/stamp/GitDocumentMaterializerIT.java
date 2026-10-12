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
import design.knowledge.stamp.adoc.model.Affirmation;
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
        assertEquals(1, first.affirmations());
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

        GitDocumentMaterializer.Result second = GitDocumentMaterializer.materialize(root, file);
        GitDocumentMaterializer.StoredDocument again = GitDocumentMaterializer.load(second.anchor()).orElseThrow();

        assertEquals(first.anchor(), second.anchor());
        assertEquals(0, second.commits(), "every commit was already recorded");
        assertEquals(stored.manifestVersions(), again.manifestVersions(), "re-materializing adds no versions");
        assertEquals(stored.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList(),
                again.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList(), "identities are deterministic");
    }

    /** alice creates three lines; bob inserts a topic-id line and edits the middle; Rita affirms lines 2–3 of bob's version. */
    private Path seedRepository() throws Exception {
        git("init", "-q", "-b", "main");
        Path file = root.resolve("doc.adoc");
        Files.writeString(file, "one\ntwo\nthree\n");
        git("add", "doc.adoc");
        git("-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign", "--date=2026-01-01T00:00:00Z", "-m", "add doc");
        Files.writeString(file, ":topic-id: anf-demo-topic\none\ntwo-b\nthree\n");
        git("add", "doc.adoc");
        git("-c", "user.name=bob", "-c", "user.email=bob@example.test", "commit", "-q", "--no-gpg-sign", "--date=2026-01-02T00:00:00Z", "-m", "title and edit");
        String head;
        try (GitRepo repo = GitRepo.open(root)) {
            head = repo.headRevision();
        }
        lastContentCommit = head;
        Affirmation affirmation = Affirmation.of(Path.of("doc.adoc"), 3, 3, head, List.of(":topic-id: anf-demo-topic", "one", "two-b", "three"));
        git("-c", "user.name=Rita Reviewer", "-c", "user.email=rita@example.test", "commit", "-q", "--no-gpg-sign", "--allow-empty",
                "--date=2026-01-03T00:00:00Z", "-m", "Affirm doc.adoc 3-3", "--trailer", affirmation.trailer());
        return file;
    }

    private void git(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
