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
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledSegment;
import network.ike.komet.claude.doc.BlockFactory;
import network.ike.komet.claude.doc.DocumentSurface;
import network.ike.komet.claude.doc.print.settings.PrintSettings;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.print.DocumentFonts;
import network.ike.komet.claude.stamp.print.DocumentModel;
import network.ike.komet.claude.stamp.print.GreedyFxOracle;
import network.ike.komet.claude.stamp.print.InlineText;
import network.ike.komet.claude.stamp.print.LayoutOracle;
import network.ike.komet.claude.stamp.print.StoredDocumentModel;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inline spans on the Stamped Document card's pages, from a stored document through the units,
 * both oracles and the page lines: no markup is drawn, inline code is drawn in the mono font at
 * the raw size with every piece keeping its source line, Typst's lines are the lines drawn, and a
 * footnote stands at the page's foot under its rule.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageInlineIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final String FILLER = "The fence reads the record and the holder renews it again while the daemon keeps watch over every lease.";
    /** Code words enough that some wrapped line of an item begins with one. */
    private static final String LIST_WORDS = "`downstream:` `upstream:` the `notify:` entry and ".repeat(9);
    private static final String DOCUMENT = """
            :topic-id: page-inline
            = Page Inline

            The `measurementBasis` field is *strong* here,
            and _for submissions like this one_ see xref:anf-data-types[the data types].
            %s
            %s
            Said so. footnote:[With `code` in it.] And <<anf-data-types>> after.

            A second paragraph with `code` to close.

            . *Cascade-walker reachability* over `release-cascade.yaml`, a %s
            . *Set notify policy* for %s
            """.formatted(FILLER, FILLER, LIST_WORDS, LIST_WORDS);

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
    void thePagesDrawTheSpansTypstPrints() throws Exception {
        Path dir = Files.createDirectories(root.resolve("page-inline"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve("page-inline.adoc");
        Files.writeString(file, DOCUMENT);
        git(dir, "add", "page-inline.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "The document");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();

        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurfaceView view = new DocumentSurfaceView(host(), new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
            view.show(DocumentAssembly.single(document, "page-inline.adoc"));
            return view;
        });
        PrintSettings settings = PrintSettings.DEFAULT;
        List<LayoutOracle.Unit> units = onFx(() -> surface.pageUnits(settings.theme()));

        LayoutOracle.Unit body = units.stream().filter(u -> u.text().startsWith("The measurementBasis")).findFirst().orElseThrow();
        assertFalse(body.text().contains("`") || body.text().contains("xref:") || body.text().contains("footnote:"), body.text());
        assertTrue(body.text().contains("the data types"), body.text());
        assertTrue(body.text().contains("Said so.¹ And anf data types after."), body.text());
        LayoutOracle.Unit note = units.get(body.index() + 1);
        assertEquals(LayoutOracle.Kind.FOOTNOTE, note.kind());
        assertEquals("¹With code in it.", note.text());

        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        GreedyFxOracle greedy = onFx(() -> new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO));

        // The first pass stands without typst.
        LayoutOracle.Layout first = onFx(() -> greedy.layout(units, geometry));
        assertEquals(geometry.contentBottom(), first.ofUnit(note.index()).getLast().baseline(), 0.01);
        checkPage(surface, units, first, settings, geometry, document);

        // The second pass is Typst's.
        Assumptions.assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        DocumentModel model = StoredDocumentModel.of(document);
        Path folder = Files.createDirectories(root.resolve("typst"));
        Path source = folder.resolve("page-inline.typ");
        Files.writeString(source, TypstWriter.write(model, TypstPdf.setup(settings), TypstWriter.Labels.WORDS), StandardCharsets.UTF_8);
        LayoutOracle.Layout typst = new TypstOracle(folder, source, DocumentFonts.extractTo(folder.resolve("fonts")),
                (text, unit, mono, bold, italic) -> supplyOnFx(() -> greedy.width(text, unit, mono, bold, italic))).layout(units, geometry);
        assertTrue(typst.notes().stream().noneMatch(n -> n.contains("not placed") || n.contains("typst did not")), typst.notes().toString());
        assertTrue(typst.ofUnit(note.index()).getFirst().baseline() > typst.ofUnit(body.index()).getLast().baseline());
        checkList(units, typst);
        checkPage(surface, units, typst, settings, geometry, document);
    }

    /** A list item prints its spans, and every wrapped line hangs at one edge, a line led by inline code too. */
    private static void checkList(List<LayoutOracle.Unit> units, LayoutOracle.Layout typst) {
        List<LayoutOracle.Unit> items = units.stream().filter(u -> u.kind() == LayoutOracle.Kind.LIST_ITEM).toList();
        assertEquals(2, items.size());
        assertTrue(items.getFirst().text().startsWith("1. Cascade-walker reachability over release-cascade.yaml"), items.getFirst().text());
        boolean ledByCode = false;
        for (LayoutOracle.Unit item : items) {
            assertFalse(item.text().contains("`") || item.text().contains("*"), item.text());
            assertTrue(item.runs().stream().anyMatch(InlineText.Run::bold), "the item's bold");
            assertTrue(item.runs().stream().anyMatch(InlineText.Run::mono), "the item's code");
            List<LayoutOracle.Line> lines = typst.ofUnit(item.index());
            assertTrue(lines.size() > 3, "the item wraps");
            double hang = lines.get(1).x();
            for (LayoutOracle.Line line : lines.subList(1, lines.size())) {
                ledByCode |= item.monoAt(line.from());
                assertEquals(hang, line.x(), 1.5, "line '" + line.text(item) + "' of " + lines);
            }
        }
        assertTrue(ledByCode, "a wrapped line begins with inline code");
    }

    private void checkPage(DocumentSurfaceView surface, List<LayoutOracle.Unit> units, LayoutOracle.Layout layout, PrintSettings settings,
                           LayoutOracle.Geometry geometry, StoredDocument document) throws Exception {
        String check = onFx(() -> {
            surface.beginLineCheck(geometry.contentLeft());
            surface.layoutLines(layout, units);
            return surface.lineCheck();
        });
        assertTrue(check.contains(" 0 drawing other text"), check);

        List<PageStackView.Placed> placed = onFx(() -> surface.linesOn(0, geometry.contentLeft(), geometry.contentWidth(), geometry.bandHeight()));
        double size = settings.theme().baseFontSize() * TypstWriter.RAW_SCALE;
        List<String> mono = new ArrayList<>();
        List<String> bold = new ArrayList<>();
        List<String> italic = new ArrayList<>();
        for (PageStackView.Placed line : placed) {
            RichTextArea area = (RichTextArea) line.node();
            RichParagraph paragraph = area.getModel().getParagraph(0);
            for (int k = 0; k < paragraph.getSegmentCount(); k++) {
                StyledSegment segment = paragraph.getSegment(k);
                StyleAttributeMap style = segment.getStyleAttributeMap(null);
                if (segment.getType() == StyledSegment.Type.TEXT && style != null) {
                    if (DocumentFonts.MONO.equals(style.getFontFamily())) {
                        double expected = line.baseline() > geometry.contentBottom() - 60 ? size * LayoutOracle.FOOTNOTE_SCALE : size;
                        assertEquals(expected, style.getFontSize(), 0.001, "inline code at the raw size of the text around it");
                        assertTrue(style.get(LineRunModel.LINE) != null, "a span keeps its source line");
                        mono.add(segment.getText());
                    }
                    if (style.isBold()) {
                        bold.add(segment.getText());
                    }
                    if (style.isItalic()) {
                        italic.add(segment.getText());
                    }
                }
            }
        }
        assertTrue(mono.contains("measurementBasis"), mono.toString());
        assertTrue(mono.contains("code"), mono.toString());
        assertTrue(bold.contains("strong"), bold.toString());
        assertTrue(String.join(" ", italic).contains("for submissions"), italic.toString());
        assertEquals(1, onFx(() -> surface.backdropsOn(0, geometry.contentLeft(), geometry.contentWidth())).size(), "the rule over the page's notes");
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
