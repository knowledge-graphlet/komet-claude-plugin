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
package network.ike.komet.claude.stamp.print;

import javafx.application.Platform;
import network.ike.komet.claude.doc.print.settings.PrintSettings;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.print.LayoutOracle.Geometry;
import network.ike.komet.claude.stamp.print.LayoutOracle.Layout;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The layout fidelity harness ({@code dev-document-surface-pages}): every corpus document
 * ({@code src/test/resources/fidelity/corpus}) is parsed, written as Typst and laid out by the
 * greedy JavaFX first pass, headless, against the golden layout Typst gave it
 * ({@code src/test/resources/fidelity/golden}); the score and its breakdown go to the console,
 * to {@code target/fidelity-report.txt}, and to {@code target/measurements/fidelity.properties}
 * for the build report, whose ledger holds the floor the build may not fall under.
 *
 * <p>A golden pins the Typst version and the writer's output: when the writer's Typst for a
 * document changes, the harness fails loudly rather than score against a stale golden.
 * Regenerate with {@code -Dfidelity.regenerate=true} (needs typst 0.15.1).
 */
class LayoutFidelityIT {

    private static final Path CORPUS = Path.of("src/test/resources/fidelity/corpus");
    private static final Path GOLDEN = Path.of("src/test/resources/fidelity/golden");
    private static final PrintSettings SETTINGS = PrintSettings.DEFAULT;
    private static final boolean REGENERATE = Boolean.getBoolean("fidelity.regenerate");

    private static String toolkit = "unknown";

