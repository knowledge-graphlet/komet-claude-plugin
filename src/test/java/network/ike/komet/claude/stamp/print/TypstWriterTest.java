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
import network.ike.komet.claude.doc.print.settings.FurnitureVisibility;
import network.ike.komet.claude.doc.print.settings.MarginPreset;
import network.ike.komet.claude.doc.print.settings.PageNumberPlacement;
import network.ike.komet.claude.doc.print.settings.PageOrientation;
import network.ike.komet.claude.doc.print.settings.PageSize;
import network.ike.komet.claude.doc.print.settings.PrintSettings;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.print.DocumentModel.Heading;
import network.ike.komet.claude.stamp.print.DocumentModel.ListBlock;
import network.ike.komet.claude.stamp.print.DocumentModel.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Typst writer, one construct at a time, each from a {@link StoredDocument} built the way
 * the materializer stores one. The body assertions read the source after the preamble.
 */
class TypstWriterTest {

    private static final String HEADER = """
            :topic-id: t-fixture
            = The Fixture Title

            """;

    private static String body(String asciidoc) {
        StoredDocument stored = StoredFixture.parse(HEADER + asciidoc);
        String typst = TypstWriter.write(StoredDocumentModel.of(stored), TypstWriter.PageSetup.DEFAULT);
        int title = typst.indexOf("#ike-title[");
        assertTrue(title >= 0, "title block missing in\n" + typst);
        return typst.substring(typst.indexOf('\n', title) + 1).strip();
    }

    @Test
    void pageSetupCarriesPaperMarginsFontsAndFurniture() {
        PrintSettings settings = new PrintSettings(PageSize.A4, PageOrientation.LANDSCAPE, MarginPreset.NARROW,
                DocumentTheme.MANUSCRIPT, FurnitureVisibility.SHOWN, PageNumberPlacement.FOOTER_RIGHT);
        String typst = TypstWriter.write(StoredDocumentModel.of(StoredFixture.parse(HEADER + "Hello.\n")), TypstPdf.setup(settings));
        assertTrue(typst.contains("#set document(title: \"The Fixture Title\")"));
        assertTrue(typst.contains("paper: \"a4\""));
        assertTrue(typst.contains("flipped: true"));
        assertTrue(typst.contains("margin: (top: 36pt, bottom: 36pt, left: 36pt, right: 36pt)"));
        assertTrue(typst.contains("\"Libertinus Serif\""), "serif body for the manuscript theme");
        assertTrue(typst.contains("size: 12pt"));
        assertTrue(typst.contains("header: context {"), "a running head");
        assertTrue(typst.contains("text(\"The Fixture Title\"), current)"), "title left, section right");
        assertTrue(typst.contains("footer: context align(right, text(size: 0.8em, fill: luma(70), counter(page).display(\"1\")))"));
        assertTrue(typst.contains("#ike-title[The Fixture Title]"));
    }

    @Test
    void noRunningHeadAndNoFolioLeaveNoFurniture() {
        PrintSettings settings = new PrintSettings(PageSize.LETTER, PageOrientation.PORTRAIT, MarginPreset.NORMAL,
                DocumentTheme.DEFAULT, FurnitureVisibility.HIDDEN, PageNumberPlacement.NONE);
        String typst = TypstWriter.write(StoredDocumentModel.of(StoredFixture.parse(HEADER + "Hello.\n")), TypstPdf.setup(settings));
        assertFalse(typst.contains("header:"));
        assertFalse(typst.contains("footer:"));
        assertTrue(typst.contains("\"Helvetica Neue\""), "sans body for the default theme");
        assertTrue(typst.contains("size: 11pt"));
    }

    @Test
    void headerFolioSharesTheRunningHeadBand() {
        PrintSettings settings = PrintSettings.DEFAULT.withPageNumbers(PageNumberPlacement.HEADER_RIGHT);
        String typst = TypstWriter.write(StoredDocumentModel.of(StoredFixture.parse(HEADER + "Hello.\n")), TypstPdf.setup(settings));
        assertTrue(typst.contains("#h(1em) #current], counter(page).display(\"1\"))"));
        assertFalse(typst.contains("footer:"));
    }

    @Test
    void headingsByLevel() {
        String typst = body("""
                == Section One

                Text.

                === Deeper

                More.
                """);
        assertTrue(typst.startsWith("= Section One\n\nText.\n\n== Deeper\n\nMore."), typst);
    }

    @Test
    void paragraphLinesJoinWithSpacesAndHardBreaksBreak() {
        String typst = body("""
                First line of prose
                continues here, +
                and ends with a backslash \\
                then the last.
                """);
        assertEquals("First line of prose continues here, \\\nand ends with a backslash then the last.", typst);
    }

    @Test
    void indexTermsPrintNothingAndFlowTermsKeepTheirWords() {
        String typst = body("""
                indexterm:[ANF, medication history example]
                This reference (((concealed))) covers ((hypertension)) and more.
                """);
        assertEquals("This reference covers hypertension and more.", typst);
    }

