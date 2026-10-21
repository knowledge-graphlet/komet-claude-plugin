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

import network.ike.komet.claude.stamp.print.LayoutOracle.Layout;
import network.ike.komet.claude.stamp.print.LayoutOracle.Line;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * A golden layout: Typst's layout of one corpus document as the {@link TypstOracle} read it,
 * stored beside the corpus and pinned to the Typst version and to the Typst source the writer
 * produced. The source's hash fails the harness loudly when the writer's output changes —
 * a wrong writer would teach the engine a mistake — and the units' hash when the unit model
 * does; regeneration (needs typst) is the only way past either.
 *
 * <p>Format: a header of {@code key: value} lines ({@code typst}, {@code source-sha256},
 * {@code units-sha256}, {@code units}, {@code pages}), a blank line, then one line per laid-out
 * line: {@code unit page x baseline from to hyphenated}.
 *
 * @param typstVersion the typst version that laid the document out
 * @param sourceHash   SHA-256 of the Typst source the writer wrote (block labels, no word labels)
 * @param unitsHash    SHA-256 of the units' texts
 * @param units        the unit count
 * @param layout       Typst's layout
 */
public record GoldenLayout(String typstVersion, String sourceHash, String unitsHash, int units, Layout layout) {

    /** The Typst version the goldens are pinned to. */
    public static final String TYPST_VERSION = "0.15.1";

    /** @return the golden as text */
    public String write() {
        StringBuilder sb = new StringBuilder();
        sb.append("typst: ").append(typstVersion).append('\n');
        sb.append("source-sha256: ").append(sourceHash).append('\n');
        sb.append("units-sha256: ").append(unitsHash).append('\n');
        sb.append("units: ").append(units).append('\n');
        sb.append("pages: ").append(layout.pages()).append('\n');
        sb.append('\n');
        for (Line line : layout.lines()) {
            sb.append(String.format(Locale.ROOT, "%d %d %.2f %.2f %d %d %s%n", line.unit(), line.page(), line.x(), line.baseline(),
                    line.from(), line.to(), line.hyphenated() ? "-" : "."));
        }
        return sb.toString();
    }

    /**
     * Parses a golden.
     *
     * @param text the file's text
     * @return the golden
     */
    public static GoldenLayout read(String text) {
        String typst = "";
        String source = "";
        String unitsHash = "";
        int units = 0;
        int pages = 1;
        List<Line> lines = new ArrayList<>();
        boolean body = false;
        for (String line : text.split("\n")) {
            if (!body) {
                if (line.isBlank()) {
                    body = true;
                    continue;
                }
                int colon = line.indexOf(':');
                String key = line.substring(0, colon).strip();
                String value = line.substring(colon + 1).strip();
                switch (key) {
                    case "typst" -> typst = value;
                    case "source-sha256" -> source = value;
                    case "units-sha256" -> unitsHash = value;
                    case "units" -> units = Integer.parseInt(value);
                    case "pages" -> pages = Integer.parseInt(value);
                    default -> { }
                }
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.trim().split("\\s+");
            lines.add(new Line(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                    Integer.parseInt(f[4]), Integer.parseInt(f[5]), f[6].equals("-")));
        }
        return new GoldenLayout(typst, source, unitsHash, units, new Layout(pages, lines, true, 0, List.of()));
    }

    /** Reads a golden file. */
    public static GoldenLayout read(Path file) throws IOException {
        return read(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** @return SHA-256 of a text, lower-case hex */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the hash of the units' texts, in order */
    public static String unitsHash(List<Unit> units) {
        StringBuilder sb = new StringBuilder();
        for (Unit unit : units) {
            sb.append(unit.block()).append('\t').append(unit.kind()).append('\t').append(unit.text()).append('\n');
        }
        return sha256(sb.toString());
    }

    /**
     * The source the hash pins: the document written with block labels only — the labels the
     * oracle adds for words are the oracle's own, the writer's output is what matters.
     *
     * @param model the print model
     * @param setup the page
     * @return the Typst source
     */
    public static String pinnedSource(DocumentModel model, TypstWriter.PageSetup setup) {
        return TypstWriter.write(model, setup, TypstWriter.Labels.BLOCKS);
    }
}
