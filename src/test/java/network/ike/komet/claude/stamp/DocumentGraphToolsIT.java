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
package network.ike.komet.claude.stamp;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anthropic.AnthropicTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assistant's document-graph tools against a store: dev-working-set-lease is materialized
 * from a git repository, then read with no card and no working tree, searched, read block by
 * block, and edited by proposal — the edit lands as a pending version under the Komet
 * assistant's author, which the card shows; another author's pending edit refuses the proposal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentGraphToolsIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final Path LEASE = Path.of("src/test/resources/fidelity/corpus/dev-working-set-lease.adoc");

    @TempDir
    static Path root;

    private PublicId anchor;
    private String name;
    private Map<String, AnthropicTool> tools;

    @BeforeAll
    void setup() throws Exception {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
        Path dir = Files.createDirectories(root.resolve("lab"));
        git(dir, "init", "-q", "-b", "main");
        Path file = dir.resolve("topics/dev/working-set-lease.adoc");
        Files.createDirectories(file.getParent());
        Files.copy(LEASE, file);
        git(dir, "add", ".");
        git(dir, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-10-04T00:00:00Z", "-m", "Add the working-set lease topic");
        GitDocumentMaterializer.Result result = GitDocumentMaterializer.materialize(dir, file);
        anchor = result.anchor();
        name = result.name();
        tools = new java.util.LinkedHashMap<>();
        for (AnthropicTool tool : DocumentGraphTools.tools(() -> null, () -> null)) {
            tools.put(tool.name(), tool);
        }
        for (AnthropicTool tool : StampTools.tools(() -> null)) {
            tools.put(tool.name(), tool);
        }
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    private String call(String tool, Map<String, Object> input) {
        return tools.get(tool).execute(input);
    }

    @Test
    void theDocumentIsReadFromTheStoreWithNoWorkingTree() {
        String outline = call("read_document", Map.of("document", name, "outline_only", true));

        assertTrue(outline.startsWith("Document " + name), outline);
        assertTrue(outline.contains("Commit: "), outline);
        assertTrue(outline.contains("§2 SECTION level 1 \"The missing invariant\""), outline);
        assertTrue(outline.contains("editable"), outline);

        String page = call("read_document", Map.of("document", name, "max_blocks", 5));
        assertTrue(page.contains("alice"), "lines carry their introducing author: " + page);
        assertTrue(page.contains("call read_document with offset=5"), page);
        String next = call("read_document", Map.of("document", name, "offset", 5, "max_blocks", 5));
        assertTrue(next.contains("Blocks 5-9 of"), next);

        String unknown = call("read_document", Map.of("document", "no-such-document"));
        assertTrue(unknown.startsWith("Error: the store holds no document named 'no-such-document'"), unknown);
        assertTrue(unknown.contains(name), "the refusal lists the documents the store holds: " + unknown);
    }

    @Test
    void searchFindsTheBlockAndReadBlockGivesItsProvenance() {
        String found = call("find_in_document", Map.of("document", name, "query", "local-origin model"));
        assertTrue(found.contains("match(es)"), found);
        String path = firstPath(found);

        String block = call("read_block", Map.of("document", name, "block", path));
        assertTrue(block.contains("PARAGRAPH"), block);
        assertTrue(block.contains("The sibling local-origin model"), block);
        assertTrue(block.contains("alice"), block);
        assertTrue(block.contains("reference"), "the paragraph's cross-reference is listed: " + block);

        String byLine = call("read_block", Map.of("document", name, "block", lineOf(block)));
        assertEquals(block, byLine, "a line names the block holding it");
    }

    @Test
    void aProposedEditLandsPendingUnderTheAssistantAndAnotherAuthorsEditRefusesIt() {
        String path = firstPath(call("find_in_document", Map.of("document", name, "query", "local-origin model")));
        GitDocumentMaterializer.StoredDocument before = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        DocumentOutline.Node node = new DocumentOutline(before).find(path).orElseThrow();
        List<String> lines = new ArrayList<>(node.lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList());
        lines.set(0, lines.getFirst().replace("The sibling local-origin model", "The sibling's local-origin model"));
        String text = String.join("\n", lines);

        String proposed = call("propose_block_edit", Map.of("document", name, "block", path, "text", text));

        assertTrue(proposed.startsWith("Proposed for §" + path), proposed);
        assertTrue(proposed.contains("Nothing is in git until the person commits it"), proposed);
        assertEquals(IkeConcept.KOMET_ASSISTANT.nid(), DocumentEditor.pendingAuthor(anchor).orElseThrow(), "the edit is the assistant's");
        String after = call("read_block", Map.of("document", name, "block", path));
        assertTrue(after.contains("The sibling's local-origin model"), after);
        assertTrue(after.contains("uncommitted, ") && after.contains("✎"), "the edited line reads as uncommitted: " + after);
        String header = call("read_document", Map.of("document", name, "outline_only", true, "max_blocks", 1));
        assertTrue(header.contains("Pending edit (uncommitted"), header);

        String again = call("propose_block_edit", Map.of("document", name, "block", path, "text", text.replace("sibling's", "siblings'")));
        assertTrue(again.startsWith("Proposed"), "the assistant revises its own pending edit: " + again);

        DocumentEditor.cancel(anchor);
        DocumentEditor.editBlock(anchor, node.block().nid(), String.join("\n", node.lines().stream()
                .map(GitDocumentMaterializer.StoredLine::text).toList()).replace("began by", "started by") + "\n", Coordinates.Edit.Default());
        String refused = call("propose_block_edit", Map.of("document", name, "block", path, "text", text));
        assertTrue(refused.startsWith("Not proposed: an edit of " + name + " by "), refused);
        assertTrue(refused.contains("Commit…") && refused.contains("Discard edit"), refused);
        DocumentEditor.cancel(anchor);
    }

    @Test
    void theProvenanceToolsSayWhatIsMissingWithoutACard() {
        String lines = call("stamp_lines", Map.of());
        assertTrue(lines.contains("no Stamped Document card shows a document"), lines);
        assertTrue(lines.contains("read_document"), lines);
        assertTrue(!lines.contains("HEAD") && !lines.contains("commit the file"), "never 'not in HEAD' or 'commit the file first': " + lines);
        String annotation = call("propose_annotation", Map.of("kind", "affirm", "ranges", List.of(Map.of("from", 1, "to", 2))));
        assertTrue(annotation.contains("no Stamped Document card shows a document"), annotation);
    }

    private static String firstPath(String found) {
        Matcher m = Pattern.compile("§([0-9.]+)").matcher(found);
        assertTrue(m.find(), found);
        return m.group(1);
    }

    private static String lineOf(String block) {
        Matcher m = Pattern.compile("\\bL([0-9]+)\\b").matcher(block);
        assertTrue(m.find(), block);
        return "L" + m.group(1);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
