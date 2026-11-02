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
package network.ike.komet.claude.complexconcept;

import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.decision.DecisionTreeFeedback;
import network.ike.komet.claude.decision.DecisionTreeFeedback.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What Komet tells the assistant about the {@code complex-concept} blocks in a reply, so the
 * assistant can correct its own block, as {@link DecisionTreeFeedback} does for decision trees:
 * the reader's findings, a focus that names the concept itself among them, and the Koncept
 * Badge form written where the block's token form belongs. What a specification still lacks is
 * not sent back: the card lists it, and the person may want it left open.
 *
 * <p>Pure: it reads by whatever {@link Identities} the caller gives, so it is testable store-free
 * and runs off the application thread. The note that lists the block's local symbols informs
 * rather than faults and is not sent.
 */
public final class ComplexConceptFeedback {
    /** The first line of every feedback message; a transcript recognises the turn by it. */
    public static final String PREFIX = "Komet read the complex concept";

    private ComplexConceptFeedback() {
    }

    /** The fenced blocks of a Markdown text whose info string names the complex-concept block. */
    public static List<Block> blocks(String markdown) {
        return DecisionTreeFeedback.blocks(markdown, ComplexConceptText::isTagged);
    }

    /** What is wrong with one block: the reader's findings, less the local-symbols note; empty for a block that reads clean. */
    public static List<String> problems(Block block, Identities ids) {
        List<String> problems = new ArrayList<>();
        ComplexConceptText.Parsed parsed = ComplexConceptText.parse(block.body(), ids);
        for (String finding : parsed.findings()) {
            if (!finding.contains("local to the block")) {
                problems.add(finding);
            }
        }
        return problems;
    }

    /**
     * The message that sends a reply's findings back to the assistant, when any of its
     * complex-concept blocks has them; empty when every block reads clean, or the reply has none.
     */
    public static Optional<String> message(String reply, Identities ids) {
        List<Block> blocks = blocks(reply);
        List<List<String>> problems = new ArrayList<>();
        boolean any = false;
        for (Block block : blocks) {
            List<String> found = problems(block, ids);
            problems.add(found);
            any |= !found.isEmpty();
        }
        if (!any) {
            return Optional.empty();
        }
        StringBuilder message = new StringBuilder(PREFIX);
        message.append(blocks.size() == 1 ? " block" : " blocks").append(" in your reply and found:\n");
        for (int i = 0; i < blocks.size(); i++) {
            if (problems.get(i).isEmpty()) {
                continue;
            }
            if (blocks.size() > 1) {
                message.append("\nBlock ").append(i + 1).append(":\n");
            }
            for (String problem : problems.get(i)) {
                message.append("- ").append(problem).append('\n');
            }
        }
        message.append("\nRestate your whole answer with the block corrected: the complete block, then your sentences. "
                + "Change only what these findings name; keep every identity and every value the person gave, and "
                + "the block's structure otherwise. Do not explain the correction. Load the Complex concept skill "
                + "if you need the grammar.");
        return Optional.of(message.toString());
    }

    /** Whether a transcript turn is Komet's feedback on a complex concept rather than a person's words. */
    public static boolean isFeedback(String content) {
        return content != null && content.startsWith(PREFIX);
    }
}
