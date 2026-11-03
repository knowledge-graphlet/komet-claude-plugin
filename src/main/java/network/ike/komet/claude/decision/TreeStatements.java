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

import network.ike.komet.claude.anf.element.ElementText;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionElement.Event;
import network.ike.komet.claude.decision.DecisionElement.Outcome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The ANF statements a decision tree's nodes hold by naming the document's {@code anf} blocks,
 * {@code statement=anf:req-scan} ({@code dev-decision-point-knowledge}): an alternative is a
 * course someone asks for, so it holds a request statement; an event or outcome is what is found
 * or happens, so it holds a performance statement. The null course, "do not scan", holds none, and
 * a decision or chance holds none of its own: the alternatives and events beneath it do. Statements
 * on a tree are templates, so they carry no subject of record, author or statement time; the
 * tree is the note.
 *
 * <p>Pure: the kind of each named statement comes from the caller, the surface's drawn blocks or
 * the fences of a reply, so the same findings reach the person and the assistant.
 */
public final class TreeStatements {

    /** The three ANF statement types. */
    public enum Kind {
        REQUEST, PERFORMANCE, NARRATIVE;

        /** The type word, as an {@code anf} fence writes it. */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One statement a node holds: the node's vertex and the anf block's name. */
    public record Held(int vertexIndex, String name) {
    }

    private TreeStatements() {
    }

    /** Every statement the tree's nodes hold by name, in the order the text states them. */
    public static List<Held> held(DecisionTreeText.Parsed parsed) {
        List<Held> held = new ArrayList<>();
        List<int[]> vertexLines = new ArrayList<>();
        parsed.lines().forEachKeyValue((vertex, line) -> vertexLines.add(new int[]{vertex, line}));
        vertexLines.sort(Comparator.comparingInt(pair -> pair[1]));
        for (int[] pair : vertexLines) {
            for (String name : parsed.documentStatements(pair[0])) {
                held.add(new Held(pair[0], name));
            }
        }
        return held;
    }

    /** The kind of statement a node holds: a request for an alternative, a performance for an event or outcome; none otherwise. */
    public static Optional<Kind> expected(DecisionElement element) {
        return switch (element) {
            case Alternative a -> Optional.of(Kind.REQUEST);
            case Event e -> Optional.of(Kind.PERFORMANCE);
            case Outcome o -> Optional.of(Kind.PERFORMANCE);
            default -> Optional.empty();
        };
    }

    /** The statement type an {@code anf} fence's info names, {@code type=…}; performance when it names none. */
    public static Kind kindOf(String info) {
        String type = ElementText.attributes(info == null ? "" : info).getOrDefault("type", "performance");
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "request" -> Kind.REQUEST;
            case "narrative" -> Kind.NARRATIVE;
            default -> Kind.PERFORMANCE;
        };
    }

    /** The kinds of the {@code anf} fences of a Markdown text, by the name each gives its statement, {@code id=…}. */
    public static Map<String, Kind> fenceKinds(String markdown) {
        Map<String, Kind> kinds = new HashMap<>();
        for (DecisionTreeFeedback.Block block : DecisionTreeFeedback.blocks(markdown, TreeStatements::isAnfFence)) {
            String id = ElementText.attributes(block.info()).get("id");
            if (id != null && !id.isBlank()) {
                kinds.put(id, kindOf(block.info()));
            }
        }
        return kinds;
    }

    /** Whether a fence's info names an {@code anf} block: its first word is {@code anf}, with or without a comma after. */
    public static boolean isAnfFence(String info) {
        if (info == null) {
            return false;
        }
        String first = info.strip().split("\\s+", 2)[0];
        return first.equals("anf") || first.startsWith("anf,");
    }

    /**
     * What is wrong with the statements the tree's nodes hold: a statement on a node that holds
     * none, one of the wrong type, and, when {@code reportMissing}, a name no {@code anf} block
     * gives. A name whose kind is unknown is otherwise passed over, since the block may be in an
     * earlier reply.
     */
    public static List<String> findings(DecisionTree tree, Identities ids, DecisionTreeText.Parsed parsed,
                                        Function<String, Optional<Kind>> kindOf, boolean reportMissing) {
        List<String> findings = new ArrayList<>();
        for (Held held : held(parsed)) {
            Optional<DecisionElement> element = tree.node(held.vertexIndex());
            if (element.isEmpty()) {
                continue;
            }
            String node = DecisionTreeFeedback.describe(tree, ids, element.get());
            Optional<Kind> expected = expected(element.get());
            if (expected.isEmpty()) {
                findings.add(node + " holds anf:" + held.name() + ", but a decision or chance holds no statement;"
                        + " the alternatives and events beneath it do");
                continue;
            }
            Optional<Kind> kind = kindOf.apply(held.name());
            if (kind.isEmpty()) {
                if (reportMissing) {
                    findings.add(node + " holds anf:" + held.name() + ", which no anf block before the tree names");
                }
                continue;
            }
            if (kind.get() != expected.get()) {
                findings.add(node + " holds anf:" + held.name() + ", a " + kind.get().word() + " statement;"
                        + " an alternative holds a request, an event or outcome a performance");
            }
        }
        return findings;
    }
}
