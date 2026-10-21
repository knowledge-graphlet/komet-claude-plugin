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

import network.ike.komet.claude.doc.print.settings.PrintSettings;
import network.ike.komet.claude.stamp.print.LayoutOracle.Layout;
import network.ike.komet.claude.stamp.print.LayoutOracle.Line;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measures the Page layout's two passes ({@code dev-document-surface-pages}): the greedy JavaFX
 * estimate shown first against Typst's own lines settled on second — page counts, the unit each
 * page breaks at, the units that start on the same page, and the lines that agree character for
 * character — and whether the word labels the Typst oracle reads its lines from moved any block
 * of Typst's output against the unlabelled document that Print to PDF compiles. After the second
 * pass the pages are Typst's by construction; the first comparison is the cost of showing the
 * estimate until typst answers, the second is what remains inexact.
 */
public final class PageFidelity {

    private static final Pattern LOCATION = Pattern.compile("\\{[^{}]*\"page\"\\s*:\\s*(\\d+)[^{}]*\"y\"\\s*:\\s*\"?(-?[0-9.]+)(pt)?\"?[^{}]*}");

    private PageFidelity() { }

    /**
     * Where a labelled block starts in Typst's output.
     *
     * @param page the 1-based page
     * @param y    the offset from the page's top edge, in points
     */
    public record Placed(int page, double y) { }

    /**
     * The comparison of the two passes.
     *
     * @param greedyPages   pages in the greedy estimate
     * @param typstPages    pages in Typst's layout
     * @param greedyBreaks  the unit each greedy page starts with, from page 2 on; -1 inside a unit
     * @param typstBreaks   the unit each Typst page starts with, from page 2 on; -1 inside a unit
     * @param units         units compared
     * @param samePage      units that start on the same page in both
     * @param sameLineCount units with the same number of lines in both
     * @param greedyLines   lines in the greedy estimate
     * @param typstLines    lines in Typst's layout
     * @param sameLines     greedy lines that hold exactly the characters of a Typst line of the same unit
     * @param hyphenated    Typst lines ending in a hyphen Typst inserted
     * @param notes         the Typst oracle's notes
     */
    public record Report(int greedyPages, int typstPages, List<Integer> greedyBreaks, List<Integer> typstBreaks, int units, int samePage,
                         int sameLineCount, int greedyLines, int typstLines, int sameLines, int hyphenated, List<String> notes) {

        /** @return the report as text, one finding per line */
        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "pages: greedy JavaFX %d, Typst %d%n", greedyPages, typstPages));
            sb.append(String.format(Locale.ROOT, "page breaks (unit that starts each page from page 2): greedy %s, Typst %s%n", greedyBreaks, typstBreaks));
            sb.append(String.format(Locale.ROOT, "units starting on the same page: %d of %d%n", samePage, units));
            sb.append(String.format(Locale.ROOT, "units with the same line count: %d of %d%n", sameLineCount, units));
            sb.append(String.format(Locale.ROOT, "lines: greedy %d, Typst %d; greedy lines breaking exactly as Typst's: %d (%.0f%% of Typst's); Typst lines hyphenated: %d%n",
                    greedyLines, typstLines, sameLines, typstLines == 0 ? 0 : 100.0 * sameLines / typstLines, hyphenated));
            for (String note : notes) {
                sb.append("note: ").append(note).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * Compares the greedy estimate with Typst's layout of the same units.
     *
     * @param greedy the first pass
     * @param typst  the second
     * @param units  the units both laid out
     * @return the report
     */
    public static Report compare(Layout greedy, Layout typst, List<Unit> units) {
        int samePage = 0;
        int sameLineCount = 0;
        int sameLines = 0;
        int hyphenated = 0;
        for (Unit unit : units) {
            List<Line> g = greedy.ofUnit(unit.index());
            List<Line> t = typst.ofUnit(unit.index());
            if (!g.isEmpty() && !t.isEmpty() && g.getFirst().page() == t.getFirst().page()) {
                samePage++;
            }
            if (g.size() == t.size()) {
                sameLineCount++;
            }
            for (Line line : g) {
                if (t.stream().anyMatch(o -> o.from() == line.from() && o.to() == line.to())) {
                    sameLines++;
                }
            }
            hyphenated += (int) t.stream().filter(Line::hyphenated).count();
        }
        return new Report(greedy.pages(), typst.pages(), greedy.breaks(), typst.breaks(), units.size(), samePage, sameLineCount,
                greedy.lines().size(), typst.lines().size(), sameLines, hyphenated, typst.notes());
    }

    /**
     * Whether the word labels moved anything: compiles the model with block labels only and
     * with word labels too, and compares where every block starts.
     *
     * @param model    the print model
     * @param settings the print settings
     * @param folder   where the sources go; typst's root
     * @return one line of findings, ending in a newline
     * @throws IOException if the sources cannot be written
     */
    public static String perturbation(DocumentModel model, PrintSettings settings, Path folder) throws IOException {
        Files.createDirectories(folder);
        Path fonts = DocumentFonts.extractTo(folder.resolve("fonts"));
        Path blocksOnly = folder.resolve("blocks.typ");
        Path words = folder.resolve("words.typ");
        Files.writeString(blocksOnly, TypstWriter.write(model, TypstPdf.setup(settings), TypstWriter.Labels.BLOCKS), StandardCharsets.UTF_8);
        Files.writeString(words, TypstWriter.write(model, TypstPdf.setup(settings), TypstWriter.Labels.WORDS), StandardCharsets.UTF_8);
        TypstCompiler.Result a = TypstCompiler.locate(folder, blocksOnly, TypstWriter.BLOCK_LABEL, fonts);
        TypstCompiler.Result b = TypstCompiler.locate(folder, words, TypstWriter.BLOCK_LABEL, fonts);
        if (!a.ok() || !b.ok()) {
            return "label perturbation: not measured (" + (a.ok() ? b : a).output().strip() + ")\n";
        }
        List<Placed> without = parse(a.output());
        List<Placed> with = parse(b.output());
        int moved = 0;
        double largest = 0;
        for (int i = 0; i < Math.min(without.size(), with.size()); i++) {
            if (without.get(i).page() != with.get(i).page()) {
                moved++;
                largest = Double.MAX_VALUE;
            } else {
                double d = Math.abs(without.get(i).y() - with.get(i).y());
                if (d > 0.05) {
                    moved++;
                }
                largest = Math.max(largest, d);
            }
        }
        return String.format(Locale.ROOT, "label perturbation: %d of %d block starts moved by the word labels (largest %s)%n",
                moved, Math.min(without.size(), with.size()), largest == Double.MAX_VALUE ? "a page" : String.format(Locale.ROOT, "%.2f pt", largest));
    }

    /**
     * Parses typst's answer to a location query: one {@code {"page": n, "y": "123.4pt"}} per block.
     *
     * @param json the JSON array
     * @return the placements in document order
     */
    public static List<Placed> parse(String json) {
        List<Placed> out = new ArrayList<>();
        Matcher m = LOCATION.matcher(json);
        while (m.find()) {
            out.add(new Placed(Integer.parseInt(m.group(1)), Double.parseDouble(m.group(2))));
        }
        return out;
    }
}
