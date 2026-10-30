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
package network.ike.komet.claude.decision.render;

import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.decision.BlockSweep;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTreeSensitivity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import network.ike.komet.claude.stamp.print.TypstCompiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The scan tree and its tornado as Typst, against goldens: the code the writer emits is the code
 * the print pipeline pastes, so it changes only on purpose. Run with
 * {@code -Ddecision.golden.update=true} to rewrite the goldens from the current output, and read
 * the diff before committing them. Where a {@code typst} binary is on the path, the fragments
 * are compiled too, so the golden is known to be Typst and not merely stable.
 */
class DecisionTreeTypstTest {

    static final DecisionTerms T = DecisionTerms.synthetic();
    static BlockIdentities ids;
    static DecisionTreeSensitivity sweep;

    @BeforeAll
    static void sweepTheScanTree() throws Exception {
        String body;
        try (InputStream in = DecisionTreeTypstTest.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            body = text.substring(text.indexOf("----\n") + 5, text.lastIndexOf("----"));
        }
        ids = BlockIdentities.storeFree();
        sweep = BlockSweep.sweep(body, T, ids, 100_000, BlockSweep.DEFAULT_STEPS).orElseThrow();
    }

    @Test
    @DisplayName("The tree as Typst matches its golden")
    void treeGolden() throws Exception {
        String typst = DecisionTreeTypst.tree(sweep.tree(), sweep.base(), ids, Optional.of(sweep));
        assertTrue(typst.startsWith("// decision-tree\n#box(width: "), typst.substring(0, 40));
        assertTrue(typst.contains("rect(width: 13pt, height: 13pt, fill: rgb(\"#e8eef9\")"), "a square for a decision");
        assertTrue(typst.contains("circle(radius: 6.5pt, fill: rgb(\"#fff3e0\")"), "a circle for a chance");
        assertTrue(typst.contains("polygon(fill: rgb(\"#eaf4e8\")"), "a triangle for an outcome");
        assertTrue(typst.contains("thickness: 2.6pt"), "the chosen alternative is heavy");
        assertTrue(typst.contains("\"⇒ 3,880\""));
        golden("rda-scan-tree.typ", typst);
    }

    @Test
    @DisplayName("The tornado as Typst matches its golden")
    void tornadoGolden() throws Exception {
        String typst = DecisionTreeTypst.tornado(sweep, ids);
        assertTrue(typst.startsWith("// decision-tornado\n#box(width: 640pt"), typst.substring(0, 40));
        assertTrue(typst.contains("dash: (array: (3pt, 3pt), phase: 0pt)"), "the base line is dashed");
        assertTrue(typst.contains("\"flips at 1,008\""));
        golden("rda-scan-tornado.typ", typst);
    }

    private static void golden(String name, String actual) throws IOException, InterruptedException {
        Path source = Path.of("src/test/resources/decision", name);
        if (Boolean.getBoolean("decision.golden.update")) {
            Files.writeString(source, actual, StandardCharsets.UTF_8);
        }
        String expected;
        if (Files.exists(source)) {
            expected = Files.readString(source, StandardCharsets.UTF_8); // the source tree, where an update lands
        } else {
            try (InputStream in = DecisionTreeTypstTest.class.getResourceAsStream("/decision/" + name)) {
                assertTrue(in != null, "golden " + name + " is missing; run with -Ddecision.golden.update=true to write it");
                expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        assertEquals(expected, actual, name + " differs from its golden; if the change is meant, "
                + "rerun with -Ddecision.golden.update=true");
        compiles(name, actual);
    }

    /** Compiles the fragment with the typst binary on the path, where there is one. */
    private static void compiles(String name, String fragment) throws IOException, InterruptedException {
        // The print pipeline's own lookup: the path, then the login shell, then the usual
        // install folders — a CI agent's launchd environment has no Homebrew bin on its path.
        Path typst = TypstCompiler.locate().orElse(null);
        assumeTrue(typst != null, "no typst binary on the path or in the usual folders");
        Path dir = Files.createTempDirectory("decision-typst");
        Path page = dir.resolve(name);
        Files.writeString(page, "#set page(width: auto, height: auto, margin: 12pt)\n" + fragment, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(typst.toString(), "compile", page.toString(), dir.resolve("out.pdf").toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "typst did not finish");
        assertEquals(0, process.exitValue(), "typst refused " + name + ":\n" + output);
        assertTrue(Files.size(dir.resolve("out.pdf")) > 0);
    }
}
