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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two files introduced by one commit materialize as two documents that share nothing: a line's
 * identity names the file it was introduced in, so line {@code n} of each file is its own prose
 * element, and each document reads back as its own text (IKE-Network/ike-issues#1307). Before the
 * path was part of the identity, the second file written overwrote the first file's lines and
 * the STAMPed Document card showed one topic's body under another's header.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitDocumentCrossFileIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final List<String> MEDICATION = List.of(
            ":topic-id: anf-example-medication",
            "[[anf-example-medication]]",
            "= Example Medication",
            "",
            "A dose of 5 mg is recorded as a quantity.",
            "The route is oral, see xref:anf-reference-integrity[].");

    private static final List<String> INTEGRITY = List.of(
            ":topic-id: anf-reference-integrity",
            "[[anf-reference-integrity]]",
            "= Reference Integrity",
            "",
            "Every reference resolves to a concept.",
            "A dangling reference is an error, see xref:anf-example-medication[].");

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
    void twoFilesFromOneCommitShareNoProseElement() throws Exception {
        Path repo = Files.createDirectories(root.resolve("two-files"));
        gitIn(repo, "init", "-q", "-b", "main");
        Path topics = Files.createDirectories(repo.resolve("topics/src/docs/asciidoc/topics/anf"));
        Files.writeString(topics.resolve("medication.adoc"), String.join("\n", MEDICATION) + "\n");
        Files.writeString(topics.resolve("integrity.adoc"), String.join("\n", INTEGRITY) + "\n");
        gitIn(repo, "add", "topics/src/docs/asciidoc/topics/anf/medication.adoc", "topics/src/docs/asciidoc/topics/anf/integrity.adoc");
        gitIn(repo, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-04-03T00:00:00Z", "-m", "introduce two topics in one commit");

        GitDocumentMaterializer.Result medication = GitDocumentMaterializer.materialize(repo,
                Path.of("topics/src/docs/asciidoc/topics/anf/medication.adoc"), false);
        GitDocumentMaterializer.Result integrity = GitDocumentMaterializer.materialize(repo,
                Path.of("topics/src/docs/asciidoc/topics/anf/integrity.adoc"), false);

        assertEquals("anf-example-medication", medication.name());
        assertEquals("anf-reference-integrity", integrity.name());
        assertNotEquals(medication.anchor(), integrity.anchor());
        assertEquals(MEDICATION.size(), medication.lines(), "every line of the second file is written, none already present");
        assertEquals(INTEGRITY.size(), integrity.lines(), "every line of the second file is written, none already present");

        GitDocumentMaterializer.StoredDocument storedMedication = GitDocumentMaterializer.load(medication.anchor()).orElseThrow();
        GitDocumentMaterializer.StoredDocument storedIntegrity = GitDocumentMaterializer.load(integrity.anchor()).orElseThrow();

        assertEquals(MEDICATION, storedMedication.lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList(),
                "the medication topic reads back as its own file");
        assertEquals(INTEGRITY, storedIntegrity.lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList(),
                "the integrity topic reads back as its own file, not as the last file written");
        assertEquals(1, storedMedication.manifestVersions(), "one commit, one manifest version");
        assertEquals(1, storedIntegrity.manifestVersions(), "one commit, one manifest version");

        Set<Long> medicationLines = new HashSet<>(storedMedication.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList());
        Set<Long> integrityLines = new HashSet<>(storedIntegrity.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList());
        assertEquals(MEDICATION.size(), medicationLines.size());
        Set<Long> sharedLines = new HashSet<>(medicationLines);
        sharedLines.retainAll(integrityLines);
        assertEquals(Set.of(), sharedLines, "no prose element belongs to both documents");

        Set<Long> sharedBlocks = new HashSet<>(storedMedication.blocks().keySet());
        sharedBlocks.retainAll(storedIntegrity.blocks().keySet());
        assertEquals(Set.of(), sharedBlocks, "block identities follow the line identities: none shared");
        assertEquals("Example Medication", storedMedication.attributes().getFirst());
        assertEquals("Reference Integrity", storedIntegrity.attributes().getFirst());
        for (GitDocumentMaterializer.StoredBlock block : storedMedication.blocks().values()) {
            for (long member : block.members()) {
                assertTrue(medicationLines.contains(member) || storedMedication.blocks().containsKey(member),
                        "a medication block holds only medication lines and blocks");
            }
        }

        Set<Long> sharedReferences = new HashSet<>(storedMedication.references().keySet());
        sharedReferences.retainAll(storedIntegrity.references().keySet());
        assertEquals(Set.of(), sharedReferences, "references are keyed by their own file's line");
        assertEquals(1, storedMedication.references().size(), "the medication topic's one xref sits on a medication line");
        assertTrue(medicationLines.containsAll(storedMedication.references().keySet()));
        assertEquals("anf-reference-integrity", storedMedication.references().values().iterator().next().getFirst().target());
        assertEquals("anf-example-medication", storedIntegrity.references().values().iterator().next().getFirst().target());
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
