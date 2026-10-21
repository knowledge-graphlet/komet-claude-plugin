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

import network.ike.komet.claude.doc.print.settings.DocumentTheme;
import network.ike.komet.claude.doc.print.settings.MarginPreset;
import network.ike.komet.claude.doc.print.settings.PrintSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Print to PDF for a stamped document: the document's model written as Typst under the
 * surface's {@link PrintSettings}, into a folder of its own beside the system's temporary
 * files, and compiled there with {@code typst compile --root}. The {@code .typ} stays beside
 * the PDF, so what typst was given can be read when it complains.
 */
public final class TypstPdf {

    private static final List<String> SERIF = List.of(DocumentFonts.SERIF, "Libertinus Serif", "Georgia", "Times New Roman");
    private static final List<String> SANS = List.of("Helvetica Neue", "Noto Sans", "Helvetica", "Arial");
    private static final List<String> MONO = List.of(DocumentFonts.MONO, "Menlo", "DejaVu Sans Mono", "Courier New");

    private TypstPdf() { }

    /**
     * What one print produced.
     *
     * @param source the Typst source written
     * @param result the compile
     */
    public record Printed(Path source, TypstCompiler.Result result) {

        /** @return whether the PDF was written */
        public boolean ok() {
            return result.ok();
        }

        /** @return the PDF */
        public Path pdf() {
            return result.pdf();
        }

        /** @return the report for the status line: the PDF path, or typst's own words */
        public String report() {
            return result.report() + (result.ok() ? "" : " (source: " + source + ")");
        }
    }

    /**
     * The page the settings describe, in the writer's terms, naming only the fonts the installed
     * typst has.
     *
     * @param settings the surface's print settings
     * @return the page setup
     */
    public static TypstWriter.PageSetup setup(PrintSettings settings) {
        return setup(settings, TypstCompiler.installedFonts());
    }

    /**
     * The page the settings describe, in the writer's terms.
     *
     * @param settings  the surface's print settings
     * @param installed the font families typst has, lower-cased, or empty when unknown (then every preferred family is named)
     * @return the page setup
     */
    public static TypstWriter.PageSetup setup(PrintSettings settings, Optional<Set<String>> installed) {
        String paper = switch (settings.pageSize()) {
            case LETTER -> "us-letter";
            case A4 -> "a4";
            case LEGAL -> "us-legal";
        };
        MarginPreset m = settings.margins();
        DocumentTheme theme = settings.theme();
        TypstWriter.Folio folio = switch (settings.pageNumbers()) {
            case NONE -> TypstWriter.Folio.NONE;
            case HEADER_RIGHT -> TypstWriter.Folio.HEADER_RIGHT;
            case FOOTER_CENTER -> TypstWriter.Folio.FOOTER_CENTER;
            case FOOTER_RIGHT -> TypstWriter.Folio.FOOTER_RIGHT;
        };
        return new TypstWriter.PageSetup(paper, settings.orientation().swapsDimensions(), m.top(), m.right(), m.bottom(), m.left(),
                available(theme.fontFamily() == null ? SANS : SERIF, installed), available(MONO, installed), theme.baseFontSize(),
                settings.runningHead().shown(), folio);
    }

    /** The preferred families typst has, in preference order; all of them when the installed set is unknown or holds none. */
    static List<String> available(List<String> preferred, Optional<Set<String>> installed) {
        if (installed.isEmpty()) {
            return preferred;
        }
        // The bundled families are always there: the compile names their folder with --font-path.
        List<String> have = preferred.stream().filter(f -> f.equals(DocumentFonts.SERIF) || f.equals(DocumentFonts.MONO)
                || installed.get().contains(f.toLowerCase(Locale.ROOT))).toList();
        return have.isEmpty() ? preferred : have;
    }

    /**
     * Writes and compiles.
     *
     * @param model    the document
     * @param settings the page settings
     * @param name     the file name stem, cleaned of path separators
     * @return what was produced
     * @throws IOException if the output folder or the source cannot be written
     */
    public static Printed print(DocumentModel model, PrintSettings settings, String name) throws IOException {
        Path folder = Files.createTempDirectory("stamped-document-");
        return print(model, settings, folder, name);
    }

    /**
     * Writes and compiles into a given folder, which is also typst's root.
     *
     * @param model    the document
     * @param settings the page settings
     * @param folder   where the source and the PDF go
     * @param name     the file name stem
     * @return what was produced
     * @throws IOException if the source cannot be written
     */
    public static Printed print(DocumentModel model, PrintSettings settings, Path folder, String name) throws IOException {
        Files.createDirectories(folder);
        String stem = stem(name);
        Path source = folder.resolve(stem + ".typ");
        Path pdf = folder.resolve(stem + ".pdf");
        Files.writeString(source, TypstWriter.write(model, setup(settings)), StandardCharsets.UTF_8);
        Path fonts = DocumentFonts.extractTo(folder.resolve("fonts"));
        return new Printed(source, TypstCompiler.compile(folder, source, pdf, fonts));
    }

    /** A file name stem from a document name or path: the last path element, its extension dropped, unsafe characters replaced. */
    public static String stem(String name) {
        String last = name == null || name.isBlank() ? "document" : name;
        int slash = Math.max(last.lastIndexOf('/'), last.lastIndexOf(java.io.File.separatorChar));
        if (slash >= 0) {
            last = last.substring(slash + 1);
        }
        if (last.endsWith(".adoc") || last.endsWith(".asciidoc")) {
            last = last.substring(0, last.lastIndexOf('.'));
        }
        String cleaned = last.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "document" : cleaned;
    }
}
