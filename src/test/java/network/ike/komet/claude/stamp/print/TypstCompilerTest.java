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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The writer's output compiles under the installed typst — every construct in one document —
 * and a broken source reports typst's own words. Skipped where no typst is installed.
 */
class TypstCompilerTest {

    private static final String EVERYTHING = """
            :topic-id: t-everything
            = Everything At Once

            A paragraph with *stars*, _underscores_, #hashes, [brackets] and a -- dash.

            == Lists

            * one
            * two
            ** nested

            . first
            . second

            Term:: Meaning.

            == Blocks

            NOTE: A note.

            [WARNING]
            ====
            A warning with a body.
            ====

            .Code
            [source,java]
            ----
            int x = 1; // `tick`
            ----

            .Elements
            [cols="2,3", options="header"]
            |===
            | Element | Topic

            | Gender
            | Female

            | Age
            | 58
            |===

            .An example
            ====
            Example text.
            ====

            image::images/missing.svg[A figure]
            """;

    @Test
    void everyConstructCompilesToAPdf(@TempDir Path folder) throws IOException {
        assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        DocumentModel model = StoredDocumentModel.of(StoredFixture.parse(EVERYTHING));
        TypstPdf.Printed printed = TypstPdf.print(model, PrintSettings.DEFAULT, folder, "everything");
        assertTrue(printed.ok(), printed.report());
        assertTrue(Files.size(printed.pdf()) > 1000);
        assertEquals(folder.resolve("everything.typ"), printed.source());
        assertTrue(printed.result().output().isBlank(), "typst warned: " + printed.result().output());
        TypstCompiler.Result png = TypstCompiler.renderPage(folder, printed.source(), folder.resolve("page-1.png"), 1);
        assertTrue(png.ok(), png.report());
    }

    @Test
    void aBrokenSourceReportsTypstsOwnWords(@TempDir Path folder) throws IOException {
        assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        Path source = folder.resolve("broken.typ");
        Files.writeString(source, "#set page(paper: \"no-such-paper\")\nHello\n");
        TypstCompiler.Result result = TypstCompiler.compile(folder, source, folder.resolve("broken.pdf"));
        assertFalse(result.ok());
        assertTrue(result.report().startsWith("typst compile failed: "), result.report());
        assertTrue(result.output().contains("error"), result.output());
    }
}