    @Test
    void markupCharactersAreEscaped() {
        String typst = body("""
                Use *bold* and _emphasis_, a #hash, 100% of [brackets] <angles> and a -- dash.
                """);
        assertEquals("Use \\*bold\\* and \\_emphasis\\_, a \\#hash, 100% of \\[brackets\\] \\<angles\\> and a \\-\\- dash.", typst);
    }

    @Test
    void unorderedAndOrderedLists() {
        String typst = body("""
                * first item
                  wraps here
                * second item

                Between.

                . step one
                . step two
                """);
        assertEquals("- first item wraps here\n- second item\n\nBetween.\n\n+ step one\n+ step two", typst);
    }

    @Test
    void nestedListAndContinuationParagraph() {
        String typst = body("""
                * outer
                ** inner one
                ** inner two
                * outer two
                +
                Attached paragraph.
                """);
        assertEquals("- outer\n  - inner one\n  - inner two\n- outer two\n  Attached paragraph.", typst);
    }

    @Test
    void descriptionListTermsAndText() {
        String typst = body("""
                Term one:: Its meaning.
                Term two::
                  Wrapped meaning.
                """);
        assertEquals("/ Term one: Its meaning.\n/ Term two: Wrapped meaning.", typst);
    }

    @Test
    void listTitleBecomesABlockTitle() {
        String typst = body("""
                .Things
                * one
                """);
        assertEquals("#ike-block-title[Things]\n- one", typst);
    }

    @Test
    void paragraphAdmonition() {
        String typst = body("""
                NOTE: Mind the gap
                on the platform.
                """);
        assertEquals("#ike-admonition(\"NOTE\")[\n  Mind the gap on the platform.\n]", typst);
    }

    @Test
    void blockAdmonitionCarriesItsBody() {
        String typst = body("""
                [WARNING]
                ====
                First paragraph.

                * a list inside
                ====
                """);
        assertEquals("#ike-admonition(\"WARNING\")[\n  First paragraph.\n\n  - a list inside\n]", typst);
    }

    @Test
    void listingIsFencedWithoutDelimitersAndKeepsLines() {
        String typst = body("""
                .Example code
                [source,java]
                ----
                int x = `tick`;
                  indented *stars* #hash
                ----
                """);
        assertEquals("#ike-block-title[Example code]\n```\nint x = `tick`;\n  indented *stars* #hash\n```", typst);
    }

    @Test
    void literalBlockFenceOutgrowsBackticksInside() {
        String typst = body("""
                ....
                a ``` run
                ....
                """);
        assertEquals("````\na ``` run\n````", typst);
    }

    @Test
    void tableWithHeaderRowColsAndTitle() {
        String typst = body("""
                .Elements
                [cols="2,3", options="header"]
                |===
                | Element | Topic

                | Gender (female)
                | Female

                | 58 years
                | Age
                |===
                """);
        String expected = """
                #figure(
                  table(
                    columns: (2fr, 3fr),
                    table.header(repeat: true,
                      [Element], [Topic], \n\
                    ),
                    [Gender (female)], [Female], \n\
                    [58 years], [Age], \n\
                  ),
                  caption: [Elements],
                  kind: table,
                )""";
        assertEquals(expected, typst);
    }

    @Test
    void tableWithoutColsOrHeaderPadsShortRows() {
        String typst = body("""
                |===
                | a | b | c

                | d
                |===
                """);
        assertTrue(typst.contains("columns: (1fr, 1fr, 1fr),"), typst);
        assertFalse(typst.contains("table.header"), typst);
        assertTrue(typst.contains("[a], [b], [c], \n    [d], [], [], \n"), typst);
    }

    @Test
    void tableModelReadsRepeatedColumnSpecs() {
        StoredDocument stored = StoredFixture.parse(HEADER + """
                [cols="3*1"]
                |===
                | a | b | c
                |===
                """);
        DocumentModel model = StoredDocumentModel.of(stored);
        Table table = assertInstanceOf(Table.class, model.blocks().getFirst());
        assertEquals(List.of(1, 1, 1), table.widths());
        assertEquals(0, table.headerRows());
    }

    @Test
    void exampleBlockWithTitle() {
        String typst = body("""
                .Narrative
                ====
                *Medication History* +
                Patient reports.
                ====
                """);
        assertEquals("#ike-example([Narrative])[\n  \\*Medication History\\* \\\n  Patient reports.\n]", typst);
    }

    @Test
    void quoteAndSidebar() {
        String typst = body("""
                ____
                A quotation.
                ____

                ****
                An aside.
                ****
                """);
        assertEquals("#quote(block: true)[\n  A quotation.\n]\n\n#ike-sidebar(none)[\n  An aside.\n]", typst);
    }

    @Test
    void imageIsNamedNotEmbedded() {
        String typst = body("""
                .A figure
                image::images/figure.svg[The figure]
                """);
        assertEquals("#ike-image(\"images/figure.svg\", [A figure])", typst);
    }

