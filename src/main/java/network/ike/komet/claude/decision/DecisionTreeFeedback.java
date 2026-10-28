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
package network.ike.komet.claude.decision;

import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionElement.Chance;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.Decision;
import network.ike.komet.claude.decision.DecisionElement.Event;
import network.ike.komet.claude.decision.DecisionElement.HealthCost;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Outcome;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import network.ike.komet.claude.decision.DecisionElement.Probability;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * What Komet tells the assistant about the {@code decision-tree} blocks in a reply, so the
 * assistant can correct its own block: the findings the reader, the profile check and the
 * rollback raise, worded as the reader shows them beneath the drawn tree. The loop is a
 * compiler's: a block that reads clean and rolls back at every node needs no turn; one that
 * does not comes back to the assistant as a message, and its next reply replaces the draft.
 *
 * <p>Pure: it reads by whatever {@link Identities} the caller gives, so it is testable store-free
 * and runs off the application thread. A finding that only informs, the list of symbols local to
 * the block, is not sent back; it names nothing to correct.
 */
public final class DecisionTreeFeedback {

    /** The first line of every feedback message; a transcript recognises the turn by it. */
    public static final String PREFIX = "Komet read the decision tree";

    /** How many correcting turns a reply may take before its block is left as it stands. */
    public static final int MAX_ROUNDS = 2;

    private DecisionTreeFeedback() {
    }

    /** One fenced block as a reply carried it: the fence's info string and the body between the fences. */
    public record Block(String info, String body) {
    }

    /**
     * The fenced blocks of a Markdown text whose info string names the decision-tree profile. A
     * fence opens with three or more backticks or tildes and closes with at least as many of the
     * same character on a line of their own.
     */
    public static List<Block> blocks(String markdown) {
        List<Block> blocks = new ArrayList<>();
        if (markdown == null) {
            return blocks;
        }
        String[] lines = markdown.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].stripTrailing();
            String stripped = line.stripLeading();
            char fence = stripped.startsWith("```") ? '`' : stripped.startsWith("~~~") ? '~' : 0;
            if (fence == 0) {
                i++;
                continue;
            }
            int width = 0;
            while (width < stripped.length() && stripped.charAt(width) == fence) {
                width++;
            }
            String info = stripped.substring(width).strip();
            StringBuilder body = new StringBuilder();
            int j = i + 1;
            for (; j < lines.length; j++) {
                String candidate = lines[j].strip();
                if (candidate.length() >= width && candidate.chars().allMatch(c -> c == fence)) {
                    break;
                }
                body.append(lines[j]).append('\n');
            }
            if (DecisionTreeText.isTagged(info)) {
                blocks.add(new Block(info, body.toString()));
            }
            i = j + 1;
        }
        return blocks;
    }

    /**
     * What is wrong with one block, in the order the reader, the profile check and the rollback
     * find it: empty for a block that reads clean and rolls back at every node.
     */
    public static List<String> problems(Block block, DecisionTerms terms, Identities ids) {
        Set<String> problems = new LinkedHashSet<>();
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(block.body(), terms, ids);
        for (ProfileFinding finding : parsed.findings()) {
            if (!informs(finding)) {
                problems.add(finding.message());
            }
        }
        if (parsed.tree().isEmpty()) {
            return new ArrayList<>(problems);
        }
        DecisionTree tree = parsed.tree().get();
        for (ProfileFinding finding : tree.check()) {
            problems.add(describe(tree, ids, finding));
        }
        double price = DecisionTreeText.healthPrice(block.info()).orElse(DecisionTreeText.DEFAULT_HEALTH_PRICE);
        ValuationCoordinate coordinate = ValuationCoordinate.pricingHealthAt(Bounds.point(price),
                semantic(tree, MoneyCost.class, terms.moneySemantic()),
                semantic(tree, HealthCost.class, terms.healthSemantic()));
        Rollback rollback = Rollback.of(tree, coordinate);
        for (ProfileFinding finding : rollback.findings()) {
            problems.add(describe(tree, ids, finding));
        }
        return new ArrayList<>(problems);
    }

    /**
     * The message that sends a reply's findings back to the assistant, when any of its
     * decision-tree blocks has them; empty when every block reads clean, or the reply has none.
     */
    public static Optional<String> message(String reply, DecisionTerms terms, Identities ids) {
        List<Block> blocks = blocks(reply);
        List<List<String>> problems = new ArrayList<>();
        boolean any = false;
        for (Block block : blocks) {
            List<String> found = problems(block, terms, ids);
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
                + "Change only what these findings name; keep every identity, every number the person gave, and "
                + "the block's structure otherwise. Do not explain the correction. Load the Decision tree skill "
                + "if you need the grammar.");
        return Optional.of(message.toString());
    }

    /** Whether a transcript turn is Komet's feedback rather than a person's words. */
    public static boolean isFeedback(String content) {
        return content != null && content.startsWith(PREFIX);
    }

    /** A finding that informs rather than faults: the symbols the block reads as local to itself. */
    private static boolean informs(ProfileFinding finding) {
        return finding.message().contains("local to the block");
    }

    /** A finding named by the node it is on, as the person sees the tree, rather than by a vertex index. */
    public static String describe(DecisionTree tree, Identities ids, ProfileFinding finding) {
        Optional<DecisionElement> element = finding.vertexIndex() < 0
                ? Optional.empty() : tree.node(finding.vertexIndex());
        if (element.isEmpty()) {
            return finding.message();
        }
        return describe(tree, ids, element.get()) + ": " + finding.message();
    }

    private static String describe(DecisionTree tree, Identities ids, DecisionElement element) {
        return switch (element) {
            case Decision d -> "the decision " + label(ids, d.concept());
            case Alternative a -> "the alternative " + label(ids, a.concept());
            case Chance c -> "the chance " + label(ids, c.concept());
            case Event e -> (e.residual() ? "the residual " : "the event ") + label(ids, e.concept());
            case Outcome o -> "the outcome " + label(ids, o.concept());
            case MoneyCost m -> "the money cost under " + parent(tree, ids, m.vertexIndex());
            case HealthCost h -> "the health cost under " + parent(tree, ids, h.vertexIndex());
            case Probability p -> "the probability of " + parent(tree, ids, p.vertexIndex());
            case Parameter p -> "the parameter " + label(ids, p.quantity());
        };
    }

    private static String parent(DecisionTree tree, Identities ids, int vertexIndex) {
        OptionalInt parent = tree.parentIndex(vertexIndex);
        if (parent.isEmpty()) {
            return "the root";
        }
        return tree.node(parent.getAsInt()).map(node -> describe(tree, ids, node)).orElse("the root");
    }

    private static String label(Identities ids, OptionalLong concept) {
        return concept.isPresent() ? "“" + ids.label(concept.getAsLong()) + "”" : "(unnamed)";
    }

    /** The semantic the tree's costs of a kind are on, from the first such cost; the parent semantic otherwise. */
    private static long semantic(DecisionTree tree, Class<? extends Cost> kind, long fallback) {
        ImmutableList<? extends Cost> costs = tree.nodesOfType(kind);
        return costs.isEmpty() ? fallback : costs.get(0).measure().semanticNid();
    }
}
