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

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting a conversation with no knowledge base open deletes nothing: its file gone, its journal
 * would stay in the knowledge base with nothing to find it by, as it did when the Trash was emptied
 * after the store had stopped answering (IKE-Network/ike-issues#1313).
 */
class PluginTrashClosedTest {

    @Test
    void aConversationIsNotDeletedWhileItsJournalCannotBe(@TempDir Path folder) throws Exception {
        Assumptions.assumeFalse(PrimitiveData.running(), "another test in this JVM has a store open");
        Path file = Files.writeString(folder.resolve("conversation-1.json"), "{}");
        assertTrue(PluginTrash.deletePayload(file, PublicIds.newRandom()).orElse("").contains("not open"));
        assertTrue(Files.exists(file), "it stays in the Trash, to be deleted the next time");
        assertTrue(PluginTrash.deletePayload(file, null).isEmpty(), "an instruction set has no journal");
        assertTrue(Files.notExists(file));
    }
}
