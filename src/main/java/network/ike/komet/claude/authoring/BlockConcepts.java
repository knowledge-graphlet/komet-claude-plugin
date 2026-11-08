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
package network.ike.komet.claude.authoring;

import dev.ikm.komet.framework.observable.ObservableComposer;
import dev.ikm.komet.kview.mvvm.view.genpurpose.StatedDefinitionSeeds;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.PatternFacade;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Synonym;
import network.ike.komet.claude.stamp.LocalConcepts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a structured block or an action writes, in one pending transaction, so its new concepts
 * publish with what it states. Which concept a name means is {@link LocalConcepts}'s to settle; a
 * concept the store lacks is written here the way every new concept is, by
 * {@link ConceptProposalWriter}, under the identity its name derives.
 *
 * <p>A block says what its concepts are called, not what they are, so a new concept's stated
 * definition is the classic concept window's seed ({@link StatedDefinitionSeeds}): primitive, an
 * is-a to the Anonymous concept, for the person to replace with the real parent. A writer that
 * knows the parent states it ({@link #concept(PublicId, String, Collection, DiTreeEntity)}).
 *
 * <p>Any thread may call this: the writes run on the JavaFX application thread, where
 * {@link ObservableComposer} requires them, and the caller waits for each.
 */
public final class BlockConcepts {

    private final String name;
    private final ViewCalculator view;
    private final ConceptFacade author;
    private final ConceptFacade module;
    private final ConceptFacade path;
    /** The composer, once something is written. Used only on the JavaFX application thread. */
    private ObservableComposer composer;

    /**
     * @param name   the transaction's name, should anything be written
     * @param view   the view the patterns' field definitions are read on
     * @param author the author of what is written
     * @param module the module it is written in
     * @param path   the path it is written on
     */
    public BlockConcepts(String name, ViewCalculator view, ConceptFacade author, ConceptFacade module, ConceptFacade path) {
        this.name = name;
        this.view = view;
        this.author = author;
        this.module = module;
        this.path = path;
    }

    /**
     * Writes a concept the store lacks, uncommitted, with the seed's stated definition.
     *
     * @param concept the identity its name derives
     * @param fqn     its fully qualified name, which is also a preferred regular name
     * @param others  the other labels it goes by, preferred regular names too
     */
    public void concept(PublicId concept, String fqn, Collection<String> others) {
        concept(concept, fqn, others, StatedDefinitionSeeds.seedDefinition(true));
    }

    /**
     * Writes a concept the store lacks, uncommitted.
     *
     * @param concept    the identity its name derives
     * @param fqn        its fully qualified name, which is also a preferred regular name
     * @param others     the other labels it goes by, preferred regular names too
     * @param definition its stated definition
     */
    public void concept(PublicId concept, String fqn, Collection<String> others, DiTreeEntity definition) {
        Set<String> regular = new LinkedHashSet<>();
        regular.add(fqn);
        regular.addAll(others);
        List<Synonym> synonyms = new ArrayList<>();
        regular.forEach(label -> synonyms.add(new Synonym(label, true, false)));
        ConceptProposalTools.onFxThread(() -> ConceptProposalWriter.write(composer(), view, concept, fqn,
                definition, synonyms, null, null));
    }

    /**
     * Writes what the block states, a semantic with one field, in the concepts' transaction.
     *
     * @param id         the semantic's public id
     * @param pattern    its pattern
     * @param referenced the component it is on
     * @param value      its field's value
     */
    public void semantic(PublicId id, PatternFacade pattern, EntityFacade referenced, Object value) {
        ConceptProposalTools.onFxThread(() -> ConceptProposalWriter.semantic(composer(), id, referenced, pattern,
                fields -> fields.getFirst().setObjectValue(value)));
    }

    /**
     * Writes a semantic with one field, new or a new version, in the concepts' transaction but in
     * another module: an existing component stays in its own module, and still publishes with the
     * concepts.
     *
     * @param module     the module the version is written in
     * @param id         the semantic's public id
     * @param pattern    its pattern
     * @param referenced the component it is on
     * @param value      its field's value
     * @return the stamp of the version written
     */
    public StampEntity<?> semanticIn(ConceptFacade module, PublicId id, PatternFacade pattern, EntityFacade referenced, Object value) {
        return ConceptProposalTools.onFxThread(() -> {
            var semantic = composer().composeSemanticInModule(id, referenced, pattern, module);
            semantic.getEditableVersion().getEditableFields().getFirst().setObjectValue(value);
            semantic.save();
            return ConceptProposalWriter.versionIn(composer(),
                    (SemanticEntity<?>) EntityHandle.get(semantic.getEntity().nid()).expectEntity()).stamp();
        });
    }

    /**
     * Joins the unpublished change that writes components what follows depends on without
     * writing them, so that change and what follows publish together and nothing publishes a
     * reference to a component that is not published
     * ({@link ObservableComposer#continuePendingWorkOf}). Call before writing.
     *
     * @param components the components what follows depends on
     * @return true when none of them is left unpublished in another change; false, with nothing
     *         changed, when one is held by a change this writer cannot join
     */
    public boolean continuePendingWorkOf(EntityFacade... components) {
        return ConceptProposalTools.onFxThread(() -> composer().continuePendingWorkOf(components));
    }

    /**
     * Takes back the version of one semantic written here, leaving the rest of the change: what a
     * failed write undoes once it has joined another change, whose work is not its own to discard
     * ({@link ObservableComposer#withdraw}).
     *
     * @param semantic the semantic whose version written here is taken back
     */
    public void withdraw(long semantic) {
        ConceptProposalTools.onFxThread(() -> composer != null && composer.withdraw(semantic));
    }

    /** Takes back everything written, when the block turns out not to be writable. */
    public void rollback() {
        ConceptProposalTools.onFxThread(() -> {
            if (composer != null) {
                composer.rollback();
            }
            return null;
        });
    }

    private ObservableComposer composer() {
        if (composer == null) {
            composer = ConceptProposalWriter.composer(view, author, module, path, name);
        }
        return composer;
    }
}
