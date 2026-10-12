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
import network.ike.komet.claude.anthropic.AnthropicTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StampToolsTest {

    @TempDir
    Path root;

    private final StampTools.CardLocator noCard = () -> null;

    @Test
    void toolsAreNamedAndSchemaed() {
        List<AnthropicTool> tools = StampTools.tools(noCard);

        assertEquals(List.of("stamp_lines", "propose_affirmation"), tools.stream().map(AnthropicTool::name).toList());
        assertEquals("object", tools.get(0).inputSchema().get("type"));
        assertEquals(List.of("ranges"), tools.get(1).inputSchema().get("required"));
    }

    @Test
    void stampLinesReportsIntroducersAffirmersAndTheQueue() throws Exception {
        Path file = seedRepository();

        String out = StampTools.stampLines(noCard).execute(Map.of("file", file.toString()));

        assertTrue(out.contains("doc.adoc @ "), out);
        assertTrue(out.contains("3 lines, 2 STAMPs, 2 lines affirmed, 1 in the review queue"), out);
        assertTrue(out.contains("Rita Reviewer"), out);
        assertTrue(out.contains("affirmed 2-3 (unsigned)"), out);
        assertTrue(out.contains("Review queue (introduced, affirmed by no one): 1"), out);
        assertTrue(out.contains("    2  "), out);
        assertTrue(out.lines().anyMatch(l -> l.startsWith("    2  ") && l.contains("✓") && l.endsWith("two")), out);
        assertTrue(out.lines().anyMatch(l -> l.startsWith("    1  ") && !l.contains("✓") && l.endsWith("one")), out);
    }

    @Test
    void stampLinesHonorsARange() throws Exception {
        Path file = seedRepository();

        String out = StampTools.stampLines(noCard).execute(Map.of("file", file.toString(), "from", 3, "to", 3));

        assertTrue(out.contains("Lines 3-3"), out);
        assertFalse(out.lines().anyMatch(l -> l.startsWith("    1  ")), out);
    }

    @Test
    void proposeAffirmationPreparesTrailersButWritesNothingWithoutACard() throws Exception {
        Path file = seedRepository();
        String headBefore;
        try (GitRepo repo = GitRepo.open(root)) {
            headBefore = repo.headRevision();
        }

        String out = StampTools.proposeAffirmation(noCard).execute(Map.of("file", file.toString(),
                "ranges", List.of(Map.of("from", 1, "to", 1)), "note", "read it twice"));

        assertTrue(out.startsWith("Prepared 1 trailer(s) for Affirm doc.adoc 1-1:"), out);
        assertTrue(out.contains("Affirms: doc.adoc:1-1@" + headBefore.substring(0, 7) + "#"), out);
        assertTrue(out.contains("No STAMP Provenance card shows this file"), out);
        try (GitRepo repo = GitRepo.open(root)) {
            assertEquals(headBefore, repo.headRevision(), "the tool never commits");
        }
    }

    @Test
    void errorsComeBackAsTextNotExceptions() throws Exception {
        Path file = seedRepository();

        assertTrue(StampTools.stampLines(noCard).execute(Map.of()).startsWith("Error: no file given"));
        assertTrue(StampTools.stampLines(noCard).execute(Map.of("file", root.resolve("missing.adoc").toString())).startsWith("Error:"));
        assertTrue(StampTools.proposeAffirmation(noCard).execute(Map.of("file", file.toString(), "ranges", List.of(Map.of("from", 2, "to", 9))))
                .startsWith("Error:"));
    }

    /**
     * One file by alice, lines 2–3 affirmed by Rita in an unsigned empty commit. Seeded through the
     * git on PATH (as the core's own tests are) rather than JGit, which this module does not read.
     */
    private Path seedRepository() throws Exception {
        git("init", "-q", "-b", "main");
        Path file = root.resolve("doc.adoc");
        Files.writeString(file, "one\ntwo\nthree\n");
        git("add", "doc.adoc");
        git("-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign", "-m", "add doc");
        String head;
        try (GitRepo repo = GitRepo.open(root)) {
            head = repo.headRevision();
        }
        Affirmation affirmation = Affirmation.of(Path.of("doc.adoc"), 2, 3, head, List.of("one", "two", "three"));
        git("-c", "user.name=Rita Reviewer", "-c", "user.email=rita@example.test", "commit", "-q", "--no-gpg-sign", "--allow-empty",
                "-m", "Affirm doc.adoc 2-3", "--trailer", affirmation.trailer());
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
