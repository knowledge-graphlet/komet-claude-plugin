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
package network.ike.komet.claude.documenttools;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.KernelPattern;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.document.LocalConcepts;
import network.ike.komet.claude.complexconcept.ComplexConceptWrites;
import network.ike.komet.claude.decision.DecisionTreeWrites;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity from content (ike-lab-documents topic {@code dev-decision-document}, "Identity"):
 * the same fully qualified name is the same concept, wherever and under whatever symbol it is
 * written; what a block states derives its public id, so a store rebuilt from the same text holds
 * the same components; a name the store already holds is that concept; and only the same name with
 * different field values waits for the person.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlockIdentityIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** A tree with one outcome, on two branches under two symbols. */
    private static String tree(String root, String p) {
        return """
                decision: k:Root[%s] @id=bi-root
                  alternative: k:Inspect[Inspect the clinical site] @id=bi-inspect
                    money = [10000,10000] k:Usd[US dollar] @id=bi-usd
                    outcome: k:Bad[Approval on unreliable data] @id=bi-bad-inspected
                  alternative: k:Skip[Skip the site inspection] @id=bi-skip
                    chance: k:Reliable[Reliability of the site's data] @id=bi-reliable
                      event: k:Bad2[Approval on unreliable data] @id=bi-bad-skipped
                        p = [%s,%s]
                        outcome: k:Costly[Costly recall after approval] @id=bi-recall
                          money = [200000,200000] k:Usd[US dollar] @id=bi-usd
                      residual: k:Good[Approval on reliable data] @id=bi-good
                        outcome: k:Fine[No recall after approval] @id=bi-fine
                """.formatted(root, p, p);
    }

    private static final String SPEC = """
            concept: k:Decision[%s] @id=bi-spec
              expansion: focused k:Focus[Clinical site inspection decision] @id=bi-focus
                prerequisite: k:First[First-of-a-kind device] @id=bi-first
                  value = [%s,1] k:Presence[Presence of the characteristic] @id=bi-presence
            """;

    private EditCoordinateRecord edit;
    private ViewCalculator view;

    @BeforeAll
    void setup() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        try {
            HeadlessFx.select();
            javafx.application.Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // the toolkit outlives a test class
        }
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
        edit = Coordinates.Edit.Default();
        view = Calculators.View.Default();
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    private static PublicId concept(String fqn) {
        return PublicIds.of(LocalConcepts.uuid(fqn));
    }

    private static boolean held(String message) {
        return message.contains("not written") && message.contains("Ask the person");
    }

    /** Commits what a pending version holds, so a later write meets a settled component. */
    private void commit(PublicId semantic) {
        SemanticRecord record = (SemanticRecord) EntityHandle.get(PrimitiveData.nid(semantic)).orNull();
        Transaction.forVersion(record.versions().getLast()).orElseThrow().commit();
    }

    @Test
    void oneNameIsOneConceptWhateverSymbolsItIsWrittenUnder() {
        DecisionTreeWrites.Written written = DecisionTreeWrites.write("decision-tree", tree("Inspect this first site?", "0.1"), view, edit);
        assertTrue(written.tree().isPresent(), written.message());
        // the outcome on two branches under two symbols is one concept with its names written once
        long approval = PrimitiveData.nid(concept("approval on UNRELIABLE   data"));
        List<SemanticEntityVersion> names = EntityService.get().semanticsForComponentOfPattern(approval, KernelPattern.DESCRIPTION_PATTERN.nid()).toList()
                .stream().map(s -> (SemanticEntityVersion) s.versions().get(0)).toList();
        assertEquals(2, names.size(), "a fully qualified name and a regular name, once each");
        assertEquals("Approval on unreliable data", names.getFirst().fieldValues().get(1));
    }

    @Test
    void aBlocksComponentsDeriveTheirIdentities() {
        DecisionTreeWrites.Written written = DecisionTreeWrites.write("decision-tree", tree("Derive the tree's identity?", "0.1"), view, edit);
        PublicId root = concept("Derive the tree's identity?");
        assertEquals(LocalConcepts.derived(root, "decision-tree"), written.tree().orElseThrow());
        long regular = PrimitiveData.nid(LocalConcepts.derived(root, "regular-name|en|derive the tree's identity?"));
        assertTrue(EntityHandle.get(regular).orNull() != null, "the regular name's identity derives from the concept");

        ComplexConceptWrites.Written proposed = ComplexConceptWrites.write(SPEC.formatted("Derived specification decision", "1"), view, edit,
                LocalConcepts.Context.ALONE);
        assertTrue(proposed.wrote(), proposed.message());
        assertTrue(PrimitiveData.get().hasPublicId(LocalConcepts.derived(concept("Derived specification decision"), "complex-concept specification")),
                proposed.message());
    }

    @Test
    void aConceptTheStoreHoldsUnderThatNameIsThatConcept() {
        String held = view.getFullyQualifiedDescriptionTextWithFallbackOrNid(IkeConcept.PERFORMANCE_CIRCUMSTANCE.nid());
        PublicId found = LocalConcepts.identity(held.toUpperCase(java.util.Locale.ROOT), view);
        assertEquals(IkeConcept.PERFORMANCE_CIRCUMSTANCE.nid(), PrimitiveData.nid(found), "the name finds the starter set's own concept");
        assertFalse(PrimitiveData.get().hasPublicId(concept(held)) && !found.equals(concept(held)) && EntityHandle.get(PrimitiveData.nid(concept(held))).orNull() != null,
                "nothing new is minted for it");
    }

    @Test
    void theSameNameWithDifferentFieldValuesWaitsForThePerson() {
        String body = tree("Which of two trees?", "0.1");
        DecisionTreeWrites.Written first = DecisionTreeWrites.write("decision-tree", body, view, edit);
        PublicId semantic = first.tree().orElseThrow();
        commit(semantic);

        // the same tree from elsewhere is the same component
        String same = DecisionTreeWrites.write("decision-tree", body, view, edit).message();
        assertTrue(same.contains("already in the knowledge base"), same);

        // a different tree of that decision, from a block that did not state the one held, waits
        String other = tree("Which of two trees?", "0.3");
        String waiting = DecisionTreeWrites.write("decision-tree", other, view, edit).message();
        assertTrue(held(waiting), waiting);
        SemanticRecord record = (SemanticRecord) EntityHandle.get(PrimitiveData.nid(semantic)).orNull();
        assertEquals(1, record.versions().size(), "nothing is written over the tree held");

        // the block that stated it revises it
        DecisionTreeWrites.Written revised = DecisionTreeWrites.write("decision-tree", other, view, edit,
                new LocalConcepts.Context(false, LocalConcepts.Names.NONE, Optional.of(body)));
        assertTrue(revised.message().startsWith("Wrote a new version"), revised.message());

        // a pending tree waits for the person just as a committed one does: another block does not rewrite it
        String pendingBody = tree("Which of two pending trees?", "0.1");
        PublicId pending = DecisionTreeWrites.write("decision-tree", pendingBody, view, edit).tree().orElseThrow();
        String overPending = DecisionTreeWrites.write("decision-tree", tree("Which of two pending trees?", "0.3"), view, edit).message();
        assertTrue(held(overPending), overPending);
        assertEquals(1, ((SemanticRecord) EntityHandle.get(PrimitiveData.nid(pending)).orNull()).versions().size());

        // a specification waits the same way
        String spec = SPEC.formatted("Which of two specifications?", "1");
        ComplexConceptWrites.Written proposed = ComplexConceptWrites.write(spec, view, edit, LocalConcepts.Context.ALONE);
        assertTrue(proposed.wrote(), proposed.message());
        long specification = EntityService.get().semanticsForComponentOfPattern(PrimitiveData.nid(proposed.concept().orElseThrow()),
                IkePattern.COMPLEX_CONCEPT_PATTERN.nid()).toList().getFirst().nid();
        commit(PrimitiveData.publicId(specification));
        assertTrue(ComplexConceptWrites.write(spec, view, edit, LocalConcepts.Context.ALONE).message().contains("already in the knowledge base"));
        String different = ComplexConceptWrites.write(SPEC.formatted("Which of two specifications?", "0"), view, edit, LocalConcepts.Context.ALONE).message();
        assertTrue(held(different), different);
        String pendingSpec = SPEC.formatted("Which of two pending specifications?", "1");
        assertTrue(ComplexConceptWrites.write(pendingSpec, view, edit, LocalConcepts.Context.ALONE).wrote());
        String overPendingSpec = ComplexConceptWrites.write(SPEC.formatted("Which of two pending specifications?", "0"), view, edit,
                LocalConcepts.Context.ALONE).message();
        assertTrue(held(overPendingSpec), overPendingSpec);
    }

    @Test
    void aSymbolTiesATreesShortLabelToTheConceptAComplexConceptDefines() {
        LocalConcepts.Names names = LocalConcepts.Names.of(List.of(
                "decision: k:Inspect[Inspect the site?] @id=sym-decision\n",
                "concept: k:Decision[Site inspection decision for a first-of-a-kind device] @id=sym-decision\n"));
        assertEquals("Site inspection decision for a first-of-a-kind device", names.fqn("sym-decision", "Inspect the site?"));
        assertEquals(List.of("Inspect the site?"), names.others("sym-decision", names.fqn("sym-decision", "Inspect the site?")));
        assertEquals(List.of("clear"), LocalConcepts.Names.of(List.of("outcome: k:Clear[clear] @id=sym-clear\n"
                + "outcome: k:Usd[US dollar] @id=sym-usd\n")).bare(symbol -> false), "one word cannot stand alone");
    }
}
