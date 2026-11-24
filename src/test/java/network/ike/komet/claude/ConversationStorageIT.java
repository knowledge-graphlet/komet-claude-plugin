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
package network.ike.komet.claude;

import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.komet.kview.storage.ExportToFile;
import dev.ikm.komet.kview.storage.ImportFromFile;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.entity.boundary.CommitBoundary;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.scope.DataInventory;
import dev.ikm.tinkar.entity.scope.ScopeOperations;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.json.Json;
import network.ike.komet.document.DocumentScopes;
import network.ike.komet.document.VertexMaterializer;
import network.ike.komet.document.surface.JournalStore;
import org.eclipse.collections.api.factory.primitive.LongSets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conversation exported to a file and imported (IKE-Network/ike-issues#1313; step 2 of
 * {@code plan-2026-11-22-remove-export-import-data}), by the two Komet actions, on the memory-only
 * 64-bit store with the starter set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationStorageIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    @TempDir
    Path folder;

    private JournalStore store;

    @BeforeAll
    void setupDatabase() {
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "vertex");
        System.setProperty(CommitBoundary.MODE_PROPERTY, "fail");
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        store = new JournalStore(Calculators.View::Default);
    }

    @AfterAll
    void teardownDatabase() {
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        System.clearProperty(CommitBoundary.MODE_PROPERTY);
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    @Test
    void aConversationGoesToAFileAndComesBackWhole() {
        PublicId anchor = store.appendExchange(null, "What is a finding?", "What is a finding?", "A clinical **observation**.\n\n- one\n- two");
        store.appendExchange(anchor, "What is a finding?", "And a disorder?", "A kind of finding.");
        List<JournalStore.TurnRecord> said = store.load(anchor).orElseThrow();
        Path file = folder.resolve("finding.komet-export.zip");

        KometActionResult exported = export(anchor, file);
        assertTrue(exported.completed(), exported.message());
        ScopeOperations.Manifest manifest = ScopeOperations.manifest(file.toFile());
        assertEquals(DataKind.LOCAL, manifest.kind());
        assertEquals(List.of(IkeConcept.CONVERSATION_JOURNAL_MODULE.publicId()), manifest.modules());
        assertEquals(said, store.load(anchor).orElseThrow(), "nothing in the knowledge base changed");

        KometActionResult here = importFrom(file, false);
        assertEquals(KometConcept.ACTION_REFUSED, here.outcome());
        assertEquals("Not imported. This knowledge base has it already.", here.message());

        DocumentScopes.delete(anchor);
        assertTrue(store.load(anchor).isEmpty());
        KometActionResult imported = importFrom(file, false);
        assertTrue(imported.completed(), imported.message());
        assertEquals(said, store.load(anchor).orElseThrow(), "every turn, who said it and when");

        // It can go on where it was brought.
        store.appendExchange(anchor, "What is a finding?", "And a procedure?", "Something done.");
        assertEquals(6, store.load(anchor).orElseThrow().size());
        KometActionResult replaced = importFrom(file, true);
        assertTrue(replaced.completed(), replaced.message());
        assertTrue(replaced.message().startsWith("Replaced "), replaced.message());
        assertEquals(said, store.load(anchor).orElseThrow(), "as it was when it was exported");
    }

    @Test
    void whatIsNotExportedSaysWhy() {
        KometActionResult knowledge = new ExportToFile().perform(ExportToFile.input(IkeConcept.KOMET_ASSISTANT.publicId(),
                folder.resolve("k.zip")), Calculators.View.Default(), Coordinates.Edit.Default());
        assertEquals(KometConcept.ACTION_REFUSED, knowledge.outcome());
        assertTrue(knowledge.message().startsWith("It is knowledge."), knowledge.message());
        assertFalse(Files.exists(folder.resolve("k.zip")));
        KometActionResult absent = new ExportToFile().perform(ExportToFile.input(dev.ikm.tinkar.common.id.PublicIds.newRandom(),
                folder.resolve("a.zip")), Calculators.View.Default(), Coordinates.Edit.Default());
        assertEquals("It is not in the knowledge base.", absent.message());
        KometActionResult plain = importFrom(PB_STARTER_DATA.toPath(), false);
        assertEquals(KometConcept.ACTION_REFUSED, plain.outcome());
        assertTrue(plain.message().endsWith("is not a file that Export to File wrote."), plain.message());
    }

    /** The conversation's own file when the export had it, and one made from the journal when not. */
    @Test
    void anImportedConversationIsItsOwnFileOrMadeFromItsJournal() {
        PublicId anchor = store.appendExchange(null, "Made from its journal", "Hello?", "Hello.");
        ConversationStorage.Waiting made = ConversationStorage.conversation(anchor, "Made from its journal", null);
        assertEquals("Made from its journal", made.title());
        ClaudeCard.ConversationDto dto = Json.parse(made.json(), ClaudeCard.ConversationDto.class);
        assertEquals(made.id(), dto.id());
        assertEquals(anchor.asUuidArray()[0].toString(), dto.journalAnchor());
        assertEquals(List.of("user", "assistant"), dto.turns().stream().map(turn -> turn.get("role")).toList());
        assertEquals(List.of("Hello?", "Hello."), dto.turns().stream().map(turn -> turn.get("content")).toList());

        String id = UUID.randomUUID().toString();
        byte[] own = Json.stringify(Map.of("id", id, "name", "Renamed", "turns", List.of(), "journalAnchor",
                anchor.asUuidArray()[0].toString(), "titles", Map.of("0", "A greeting"), "times", List.of("2026-11-23T19:41:07.125Z", ""))).getBytes(StandardCharsets.UTF_8);
        ConversationStorage.Waiting kept = ConversationStorage.conversation(anchor, "Made from its journal", own);
        assertEquals(id, kept.id());
        assertEquals("A greeting", kept.title(), "as a card's list shows it");
        assertEquals("A greeting", Json.parse(kept.json(), ClaudeCard.ConversationDto.class).titles().get("0"));
        assertEquals(List.of("2026-11-23T19:41:07.125Z", ""), Json.parse(kept.json(), ClaudeCard.ConversationDto.class).times(),
                "when each turn was said");

        byte[] another = Json.stringify(Map.of("id", id, "name", "Another", "turns", List.of(), "journalAnchor",
                UUID.randomUUID().toString())).getBytes(StandardCharsets.UTF_8);
        assertNotEquals(id, ConversationStorage.conversation(anchor, "n", another).id(), "a file of another journal is not used");
        assertNotEquals(id, ConversationStorage.conversation(anchor, "n", "not json".getBytes(StandardCharsets.UTF_8)).id());
    }

    /** A journal no list shows is one whole group. */
    @Test
    void aJournalIsOneGroupOfTheInventory() {
        PublicId anchor = store.appendExchange(null, "Not shown", "Are you shown?", "Not anywhere.");
        long nid = PrimitiveData.nid(anchor);
        assertEquals(1, DataInventory.unlisted(LongSets.immutable.empty()).stream().filter(scope -> scope.contains(nid)).count());
        assertTrue(JournalStore.isJournal(anchor));
        assertFalse(JournalStore.isJournal(IkeConcept.KOMET_ASSISTANT.publicId()));
        assertEquals(0, DataInventory.unlisted(DocumentScopes.of(anchor).members()).stream().filter(scope -> scope.contains(nid)).count());
    }

    private static KometActionResult export(PublicId anchor, Path file) {
        return new ExportToFile().perform(ExportToFile.input(anchor, file), Calculators.View.Default(), Coordinates.Edit.Default());
    }

    private static KometActionResult importFrom(Path file, boolean replace) {
        return new ImportFromFile().perform(ImportFromFile.input(file, replace), Calculators.View.Default(), Coordinates.Edit.Default());
    }
}
