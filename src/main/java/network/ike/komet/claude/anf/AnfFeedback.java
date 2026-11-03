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
package network.ike.komet.claude.anf;

import network.ike.komet.claude.anf.element.ElementText;
import network.ike.komet.claude.decision.DecisionTreeFeedback;
import network.ike.komet.claude.decision.DecisionTreeFeedback.Block;
import network.ike.komet.claude.decision.TreeStatements;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What Komet tells the assistant about the {@code anf} blocks of a reply, as it does for decision
 * trees and complex concepts: read store-free against the grammar of {@link AnfAdoc}, a field the
 * grammar does not know, a concept field whose value is not a concept, a measure field that does
 * not start with an interval, and a field of the other statement type. Grounding is the surface's:
 * whether an identifier is in the knowledge base is not checked here.
 */
public final class AnfFeedback {
    /** The first line of every feedback message; a transcript recognises the turn by it. */
    public static final String PREFIX = "Komet read the ANF statement";

    private static final Set<String> MEASURES = Set.of("statement_time", "result", "normal_range", "requested_result", "timing",
            "repetition_period_start", "repetition_period_duration", "repetition_event_separation",
            "repetition_event_duration", "repetition_event_frequency");
    private static final Set<String> CONCEPTS = Set.of("topic", "subject_of_information", "subject_of_record", "author",
            "disposition", "status", "health_risk", "body_site", "method", "laterality", "device", "approach", "specimen_type",
            "specimen_source_site", "participant", "priority", "location", "conditional_trigger", "purpose");
    private static final Set<String> PERFORMANCE_ONLY = Set.of("result", "health_risk", "body_site", "laterality", "approach",
            "normal_range");
    private static final Set<String> REQUEST_ONLY = Set.of("requested_result", "priority", "location", "conditional_trigger",
            "repetition_period_start", "repetition_period_duration", "repetition_event_separation",
            "repetition_event_duration", "repetition_event_frequency");
    private static final Pattern FIELD = Pattern.compile("^([a-z_]+)::\\s*(.*)$");
    private static final Pattern TOKEN = Pattern.compile("^k:[^\\[\\s]*\\[.*?]\\s*@id=\\S+.*$");
    private static final Pattern CANDIDATE = Pattern.compile("^candidate\\[.*?]\\s*disp=\\S+.*$");
    private static final Pattern CLARIFY = Pattern.compile("^clarify\\[.*?].*$");
    private static final Pattern INTERVAL = Pattern.compile("^[\\[(]\\s*\\S*?\\s*,\\s*\\S*?\\s*[\\])].*$");

    private AnfFeedback() {
    }

    /** The {@code anf} fences of a Markdown text. */
    public static List<Block> blocks(String markdown) {
        return DecisionTreeFeedback.blocks(markdown, TreeStatements::isAnfFence);
    }

    /** What is wrong with one block; empty for a block that reads clean. */
    public static List<String> problems(Block block) {
        List<String> problems = new ArrayList<>();
        TreeStatements.Kind kind = TreeStatements.kindOf(block.info());
        String[] lines = block.body().split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher m = FIELD.matcher(line);
            if (!m.matches()) {
                problems.add("line " + (i + 1) + " is not a field:: value line: " + line);
                continue;
            }
            String field = m.group(1).toLowerCase(Locale.ROOT);
            String value = m.group(2).strip();
            if (field.equals("text")) {
                continue;
            }
            if (MEASURES.contains(field)) {
                if (!INTERVAL.matcher(value).matches()) {
                    problems.add(field + ":: is a measure and starts with an interval, [lo,hi] then its unit as a k: token, not: " + value);
                }
            } else if (CONCEPTS.contains(field)) {
                String head = value.replaceAll("\\s+role=.*$", "");
                if (!TOKEN.matcher(head).matches() && !CANDIDATE.matcher(head).matches() && !CLARIFY.matcher(head).matches()) {
                    problems.add(field + ":: takes a concept, k:Key[label] @id=<identifier> or candidate[label] disp=PENDING, not: " + value);
                }
            } else {
                problems.add(field + ":: is not a field of an ANF statement");
                continue;
            }
            if (kind == TreeStatements.Kind.REQUEST && PERFORMANCE_ONLY.contains(field)) {
                problems.add(field + ":: belongs to a performance; a request states requested_result:: instead");
            }
            if (kind == TreeStatements.Kind.PERFORMANCE && REQUEST_ONLY.contains(field)) {
                problems.add(field + ":: belongs to a request, not a performance");
            }
        }
        return problems;
    }

    /** The message that sends a reply's anf findings back; empty when every block reads clean, or there is none. */
    public static Optional<String> message(String reply) {
        List<Block> blocks = blocks(reply);
        StringBuilder message = new StringBuilder();
        for (Block block : blocks) {
            List<String> found = problems(block);
            if (found.isEmpty()) {
                continue;
            }
            String name = ElementText.attributes(block.info()).getOrDefault("id", "unnamed");
            message.append("\nThe statement ").append(name).append(":\n");
            for (String problem : found) {
                message.append("- ").append(problem).append('\n');
            }
        }
        if (message.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(PREFIX + (blocks.size() == 1 ? "" : "s") + " in your reply and found:\n" + message
                + "\nRestate your whole answer with these statements corrected: change only what the findings name, keep"
                + " every identity and every other block as it is, and do not explain the correction. Leave out a field"
                + " you have no concept for rather than writing a word.");
    }

    /** Whether a transcript turn is Komet's feedback on ANF statements. */
    public static boolean isFeedback(String content) {
        return content != null && content.startsWith(PREFIX);
    }
}
