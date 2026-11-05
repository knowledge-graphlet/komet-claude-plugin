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

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import javafx.application.Platform;
import jfx.incubator.scene.control.richtext.RichTextArea;
import network.ike.komet.claude.doc.BlockFactory;
import network.ike.komet.claude.doc.DocumentSurface;
import network.ike.komet.claude.doc.print.settings.PrintSettings;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.print.DocumentFonts;
import network.ike.komet.claude.stamp.print.DocumentModel;
import network.ike.komet.claude.stamp.print.GreedyFxOracle;
import network.ike.komet.claude.stamp.print.LayoutOracle;
import network.ike.komet.claude.stamp.print.StoredDocumentModel;
import network.ike.komet.claude.stamp.print.TableCells;
import network.ike.komet.claude.stamp.print.TypstCompiler;
import network.ike.komet.claude.stamp.print.TypstOracle;
import network.ike.komet.claude.stamp.print.TypstPdf;
import network.ike.komet.claude.stamp.print.TypstWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A table taller than a page on the Stamped Document card's pages, from a stored document through
 * the units, both oracles and the page lines: every row is drawn, and its header at the top of
 * every page the table reaches, by either oracle.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageTableIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

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
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit.getMessage());
        }
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
        DocumentFonts.loadForJavaFx();
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    private static String document(int rows) {
        StringBuilder text = new StringBuilder("""
                :topic-id: page-table
                = Page Table

                A paragraph before the table.

                .Rows
                [cols="1,3", options="header"]
                |===
                | Name | Value

                """);
        for (int r = 0; r < rows; r++) {
            text.append("| Name ").append(r).append("\n| The value of row ").append(r).append(".\n\n");
        }
        return text.append("|===\n\nA paragraph after it.\n").toString();
    }

    @Test
    void thePagesDrawEveryRowAndTheHeaderOnEachPage() throws Exception {
        StoredDocument document = stored("page-table", document(70));

        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurfaceView view = new DocumentSurfaceView(host(), new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
            view.show(DocumentAssembly.single(document, "page-table.adoc"));
            return view;
        });
        PrintSettings settings = PrintSettings.DEFAULT;
        List<LayoutOracle.Unit> units = onFx(() -> surface.pageUnits(settings.theme()));
        List<LayoutOracle.Unit> rows = units.stream().filter(u -> u.kind() == LayoutOracle.Kind.TABLE_ROW).toList();
        assertEquals(71, rows.size());
        assertTrue(rows.getFirst().bold(), "the header is told by its weight, and no other row: " + rows.getFirst());
        assertTrue(rows.stream().skip(1).noneMatch(LayoutOracle.Unit::bold));

        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        GreedyFxOracle greedy = onFx(() -> new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO));
        checkPages(surface, units, onFx(() -> greedy.layout(units, geometry)), rows, geometry);

        Assumptions.assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        DocumentModel model = StoredDocumentModel.of(document);
        Path folder = Files.createDirectories(root.resolve("typst"));
        Path source = folder.resolve("page-table.typ");
        Files.writeString(source, TypstWriter.write(model, TypstPdf.setup(settings), TypstWriter.Labels.WORDS), StandardCharsets.UTF_8);
        LayoutOracle.Layout typst = new TypstOracle(folder, source, DocumentFonts.extractTo(folder.resolve("fonts")),
                (text, unit, mono, bold, italic) -> supplyOnFx(() -> greedy.width(text, unit, mono, bold, italic))).layout(units, geometry);
        assertEquals(List.of(), typst.notes());
        checkPages(surface, units, typst, rows, geometry);
    }

    @Test
    void thePagesReadARowAsThePrintModelDoesHoweverItsSourceIsLaidOut() throws Exception {
        StoredDocument document = stored("page-cells", """
                :topic-id: page-cells
                = Page Cells

                .Laid out every way
                [cols="1,3", options="header"]
                |===
                | Name | Value

                | One
                | A value that runs on
                over a second line.

                | Two | Same line | Three
                | Rows with no blank line between them.
                | Four | Case a| Five
                | Six |
                |===
                """);
        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurfaceView view = new DocumentSurfaceView(host(), new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
            view.show(DocumentAssembly.single(document, "page-cells.adoc"));
            return view;
        });
        List<LayoutOracle.Unit> rows = onFx(() -> surface.pageUnits(PrintSettings.DEFAULT.theme())).stream()
                .filter(u -> u.kind() == LayoutOracle.Kind.TABLE_ROW).toList();
        DocumentModel.Table table = StoredDocumentModel.of(document).blocks().stream()
                .filter(DocumentModel.Table.class::isInstance).map(DocumentModel.Table.class::cast).findFirst().orElseThrow();
        assertEquals(List.of(List.of("Name", "Value"),
                List.of("One", "A value that runs on over a second line."),
                List.of("Two", "Same line"),
                List.of("Three", "Rows with no blank line between them."),
                List.of("Four", "Case"),
                List.of("Five", "Six")), table.rows());
        assertEquals(table.rows().stream().map(row -> String.join(TableCells.BAR, row)).toList(),
                rows.stream().map(LayoutOracle.Unit::text).toList());
        assertTrue(rows.stream().allMatch(row -> row.columns().equals(List.of(1, 3))), "every row knows its table's columns");
    }

    /** A document committed and stored, as the card reads it. */
    private StoredDocument stored(String name, String text) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve(name + ".adoc");
        Files.writeString(file, text);
        git(dir, "add", name + ".adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "The document");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        return GitDocumentMaterializer.load(anchor, true).orElseThrow();
    }

    private void checkPages(DocumentSurfaceView surface, List<LayoutOracle.Unit> units, LayoutOracle.Layout layout, List<LayoutOracle.Unit> rows,
                            LayoutOracle.Geometry geometry) throws Exception {
        assertTrue(layout.pages() > 1, "the table runs over a page");
        for (LayoutOracle.Unit row : rows.subList(1, rows.size())) {
            assertEquals(1, layout.ofUnit(row.index()).size(), "row " + row.text());
        }
        String check = onFx(() -> {
            surface.beginLineCheck(geometry.contentLeft());
            surface.layoutLines(layout, units);
            return surface.lineCheck();
        });
        assertTrue(check.contains(" 0 drawing other text"), check);

        int last = layout.ofUnit(rows.getLast().index()).getFirst().page();
        for (int page = 1; page <= last; page++) {
            int on = page;
            List<PageStackView.Placed> placed = onFx(() -> surface.linesOn(on, geometry.contentLeft(), geometry.contentWidth(), geometry.bandHeight()));
            PageStackView.Placed top = placed.stream().min(java.util.Comparator.comparingDouble(PageStackView.Placed::baseline)).orElseThrow();
            RichTextArea area = (RichTextArea) top.node();
            assertEquals(rows.getFirst().text(), area.getModel().getPlainText(0), "the top of page " + page);
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return work.call();
        }
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

    private static double supplyOnFx(Callable<Double> work) {
        try {
            return onFx(work);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
