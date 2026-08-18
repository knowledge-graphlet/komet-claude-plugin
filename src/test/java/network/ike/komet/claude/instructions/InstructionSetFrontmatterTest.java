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

import network.ike.komet.claude.instructions.InstructionSets.Frontmatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Store-free locks for the portable instruction-set form ({@code IKE-Network/ike-issues#1044}):
 * the {@code SKILL.md}-style frontmatter parses and composes losslessly — the payload file IS
 * the interchange format, so import and export are file copies.
 */
class InstructionSetFrontmatterTest {

    @Test
    void frontmatterRoundTrips() {
        String doc = InstructionSets.withFrontmatter("Terminology style",
                "House style for terminology answers", InstructionCategory.SYSTEM_PROMPT,
                "Always cite the store.\n\n## Rules\n- Be exact.");
        Frontmatter parsed = InstructionSets.parseFrontmatter(doc);
        assertEquals("Terminology style", parsed.name());
        assertEquals("House style for terminology answers", parsed.description());
        assertEquals(InstructionCategory.SYSTEM_PROMPT, parsed.category(),
                "the standard category rides the frontmatter");
        assertEquals("Always cite the store.\n\n## Rules\n- Be exact.", parsed.body());
    }

    @Test
    void quotedValuesAndPaddingTolerated() {
        Frontmatter parsed = InstructionSets.parseFrontmatter("""

                ---
                name: "Inclusivity review"
                description:  'Coverage near LoD'
                ---
                Body starts here.
                """);
        assertEquals("Inclusivity review", parsed.name());
        assertEquals("Coverage near LoD", parsed.description());
        assertEquals(InstructionCategory.SKILL, parsed.category(),
                "an absent category is SKILL — a SKILL.md-form document without a declared "
                        + "intent IS a skill, so foreign files import cleanly");
        assertEquals("Body starts here.\n", parsed.body());
    }

    @Test
    void categoryParsingIsTolerant() {
        assertEquals(InstructionCategory.SKILL, InstructionCategory.parse("skill"));
        assertEquals(InstructionCategory.SYSTEM_PROMPT, InstructionCategory.parse("System Prompt"));
        assertEquals(InstructionCategory.SYSTEM_PROMPT, InstructionCategory.parse("system_prompt"));
        assertEquals(InstructionCategory.TOOL_CONTRACT, InstructionCategory.parse("Tool Contract"));
        assertEquals(InstructionCategory.TOOL_CONTRACT, InstructionCategory.parse("tool_contract"));
        assertEquals(InstructionCategory.SKILL, InstructionCategory.parse("persona"),
                "an unknown value degrades to SKILL, never a throw");
        assertEquals(InstructionCategory.SKILL, InstructionCategory.parse("General"),
                "the retired General category migrates to SKILL on read");
        assertEquals(InstructionCategory.SKILL, InstructionCategory.parse(null));
    }

    @Test
    void aDocumentWithoutFrontmatterIsAllBody() {
        Frontmatter parsed = InstructionSets.parseFrontmatter("Just instructions.\nNo header.");
        assertNull(parsed.name());
        assertNull(parsed.description());
        assertEquals("Just instructions.\nNo header.", parsed.body());
        assertEquals("", InstructionSets.parseFrontmatter(null).body(), "null is empty, never a throw");
    }

    @Test
    void anUnterminatedFrontmatterConsumesToTheEndWithoutLoss() {
        Frontmatter parsed = InstructionSets.parseFrontmatter("---\nname: Dangling\nno closer");
        assertEquals("Dangling", parsed.name());
        assertEquals("", parsed.body(), "no body survives an unterminated header — nothing invented");
    }
}
