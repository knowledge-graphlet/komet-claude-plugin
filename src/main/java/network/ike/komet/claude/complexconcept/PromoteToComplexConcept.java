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
package network.ike.komet.claude.complexconcept;

import dev.ikm.komet.actions.KometAction;
import dev.ikm.komet.actions.KometActionInput;
import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalAxiom;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpression;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.anf.model.AnfExpression;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfStatement;
import network.ike.komet.claude.complexconcept.model.ComplexConceptBuilder;
import org.eclipse.collections.api.factory.Lists;

import java.util.Optional;

/**
 * The starter set's {@code Promote to complex concept} action: promotes an ANF statement's composed
 * topic to a complex concept, so instance data keeps a concept and never a composed expression
 * (ike-lab-documents topics {@code dev-ditree-editor-skin-family} and {@code arch-anf-precoordination};
 * IKE-Network/ike-issues#1312).
 *
 * <p>The input takes the {@code Complex concept promotion input pattern}: the subject is the
 * statement's semantic, and the one further field is the name of the complex concept. One
 * uncommitted change, in one transaction, writes:
 * <ol>
 *     <li>the complex concept, in the Complex concepts module, with the name as its fully qualified
 *     name and its regular name, both preferred in US English;</li>
 *     <li>its complex concept specification, referencing it: the topic's composition as the
 *     definition, and a focused expansion on the composition's parent concept, which the complex
 *     concept editor finishes with the prerequisites the composition implies;</li>
 *     <li>a new version of the statement, in its own module, whose topic is the new concept.</li>
 * </ol>
 * The result takes the {@code Version change result pattern}: the STAMP of the statement's new
 * version, and a message naming the new concept's public id.
 *
 * <p>Refused, with nothing written: a subject that is not an ANF statement, a blank name, a
 * statement with no topic, a topic that is already a single concept, a composition that names no
 * parent concept to focus the expansion on, and a statement with an edit pending, since the
 * promotion is one change and does not stack on another.
 */
public final class PromoteToComplexConcept implements KometAction {

    /** For the service loader. */
    public PromoteToComplexConcept() { }

    @Override
    public ConceptFacade concept() {
        return KometConcept.PROMOTE_TO_COMPLEX_CONCEPT;
    }

    /**
     * @param statement the nid of the statement's semantic
     * @param name      the complex concept's name
     * @return a new input asking for the promotion
     */
    public static KometActionInput input(long statement, String name) {
        return new KometActionInput(PublicIds.newRandom(), KometConcept.PROMOTE_TO_COMPLEX_CONCEPT,
                EntityProxy.Semantic.make(statement), Lists.immutable.of(name));
    }

