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
package network.ike.komet.claude.instructions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Instruction Editor's drafting seams: the bundled drafting persona must exist and teach
 * the closed category list verbatim (compiler-visible enum on one side, prompt text on the
 * other — this test is the tripwire between them), and fence-stripping must unwrap a reply
 * the model fenced despite the output contract without disturbing anything else.
 */
class InstructionEditorDraftingTest {

    @Test
    void bundledDraftingPersonaTeachesTheClosedCategoryList() {
        String persona = InstructionEditorCard.bundledResource("instruction-editor-prompt.md");
        assertFalse(persona.isBlank(), "the drafting persona ships with the plugin");
        for (InstructionCategory category : InstructionCategory.values()) {
            assertTrue(persona.contains(category.display()),
                    "the persona names every category verbatim: " + category.display());
        }
        assertTrue(persona.contains("k:"),
                "the persona teaches k: tokens as live references to preserve");
        assertTrue(persona.contains("propose_document"),
                "the persona routes revisions through the propose_document tool, "
                        + "keeping discussion in text");
    }

    @Test
    void fencedRepliesUnwrap() {
        String document = "---\nname: X\n---\nBody.";
        assertEquals(document, InstructionEditorCard.stripFence("```markdown\n" + document + "\n```"));
        assertEquals(document, InstructionEditorCard.stripFence("```\n" + document + "\n```"));
        assertEquals(document, InstructionEditorCard.stripFence(document),
                "an unfenced reply passes through untouched");
        assertEquals("", InstructionEditorCard.stripFence(null), "null is empty, never a throw");
    }

    @Test
    void innerFencesSurviveUnwrapping() {
        String document = "---\nname: X\n---\nUse:\n```\ncode\n```\nDone.";
        assertEquals(document, InstructionEditorCard.stripFence("```markdown\n" + document + "\n```"),
                "only the outermost fence is removed — inner code blocks stay intact");
    }
}