    @Test
    void commentsAttributeEntriesAndIncludesPrintNothing() {
        String typst = body("""
                // a comment
                :attr: value
                include::other.adoc[leveloffset=+1]

                Prose.
                """);
        assertEquals("Prose.", typst);
    }

    @Test
    void assemblyFlowsChildrenAtTheirOffsetLevels() {
        StoredDocument parent = StoredFixture.parse("""
                = The Compendium

                == Part One

                include::{topics}/topics/anf/a.adoc[leveloffset=+2]
                """);
        StoredDocument child = StoredFixture.parse("""
                :topic-id: anf-a
                = Child Title

                Child prose.

                == Child Section

                More.
                """);
        long includeLine = parent.lines().stream().filter(l -> l.text().startsWith("include::")).findFirst().orElseThrow().nid();
        DocumentModel model = StoredDocumentModel.of(parent, Map.of(includeLine, new StoredDocumentModel.Child(child, 2)));
        assertEquals("The Compendium", model.title());
        assertEquals(new Heading(1, "Part One"), model.blocks().get(0));
        assertEquals(new Heading(2, "Child Title"), model.blocks().get(1));
        assertEquals(new Heading(3, "Child Section"), model.blocks().get(3));
        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT);
        assertTrue(typst.contains("= Part One\n\n== Child Title\n\nChild prose.\n\n=== Child Section\n\nMore."), typst);
        assertEquals(2, StoredDocumentModel.Child.levelOffset("leveloffset=+2"));
        assertEquals(0, StoredDocumentModel.Child.levelOffset(""));
    }

    @Test
    void listKindFollowsTheMarker() {
        DocumentModel model = StoredDocumentModel.of(StoredFixture.parse(HEADER + "1. one\n2. two\n"));
        ListBlock list = assertInstanceOf(ListBlock.class, model.blocks().getFirst());
        assertEquals(ListBlock.Kind.ORDERED, list.kind());
        assertEquals("one", list.items().getFirst().text());
    }

    @Test
    void fontListsKeepOnlyInstalledFamilies() {
        assertEquals(List.of("B"), TypstPdf.available(List.of("A", "B", "C"), java.util.Optional.of(java.util.Set.of("b"))));
        assertEquals(List.of("A", "B"), TypstPdf.available(List.of("A", "B"), java.util.Optional.empty()));
        assertEquals(List.of("A", "B"), TypstPdf.available(List.of("A", "B"), java.util.Optional.of(java.util.Set.of("z"))));
    }

    @Test
    void fileStemFromNamesAndPaths() {
        assertEquals("compendium", TypstPdf.stem("compendium/src/docs/asciidoc/compendium.adoc"));
        assertEquals("anf-example-medication", TypstPdf.stem("anf-example-medication"));
        assertEquals("document", TypstPdf.stem(""));
        assertEquals("a-b", TypstPdf.stem("a b"));
    }

    @Test
    void theLabelledFormMarksTheTitleEveryTopLevelBlockAndTheEnd() {
        StoredDocument stored = StoredFixture.parse(HEADER + "A paragraph.\n\n== A section\n\nAnother paragraph.\n");
        DocumentModel model = StoredDocumentModel.of(stored);
        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT, true);

        String marker = "#metadata(0) <" + TypstWriter.BLOCK_LABEL + ">";
        java.util.regex.Matcher markers = java.util.regex.Pattern.compile("#metadata\\((\\d+)\\) <" + TypstWriter.BLOCK_LABEL + ">").matcher(typst);
        int count = 0;
        while (markers.find()) {
            assertEquals(count++, Integer.parseInt(markers.group(1)), "markers carry their index in order");
        }
        assertEquals(1 + model.blocks().size(), count, "one marker before the title and one before each top-level block");
        assertTrue(typst.contains("#metadata(none) <" + TypstWriter.END_LABEL + ">"), "the end label closes the document");
        assertTrue(typst.indexOf(marker) < typst.indexOf("#ike-title["), "the title's marker precedes it");
        assertFalse(TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT).contains("<" + TypstWriter.BLOCK_LABEL + ">"), "the plain form carries no markers");
        assertFalse(typst.contains(TypstOracle.WORD_RULE), "block labels alone label no words");
        String words = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT, TypstWriter.Labels.WORDS);
        assertTrue(words.contains(TypstOracle.WORD_RULE), "the oracle's form labels every word");
        assertTrue(words.indexOf(TypstOracle.WORD_RULE) < words.indexOf(marker), "the show rule precedes the first block");
    }

    @Test
    void theBundledNotoFamiliesComeFirst() {
        assertEquals(DocumentFonts.SERIF, TypstWriter.PageSetup.DEFAULT.bodyFonts().getFirst());
        assertEquals(DocumentFonts.MONO, TypstWriter.PageSetup.DEFAULT.monoFonts().getFirst());
        TypstWriter.PageSetup setup = TypstPdf.setup(PrintSettings.DEFAULT, java.util.Optional.of(java.util.Set.of("georgia")));
        assertEquals(List.of(DocumentFonts.SERIF, "Georgia"), setup.bodyFonts(), "the bundled family is always available; the rest only when installed");
    }
}
