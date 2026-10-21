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

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An assembly materializes as itself plus every included file, each its own document in
 * include order, and a second run writes nothing (IKE-Network/ike-issues#1307). The fixture
 * is shaped like the ANF repository: a compendium whose {@code :topics:} attribute names a
 * build-time copy, resolved through the topic-library convention onto the committed sources,
 * and one include that nothing resolves, reported by name while the rest proceed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitDocumentAssemblyIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    @TempDir
    Path root;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertTrue(count > 0, "Should load entities from the starter-data protobuf file");
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void anAssemblyMaterializesEveryTopicOnceAndARerunWritesNothing() throws Exception {
        Path repo = Files.createDirectories(root.resolve("assembly"));
        gitIn(repo, "init", "-q", "-b", "main");
        Path topics = Files.createDirectories(repo.resolve("topics/src/docs/asciidoc/topics/anf"));
        Files.writeString(topics.resolve("first.adoc"), """
                :topic-id: anf-first
                [[anf-first]]
                = First Topic

                One claim.
                Another claim.
                """);
        Files.writeString(topics.resolve("second.adoc"), """
                :topic-id: anf-second
                [[anf-second]]
                = Second Topic

                A claim that cites xref:anf-first[].
                """);
        Path compendium = Files.createDirectories(repo.resolve("compendium/src/docs/asciidoc")).resolve("compendium.adoc");
        Files.writeString(compendium, """
                = Fixture Compendium
                :doctype: book

                // resolved by the build, so the topic-library convention stands in
                :topics: {generated}/topics-asciidoc

                == Part One

                include::{topics}/topics/anf/first.adoc[leveloffset=+2]

                include::{topics}/topics/anf/second.adoc[leveloffset=+2]

                include::{topics}/topics/anf/first.adoc[leveloffset=+2]

                include::{topics}/topics/anf/absent.adoc[leveloffset=+2]
                """);
        gitIn(repo, "add", "topics/src/docs/asciidoc/topics/anf/first.adoc", "topics/src/docs/asciidoc/topics/anf/second.adoc",
                "compendium/src/docs/asciidoc/compendium.adoc");
        gitIn(repo, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-04-01T00:00:00Z", "-m", "assemble two topics");

        GitDocumentMaterializer.AssemblyResult first = GitDocumentMaterializer.materializeAssembly(repo, compendium);

        assertEquals(List.of("compendium/src/docs/asciidoc/compendium.adoc", "anf-first", "anf-second"),
                first.documents().stream().map(GitDocumentMaterializer.Result::name).toList(),
                "the assembly itself, then each included topic once, in include order");
        assertEquals(3, first.commits(), "one commit per document");
        assertTrue(first.lines() > 0 && first.blocks() > 0);
        assertEquals(1, first.unresolved().size(), "the absent topic is reported, not fatal");
        GitDocumentMaterializer.Unresolved absent = first.unresolved().getFirst();
        assertEquals(Path.of("compendium/src/docs/asciidoc/compendium.adoc"), absent.including());
        assertEquals(15, absent.line());
        assertEquals("{topics}/topics/anf/absent.adoc", absent.target());
        for (GitDocumentMaterializer.Result document : first.documents()) {
            assertTrue(GitDocumentMaterializer.load(document.anchor()).isPresent(), document.name() + " is in the store");
        }
        Map<String, ?> stored = GitDocumentMaterializer.storedDocuments();
        assertTrue(stored.containsKey("anf-first") && stored.containsKey("anf-second"), "both topics are in the chooser: " + stored.keySet());

        GitDocumentMaterializer.AssemblyResult again = GitDocumentMaterializer.materializeAssembly(repo, compendium);

        assertEquals(first.documents().stream().map(GitDocumentMaterializer.Result::anchor).toList(),
                again.documents().stream().map(GitDocumentMaterializer.Result::anchor).toList(), "the same documents");
        assertEquals(0, again.commits(), "a re-run writes nothing");
        assertEquals(0, again.lines());
        assertEquals(0, again.blocks());
        assertEquals(first.unresolved(), again.unresolved());
    }

    /** An included file that is itself an aggregator is followed; a cycle ends where it began. */
    @Test
    void aNestedAggregatorIsFollowedAndACycleEnds() throws Exception {
        Path repo = Files.createDirectories(root.resolve("nested"));
        gitIn(repo, "init", "-q", "-b", "main");
        Files.createDirectories(repo.resolve("docs/parts"));
        Files.writeString(repo.resolve("docs/book.adoc"), "= Book\n\ninclude::parts/part.adoc[]\n");
        Files.writeString(repo.resolve("docs/parts/part.adoc"), ":topic-id: nested-part\n= Part\n\ninclude::leaf.adoc[]\n\ninclude::../book.adoc[]\n");
        Files.writeString(repo.resolve("docs/parts/leaf.adoc"), ":topic-id: nested-leaf\n= Leaf\n\nA claim.\n");
        gitIn(repo, "add", "docs/book.adoc", "docs/parts/part.adoc", "docs/parts/leaf.adoc");
        gitIn(repo, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-04-02T00:00:00Z", "-m", "nest");

        GitDocumentMaterializer.AssemblyResult result = GitDocumentMaterializer.materializeAssembly(repo, Path.of("docs/book.adoc"));

        assertEquals(List.of("docs/book.adoc", "nested-part", "nested-leaf"),
                result.documents().stream().map(GitDocumentMaterializer.Result::name).toList());
        assertTrue(result.unresolved().isEmpty(), "the cycle back to the book is neither followed nor an error");
    }

    private static void gitIn(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
