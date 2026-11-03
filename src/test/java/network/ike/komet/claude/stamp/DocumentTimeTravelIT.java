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
import design.knowledge.stamp.adoc.model.AnnotationKind;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import network.ike.komet.claude.doc.BlockFactory;
import network.ike.komet.claude.doc.DocumentSurface;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stored document read as of a time (ike-lab-documents topic {@code dev-decision-document},
 * "Time travel"): every vertex at its latest committed version no later than the time, so the
 * prose of that time and each decision tree block's text, from which the tree is drawn, as they
 * stood then. An uncommitted edit is the present, so a past reading has none, and a past reading
 * on the surface is read, never edited.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentTimeTravelIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final String MARCH = "2026-03-01T12:00:00Z";
    private static final String APRIL = "2026-04-01T12:00:00Z";
    private static final String DOCUMENT = """
            :topic-id: time-travel
            = Time travel

            %s

            [decision-tree,id=ttv-choice]
            ----
            decision: k:Treat[Treat now?] @id=ttv-treat
              alternative: k:DoTreat[treat] @id=ttv-do-treat
                money = [10000,10000] k:Usd[US dollar] @id=ttv-usd
                outcome: k:Safe[safe] @id=ttv-safe
              alternative: k:Wait[wait] @id=ttv-wait
                chance: k:Ruptures[Rupture?] @id=ttv-ruptures
                  event: k:Rupture[rupture] @id=ttv-rupture
                    p = [%s,%s]
                    outcome: k:Ruptured[ruptured] @id=ttv-ruptured
                      money = [200000,200000] k:Usd[US dollar] @id=ttv-usd
                  residual: k:Intact[intact] @id=ttv-intact
                    outcome: k:Fine[fine] @id=ttv-fine
            ----
            """;

    @TempDir
    Path root;

    @BeforeAll
    void setup() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Started by another test class in this JVM.
        } catch (RuntimeException | Error noToolkit) {
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit);
        }
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @Test
    void aDocumentReadsAsItStoodAtTheViewsTime() throws Exception {
        Path dir = repository();
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("time-travel.adoc")).anchor();
        long between = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli();
        long before = Instant.parse("2026-02-01T12:00:00Z").toEpochMilli();

        StoredDocument latest = GitDocumentMaterializer.load(anchor, false, Long.MAX_VALUE).orElseThrow();
        assertTrue(text(latest).contains("The April words."), text(latest));
        assertTrue(tree(latest).contains("p = [0.3,0.3]"), tree(latest));
        assertEquals(2, latest.manifestVersions(), "both commits by the latest");

        StoredDocument march = GitDocumentMaterializer.load(anchor, false, between).orElseThrow();
        assertTrue(text(march).contains("The March words."), text(march));
        assertFalse(text(march).contains("April"), text(march));
        assertTrue(tree(march).contains("p = [0.1,0.1]"), "the tree block's text, so its tree, as it stood then: " + tree(march));
        assertEquals(1, march.manifestVersions(), "one commit by then");

        assertTrue(GitDocumentMaterializer.load(anchor, false, before).isEmpty(), "nothing before the first commit");

        // a pending edit is the present: the latest reading has it, a past one does not
        StoredBlock block = treeBlock(latest);
        String source = String.join("\n", StructuredBlocks.source(latest, block).lines());
        DocumentEditor.editBlock(anchor, block.nid(), source.replace("p = [0.3,0.3]", "p = [0.5,0.5]"), Coordinates.Edit.Default());
        assertTrue(tree(GitDocumentMaterializer.load(anchor, true, Long.MAX_VALUE).orElseThrow()).contains("p = [0.5,0.5]"));
        assertTrue(tree(GitDocumentMaterializer.load(anchor, true, between).orElseThrow()).contains("p = [0.1,0.1]"));
        DocumentEditor.cancel(anchor);

        // on the surface a past reading draws its blocks and edits none of them
        DocumentSurfaceView surface = onFx(() -> new DocumentSurfaceView(host(),
                new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13))));
        long marchBlock = treeBlock(march).nid();
        onFx(() -> {
            surface.setPast(true);
            surface.show(DocumentAssembly.single(march, "time-travel.adoc"));
            return null;
        });
        assertTrue(onFx(() -> surface.structuredViewOfBlock(marchBlock)).isPresent(), "the past tree is drawn");
        assertFalse(onFx(() -> surface.editBlock(marchBlock)), "a past reading is not edited");
        onFx(() -> {
            surface.setPast(true);
            surface.show(DocumentAssembly.single(StoredDocument.nothing(anchor), ""));   // before the first commit
            return null;
        });
        assertTrue(onFx(() -> surface.structuredViewOfBlock(marchBlock)).isEmpty(), "nothing is shown of a document not yet written");
        onFx(() -> {
            surface.setPast(false);
            surface.show(DocumentAssembly.single(latest, "time-travel.adoc"));
            return null;
        });
        assertTrue(onFx(() -> surface.editBlock(block.nid())), "the latest reading is edited");
    }

    private static String text(StoredDocument document) {
        return String.join("\n", document.lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList());
    }

    private static StoredBlock treeBlock(StoredDocument document) {
        return document.blocks().values().stream()
                .filter(b -> b.kind() == Block.Kind.LISTING && b.parameters().startsWith("[decision-tree")).findFirst()
                .orElseThrow(() -> new AssertionError("no decision-tree block"));
    }

    private static String tree(StoredDocument document) {
        return StructuredBlocks.source(document, treeBlock(document)).content();
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }

    private static DocumentSurfaceView.Host host() {
        return new DocumentSurfaceView.Host() {
            @Override
            public ViewCalculator view() {
                return Calculators.View.Default();
            }

            @Override
            public CommitSummaries summaries() {
                return CommitSummaries.NONE;
            }

            @Override
            public void status(String text) {
            }

            @Override
            public void annotate(AnnotationKind kind) {
            }

            @Override
            public void saveEdit(StoredBlock edited, String text) {
            }

            @Override
            public void showHistory() {
            }

            @Override
            public void selectionChanged() {
            }

            @Override
            public void showingMember(DocumentAssembly.Member member) {
            }
        };
    }

    private Path repository() throws Exception {
        Path dir = Files.createDirectories(root.resolve("time-travel"));
        git(dir, Map.of(), "init", "-q", "-b", "main");
        git(dir, Map.of(), "config", "user.name", "author");
        git(dir, Map.of(), "config", "user.email", "author@example.test");
        git(dir, Map.of(), "config", "commit.gpgsign", "false");
        commit(dir, "The March words.", "0.1", MARCH);
        commit(dir, "The April words.", "0.3", APRIL);
        return dir;
    }

    private static void commit(Path dir, String words, String p, String date) throws Exception {
        Files.writeString(dir.resolve("time-travel.adoc"), DOCUMENT.formatted(words, p, p));
        git(dir, Map.of(), "add", "time-travel.adoc");
        git(dir, Map.of("GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date), "commit", "-q", "--no-gpg-sign", "-m", "Words of " + date);
    }

    private static void git(Path dir, Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
