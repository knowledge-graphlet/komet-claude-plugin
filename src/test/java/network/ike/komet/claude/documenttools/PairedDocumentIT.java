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
package network.ike.komet.claude.documenttools;

import network.ike.komet.document.StructuredBlocks;

import network.ike.komet.document.GitDocumentMaterializer;

import network.ike.komet.document.DocumentPairing;

import network.ike.komet.document.DocumentOutline;

import network.ike.komet.document.DocumentNarratives;

import network.ike.komet.document.DocumentEditor;

import network.ike.komet.document.DocumentChanges;

import network.ike.komet.claude.documenttools.DocumentGraphTools;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.decision.DecisionTreeWrites;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assistant writing a paired document (ike-lab-documents topic {@code dev-decision-document},
 * "The assistant edits the document"): the document tools default to the paired document, a
 * structured block goes in with its style line, what it states is written as a version, Komet's
 * findings come back in the tool's result, the person's and the assistant's edits are one pending
 * edit, the lines of a request say what changed, and {@code create_document} makes, stores and
 * pairs a new document.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PairedDocumentIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final String DOCUMENT = """
            :topic-id: paired-%s
            = Paired

            A paragraph to write after.

            [anf,type=request,id=pdi-request-%s]
            --
            topic::   candidate[clinical site inspection] disp=PENDING
            --

            A closing paragraph.
            """;
    private static final String TREE = """
            [decision-tree,id=%s]
            ----
            decision: k:Treat[Treat the aneurysm now?] @id=pdi-treat
              alternative: k:DoTreat[Treat the aneurysm] @id=pdi-do-treat
                money = [10000,10000] k:Usd[US dollar] @id=pdi-usd
                outcome: k:Safe[Aneurysm treated safely] @id=pdi-safe
              alternative: k:Wait[Watch the aneurysm] @id=pdi-wait
                chance: k:Ruptures[Aneurysm rupture chance] @id=pdi-ruptures
                  event: k:Rupture[Aneurysm ruptures] @id=pdi-rupture
                    p = [%s,%s]
                    outcome: k:Ruptured[Ruptured aneurysm] @id=pdi-ruptured
                      money = [200000,200000] k:Usd[US dollar] @id=pdi-usd
                  residual: k:Intact[Aneurysm stays intact] @id=pdi-intact
                    outcome: k:Fine[Intact aneurysm at follow-up] @id=pdi-fine
            ----
            """;

    @TempDir
    Path root;

    private final Map<String, AnthropicTool> tools = new HashMap<>();
    private final List<PublicId> pairedAnchors = new ArrayList<>();
    private PublicId paired;
    private ViewCalculator view;

    @BeforeAll
    void setup() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        try {
            HeadlessFx.select();
            javafx.application.Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // the toolkit outlives a test class
        }
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
        view = Calculators.View.Default();
        DocumentGraphTools.Pairing pairing = new DocumentGraphTools.Pairing() {
            @Override
            public Optional<PublicId> document() {
                return Optional.ofNullable(paired);
            }

            @Override
            public void pair(PublicId anchor) {
                pairedAnchors.add(anchor);
            }
        };
        for (AnthropicTool tool : DocumentGraphTools.tools(() -> null, () -> view, pairing)) {
            tools.put(tool.name(), tool);
        }
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    private String run(String tool, Object... kv) {
        Map<String, Object> input = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            input.put((String) kv[i], kv[i + 1]);
        }
        return tools.get(tool).execute(input);
    }

    private PublicId document(String name) throws Exception {
        Path dir = repository(name);
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("paired.adoc")).anchor();
        paired = anchor;
        return anchor;
    }

    private static StoredDocument load(PublicId anchor) {
        return GitDocumentMaterializer.load(anchor, true).orElseThrow();
    }

    private static String text(PublicId anchor) {
        return String.join("\n", load(anchor).lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList());
    }

    /** A block's path, by a phrase one of its lines holds. */
    private static String pathOf(PublicId anchor, String phrase) {
        return new DocumentOutline(load(anchor)).nodes().stream()
                .filter(n -> n.lines().stream().anyMatch(l -> l.text().contains(phrase))).findFirst()
                .map(n -> "§" + n.path()).orElseThrow(() -> new AssertionError("no block holds " + phrase));
    }

    @Test
    void structuredBlocksGoInWithTheirStyleLinesAndFindingsComeBackInTheResult() throws Exception {
        PublicId anchor = document("blocks");
        DocumentPairing.pair("blocks", anchor);

        // a tree inserted after a structured open block, which an insertion treats as one block
        String tree = TREE.formatted("pdi-tree", "0.1", "0.1");
        String result = run("propose_block_insert", "after", pathOf(anchor, "clinical site inspection"), "kind", "listing", "text", tree);
        assertTrue(result.startsWith("Proposed after"), result);
        assertTrue(result.contains("as its first version"), "what it states is written: " + result);
        assertTrue(result.contains("structured blocks clean"), "no findings on a sound tree: " + result);
        assertTrue(text(anchor).contains("[decision-tree,id=pdi-tree]"), text(anchor));
        assertTrue(PrimitiveData.get().hasPublicId(DecisionTreeWrites.treeId(dev.ikm.tinkar.common.id.PublicIds.of(network.ike.komet.document.LocalConcepts.uuid("Treat the aneurysm now?")))), "the tree semantic exists");

        // a statement goes in as an open block, and its statement is written on the block
        String statement = "[anf,type=request,id=pdi-second]\n--\ntopic::   candidate[a second look] disp=PENDING\n--\n";
        result = run("propose_block_insert", "after", pathOf(anchor, "A paragraph to write after"), "kind", "open", "text", statement);
        assertTrue(result.contains("Added ANF statement"), result);
        long block = load(anchor).blocks().values().stream().filter(b -> b.parameters().contains("pdi-second")).findFirst().orElseThrow().nid();
        assertEquals(1, EntityService.get().semanticsForComponentOfPattern(block, IkePattern.ANF_STATEMENT_PATTERN.nid()).toList().size());

        // an open block edited to its lines still takes an insertion after it
        result = run("propose_block_edit", "block", pathOf(anchor, "a second look"), "text", statement.replace("a second look", "another look"));
        assertTrue(result.startsWith("Proposed for"), result);
        result = run("propose_block_insert", "after", pathOf(anchor, "another look"), "kind", "paragraph", "text", "A paragraph after the statement.");
        assertTrue(result.startsWith("Proposed after"), result);

        // a block with a problem lands, and the tool says what is wrong, in the correction turn's words
        result = run("propose_block_edit", "block", pathOf(anchor, "decision-tree"), "text", TREE.formatted("pdi-tree", "1.5", "1.5"));
        assertTrue(result.contains("Komet read the decision tree block in the document and found:"), result);
        assertTrue(result.contains("Correct them now with propose_block_edit"), result);
        assertFalse(result.contains("Restate your whole answer"), "the block is corrected in the document, not restated: " + result);

        // a block's attribute line is read from its lines, so an edit of it reads back at once
        String priced = TREE.formatted("pdi-tree", "0.1", "0.1").replace("[decision-tree,id=pdi-tree]", "[decision-tree,id=pdi-tree,health-price=50000]");
        assertTrue(run("propose_block_edit", "block", pathOf(anchor, "decision-tree"), "text", priced).startsWith("Proposed for"));
        StoredDocument edited = load(anchor);
        var pricedBlock = edited.blocks().values().stream().filter(b -> StructuredBlocks.source(edited, b).lines().stream()
                .anyMatch(l -> l.contains("pdi-treat"))).findFirst().orElseThrow();
        assertEquals("[decision-tree,id=pdi-tree,health-price=50000]", StructuredBlocks.source(edited, pricedBlock).parameters());

        // a stale path cannot put one block's text over a structured block of another style
        String stale = run("propose_block_edit", "block", pathOf(anchor, "another look"), "text", tree);
        assertTrue(stale.startsWith("Error") && stale.contains("read the document again"), stale);
        assertTrue(text(anchor).contains("another look"), text(anchor));
        DocumentEditor.cancel(anchor);
        DocumentPairing.pair("blocks", null);
    }

    @Test
    void aPairedDocumentsTwoAuthorsShareOnePendingEdit() throws Exception {
        PublicId anchor = document("shared");
        String paragraph = pathOf(anchor, "A paragraph to write after");
        long block = new DocumentOutline(load(anchor)).find(paragraph).orElseThrow().block().nid();
        DocumentEditor.editBlock(anchor, block, "The person's words.", Coordinates.Edit.Default());

        // unpaired, the assistant waits on the person's edit
        String refused = run("propose_block_edit", "block", paragraph, "text", "The assistant's words.");
        assertTrue(refused.startsWith("Not proposed"), refused);

        DocumentPairing.pair("shared", anchor);
        String closing = run("propose_block_edit", "block", pathOf(anchor, "A closing paragraph"), "text", "The assistant's closing.");
        assertTrue(closing.startsWith("Proposed for"), closing);
        String same = run("propose_block_edit", "block", pathOf(anchor, "The person's words"), "text", "The assistant's words.");
        assertTrue(same.startsWith("Proposed for"), same);
        String text = text(anchor);
        assertTrue(text.contains("The assistant's words.") && text.contains("The assistant's closing."), text);
        assertFalse(text.contains("The person's words."), "a second author rewrites a pending line in place: " + text);

        assertTrue(DocumentEditor.cancel(anchor), "one pending edit, settled together");
        assertTrue(text(anchor).contains("A paragraph to write after"), text(anchor));
        assertTrue(text(anchor).contains("A closing paragraph."), text(anchor));
        DocumentPairing.pair("shared", null);
    }

    @Test
    void aRequestSaysWhichBlocksChanged() throws Exception {
        PublicId anchor = document("changes");
        Map<Long, String> seen = DocumentChanges.snapshot(load(anchor));
        assertEquals(List.of(), DocumentChanges.since(seen, load(anchor)));
        String paragraph = pathOf(anchor, "A closing paragraph");
        long block = new DocumentOutline(load(anchor)).find(paragraph).orElseThrow().block().nid();
        DocumentEditor.editBlock(anchor, block, "A closing paragraph, reworded.", Coordinates.Edit.Default());
        List<String> lines = DocumentChanges.since(seen, load(anchor));
        assertEquals(List.of(paragraph + " changed since your last turn, not by you"), lines);
        DocumentEditor.cancel(anchor);
    }

    @Test
    void createDocumentMakesStoresAndPairsANewDocument() throws Exception {
        Path dir = repository("create");
        DocumentNarratives.workingTreeOpened(dir);
        paired = null;
        String result = run("create_document", "path", "decisions/site.adoc", "title", "Which site", "summary", "What this decides.");
        assertTrue(result.startsWith("Created decisions/site.adoc"), result);
        assertTrue(result.contains("nothing pushed"), result);
        assertEquals(1, pairedAnchors.size());
        assertTrue(text(pairedAnchors.getFirst()).contains("What this decides."), text(pairedAnchors.getFirst()));
        assertEquals("", git(dir, "status", "--porcelain").strip(), "the file is committed, alone");
        assertEquals("Add Which site", git(dir, "log", "-1", "--format=%s").strip());

        assertTrue(run("create_document", "path", "decisions/site.adoc", "title", "Again", "summary", "Again.").startsWith("Error"), "the file exists");
        assertTrue(run("create_document", "path", "../outside.adoc", "title", "Out", "summary", "Out.").startsWith("Error"), "outside the tree");
        assertFalse(Files.exists(dir.getParent().resolve("outside.adoc")));
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("paired.adoc"), DOCUMENT.formatted(name, name));
        git(dir, "add", "paired.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=2026-03-01T12:00:00", "-m", "Add the document");
        return dir;
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
    }
}
