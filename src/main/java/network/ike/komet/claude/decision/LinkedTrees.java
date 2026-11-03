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

import network.ike.komet.claude.block.Identities;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The trees an outcome may fold in, {@code tree=<identity>}: a tree written once, the
 * consequences of a rupture say, and folded in wherever another tree ends in that event. A tree
 * is found by its root's identifier, the {@code @id} its root line carries, and read under the
 * identities of the tree that folds it in, so a sweep that re-reads a block under its own
 * identities finds it as the surface does.
 */
@FunctionalInterface
public interface LinkedTrees {

    /** The tree whose root is a concept, read under the identities of the tree that folds it in. */
    Optional<DecisionTree> tree(long rootConceptNid);

    /** No tree to fold in: an outcome that names one is not determinable. */
    static LinkedTrees none() {
        return nid -> Optional.empty();
    }

    /**
     * The trees of blocks by their root identifiers, each read on first use under the given
     * identities, with the quantities other blocks compute.
     *
     * @param bodies the blocks' bodies, by their root lines' identifiers
     */
    static LinkedTrees of(Map<String, String> bodies, DecisionTerms terms, Identities ids, ExternalQuantities externals) {
        if (bodies.isEmpty()) {
            return none();
        }
        Map<Long, Optional<DecisionTree>> read = new HashMap<>();
        return nid -> read.computeIfAbsent(nid, n -> ids.identifier(n)
                .map(bodies::get)
                .flatMap(body -> DecisionTreeText.parse(body, terms, ids, externals).tree()));
    }

    /** The identifier a tree's root line names, the key another tree folds it in by; empty when the root names none. */
    static Optional<String> rootIdentifier(DecisionTree tree, Identities ids) {
        return tree.root().flatMap(root -> {
            java.util.OptionalLong concept = root.concept();
            return concept.isPresent() ? ids.identifier(concept.getAsLong()) : Optional.empty();
        });
    }
}