    @BeforeAll
    static void startJavaFx() {
        // Headless under Monocle when it is on the class path; the default toolkit otherwise.
        try {
            Class.forName("com.sun.glass.ui.monocle.MonoclePlatformFactory");
            System.setProperty("glass.platform", "Monocle");
            System.setProperty("monocle.platform", "Headless");
            System.setProperty("prism.order", "sw");
            System.setProperty("prism.text", "t2k");
            System.setProperty("java.awt.headless", "true");
            toolkit = "Monocle headless";
        } catch (ClassNotFoundException e) {
            toolkit = "default (Monocle not on the class path)";
        }
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyRunning) {
            toolkit += ", already started";
        }
        DocumentFonts.loadForJavaFx();
    }

    /** The corpus documents, by file name without the suffix, sorted. */
    static List<String> corpus() throws IOException {
        try (Stream<Path> files = Files.list(CORPUS)) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".adoc"))
                    .map(n -> n.substring(0, n.length() - 5)).sorted().toList();
        }
    }

    static DocumentModel model(String name) throws IOException {
        StoredDocument stored = StoredFixture.parse(Files.readString(CORPUS.resolve(name + ".adoc"), StandardCharsets.UTF_8));
        return StoredDocumentModel.of(stored);
    }

    static Geometry geometry() {
        double[] dims = network.ike.komet.claude.doc.print.PagedDocumentEngine.pageDimensionsPoints(SETTINGS);
        var m = SETTINGS.margins();
        return new Geometry(dims[0], dims[1], m.left(), m.top(), dims[0] - m.left() - m.right(), dims[1] - m.top() - m.bottom());
    }

    @Test
    void theFirstPassScoresAgainstTypstsGoldens() throws Exception {
        List<String> names = corpus();
        assertTrue(names.size() >= 20, "the corpus holds " + names.size() + " document(s)");
        if (REGENERATE) {
            regenerate(names);
        }
        double measure = Double.parseDouble(System.getProperty("fidelity.measure", Double.toString(GreedyFxOracle.MEASURE)));
        boolean hyphenation = !"false".equals(System.getProperty("fidelity.hyphenate"));
        GreedyFxOracle greedy = new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO, measure, hyphenation);
        Geometry geometry = geometry();
        TypstWriter.PageSetup setup = TypstPdf.setup(SETTINGS);
        List<FidelityScore.Document> scores = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        long started = System.nanoTime();
        for (String name : names) {
            Path file = GOLDEN.resolve(name + ".layout");
            assertTrue(Files.isRegularFile(file), "no golden for " + name + ": regenerate with -Dfidelity.regenerate=true");
            GoldenLayout golden = GoldenLayout.read(file);
            DocumentModel model = model(name);
            List<Unit> units = ModelUnits.of(model, SETTINGS.theme().baseFontSize());
            String source = GoldenLayout.sha256(GoldenLayout.pinnedSource(model, setup));
            if (!source.equals(golden.sourceHash())) {
                stale.add(name + ": the writer's Typst changed (source " + source.substring(0, 12) + " vs golden " + golden.sourceHash().substring(0, 12) + ")");
            } else if (!GoldenLayout.unitsHash(units).equals(golden.unitsHash())) {
                stale.add(name + ": the unit model changed");
            } else if (!GoldenLayout.TYPST_VERSION.equals(golden.typstVersion())) {
                stale.add(name + ": golden from typst " + golden.typstVersion() + ", pinned " + GoldenLayout.TYPST_VERSION);
            }
            Layout pass = greedy.layout(units, geometry);
            scores.add(FidelityScore.score(name, golden.layout(), pass, units));
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        FidelityScore.Corpus corpus = new FidelityScore.Corpus(scores);
        String report = "toolkit: " + toolkit + "; measure " + measure + (hyphenation ? "" : "; no hyphenation") + "; " + names.size() + " document(s) in " + millis + " ms\n" + corpus.text();
        System.out.println(report);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/fidelity-report.txt"), report, StandardCharsets.UTF_8);
        Path measures = FidelityMeasures.write(corpus, Path.of("target"));
        assertTrue(Files.isRegularFile(measures));
        if (!stale.isEmpty()) {
            fail("golden layouts are stale — a wrong writer would teach the engine a mistake; regenerate with -Dfidelity.regenerate=true (needs typst "
                    + GoldenLayout.TYPST_VERSION + "):\n  " + String.join("\n  ", stale));
        }
        if (Boolean.getBoolean("fidelity.analyze")) {
            analyze(names, greedy, geometry);
        }
    }

    /** Prints what the goldens say about the spacing between units, by the kinds on either side, against the greedy rule. */
    private static void analyze(List<String> names, GreedyFxOracle greedy, Geometry geometry) throws Exception {
        java.util.Map<String, List<Double>> gaps = new java.util.TreeMap<>();
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (String name : names) {
            GoldenLayout golden = GoldenLayout.read(GOLDEN.resolve(name + ".layout"));
            List<Unit> units = ModelUnits.of(model(name), SETTINGS.theme().baseFontSize());
            for (Unit u : units) {
                kinds.merge(name + " " + u.kind(), 1, Integer::sum);
            }
            Layout g = golden.layout();
            for (int i = 1; i < units.size(); i++) {
                List<LayoutOracle.Line> before = g.ofUnit(i - 1);
                List<LayoutOracle.Line> own = g.ofUnit(i);
                if (before.isEmpty() || own.isEmpty() || before.getLast().page() != own.getFirst().page()) {
                    continue;
                }
                double typst = own.getFirst().baseline() - before.getLast().baseline();
                Unit prev = units.get(i - 1);
                Unit unit = units.get(i);
                double rule = UnitSpacing.gap(prev, unit) + unit.fontSize() * LayoutOracle.CAP_HEIGHT;
                gaps.computeIfAbsent(prev.kind() + "(" + (int) prev.fontSize() + ")->" + unit.kind() + "(" + (int) unit.fontSize() + ")", k -> new ArrayList<>()).add(typst - rule);
            }
            // The pitch within a unit.
            for (int i = 0; i < units.size(); i++) {
                List<LayoutOracle.Line> own = g.ofUnit(i);
                for (int k = 1; k < own.size(); k++) {
                    if (own.get(k).page() == own.get(k - 1).page()) {
                        gaps.computeIfAbsent("pitch " + units.get(i).kind() + "(" + (int) units.get(i).fontSize() + ")", x -> new ArrayList<>())
                                .add((own.get(k).baseline() - own.get(k - 1).baseline()) / units.get(i).fontSize());
                    }
                }
            }
        }
        // What Typst does at page breaks: units split with one line left or carried, headings last on a page.
        int orphans = 0;
        int widows = 0;
        int splits = 0;
        int headingsLast = 0;
        int headings = 0;
        for (String name : names) {
            Layout g = GoldenLayout.read(GOLDEN.resolve(name + ".layout")).layout();
            List<Unit> units = ModelUnits.of(model(name), SETTINGS.theme().baseFontSize());
            for (int i = 0; i < units.size(); i++) {
                List<LayoutOracle.Line> own = g.ofUnit(i);
                if (own.isEmpty()) {
                    continue;
                }
                if (own.getFirst().page() != own.getLast().page()) {
                    splits++;
                    int firstPage = own.getFirst().page();
                    long before = own.stream().filter(l -> l.page() == firstPage).count();
                    if (before == 1) {
                        orphans++;
                    }
                    if (own.size() - before == 1) {
                        widows++;
                    }
                }
                if (units.get(i).kind() == LayoutOracle.Kind.HEADING && i + 1 < units.size() && !g.ofUnit(i + 1).isEmpty()) {
                    headings++;
                    if (g.ofUnit(i + 1).getFirst().page() != own.getLast().page()) {
                        headingsLast++;
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "ANALYSIS page breaks in the goldens: %d split units, %d leave one line behind (orphan), %d carry one over (widow); %d of %d headings are last on their page%n",
                splits, orphans, widows, headingsLast, headings);
        System.out.println("ANALYSIS gaps: typst minus the greedy rule, in points (count, median, mean)");
        for (var e : gaps.entrySet()) {
            List<Double> v = new ArrayList<>(e.getValue());
            java.util.Collections.sort(v);
            double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            System.out.printf(Locale.ROOT, "ANALYSIS %-44s n=%4d median=%7.2f mean=%7.2f%n", e.getKey(), v.size(), v.get(v.size() / 2), mean);
        }
        for (var e : kinds.entrySet()) {
            if (e.getKey().startsWith("synthetic-long-table")) {
                System.out.println("ANALYSIS units " + e.getKey() + " = " + e.getValue());
            }
        }
    }

    /** Lays every corpus document out with typst and writes the goldens; the oracle's notes name what stayed inexact. */
    private static void regenerate(List<String> names) throws Exception {
        assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        String version = typstVersion();
        assertEquals(GoldenLayout.TYPST_VERSION, version, "the goldens are pinned to typst " + GoldenLayout.TYPST_VERSION);
        Files.createDirectories(GOLDEN);
        Path work = Files.createTempDirectory("fidelity-golden-");
        Path fonts = DocumentFonts.extractTo(work.resolve("fonts"));
        GreedyFxOracle measure = new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO);
        TypstWriter.PageSetup setup = TypstPdf.setup(SETTINGS);
        Geometry geometry = geometry();
        for (String name : names) {
            DocumentModel model = model(name);
            List<Unit> units = ModelUnits.of(model, SETTINGS.theme().baseFontSize());
            Path source = work.resolve(name + ".typ");
            Files.writeString(source, TypstWriter.write(model, setup, TypstWriter.Labels.WORDS), StandardCharsets.UTF_8);
            Layout layout = new TypstOracle(work, source, fonts, measure::width).layout(units, geometry);
            assertTrue(!layout.lines().isEmpty(), name + ": typst gave no layout: " + layout.notes());
            GoldenLayout golden = new GoldenLayout(version, GoldenLayout.sha256(GoldenLayout.pinnedSource(model, setup)),
                    GoldenLayout.unitsHash(units), units.size(), layout);
            Files.writeString(GOLDEN.resolve(name + ".layout"), golden.write(), StandardCharsets.UTF_8);
            System.out.println("golden " + name + ": " + layout.pages() + " page(s), " + layout.lines().size() + " line(s), " + layout.millis() + " ms"
                    + (layout.notes().isEmpty() ? "" : " — " + String.join("; ", layout.notes())));
        }
    }

    private static String typstVersion() throws Exception {
        Process process = new ProcessBuilder(TypstCompiler.locate().orElseThrow().toString(), "--version").redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        process.waitFor();
        String[] parts = out.split("\\s+");
        return parts.length > 1 ? parts[1] : out;
    }
}
