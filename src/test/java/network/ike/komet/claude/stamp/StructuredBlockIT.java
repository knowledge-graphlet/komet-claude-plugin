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
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A structured block on the document surface (IKE-Network/ike-issues#1312): a listing block whose
 * style a registered kind names is read through the kind's control and edited in its editor, and
 * the editor's save is an ordinary pending edit of the block's text. The kind here is a stand-in
 * for the ANF statement and complex concept kinds, which register the same way.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructuredBlockIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final String STYLE = "test-structure";
    private static final String DOCUMENT = """
            :topic-id: structured
            = Structured

            A paragraph before it.

            [test-structure,kind=sample]
            ----
            alpha
            beta
            ----

            A paragraph after it.
            """;

    @TempDir
    Path root;

    /** A kind that reads its content as one label and edits it in a text area. */
    private static final StructuredBlockKind KIND = new StructuredBlockKind() {
        @Override
        public String style() {
            return STYLE;
        }

        @Override
        public String name() {
            return "test structure";
        }

        @Override
        public Node view(Source source, Context context) {
            Label label = new Label(source.content().replace("\n", "|"));
            label.setId("testStructureView");
            return label;
        }

        @Override
        public Optional<Editor> editor(Source source, Context context) {
            TextArea area = new TextArea(source.content());
            return Optional.of(new Editor() {
                @Override
                public Node node() {
                    return area;
                }

                @Override
                public String content() {
                    if (area.getText().isBlank()) {
                        throw new IllegalStateException("A test structure holds at least one line");
                    }
                    return area.getText();
                }
            });
        }
    };

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
        StructuredBlocks.register(KIND);
    }

    @AfterAll
    void teardown() {
        StructuredBlocks.unregister(STYLE);
        PrimitiveData.stop();
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

    private static StoredBlock structuredBlock(StoredDocument document) {
        return document.blocks().values().stream()
                .filter(block -> block.kind() == Block.Kind.LISTING && block.parameters().startsWith("[" + STYLE))
                .findFirst().orElseThrow(() -> new AssertionError("no " + STYLE + " listing block"));
    }

    private static Node lookup(Node node, String id) {
        if (id.equals(node.getId())) {
            return node;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = lookup(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void aStyledListingBlockReadsAndEditsThroughItsKind() throws Exception {
        Path dir = repository();
        Path file = dir.resolve("structured.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        StoredBlock block = structuredBlock(document);

        // The block is flat lines, so a save of its text is an ordinary edit of those lines.
        assertTrue(document.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList()
                .containsAll(block.members()), "a listing block holds lines only");
        assertEquals(KIND, StructuredBlocks.kindOf(block).orElseThrow());
        StructuredBlockKind.Source source = StructuredBlocks.source(document, block);
        assertEquals("alpha\nbeta", source.content());

        AtomicReference<String> saved = new AtomicReference<>();
        List<String> statuses = new ArrayList<>();
        DocumentSurfaceView.Host host = new DocumentSurfaceView.Host() {
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
                statuses.add(text);
            }

            @Override
            public void annotate(AnnotationKind kind) {
            }

            @Override
            public void saveEdit(StoredBlock edited, String text) {
                saved.set(text);
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

        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurface documentSurface = new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13));
            DocumentSurfaceView view = new DocumentSurfaceView(host, documentSurface);
            view.show(DocumentAssembly.single(document, "structured.adoc"));
            return view;
        });

        // Read through the kind's control, in place of the text.
        Node view = onFx(() -> surface.structuredViewOfBlock(block.nid()).orElseThrow());
        assertEquals("alpha|beta", ((Label) view).getText());
        assertTrue(view.isVisible());

        // Edit through the kind's editor; Save writes its content between the block's fences.
        assertTrue(onFx(() -> surface.editBlock(block.nid())));
        Parent holder = onFx(view::getParent);
        TextArea editor = (TextArea) onFx(() -> lookup(holder, "stampedDocumentStructuredEditor"));
        Button save = (Button) onFx(() -> lookup(holder, "stampedDocumentSaveEdit"));
        assertFalse(onFx(view::isVisible), "the editor takes the control's place");

        onFx(() -> {
            editor.setText("");
            save.fire();
            return null;
        });
        assertEquals(null, saved.get(), "an editor that holds nothing to save refuses, and nothing is saved");
        assertTrue(statuses.contains("A test structure holds at least one line"), statuses.toString());

        onFx(() -> {
            editor.setText("alpha\ngamma");
            save.fire();
            return null;
        });
        assertEquals("[test-structure,kind=sample]\n----\nalpha\ngamma\n----", saved.get());

        // The saved text is an ordinary pending edit of the block's lines.
        DocumentEditor.editBlock(anchor, block.nid(), saved.get(), Coordinates.Edit.Default());
        StoredDocument pending = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        assertEquals("alpha\ngamma", StructuredBlocks.source(pending, structuredBlock(pending)).content());
        DocumentEditor.cancel(anchor);
    }

    private Path repository() throws Exception {
        Path dir = Files.createDirectories(root.resolve("structured"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("structured.adoc"), DOCUMENT);
        git(dir, "add", "structured.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=2026-03-01T12:00:00", "-m", "Add the document");
        return dir;
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
