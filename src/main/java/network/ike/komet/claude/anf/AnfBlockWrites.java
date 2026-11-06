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
import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.tools.GraphTools;

import java.util.List;

/**
 * Writes the statement an {@code anf} block states as a statement of its block, uncommitted
 * (ike-lab-documents topic {@code dev-decision-document}, "Versions"): the block's text is the
 * canonical form, read by {@link AnfAdoc#parse}, and the statement's tree is derived from it by
 * {@link AnfProjection#toTree}. The first write performs {@link AddAnfStatement}, so the statement
 * references the block's element semantic, and each write after performs {@link EditAnfStatement}
 * on the same statement, which rewrites the author's pending version or, after a commit, writes
 * a new one. A block whose text is not yet a statement, or names a concept the store lacks,
 * writes nothing and says why.
 */
public final class AnfBlockWrites {

    private AnfBlockWrites() {
    }

    /**
     * Writes the statement a block states, after the block was saved.
     *
     * @param lines every line of the block: its attribute line, fences and content
     * @param id    the block's {@code id=}, for the person's sentence; empty when it names none
     * @param block the nid of the block's element semantic
     * @param view  the view the block's identifiers are read under
     * @param edit  whose change it is, in which module and on which path
     * @return a sentence for the person
     */
    public static String write(List<String> lines, String id, long block, ViewCalculator view, EditCoordinate edit) {
        String name = id.isEmpty() ? "The statement" : "The statement " + id;
        AnfStatement statement;
        DiTreeEntity tree;
        try {
            statement = AnfAdoc.parse(String.join("\n", lines) + "\n", identifier -> {
                // A parse keeps an identifier that does not resolve, as a question to clarify; a
                // write must not, or the store would hold a statement naming nothing.
                if (!GraphTools.inStore(identifier)) {
                    throw new IllegalArgumentException("it names " + identifier + ", which is not in this knowledge base.");
                }
                return GraphTools.resolveConcept(identifier, view);
            });
            if (statement == null) {
                return name + " was not written: the block names no statement type.";
            }
            tree = AnfProjection.toTree(statement);
        } catch (RuntimeException e) {
            return name + " was not written: " + e.getMessage();
        }
        List<Long> statements = EntityService.get().semanticsForComponentOfPattern(block, IkePattern.ANF_STATEMENT_PATTERN.nid())
                .toList().stream().filter(semantic -> AnfStatementWrites.head(semantic, view).isPresent())
                .map(semantic -> semantic.nid()).toList();
        if (statements.size() > 1) {
            return name + " was not written: its block holds " + statements.size()
                    + " statements, and a block's text states one; edit them one by one.";
        }
        KometActionInput input = statements.isEmpty() ? AddAnfStatement.input(block, tree)
                : EditAnfStatement.input(statements.getFirst(), tree);
        KometActionResult result = statements.isEmpty() ? new AddAnfStatement().perform(input, view, edit)
                : new EditAnfStatement().perform(input, view, edit);
        return result.completed() ? name + ": " + result.message() : name + " was not written: " + result.message();
    }
}
