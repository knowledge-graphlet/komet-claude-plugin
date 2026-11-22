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
package network.ike.komet.claude.narrator;

import design.knowledge.stamp.adoc.git.AnnotationCommit;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import network.ike.komet.document.CommitAuthors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commit a narration of a document's commit is written as (IKE-Network/ike-issues#1399, decision 4):
 * the person's assistant's, under the person, when the file of authors names an assistant, and then its
 * annotation names no annotator of its own; with no assistant named, git's own author's, naming the
 * shared narrator, as before. The scratch repositories run no hooks: a hook from the runner's global
 * core.hooksPath would otherwise change what the test asserts. The names are invented.
 */
class NarrationCommitTest {

    private static final PublicId NARRATION = PublicIds.of(UUID.fromString("2f4e6a8c-1b3d-5f70-9a2b-4c6d8e0f1a3b"));

    @TempDir
    Path root;

    @Test
    void aNarrationIsTheAssistantsCommitUnderThePerson() throws Exception {
        Path dir = repository("assistant");
        String narrated = git(dir, "rev-parse", "HEAD");
        CommitAuthors authors = new CommitAuthors(new CommitAuthors.Author("Ada Lovelace", "ada@komet.ike.network"),
                Optional.of(new CommitAuthors.Author("Ada's Claude", "ada-claude@komet.ike.network")));

        AnnotationCommit.Result written = CommitNarrator.writeNarration(dir, narrated, NARRATION, " Two claims, first stated. \n",
                authors.assistantsWork());

        assertTrue(written.ok(), written.output());
        assertEquals("Ada's Claude <ada-claude@komet.ike.network>", head(dir, "%an <%ae>"), "the assistant wrote it");
        assertEquals("Ada Lovelace <ada@komet.ike.network>", head(dir, "%cn <%ce>"), "the person committed it");
        String trailer = head(dir, "%(trailers:key=Annotation,valueonly)");
        assertTrue(trailer.contains("[" + narrated.substring(0, 7) + "]"), "a comment on the narrated commit's STAMP: " + trailer);
        assertFalse(trailer.contains(" by "), "the commit's author is the annotator: " + trailer);
        assertTrue(trailer.contains(" id " + NARRATION.asUuidArray()[0]), trailer);
        assertEquals("Two claims, first stated.", git(dir, "log", "-1", "--format=%b").lines().findFirst().orElseThrow());
        assertEquals("N", head(dir, "%G?"), "unsigned");
    }

    @Test
    void withNoAssistantANarrationIsGitsOwnAuthorsNamingTheNarrator() throws Exception {
        Path dir = repository("no-assistant");
        String narrated = git(dir, "rev-parse", "HEAD");

        AnnotationCommit.Result written = CommitNarrator.writeNarration(dir, narrated, NARRATION, "Two claims, first stated.", Map.of());

        assertTrue(written.ok(), written.output());
        assertEquals("rita <rita@example.test>", head(dir, "%an <%ae>"));
        assertEquals("rita <rita@example.test>", head(dir, "%cn <%ce>"));
        String trailer = head(dir, "%(trailers:key=Annotation,valueonly)");
        assertTrue(trailer.contains(" by ") && trailer.contains(NarratorIdentity.NARRATOR_AUTHOR_NAME), trailer);
        assertTrue(trailer.contains(NarratorIdentity.NARRATOR_AUTHOR.publicId().asUuidArray()[0].toString()), trailer);
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "rita");
        git(dir, "config", "user.email", "rita@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "core.hooksPath", "/dev/null");
        Files.writeString(dir.resolve("note.adoc"), "= Note\n\nThe first claim.\n");
        git(dir, "add", "note.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "Write the note");
        return dir;
    }

    private static String head(Path dir, String format) throws Exception {
        return git(dir, "log", "-1", "--format=" + format);
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
    }
}
