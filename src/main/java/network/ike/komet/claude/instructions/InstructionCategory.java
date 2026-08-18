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

import java.util.Locale;

/**
 * The standard categories a titled instruction set is <em>intended</em> as — a closed list
 * where every entry names a real attachment role (KEC 2026-08-17, settled: no catch-all),
 * carried as a {@code category:} frontmatter key in the portable form. Category CLASSIFIES
 * (it drives which selectors surface a set); the attachment at a use site remains the
 * enforcing act, so the editor stays roleless — declaring an intent is not attaching a role.
 */
public enum InstructionCategory {

    /** Intended as a card's persona/instruction layer — surfaced by system-prompt selectors. */
    SYSTEM_PROMPT("System Prompt"),

    /** Intended as an includable skill — surfaced by skill-include selectors (ike-issues#1042). */
    SKILL("Skill"),

    /**
     * Intended as a card's tool contract — the fixed mechanics layer beneath the persona,
     * surfaced by tool-contract selectors. Improved and saved in our tooling, not elsewhere.
     */
    TOOL_CONTRACT("Tool Contract");

    private final String display;

    InstructionCategory(String display) {
        this.display = display;
    }

    /**
     * The display form, also the frontmatter value.
     *
     * @return the human-readable category name
     */
    public String display() {
        return display;
    }

    @Override
    public String toString() {
        return display;
    }

    /**
     * Parses a frontmatter value: the display form or the enum name, case-insensitive.
     * Anything else — absence, a foreign value, or the retired {@code General} — is
     * {@link #SKILL}: a SKILL.md-form document without a recognizable declared intent
     * <em>is</em> a skill, so foreign documents import cleanly.
     *
     * @param value the frontmatter {@code category:} value, or {@code null}
     * @return the category (never {@code null})
     */
    public static InstructionCategory parse(String value) {
        if (value == null || value.isBlank()) {
            return SKILL;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (InstructionCategory category : values()) {
            if (category.display.toLowerCase(Locale.ROOT).equals(normalized)
                    || category.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                            .equals(normalized.replace('_', ' '))) {
                return category;
            }
        }
        return SKILL;
    }
}
