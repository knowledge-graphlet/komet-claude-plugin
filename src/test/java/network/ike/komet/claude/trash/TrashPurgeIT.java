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
package network.ike.komet.claude.trash;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.boundary.CommitBoundary;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import network.ike.komet.claude.doc.JournalStore;
import network.ike.komet.claude.stamp.VertexMaterializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting a trashed conversation for good, on the memory-only 64-bit store with the starter
 * set: its journal, instance data in its own pattern, leaves the knowledge base whole, and its
 * file leaves the disk (KEC 2026-10-23).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrashPurgeIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private JournalStore store;

    @BeforeAll
    void setupDatabase() {
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "vertex");
        System.setProperty(CommitBoundary.MODE_PROPERTY, "fail");
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        ViewCalculator view = Calculators.View.Default();
        store = new JournalStore(() -> view);
    }

    @AfterAll
    void teardownDatabase() {
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        System.clearProperty(CommitBoundary.MODE_PROPERTY);
        dev.ikm.tinkar.entity.boundary.ModuleKinds.clear();
        dev.ikm.tinkar.entity.boundary.EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    @Test
    void deletingAConversationDeletesItsJournalAndItsFile(@TempDir Path folder) throws Exception {
        PublicId anchor = store.appendExchange(null, "Conversation for the Trash", "What is a finding?", "A clinical observation.");
        store.appendExchange(anchor, "Conversation for the Trash", "And a disorder?", "A kind of finding.");
        long rootNid = EntityService.get().nidForPublicId(anchor);
        long patternNid = EntityHandle.get(rootNid).expectSemantic().patternNid();
        int[] vertices = {0};
        EntityService.get().forEachSemanticOfPattern(patternNid, s -> vertices[0]++);
        assertTrue(vertices[0] > 4, "the journal holds its root and its turns");
        Path file = Files.writeString(folder.resolve("conversation-1.json"), "{}");

        Optional<String> problem = PluginTrash.deletePayload(file, anchor);

        assertEquals(Optional.empty(), problem);
        assertFalse(Files.exists(file), "the conversation's file is gone");
        int[] left = {0};
        EntityService.get().forEachSemanticOfPattern(patternNid, s -> left[0]++);
        assertEquals(0, left[0], "no vertex of the journal is left");
        assertTrue(EntityHandle.get(rootNid).entity().filter(e -> !e.canceled()).isEmpty(), "the root is gone");
    }

    @Test
    void anotherJournalIsUntouched(@TempDir Path folder) {
        PublicId kept = store.appendExchange(null, "Conversation kept", "Keep me?", "Kept.");
        PublicId deleted = store.appendExchange(null, "Conversation deleted", "Delete me?", "Deleted.");

        assertEquals(Optional.empty(), PluginTrash.deletePayload(folder.resolve("absent.json"), deleted));

        assertEquals(java.util.List.of("Keep me?", "Kept."),
                store.load(kept).orElseThrow().stream().map(JournalStore.TurnRecord::markdown).toList());
        SemanticEntity<?> root = EntityHandle.get(EntityService.get().nidForPublicId(kept)).expectSemantic();
        assertFalse(root.versions().isEmpty());
    }

    @Test
    void aJournalTheStoreNeverHeldIsNothingToDelete(@TempDir Path folder) {
        PublicId stranger = PublicIds.of(UUID.nameUUIDFromBytes("never written".getBytes()));
        assertEquals(Optional.empty(), PluginTrash.deletePayload(null, stranger));
    }
}
