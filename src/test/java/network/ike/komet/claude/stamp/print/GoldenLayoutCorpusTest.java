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
import network.ike.komet.claude.stamp.print.LayoutOracle.Line;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The committed goldens as the page stack will draw them: every line starts at or right of the
 * measure's left edge and holds some of its unit's text — a line the oracle placed left of the
 * margin or empty would draw cut or blank on the page.
 */
class GoldenLayoutCorpusTest {

    private static final Path GOLDEN = Path.of("src/test/resources/fidelity/golden");

    @Test
    void everyGoldenLineStartsInsideTheMeasureAndHoldsText() throws IOException {
        double left = PrintSettings.DEFAULT.margins().left();
        List<String> findings = new ArrayList<>();
        int lines = 0;
        try (Stream<Path> files = Files.list(GOLDEN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".layout")).sorted().toList()) {
                GoldenLayout golden = GoldenLayout.read(file);
                for (Line line : golden.layout().lines()) {
                    lines++;
                    if (line.x() < left - 0.5) {
                        findings.add(file.getFileName() + ": unit " + line.unit() + " line at x " + line.x() + " is left of the measure at " + left);
                    }
                    if (line.to() <= line.from()) {
                        findings.add(file.getFileName() + ": unit " + line.unit() + " line [" + line.from() + "," + line.to() + ") holds no text");
                    }
                }
            }
        }
        assertTrue(lines > 1000, "the goldens hold " + lines + " lines");
        assertTrue(findings.isEmpty(), String.join("\n", findings));
    }
}
