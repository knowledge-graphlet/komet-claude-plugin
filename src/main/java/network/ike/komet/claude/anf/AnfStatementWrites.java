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

import dev.ikm.komet.actions.KometActionInput;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;

import java.util.List;
import java.util.Optional;

/**
 * What {@link AddAnfStatement} and {@link EditAnfStatement} share: reading the one field of the
 * {@code ANF statement tree input pattern}, and refusing a tree that is not a well-formed
 * statement (IKE-Network/ike-issues#1312).
 */
final class AnfStatementWrites {

    private AnfStatementWrites() {
    }

    /**
     * @param input a run's input
     * @return the statement tree, when the input's one further field is one
     */
    static Optional<DiTreeEntity> tree(KometActionInput input) {
        if (input.fields().size() == 1 && input.fields().getFirst() instanceof DiTreeEntity tree) {
            return Optional.of(tree);
        }
        return Optional.empty();
    }

    /**
     * @param tree a statement tree
     * @return why the tree is not a well-formed statement, as one message naming every rule it
     *         breaks; empty when it is well formed
     */
    static Optional<String> notWellFormed(DiTreeEntity tree) {
        List<String> broken = AnfExpressionBuilder.wellFormedness(tree);
        if (broken.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("The tree is not a well-formed ANF statement: " + String.join("; ", broken) + ".");
    }
}
