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

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.komet.document.GitDocumentMaterializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The narrator narrates knowledge only: a component born in a file is never narrated, even under a
 * knowledge STAMP, since the narration would be knowledge referring to it (IKE-Network/ike-issues#1313).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NarratableKindIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    @TempDir
    Path root;

    @BeforeAll
    void setup() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
    }

    @AfterAll
    void teardown() {
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    @Test
    void aComponentBornInAFileIsNotNarratedEvenUnderAKnowledgeStamp() throws Exception {
        Path dir = Files.createDirectories(root.resolve("notes"));
        git(dir, "init", "-q", "-b", "main");
        Files.writeString(dir.resolve("note.adoc"), "= A note\n\nOne paragraph.\n");
        git(dir, "add", "note.adoc");
        git(dir, "-c", "user.name=Ada", "-c", "user.email=ada@example.test", "commit", "-q", "--no-gpg-sign", "-m", "Add a note");
        long document = EntityService.get().nidForPublicId(GitDocumentMaterializer.materialize(dir, dir.resolve("note.adoc")).anchor());
        long knowledge = KernelConcept.SOLOR_MODULE.nid();
        long knowledgeStamp = dev.ikm.tinkar.entity.EntityHandle.getEntityOrThrow(knowledge).versions().getFirst().stampNid();

        assertTrue(CommitNarrator.narratable(knowledgeStamp, knowledge), "knowledge is narrated");
        assertFalse(CommitNarrator.narratable(knowledgeStamp, document), "a document read from a file is not");
    }

    @Test
    void aRequestStoppedAsKometQuitsIsNotAFailure() {
        assertTrue(CommitNarrator.interrupted(new RuntimeException("Interrupted while contacting the API", new InterruptedException())));
        assertFalse(CommitNarrator.interrupted(new RuntimeException("The API refused the request")));
    }

    private static void git(Path dir, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git"));
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes()).strip();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
