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
package network.ike.komet.claude.documenttools;

import network.ike.komet.document.CommitSummaries;
import network.ike.komet.document.DocumentAssembly;
import network.ike.komet.document.DocumentSurfaceView;
import network.ike.komet.document.GitDocumentMaterializer;
import network.ike.komet.document.PageStackView;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import javafx.application.Platform;
import javafx.scene.layout.Region;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledSegment;
import network.ike.komet.document.surface.BlockFactory;
import network.ike.komet.document.surface.DocumentSurface;
import network.ike.komet.document.surface.print.settings.PrintSettings;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.print.DocumentFonts;
import network.ike.komet.document.print.DocumentModel;
import network.ike.komet.document.print.GreedyFxOracle;
import network.ike.komet.document.print.LayoutOracle;
import network.ike.komet.document.print.ModelUnits;
import network.ike.komet.document.print.StoredDocumentModel;
import network.ike.komet.document.print.TypstCompiler;
import network.ike.komet.document.print.TypstOracle;
import network.ike.komet.document.print.TypstPdf;
import network.ike.komet.document.print.TypstWriter;
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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A diagram block on the Stamped Document card's pages, read from a corpus document: the page
 * prints its source as Typst does, every row in the mono font at the raw size inside the
 * listing's tint, without its delimiter lines, and each page line is the line Typst placed.
 * Its caption is a unit of its own above the tint, as every titled block's is: the units on the
 * page are the print model's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageDiagramIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final Path CORPUS = Path.of("src/test/resources/documents");
    private static final String NAME = "dev-clause-semantics";
    /** The title of that document's diagram, which prints above its tint. */
    private static final String CAPTION = "A clause graph — \"morbid obesity\" worked as an expression tree";
    /** Source rows that would be markup outside a listing. */
    private static final String MARKUP_ROWS = """
            :topic-id: page-rows
            = Page Rows

            [source,java]
            ----
            // a comment in the code
            [Override]
            .field = 1
            [[anchor]]
            ----
            """;

    /** A block of every kind that takes a title, the first with inline markup and a footnote the verbatim source does not have. */
    private static final String CAPTIONS = """
            :topic-id: page-captions
            = Page Captions

            .A *bold* listingfootnote:[Its note.]
            ----
            *not bold*
            ----

            .A literal
            ....
            literal
            ....

            .A diagram
            [plantuml]
            ----
            @startuml
            a -> b
            @enduml
            ----

            .An expression
            [anf]
            --
            clinical statement
            --

            .A tree that cannot be drawn
            [decision-tree]
            --
            not a tree
            --

            .A list
            * one
            * two

            .A table
            |===
            | a | b
            |===

            .An example
            ====
            Inside the example.
            ====

            .A sidebar
            ****
            Inside the sidebar.
            ****

            .A quote
            ____
            Inside the quote.
            ____

            .An open block
            --
            Inside the open block.
            --

            The end.
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

    @Test
    void theDiagramsSourcePrintsInItsListing() throws Exception {
        StoredDocument document = stored(NAME, Files.readString(CORPUS.resolve(NAME + ".adoc")));
        DocumentSurfaceView surface = surface(document, NAME);
        PrintSettings settings = PrintSettings.DEFAULT;
        double base = settings.theme().baseFontSize();
        List<LayoutOracle.Unit> units = onFx(() -> surface.pageUnits(settings.theme()));
        DocumentModel model = StoredDocumentModel.of(document);

        // The surface's units are the print model's, its captions among them, its verbatim text the source between the delimiters.
        assertEquals(described(ModelUnits.of(model, base)), described(units));
        LayoutOracle.Unit diagram = units.stream().filter(u -> u.text().startsWith("@startuml")).findFirst().orElseThrow();
        assertEquals(LayoutOracle.Kind.VERBATIM, diagram.kind());
        assertTrue(diagram.text().endsWith("@enduml"), diagram.text());

        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        GreedyFxOracle greedy = onFx(() -> new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO));

        // The first pass stands without typst.
        checkPage(surface, units, diagram, onFx(() -> greedy.layout(units, geometry)), base, geometry);

        // The second pass is Typst's.
        Assumptions.assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        Path folder = Files.createDirectories(root.resolve("typst"));
        Path source = folder.resolve(NAME + ".typ");
        Files.writeString(source, TypstWriter.write(model, TypstPdf.setup(settings), TypstWriter.Labels.WORDS), StandardCharsets.UTF_8);
        LayoutOracle.Layout typst = new TypstOracle(folder, source, DocumentFonts.extractTo(folder.resolve("fonts")),
                (text, unit, mono, bold, italic) -> supplyOnFx(() -> greedy.width(text, unit, mono, bold, italic))).layout(units, geometry);
        assertTrue(typst.notes().stream().noneMatch(n -> n.contains("not placed") || n.contains("typst did not")), typst.notes().toString());
        assertEquals(typst.ofUnit(diagram.index() - 1).getFirst().page(), typst.ofUnit(diagram.index()).getFirst().page(), "Typst sets the caption on the diagram's first page");
        checkPage(surface, units, diagram, typst, base, geometry);
    }

    @Test
    void aListingsRowsThatReadAsMarkupPrint() throws Exception {
        StoredDocument document = stored("page-rows", MARKUP_ROWS);
        PrintSettings settings = PrintSettings.DEFAULT;
        DocumentSurfaceView surface = surface(document, "page-rows");
        LayoutOracle.Unit listing = onFx(() -> surface.pageUnits(settings.theme())).stream().filter(u -> u.kind() == LayoutOracle.Kind.VERBATIM).findFirst().orElseThrow();
        assertEquals("// a comment in the code\n[Override]\n.field = 1\n[[anchor]]", listing.text());
    }

    @Test
    void everyTitledBlocksCaptionIsAUnitAsTheModelHasIt() throws Exception {
        StoredDocument document = stored("page-captions", CAPTIONS);
        PrintSettings settings = PrintSettings.DEFAULT;
        DocumentSurfaceView surface = surface(document, "page-captions");
        List<LayoutOracle.Unit> units = onFx(() -> surface.pageUnits(settings.theme()));
        DocumentModel model = onFx(() -> StoredDocumentModel.of(document, Map.of(), surface::printFigures));
        assertEquals(described(ModelUnits.of(model, settings.theme().baseFontSize())), described(units));

        // A structured block that cannot be drawn prints as its source, under its caption; one that is drawn carries its title in its figure.
        int source = units.stream().filter(u -> u.text().equals("not a tree")).findFirst().orElseThrow().index();
        assertEquals("A tree that cannot be drawn", units.get(source - 1).text());
        assertEquals(LayoutOracle.Kind.OTHER, units.get(source - 1).kind());
        assertEquals(LayoutOracle.Kind.VERBATIM, units.get(source).kind());
    }

    /** Without a blank line under the index term the title line is text, in Asciidoctor and the model: the page prints it and keeps its block numbers. */
    @Test
    void aTitleLineUnderAnIndexTermPrintsAsTheModelHasIt() throws Exception {
        StoredDocument document = stored("page-title-text", """
                :topic-id: page-title-text
                = Page Title Text

                indexterm:[decisions]
                .For KEC
                |===
                | a | b
                |===

                The end.
                """);
        PrintSettings settings = PrintSettings.DEFAULT;
        DocumentSurfaceView surface = surface(document, "page-title-text");
        List<LayoutOracle.Unit> units = onFx(() -> surface.pageUnits(settings.theme()));
        List<LayoutOracle.Unit> printed = ModelUnits.of(StoredDocumentModel.of(document), settings.theme().baseFontSize());
        assertEquals(printed.stream().map(u -> u.block() + " " + u.kind() + " " + u.text()).toList(),
                units.stream().map(u -> u.block() + " " + u.kind() + " " + u.text().strip()).toList());
        assertTrue(printed.stream().anyMatch(u -> u.kind() == LayoutOracle.Kind.PARAGRAPH && u.text().equals(".For KEC")), printed.toString());
    }

    /** What a unit is for the page: its place, kind, block, font, spacing and text. */
    private static List<String> described(List<LayoutOracle.Unit> units) {
        return units.stream().map(u -> "%d block %d %s %.3fpt%s above %.3f below %.3f%s: %s".formatted(u.index(), u.block(), u.kind(), u.fontSize(),
                u.bold() ? " bold" : "", u.spaceAbove(), u.spaceBelow(), u.keepWithNext() ? " keeps" : "", u.text())).toList();
    }

    /** A document, committed to a repository of its own and materialized. */
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

    private static DocumentSurfaceView surface(StoredDocument document, String name) throws Exception {
        return onFx(() -> {
            DocumentSurfaceView view = new DocumentSurfaceView(host(), new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
            view.show(DocumentAssembly.single(document, name + ".adoc"));
            return view;
        });
    }

    /** Lays the pages and checks that the diagram's rows are all drawn, in the raw font, and that a tint stands behind them. */
    private void checkPage(DocumentSurfaceView surface, List<LayoutOracle.Unit> units, LayoutOracle.Unit diagram, LayoutOracle.Layout layout,
                           double base, LayoutOracle.Geometry geometry) throws Exception {
        String check = onFx(() -> {
            surface.beginLineCheck(geometry.contentLeft());
            surface.layoutLines(layout, units);
            return surface.lineCheck();
        });
        assertTrue(check.contains(" 0 drawing other text"), check);

        // Its caption stands above the tint, in bold at the size Typst sets a block's title.
        LayoutOracle.Unit caption = units.get(diagram.index() - 1);
        assertEquals(CAPTION, caption.text());
        int captionPage = layout.ofUnit(caption.index()).getFirst().page();
        PageStackView.Placed captionLine = onFx(() -> surface.linesOn(captionPage, geometry.contentLeft(), geometry.contentWidth(), geometry.bandHeight())).stream()
                .filter(line -> surface.identity(line)[0] == caption.index()).findFirst().orElseThrow();
        assertEquals(CAPTION, text(captionLine, DocumentFonts.SERIF, true, base * TypstWriter.TITLE_SCALE));
        double captionBaseline = captionLine.baseline();
        int firstRowPage = layout.ofUnit(diagram.index()).getFirst().page();
        assertTrue(captionPage <= firstRowPage, "the caption stands before the diagram");
        if (captionPage == firstRowPage) {                     // the first pass may carry a listing it will not break to the next page
            assertTrue(onFx(() -> surface.backdropsOn(captionPage, geometry.contentLeft(), geometry.contentWidth())).stream()
                    .anyMatch(tint -> ((Region) tint.node()).getStyle().contains("rgb(247,247,247)") && tint.baseline() > captionBaseline), "a tint below the caption");
        }

        List<LayoutOracle.Line> lines = layout.ofUnit(diagram.index());
        List<String> rows = new ArrayList<>();
        for (int page = lines.getFirst().page(); page <= lines.getLast().page(); page++) {
            int on = page;
            List<PageStackView.Placed> placed = onFx(() -> surface.linesOn(on, geometry.contentLeft(), geometry.contentWidth(), geometry.bandHeight()));
            double top = Double.MAX_VALUE;
            double bottom = 0;
            for (PageStackView.Placed line : placed) {
                if (surface.identity(line)[0] != diagram.index()) {
                    continue;
                }
                rows.add(text(line, DocumentFonts.MONO, false, base * TypstWriter.RAW_SCALE));
                top = Math.min(top, line.baseline());
                bottom = Math.max(bottom, line.baseline());
            }
            double first = top;
            double last = bottom;
            List<PageStackView.Placed> behind = onFx(() -> surface.backdropsOn(on, geometry.contentLeft(), geometry.contentWidth()));
            assertTrue(behind.stream().anyMatch(tint -> ((Region) tint.node()).getStyle().contains("rgb(247,247,247)")
                    && tint.baseline() < first && tint.baseline() + tint.height() > last), "a tint behind the diagram's rows on page " + on);
        }
        assertEquals(List.of(diagram.text().split("\n")), rows);
        assertFalse(rows.contains("----"), rows.toString());
    }

    /** The text of a placed page line, each piece of it in the font at the size. */
    private static String text(PageStackView.Placed line, String family, boolean bold, double size) {
        RichParagraph paragraph = ((RichTextArea) line.node()).getModel().getParagraph(0);
        StringBuilder text = new StringBuilder();
        for (int k = 0; k < paragraph.getSegmentCount(); k++) {
            StyledSegment segment = paragraph.getSegment(k);
            if (segment.getType() == StyledSegment.Type.TEXT && !segment.getText().isBlank()) {
                StyleAttributeMap style = segment.getStyleAttributeMap(null);
                assertEquals(family, style.getFontFamily(), "the font of '" + segment.getText() + "'");
                assertEquals(bold, style.isBold(), "the weight of '" + segment.getText() + "'");
                assertEquals(size, style.getFontSize(), 0.001, "the size of '" + segment.getText() + "'");
            }
            text.append(segment.getText());
        }
        return text.toString().stripTrailing();
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
