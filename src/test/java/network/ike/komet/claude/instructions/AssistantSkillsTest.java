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

import network.ike.komet.claude.instructions.AssistantSkills.Skill;
import network.ike.komet.claude.instructions.InstructionSets.Frontmatter;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store-free locks for the assistant's bundled skills: each file the plugin names is in the jar,
 * parses as a skill with a name and a description, and loads by name through {@code load_skill}.
 */
class AssistantSkillsTest {

    @Test
    void everyBundledFileIsASkill() {
        Set<String> names = new HashSet<>();
        for (String file : AssistantSkills.BUNDLED) {
            String text = AssistantSkills.bundledText(file);
            assertFalse(text.isBlank(), file + " is in the jar");
            Frontmatter parsed = InstructionSets.parseFrontmatter(text);
            assertEquals(InstructionCategory.SKILL, parsed.category(), file + " declares itself a skill");
            assertFalse(parsed.name().isBlank(), file + " has a name");
            assertTrue(parsed.description().startsWith("Use when"),
                    file + " says when to use it, whole: " + parsed.description());
            assertFalse(parsed.body().isBlank(), file + " has instructions");
            assertTrue(names.add(parsed.name()), "skill names are unique: " + parsed.name());
        }
        assertEquals(AssistantSkills.BUNDLED.size(), AssistantSkills.bundled().size());
    }

    @Test
    void promptSectionListsEverySkill() {
        List<Skill> skills = AssistantSkills.bundled();
        String section = AssistantSkills.promptSection(skills);
        assertTrue(section.contains("load_skill"));
        for (Skill skill : skills) {
            assertTrue(section.contains("**" + skill.name() + "**: " + skill.description()), skill.name());
        }
        assertEquals("", AssistantSkills.promptSection(List.of()), "no skills, no section");
    }

    @Test
    void loadSkillFindsByNameOrPart() {
        List<Skill> skills = AssistantSkills.bundled();
        var tool = AssistantSkills.loadSkillTool(() -> skills);
        Skill review = skills.stream().filter(s -> s.name().equals("Definition review")).findFirst().orElseThrow();

        String exact = tool.execute(Map.of("name", "definition REVIEW"));
        assertTrue(exact.startsWith("# Definition review\n\n"));
        assertTrue(exact.endsWith(review.body().strip()));

        assertTrue(tool.execute(Map.of("name", "ANF")).startsWith("# ANF lift\n\n"), "a part of a name finds it");

        String unknown = tool.execute(Map.of("name", "astrology"));
        assertTrue(unknown.startsWith("No skill is named \"astrology\""));
        assertTrue(unknown.contains("Concept authoring"), "an unknown name lists the skills");
    }
}
