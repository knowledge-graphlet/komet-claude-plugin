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

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpressionBuilder;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import network.ike.komet.claude.anf.editor.AnfStatementControl;
import network.ike.komet.claude.authoring.BlockConcepts;
import network.ike.komet.claude.authoring.ConceptChangeWriter;
import network.ike.komet.claude.authoring.DescriptionOperation;
import network.ike.komet.document.LocalConcepts;
import network.ike.komet.document.SemanticVersions;

import java.util.List;
import java.util.Objects;

/**
 * What a statement's form writes to the knowledge base when a person settles a value that has
 * words and no concept: a new concept made from the words, or the words given to an existing
 * concept as another name (IKE-Network/ike-issues#812). Both are written uncommitted, under the
 * person's own author, module and path, each in a change of its own that the person publishes
 * or discards with the rest of their work.
 *
 * <p>A new concept is written the way every new concept is, through {@link BlockConcepts}: its
 * name is its fully qualified name and a preferred regular name, its identity is the one its
 * name derives ({@link LocalConcepts#identity}), and its stated definition is primitive, an
 * is-a to the parent the person chose. A concept the knowledge base already has under exactly
 * that name is that concept, and nothing is written.
 *
 * <p>Runs on the JavaFX application thread, where the form calls it.
 */
public final class AnfFormConcepts implements AnfStatementControl.ConceptWriter {

    private final ViewCalculator view;
    private final EditCoordinate edit;

    /**
     * @param view the view the knowledge base is read under
     * @param edit whose changes these are, in which module and on which path
     */
    public AnfFormConcepts(ViewCalculator view, EditCoordinate edit) {
        this.view = Objects.requireNonNull(view, "view");
        this.edit = Objects.requireNonNull(edit, "edit");
    }

    @Override
    public ConceptFacade create(String name, ConceptFacade parent) {
        Objects.requireNonNull(parent, "parent");
        String fqn = name == null ? "" : name.strip();
        if (fqn.isEmpty()) {
            throw new IllegalArgumentException("a concept needs a name");
        }
        PublicId identity = LocalConcepts.identity(fqn, view);
        ConceptFacade concept = EntityProxy.Concept.make(fqn, identity);
        if (SemanticVersions.exists(identity, view)) {
            return concept;
        }
        BlockConcepts writer = new BlockConcepts("New concept from a statement: " + fqn, view,
                ConceptFacade.make(edit.getAuthorNidForChanges()), ConceptFacade.make(edit.getDefaultModuleNid()),
                ConceptFacade.make(edit.getDefaultPathNid()));
        try {
            writer.concept(identity, fqn, List.of(), primitiveBelow(parent));
        } catch (RuntimeException failure) {
            writer.rollback();
            throw failure;
        }
        return concept;
    }

    @Override
    public void addName(ConceptFacade concept, String name) {
        String words = name == null ? "" : name.strip();
        if (words.isEmpty()) {
            return;
        }
        ConceptChangeWriter.Change change = ConceptChangeWriter.open(view, ConceptFacade.make(edit.getAuthorNidForChanges()),
                ConceptFacade.make(edit.getDefaultModuleNid()), ConceptFacade.make(edit.getDefaultPathNid()), concept,
                "another name, from a statement's form");
        try {
            change.editDescriptions(List.of(new DescriptionOperation.AddSynonym(words, false, false)));
        } catch (IllegalArgumentException refused) {
            // the concept is called by these words already: there is nothing to add
            if (refused.getMessage() == null || !refused.getMessage().contains(" already has the ")) {
                throw refused;
            }
        }
    }

    /** A concept's stated definition when all that is known is what it is a kind of: primitive, an is-a to it. */
    private static DiTreeEntity primitiveBelow(ConceptFacade parent) {
        LogicalExpressionBuilder logical = new LogicalExpressionBuilder();
        logical.NecessarySet(logical.And(logical.ConceptAxiom(parent)));
        return (DiTreeEntity) logical.build().sourceGraph();
    }
}