    @Override
    public KometActionResult perform(KometActionInput input, ViewCalculator view, EditCoordinate edit) {
        if (!EntityFacade.same(input.action(), concept())) {
            return KometActionResult.refused(input, "This input asks for " + input.action().description()
                    + ", not " + concept().description() + ".");
        }
        if (input.fields().size() != 1 || !(input.fields().getFirst() instanceof String name) || name.isBlank()) {
            return KometActionResult.refused(input, "Promoting a topic takes one field, the complex concept's name.");
        }
        long statement = input.subject().nid();
        if (!(EntityHandle.get(statement).orNull() instanceof SemanticRecord record)
                || record.patternNid() != IkePattern.ANF_STATEMENT_PATTERN.nid()) {
            return KometActionResult.refused(input, "The subject is not an ANF statement.");
        }
        Latest<SemanticEntityVersion> latest = view.latest(statement);
        if (latest.isAbsent()) {
            return KometActionResult.refused(input, "No version of the statement is visible in this view.");
        }
        SemanticEntityVersion head = latest.get();
        if (head.uncommitted()) {
            return KometActionResult.refused(input, "An edit of this statement is pending; commit or cancel it"
                    + " before promoting its topic, so the promotion is one change.");
        }
        if (!(head.fieldValues().get(0) instanceof DiTreeEntity tree)) {
            return KometActionResult.refused(input, "The statement holds no tree.");
        }
        Optional<AnfStatement.Attribute.Expression> topic = AnfExpression.from(tree).root().context()
                .flatMap(AnfStatement.Context::topic);
        Optional<DiTreeEntity> composition = topic.flatMap(AnfStatement.Attribute.Expression::expressionTree);
        if (composition.isEmpty()) {
            return KometActionResult.refused(input, "The statement has no topic to promote.");
        }
        if (topic.get().singleConcept().isPresent()) {
            return KometActionResult.refused(input, "The topic is already a single concept; there is nothing to promote.");
        }
        Optional<ConceptFacade> parent = parentOf(new LogicalExpression(composition.get()));
        if (parent.isEmpty()) {
            return KometActionResult.refused(input, "The topic's composition names no parent concept to focus"
                    + " the complex concept's expansion on.");
        }

        ConceptFacade author = ConceptFacade.make(edit.getAuthorNidForChanges());
        ConceptFacade path = ConceptFacade.make(edit.getDefaultPathNid());
        StampedWriter writer = StampedWriter.open(concept().description() + ": " + name, State.ACTIVE,
                author, IkeConcept.COMPLEX_CONCEPTS_MODULE, path);
        try {
            PublicId complexConcept = PublicIds.newRandom();
            long conceptNid = writer.concept(complexConcept);
            writer.fullyQualifiedName(complexConcept, name);
            PublicId regularName = PublicIds.newRandom();
            writer.semantic(regularName, KernelPattern.DESCRIPTION_PATTERN.publicId(), complexConcept,
                    KernelConcept.ENGLISH_LANGUAGE, name, KernelConcept.DESCRIPTION_NOT_CASE_SENSITIVE,
                    KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE);
            writer.semantic(PublicIds.newRandom(), KernelPattern.US_DIALECT_PATTERN.publicId(), regularName,
                    KernelConcept.PREFERRED);

            ComplexConceptBuilder specification = new ComplexConceptBuilder();
            specification.definition(composition.get());
            specification.focusedExpansion(parent.get());
            writer.semantic(PublicIds.newRandom(), IkePattern.COMPLEX_CONCEPT_PATTERN.publicId(), complexConcept,
                    specification.asDiTree());

            writer.restamp(State.ACTIVE, Long.MAX_VALUE, author, ConceptFacade.make(head.moduleNid()), path);
            AnfExpressionBuilder promoted = new AnfExpressionBuilder(tree);
            AnfStatement.Attribute.Expression ownTopic = promoted.root().context()
                    .flatMap(AnfStatement.Context::topic).orElseThrow();
            promoted.splice(ownTopic, AnfExpressionBuilder.singleConceptExpression(ConceptFacade.make(conceptNid)));
            writer.semantic(record.publicId(), IkePattern.ANF_STATEMENT_PATTERN.publicId(),
                    PrimitiveData.publicId(record.referencedComponentNid()), promoted.asDiTree());
            StampEntity<?> written = writer.stamp();
            return KometActionResult.completed(input, written, "Promoted the topic to the complex concept "
                    + name + ", " + complexConcept.idString() + ", whose focused expansion on "
                    + parent.get().description() + " is finished in the complex concept editor;"
                    + " uncommitted, commit to keep it.");
        } catch (RuntimeException e) {
            writer.cancel();
            return KometActionResult.failed(input, "Could not promote the topic: " + e.getMessage());
        }
    }

    /**
     * The composition's parent concept: the first concept a necessary or sufficient set states
     * directly, beside the roles that refine it.
     *
     * @param composition the topic's composition
     * @return the parent, when the composition names one
     */
    static Optional<ConceptFacade> parentOf(LogicalExpression composition) {
        for (LogicalAxiom.LogicalSet set : composition.definitionRoot().sets()) {
            if (!(set instanceof LogicalAxiom.LogicalSet.NecessarySet || set instanceof LogicalAxiom.LogicalSet.SufficientSet)) {
                continue;
            }
            for (LogicalAxiom.Atom.Connective connective : set.elements()) {
                for (LogicalAxiom.Atom atom : connective.elements()) {
                    if (atom instanceof LogicalAxiom.Atom.ConceptAxiom conceptAxiom) {
                        return Optional.of(conceptAxiom.concept());
                    }
                }
            }
        }
        return Optional.empty();
    }
}
