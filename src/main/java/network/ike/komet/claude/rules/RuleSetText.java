/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.rules;

import dev.ikm.komet.framework.rulebase.ListedRuleSet;
import dev.ikm.komet.framework.rulebase.RuleFinding;
import dev.ikm.komet.framework.rulebase.RuleSource;

import java.util.List;

/**
 * The rule sets Komet lists, as Markdown: the text the Rules card shows and the text the
 * assistant's rule tools answer, so a person and the assistant read the same words.
 */
public final class RuleSetText {

    private RuleSetText() {
    }

    /**
     * Says where a rule set came from.
     *
     * @param ruleSet the rule set
     * @return "Komet", "Plugin" or "Written in Komet"
     */
    public static String origin(ListedRuleSet ruleSet) {
        return switch (ruleSet.origin()) {
            case KOMET -> "Komet";
            case PLUGIN -> "Plugin";
            case SOURCE -> "Written in Komet";
        };
    }

    /**
     * Says whether a rule set is in use, and if it is not, why.
     *
     * @param ruleSet the rule set
     * @return one short sentence
     */
    public static String state(ListedRuleSet ruleSet) {
        if (ruleSet.inUse()) {
            return "In use";
        }
        if (ruleSet.accepted()) {
            return "Not in use: it has findings";
        }
        return ruleSet.findings().isEmpty()
                ? "Proposed: not in use until a person accepts it"
                : "Proposed: it has findings, so it cannot be accepted as it is";
    }

    /**
     * Lists rule sets, each with where it came from, its state, its rules, and the identity of a
     * rule source.
     *
     * @param ruleSets the rule sets to list
     * @return the list as Markdown
     */
    public static String list(List<ListedRuleSet> ruleSets) {
        if (ruleSets.isEmpty()) {
            return "No rule sets.";
        }
        StringBuilder text = new StringBuilder();
        for (ListedRuleSet ruleSet : ruleSets) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append("**").append(ruleSet.name()).append("** · ").append(origin(ruleSet)).append(" · ")
                    .append(state(ruleSet));
            ruleSet.source().map(RuleSource::id).ifPresent(id -> text.append("\nid: ").append(id));
            if (ruleSet.ruleNames().notEmpty()) {
                text.append("\nRules: ").append(ruleSet.ruleNames().makeString("; "));
            }
            appendFindings(text, ruleSet, "\n");
        }
        return text.toString();
    }

    /**
     * Describes one rule set in full: its state, its rules, its findings and its Java source.
     *
     * @param ruleSet the rule set
     * @return the description as Markdown
     */
    public static String detail(ListedRuleSet ruleSet) {
        StringBuilder text = new StringBuilder();
        text.append("# ").append(ruleSet.name()).append("\n\n");
        text.append(origin(ruleSet)).append(" · ").append(state(ruleSet));
        ruleSet.source().map(RuleSource::id).ifPresent(id -> text.append("\n\nid: ").append(id));
        if (ruleSet.ruleNames().notEmpty()) {
            text.append("\n\nRules: ").append(ruleSet.ruleNames().makeString("; "));
        }
        if (ruleSet.findings().notEmpty()) {
            text.append("\n\n## Findings\n");
            appendFindings(text, ruleSet, "\n");
        }
        if (ruleSet.source().isPresent()) {
            String java = ruleSet.source().get().java();
            String fence = java.contains("```") ? "````" : "```";
            text.append("\n\n## Source\n\n").append(fence).append("java\n").append(java.stripTrailing())
                    .append('\n').append(fence);
        } else {
            text.append("\n\nThis rule set is compiled into ")
                    .append(ruleSet.origin() == ListedRuleSet.Origin.KOMET ? "Komet" : "a plugin")
                    .append(", so it has no source to show here.");
        }
        return text.toString();
    }

    /**
     * Says one finding, with its line when it has one.
     *
     * @param finding the finding
     * @return "Line 12: …", or the message alone
     */
    public static String finding(RuleFinding finding) {
        return finding.line() > 0 ? "Line " + finding.line() + ": " + finding.message() : finding.message();
    }

    private static void appendFindings(StringBuilder text, ListedRuleSet ruleSet, String before) {
        for (RuleFinding found : ruleSet.findings()) {
            // A compiler message runs over several lines; the lines after the first stay under its bullet.
            text.append(before).append("- ").append(finding(found).replace("\n", "\n  "));
        }
    }
}
