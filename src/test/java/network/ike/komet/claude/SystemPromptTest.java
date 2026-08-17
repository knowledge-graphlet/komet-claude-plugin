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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store-free locks for the two-layer system prompt ({@code IKE-Network/ike-issues#1039}): the
 * bundled resources carry their halves — the fixed tool contract with the grounding rule and the
 * rendering grammars, the editable instruction seed with the persona — and assembly puts the
 * editable layer first with the contract last (the recency position), never losing either.
 */
class SystemPromptTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = ClaudeCard.class.getResourceAsStream(name)) {
            assertNotNull(in, "bundled prompt resource " + name + " is present");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theToolContractCoreCarriesTheWiring() throws IOException {
        String core = resource("system-prompt-core.md");
        assertTrue(core.contains("Never state a code, identifier, name"),
                "the grounding rule lives in the fixed core");
        assertTrue(core.contains("koncept-tree"), "the tree grammar lives in the fixed core");
        assertTrue(core.contains("k:uuid=<UUID>[Name]"),
                "the Koncept Badge reference rules live in the fixed core");
        assertTrue(core.contains("Label\nidentifier columns")
                        || core.contains("Label identifier columns"),
                "the identifier-column labeling discipline rides the reference rules");
    }

    @Test
    void theInstructionSeedCarriesThePersonaNotTheWiring() throws IOException {
        String instructions = resource("system-prompt-instructions.md");
        assertTrue(instructions.contains("You are the Komet Assistant"),
                "the persona opener seeds the editable layer");
        assertTrue(!instructions.contains("koncept-tree"),
                "no rendering grammar in the editable layer — edits cannot sever it");
    }

    @Test
    void assemblyPutsInstructionsFirstAndTheContractLast() {
        assertEquals("persona\n\ncontract",
                ClaudeCard.assembleSystemPrompt("persona\n", "\ncontract"));
        assertEquals("contract", ClaudeCard.assembleSystemPrompt("  ", "contract"),
                "a blank instruction layer leaves the contract alone");
        assertEquals("persona", ClaudeCard.assembleSystemPrompt("persona", null),
                "a missing contract still yields the instructions");
        assertEquals("", ClaudeCard.assembleSystemPrompt(null, null));
    }
}
