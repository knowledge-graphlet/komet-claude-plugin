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
package network.ike.komet.claude.block;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import network.ike.komet.claude.stamp.SemanticVersions;
import network.ike.komet.claude.tools.GraphTools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The identity of what a block states, derived from its content as the starter set derives its
 * own (ike-lab-documents topic {@code dev-decision-document}, "Identity"). Components with the same
 * fully qualified name and field values are the same component: a concept's public id derives from
 * its fully qualified name, everywhere, and a semantic's from what it is about, so a block's label
 * is its concept's fully qualified name and must stand alone. A concept the store already holds
 * under that name is that concept, whichever terminology it came from. Only the same name with
 * different field values is a question for the person, whose answer is usually a better name.
 */
public final class LocalConcepts {

    /** The namespace a concept's public id derives in. */
    private static final UUID NAMESPACE = UUID.fromString("3b1c8f2e-6d5a-4c1b-9e7f-2a0d4c6e8b10");

    /** A concept token: {@code k:Key[label] @id=identity}. */
    private static final Pattern TOKEN = Pattern.compile("k:[^\\s\\[]*\\[([^\\]]*)\\]\\s+@id=([^\\s,;]+)");

    /** A complex concept's root line, whose label names the concept the block defines. */
    private static final Pattern DEFINED = Pattern.compile("(?m)^concept:\\s+" + TOKEN.pattern());

    private LocalConcepts() {
    }

    /**
     * A name as identity compares it: letter case and runs of white space make no difference, and
     * nothing else is folded, so a singular and its plural stay two names.
     *
     * @param name a name
     * @return the name to compare
     */
    public static String normal(String name) {
        return name == null ? "" : name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * @param fqn a fully qualified name
     * @return the public id a concept of that name derives
     */
    public static UUID uuid(String fqn) {
        return UuidT5Generator.get(NAMESPACE, "concept: " + normal(fqn));
    }

    /**
     * @param of   a component
     * @param what what is derived from it, as {@code complex-concept specification}
     * @return the public id it derives
     */
    public static PublicId derived(PublicId of, String what) {
        return PublicIds.of(UuidT5Generator.get(of.leastUuid(), what));
    }

    /**
     * The concept a fully qualified name names: the one derived from it, or the one the store
     * holds under exactly that name, or the derived identity of a concept yet to be written.
     *
     * @param fqn  the fully qualified name
     * @param view the view the store is read under
     * @return the concept's public id
     */
    public static PublicId identity(String fqn, ViewCalculator view) {
        PublicId derived = PublicIds.of(uuid(fqn));
        if (SemanticVersions.exists(derived, view)) {
            return derived;
        }
        return GraphTools.conceptNamed(fqn, view).<PublicId>map(PrimitiveData::publicId).orElse(derived);
    }

    /**
     * What a document's blocks call their concepts. A symbol ties labels together, so a tree's
     * short name for the decision a complex concept defines is that concept: the name the complex
     * concept defines is its fully qualified name, else the first label written.
     *
     * @param fqns   each symbol's fully qualified name
     * @param labels each symbol's labels, in the order first read
     */
    public record Names(Map<String, String> fqns, Map<String, Set<String>> labels) {

        /** No document: a block's labels stand as they are. */
        public static final Names NONE = new Names(Map.of(), Map.of());

        /**
         * Reads the names of a document's structured blocks.
         *
         * @param blocks the blocks' text, in document order
         * @return the names
         */
        public static Names of(Collection<String> blocks) {
            Map<String, String> fqns = new LinkedHashMap<>();
            Map<String, Set<String>> labels = new LinkedHashMap<>();
            for (String block : blocks) {
                Matcher defined = DEFINED.matcher(block);
                while (defined.find()) {
                    if (!defined.group(1).isBlank()) {
                        fqns.put(defined.group(2), defined.group(1).strip());
                    }
                }
            }
            for (String block : blocks) {
                Matcher token = TOKEN.matcher(block);
                while (token.find()) {
                    if (!token.group(1).isBlank()) {
                        fqns.putIfAbsent(token.group(2), token.group(1).strip());
                        labels.computeIfAbsent(token.group(2), k -> new LinkedHashSet<>()).add(token.group(1).strip());
                    }
                }
            }
            return new Names(fqns, labels);
        }

        /**
         * @param symbol a symbol
         * @param label  the label a block gives it
         * @return its fully qualified name
         */
        public String fqn(String symbol, String label) {
            return fqns.getOrDefault(symbol, label);
        }

        /**
         * @param symbol a symbol
         * @param fqn    its fully qualified name
         * @return the other labels it goes by
         */
        public List<String> others(String symbol, String fqn) {
            List<String> others = new ArrayList<>(labels.getOrDefault(symbol, Set.of()));
            others.removeIf(label -> normal(label).equals(normal(fqn)));
            return others;
        }

        /**
         * @param stored whether a symbol names what the store holds
         * @return the names of symbols the store does not hold that are one word, too bare to stand alone
         */
        public List<String> bare(java.util.function.Predicate<String> stored) {
            Set<String> bare = new LinkedHashSet<>();
            fqns.forEach((symbol, fqn) -> {
                if (!stored.test(symbol) && !fqn.strip().contains(" ")) {
                    bare.add(fqn);
                }
            });
            return List.copyOf(bare);
        }
    }

    /**
     * How a block's write stands to the document it is in.
     *
     * @param shared   whether the document is paired, its two authors sharing one pending edit
     * @param names    what the document's blocks call their concepts
     * @param previous the block's text before this edit, empty for a new block
     */
    public record Context(boolean shared, Names names, Optional<String> previous) {

        /** A block on its own: Propose from a transcript, whose text holds all it says. */
        public static final Context ALONE = new Context(false, Names.NONE, Optional.empty());
    }
}
