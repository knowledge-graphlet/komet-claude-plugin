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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * The fidelity score as build-report Measures: numeric properties under the module's
 * {@code target/measurements/}, which the build-report extension collects at session end
 * like test and warning counts, bounds against the ledger ({@code .mvn/build-report.yaml},
 * {@code measures:}) and publishes to TeamCity as statistics. Keys:
 *
 * <ul>
 *   <li>{@code fidelity.score} — the corpus score, percent of Typst's lines broken exactly</li>
 *   <li>{@code fidelity.documents}, {@code fidelity.lines}, {@code fidelity.lines.same}</li>
 *   <li>{@code fidelity.pages.matching} — documents whose page count matches Typst's</li>
 *   <li>{@code fidelity.units.samePage} — units beginning on the same page as in Typst's layout</li>
 *   <li>{@code fidelity.hyphens.missed}</li>
 *   <li>{@code fidelity.rule.<rule>} — mismatching units laid at each rule</li>
 *   <li>{@code fidelity.doc.<name>} — each document's score</li>
 * </ul>
 */
public final class FidelityMeasures {

    /** The file under {@code target/measurements}. */
    public static final String FILE = "fidelity.properties";

    private FidelityMeasures() { }

    /**
     * Writes the measures.
     *
     * @param corpus the scored corpus
     * @param target the module's build directory
     * @return the file written
     * @throws IOException if it cannot be written
     */
    public static Path write(FidelityScore.Corpus corpus, Path target) throws IOException {
        Path folder = target.resolve("measurements");
        Files.createDirectories(folder);
        StringBuilder sb = new StringBuilder();
        sb.append("# Layout fidelity: the greedy JavaFX first pass against Typst's golden layouts (LayoutFidelityIT)\n");
        sb.append(String.format(Locale.ROOT, "fidelity.score=%.2f%n", corpus.score()));
        sb.append("fidelity.documents=").append(corpus.documents().size()).append('\n');
        sb.append("fidelity.lines=").append(corpus.documents().stream().mapToInt(FidelityScore.Document::linesGolden).sum()).append('\n');
        sb.append("fidelity.lines.same=").append(corpus.documents().stream().mapToInt(FidelityScore.Document::linesSame).sum()).append('\n');
        sb.append("fidelity.pages.matching=").append(corpus.pagesMatching()).append('\n');
        sb.append("fidelity.units.samePage=").append(corpus.documents().stream().mapToInt(FidelityScore.Document::unitsSamePage).sum()).append('\n');
        sb.append("fidelity.hyphens.missed=").append(corpus.documents().stream().mapToInt(FidelityScore.Document::hyphenMissed).sum()).append('\n');
        sb.append("fidelity.units.missing=").append(corpus.attribution().getOrDefault(FidelityScore.Rule.MISSING, 0)).append('\n');
        for (Map.Entry<FidelityScore.Rule, Integer> e : corpus.attribution().entrySet()) {
            sb.append("fidelity.rule.").append(e.getKey().name().toLowerCase(Locale.ROOT)).append('=').append(e.getValue()).append('\n');
        }
        for (FidelityScore.Document d : corpus.documents()) {
            sb.append(String.format(Locale.ROOT, "fidelity.doc.%s=%.2f%n", d.name(), d.score()));
        }
        Path file = folder.resolve(FILE);
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }
}
