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
package network.ike.komet.claude.benefitrisk;

import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.Tokens;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A {@code [value-tree]} block: the objectives of a decision as a hierarchy, a goal over domains
 * over measurable criteria, with a gate where a result is not traded.
 *
 * @param goal     the goal, when the block has one
 * @param findings what could not be read
 */
public record ValueTree(Optional<Part> goal, List<String> findings) {

    /** The block's style. */
    public static final String TAG = "value-tree";

    /** What a part of the tree is. */
    public enum Kind { GOAL, DOMAIN, CRITERION, GATE }

    /** Which way a criterion improves. */
    public enum Better { HIGHER, LOWER }

    /**
     * One part of the tree.
     *
     * @param kind     what it is
     * @param id       its identifier, by which a row of an effects table names it
     * @param name     its concept's name
     * @param polarity {@code favorable} or {@code unfavorable} on a domain
     * @param better   which way a criterion improves
     * @param pass     the result a gate passes on
     * @param parts    the parts beneath it
     */
    public record Part(Kind kind, String id, String name, Optional<String> polarity, Optional<Better> better,
                       Optional<String> pass, List<Part> parts) {

        /** What the part says beyond its name: which way is better, or what a gate must find. */
        public String reading() {
            return switch (kind) {
                case CRITERION -> better.map(way -> way == Better.LOWER ? "lower is better" : "higher is better").orElse("");
                case GATE -> "gate: must be " + pass.orElse("stated");
                case DOMAIN, GOAL -> "";
            };
        }

        /** The parts an effects table has a row for, each with the domain it stands in. */
        void collectLeaves(Optional<Part> domain, List<Leaf> leaves) {
            for (Part part : parts) {
                if (part.kind() == Kind.CRITERION || part.kind() == Kind.GATE) {
                    leaves.add(new Leaf(part, domain));
                } else {
                    part.collectLeaves(Optional.of(part), leaves);
                }
            }
        }
    }

    /**
     * A criterion or gate, with the domain above it.
     *
     * @param part   the leaf
     * @param domain its domain; a gate directly beneath the goal has none
     */
    public record Leaf(Part part, Optional<Part> domain) {
    }

    /** The leaves, in the tree's order. */
    public List<Leaf> leaves() {
        List<Leaf> leaves = new ArrayList<>();
        goal.ifPresent(root -> root.collectLeaves(Optional.empty(), leaves));
        return leaves;
    }

    /**
     * Reads a block's content.
     *
     * @param body the content
     * @return the tree and what could not be read
     */
    public static ValueTree parse(String body) {
        BlockLines.Parsed lines = BlockLines.parse(body);
        List<String> findings = new ArrayList<>(lines.problems());
        if (lines.roots().size() != 1 || !"goal".equals(lines.roots().getFirst().word())) {
            findings.add("A value tree has one goal at its root.");
            return new ValueTree(Optional.empty(), findings);
        }
        return new ValueTree(part(lines.roots().getFirst(), findings), findings);
    }

    private static Optional<Part> part(BlockLines.Line line, List<String> findings) {
        Kind kind = switch (line.word()) {
            case "goal" -> Kind.GOAL;
            case "domain" -> Kind.DOMAIN;
            case "criterion" -> Kind.CRITERION;
            case "gate" -> Kind.GATE;
            default -> null;
        };
        Optional<Tokens.Concept> concept = Tokens.concept(line.head());
        if (kind == null || concept.isEmpty()) {
            findings.add("line " + line.number() + ": neither a goal, domain, criterion or gate over a concept: " + line.rest());
            return Optional.empty();
        }
        Optional<Better> better = line.attribute("better").map(word -> "lower".equalsIgnoreCase(word) ? Better.LOWER : Better.HIGHER);
        if (kind == Kind.CRITERION && better.isEmpty()) {
            findings.add(concept.get().label() + " does not say which way is better.");
        }
        List<Part> parts = new ArrayList<>();
        for (BlockLines.Line child : line.children()) {
            part(child, findings).ifPresent(parts::add);
        }
        String id = concept.get().identifier().orElse(concept.get().key());
        return Optional.of(new Part(kind, id, concept.get().label(), line.attribute("polarity"), better, line.attribute("pass"), parts));
    }
}
