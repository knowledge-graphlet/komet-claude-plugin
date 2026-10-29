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

import network.ike.komet.claude.anthropic.AnthropicTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Komet Assistant's skills (KEC 2026-10-23), after the Agent Skills convention: each skill
 * is an instruction set of category Skill, whose name and description the assistant always
 * sees, and whose body it loads with {@link #loadSkillTool} only when a request matches. The
 * plugin bundles its own skills, available to every assistant card; Skill sets a person writes
 * in an Instruction Editor card join them.
 */
public final class AssistantSkills {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantSkills.class);

    /** The bundled skills' files, in the order the assistant lists them. */
    static final List<String> BUNDLED = List.of(
            "concept-authoring.md",
            "concept-editing.md",
            "pattern-authoring.md",
            "definition-review.md",
            "document-review.md",
            "provenance-answers.md",
            "anf-lift.md",
            "decision-tree.md",
            "terminology-answer-style.md");

    private static final String RESOURCE_ROOT = "/network/ike/komet/claude/skills/";

    /** The prefix of a bundled skill's key, which a card's exclusions name. */
    public static final String BUNDLED_KEY_PREFIX = "bundled:";

    /**
     * One skill the assistant can load.
     *
     * @param key         its identity, for a card's exclusions: {@code bundled:<file>} or a set's id
     * @param name        its name, which {@code load_skill} takes
     * @param description when to use it
     * @param body        its instructions
     * @param source      where it comes from, in words ("Bundled", or an Instruction Editor's label)
     */
    public record Skill(String key, String name, String description, String body, String source) {
    }

    private AssistantSkills() {
    }

    /** @return the skills the plugin bundles */
    public static List<Skill> bundled() {
        List<Skill> skills = new ArrayList<>();
        for (String file : BUNDLED) {
            String text = bundledText(file);
            if (text.isBlank()) {
                continue;
            }
            InstructionSets.Frontmatter parsed = InstructionSets.parseFrontmatter(text);
            skills.add(new Skill(BUNDLED_KEY_PREFIX + file, parsed.name(), parsed.description(), parsed.body(), "Bundled"));
        }
        return List.copyOf(skills);
    }

    /**
     * A bundled skill's file, frontmatter and body.
     *
     * @param file the file's name
     * @return its text, or empty when the plugin does not hold it
     */
    public static String bundledText(String file) {
        try (InputStream in = AssistantSkills.class.getResourceAsStream(RESOURCE_ROOT + file)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Could not read the bundled skill {}", file, e);
            return "";
        }
    }

    /**
     * The system prompt's skills section: what each skill is for, and how to load one.
     *
     * @param skills the skills a card offers
     * @return the section, or empty when there are none
     */
    public static String promptSection(List<Skill> skills) {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder section = new StringBuilder("## Skills\n\n")
                .append("These skills hold the instructions for particular kinds of request. When a request matches ")
                .append("a skill's description, call `load_skill` with the skill's name before you answer, and follow ")
                .append("what it returns. Load a skill only when it applies; load several when several do.\n\n");
        for (Skill skill : skills) {
            section.append("- **").append(skill.name()).append("**: ").append(skill.description()).append('\n');
        }
        return section.toString();
    }

    /**
     * The tool that loads a skill's instructions by name.
     *
     * @param skills the skills the card offers at the moment of the call
     * @return the tool
     */
    public static AnthropicTool loadSkillTool(Supplier<List<Skill>> skills) {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "load_skill";
            }

            @Override
            public String description() {
                return "Load a skill's instructions by its name, as the system prompt's Skills section lists it. "
                        + "Call it when a request matches a skill's description, before answering, and follow the "
                        + "instructions it returns.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                Map<String, Object> name = new LinkedHashMap<>();
                name.put("type", "string");
                name.put("description", "The skill's name, exactly as the Skills section lists it");
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", Map.of("name", name));
                schema.put("required", List.of("name"));
                return schema;
            }

            @Override
            public String execute(Map<String, Object> input) {
                String wanted = String.valueOf(input.getOrDefault("name", "")).strip();
                List<Skill> offered = skills.get();
                for (Skill skill : offered) {
                    if (skill.name().equalsIgnoreCase(wanted)) {
                        return "# " + skill.name() + "\n\n" + skill.body().strip();
                    }
                }
                String lower = wanted.toLowerCase(Locale.ROOT);
                for (Skill skill : offered) {
                    if (!lower.isEmpty() && skill.name().toLowerCase(Locale.ROOT).contains(lower)) {
                        return "# " + skill.name() + "\n\n" + skill.body().strip();
                    }
                }
                return "No skill is named \"" + wanted + "\". The skills are: "
                        + String.join(", ", offered.stream().map(Skill::name).toList()) + ".";
            }
        };
    }
}
