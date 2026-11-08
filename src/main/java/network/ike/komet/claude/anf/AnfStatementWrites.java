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
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.document.GitDocumentMaterializer;
import network.ike.komet.document.VertexReader;

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
     * The latest version of a block or statement: as the view sees it, or, for an element of a
     * document stored from git and a statement about one, as the document reads it, since those
     * live in the working tree's module, the repository being their record, which a view need not
     * see.
     *
     * @param semantic a block's element semantic, or a statement
     * @param view     the view
     * @return the latest version, empty when there is none
     */
    static Optional<SemanticEntityVersion> head(SemanticEntity<?> semantic, ViewCalculator view) {
        SemanticEntity<?> element = semantic;
        if (semantic.patternNid() == IkePattern.ANF_STATEMENT_PATTERN.nid()
                && EntityHandle.get(semantic.referencedComponentNid()).orNull() instanceof SemanticEntity<?> block) {
            element = block;
        }
        if (VertexReader.isDocumentPattern(element.patternNid())) {
            @SuppressWarnings("unchecked")
            SemanticEntity<SemanticEntityVersion> versions = (SemanticEntity<SemanticEntityVersion>) semantic;
            return Optional.ofNullable(GitDocumentMaterializer.latest(versions, true));
        }
        Latest<SemanticEntityVersion> latest = view.latest(semantic.nid());
        return latest.isPresent() ? Optional.of(latest.get()) : Optional.empty();
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
