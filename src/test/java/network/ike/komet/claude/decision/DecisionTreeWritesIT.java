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

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.SemanticVersionRecord;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.claude.stamp.LocalConcepts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A decision tree block written as versions of its tree semantic: the first version with the
 * concepts its symbols name, a second version of the same semantic when the block changes, each
 * vertex keeping its identity, and nothing written for a block with a finding.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DecisionTreeWritesIT {
    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** A choice whose waiting course ends in a rupture now and then. */
    private static final String CHOICE = """
            decision: k:Treat[Treat now?] @id=dtw-treat
              alternative: k:DoTreat[treat] @id=dtw-do-treat
                money = [10000,10000] k:Usd[US dollar] @id=dtw-usd
                outcome: k:Safe[safe] @id=dtw-safe
              alternative: k:Wait[wait] @id=dtw-wait
                chance: k:Ruptures[Rupture?] @id=dtw-ruptures
                  event: k:Rupture[rupture] @id=dtw-rupture
                    p = [0.1,0.1]
                    outcome: k:Ruptured[ruptured] @id=dtw-ruptured
                      money = [200000,200000] k:Usd[US dollar] @id=dtw-usd
                  residual: k:Intact[intact] @id=dtw-intact
                    outcome: k:Fine[fine] @id=dtw-fine
            """;

    private EditCoordinateRecord edit;
    private ViewCalculator view;

    @BeforeAll
    void startStore() {
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
    void stopStore() {
        PrimitiveData.stop();
    }

    private boolean seen(PublicId component) {
        try {
            return view.latest(PrimitiveData.nid(component)).isPresent();
        } catch (IllegalStateException noVersions) {
            return false;
        }
    }

    private static SemanticRecord record(PublicId semantic) {
        return (SemanticRecord) EntityHandle.get(PrimitiveData.nid(semantic)).orNull();
    }

    /** The identity of the vertex whose concept a symbol names, in one version of the tree. */
    private static UUID vertexOf(SemanticVersionRecord version, String name) {
        DecisionTree tree = new DecisionTree((DiTreeEntity) version.fieldValues().get(0), DecisionTerms.fromBindings());
        long concept = PrimitiveData.nid(PublicIds.of(LocalConcepts.uuid(name)));
        for (DecisionElement node : tree.nodes()) {
            if (node.concept().orElse(0) == concept) {
                return tree.vertex(node.vertexIndex()).vertexId().asUuid();
            }
        }
        throw new AssertionError("no vertex names " + name);
    }

    @Test
    void aBlockWritesAFirstVersionThenANewVersionOfTheSameTreeEachVertexKeepingItsIdentity() {
        DecisionTreeWrites.Written first = DecisionTreeWrites.write("decision-tree id=dtw-tree", CHOICE, view, edit);
        assertTrue(first.tree().isPresent(), first.message());
        PublicId semantic = first.tree().get();
        assertEquals(DecisionTreeWrites.treeId(PublicIds.of(LocalConcepts.uuid("Treat now?"))), semantic, "the semantic's identity derives from its root concept");
        SemanticRecord written = record(semantic);
        assertEquals(IkePattern.DECISION_TREE_PATTERN.nid(), written.patternNid());
        assertEquals(PrimitiveData.nid(PublicIds.of(LocalConcepts.uuid("Treat now?"))), written.referencedComponentNid(),
                "the tree is about its root's concept");
        assertTrue(EntityHandle.get(PrimitiveData.nid(PublicIds.of(LocalConcepts.uuid("rupture")))).orNull() != null,
                "each name became a concept");
        assertEquals(1, written.versions().size());
        assertTrue(first.message().contains("uncommitted"), first.message());

        // a second edit before a commit replaces the pending version: one author's edits are one change
        DecisionTreeWrites.Written again = DecisionTreeWrites.write("decision-tree id=dtw-tree",
                CHOICE.replace("p = [0.1,0.1]", "p = [0.15,0.15]"), view, edit,
                new LocalConcepts.Context(false, LocalConcepts.Names.NONE, Optional.of(CHOICE)));
        assertEquals(Optional.of(semantic), again.tree(), again.message());
        assertEquals(1, record(semantic).versions().size(), "the pending version is replaced, not added to");
        assertTrue(again.message().startsWith("Rewrote the tree of Treat now? in its uncommitted version"), again.message());
        SemanticVersionRecord pending = record(semantic).versions().get(0);
        Transaction.forVersion(pending).orElseThrow().commit();

        // after the commit, the next edit is a new version of the same tree
        DecisionTreeWrites.Written second = DecisionTreeWrites.write("decision-tree id=dtw-tree",
                CHOICE.replace("p = [0.1,0.1]", "p = [0.2,0.2]"), view, edit,
                new LocalConcepts.Context(false, LocalConcepts.Names.NONE, Optional.of(CHOICE.replace("p = [0.1,0.1]", "p = [0.15,0.15]"))));
        assertEquals(Optional.of(semantic), second.tree(), second.message());
        assertTrue(second.message().startsWith("Wrote a new version of the tree of Treat now?"), second.message());
        SemanticRecord rewritten = record(semantic);
        assertEquals(2, rewritten.versions().size(), "a new version of the same tree, never a second tree");
        SemanticVersionRecord was = rewritten.versions().get(0);
        SemanticVersionRecord now = rewritten.versions().get(1);
        assertNotEquals(was.fieldValues().get(0), now.fieldValues().get(0));
        assertEquals(vertexOf(was, "rupture"), vertexOf(now, "rupture"), "the event keeps its identity");
        assertEquals(vertexOf(was, "wait"), vertexOf(now, "wait"));
    }

    @Test
    void theStoredTreeRollsBackAsTheTextDoes() throws Exception {
        String text;
        try (java.io.InputStream in = DecisionTreeWritesIT.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String body = text.substring(text.indexOf("----\n") + 5, text.lastIndexOf("----"));
        DecisionTreeWrites.Written written = DecisionTreeWrites.write("decision-tree id=dtw-scan", body, view, edit);
        assertTrue(written.tree().isPresent(), written.message());
        SemanticVersionRecord version = record(written.tree().get()).versions().get(0);
        DecisionTree stored = new DecisionTree((DiTreeEntity) version.fieldValues().get(0), DecisionTerms.fromBindings());
        DecisionTree parsed = DecisionTreeText.parse(body, DecisionTerms.fromBindings(),
                network.ike.komet.claude.block.BlockIdentities.storeFree()).tree().orElseThrow();
        Rollback fromStore = Rollback.of(stored, BlockSweep.coordinate(stored, 100_000));
        Rollback fromText = Rollback.of(parsed, BlockSweep.coordinate(parsed, 100_000));
        assertEquals(fromText.value(parsed.rootIndex()), fromStore.value(stored.rootIndex()),
                "references stored as components and numbers stored exactly roll back to the same value");
    }

    @Test
    void aNewConceptIsWrittenAsAProposedConceptIsWithAStatedDefinitionAndPublishesWithItsTree() {
        DecisionTreeWrites.Written written = DecisionTreeWrites.write("decision-tree id=dtw-defined",
                CHOICE.replace("[Treat now?]", "[Treat, defined?]").replace("[rupture]", "[rupture, defined]"), view, edit);
        assertTrue(written.tree().isPresent(), written.message());
        long concept = PrimitiveData.nid(PublicIds.of(LocalConcepts.uuid("rupture, defined")));

        var definitions = dev.ikm.tinkar.entity.EntityService.get().semanticsForComponentOfPattern(concept,
                dev.ikm.tinkar.terms.KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid()).toList();
        assertEquals(1, definitions.size(), "a new concept has one stated definition");
        var names = dev.ikm.tinkar.entity.EntityService.get().semanticsForComponentOfPattern(concept,
                dev.ikm.tinkar.terms.KernelPattern.DESCRIPTION_PATTERN.nid()).toList();
        assertEquals(2, names.size(), "a fully qualified name and a regular name");
        assertEquals(dev.ikm.komet.framework.controls.KonceptStatus.PRIMITIVE,
                dev.ikm.komet.framework.controls.KonceptBadge.computeStatus(concept, view,
                        dev.ikm.tinkar.coordinate.logic.PremiseType.STATED),
                "stated but not yet reasoned, it is primitive, not a root");

        var definition = definitions.getFirst().versions().getLast();
        var tree = record(written.tree().get()).versions().getLast();
        assertEquals(Transaction.forVersion(definition).orElseThrow(), Transaction.forVersion(tree).orElseThrow(),
                "the concept and the tree that names it publish together");
    }

    @Test
    void aBlockThatWritesAgainAddsNoSecondDefinition() {
        String block = CHOICE.replace("[Treat now?]", "[Treat, once?]").replace("[rupture]", "[rupture, once]");
        assertTrue(DecisionTreeWrites.write("decision-tree id=dtw-once", block, view, edit).tree().isPresent());
        DecisionTreeWrites.write("decision-tree id=dtw-once", block, view, edit);

        long concept = PrimitiveData.nid(PublicIds.of(LocalConcepts.uuid("rupture, once")));
        assertEquals(1, dev.ikm.tinkar.entity.EntityService.get().semanticsForComponentOfPattern(concept,
                dev.ikm.tinkar.terms.KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid()).count());
    }

    @Test
    void onlyAConceptWithNoStatedDefinitionReadsAsUndefined() {
        PublicId bare = PublicIds.of(LocalConcepts.uuid("Named but never defined"));
        dev.ikm.tinkar.entity.transaction.StampedWriter writer = dev.ikm.tinkar.entity.transaction.StampedWriter.open(
                "A concept with no definition", dev.ikm.tinkar.terms.State.ACTIVE,
                dev.ikm.tinkar.terms.ConceptFacade.make(edit.getAuthorNidForChanges()),
                dev.ikm.tinkar.terms.ConceptFacade.make(edit.getDefaultModuleNid()),
                dev.ikm.tinkar.terms.ConceptFacade.make(edit.getDefaultPathNid()));
        long nid = writer.concept(bare);
        writer.fullyQualifiedName(bare, "Named but never defined");

        assertEquals(dev.ikm.komet.framework.controls.KonceptStatus.UNDEFINED,
                dev.ikm.komet.framework.controls.KonceptBadge.computeStatus(nid, view,
                        dev.ikm.tinkar.coordinate.logic.PremiseType.STATED));
        assertEquals(dev.ikm.komet.framework.controls.KonceptStatus.NONE,
                dev.ikm.komet.framework.controls.KonceptBadge.computeStatus(
                        dev.ikm.tinkar.terms.KernelPattern.DESCRIPTION_PATTERN.nid(), view,
                        dev.ikm.tinkar.coordinate.logic.PremiseType.STATED),
                "a pattern is not an undefined concept");
        writer.cancel();
    }

    @Test
    void aConceptWhoseOnlyVersionWasCancelledIsWrittenAgain() {
        PublicId cancelled = PublicIds.of(LocalConcepts.uuid("Written once and cancelled"));
        dev.ikm.tinkar.entity.transaction.StampedWriter writer = dev.ikm.tinkar.entity.transaction.StampedWriter.open(
                "A write that failed", dev.ikm.tinkar.terms.State.ACTIVE,
                dev.ikm.tinkar.terms.ConceptFacade.make(edit.getAuthorNidForChanges()),
                dev.ikm.tinkar.terms.ConceptFacade.make(edit.getDefaultModuleNid()),
                dev.ikm.tinkar.terms.ConceptFacade.make(edit.getDefaultPathNid()));
        writer.concept(cancelled);
        writer.cancel();
        assertTrue(!seen(cancelled), "the cancelled concept has no version to see");

        DecisionTreeWrites.Written written = DecisionTreeWrites.write("decision-tree id=dtw-again",
                CHOICE.replace("[Treat now?]", "[Treat again?]").replace("k:Safe[safe]", "k:Safe[Written once and cancelled]"), view, edit);
        assertTrue(written.tree().isPresent(), written.message());
        assertTrue(seen(cancelled), "the concept is written again");
    }

    @Test
    void aBlockWithAFindingWritesNothing() {
        DecisionTreeWrites.Written refused = DecisionTreeWrites.write("decision-tree id=dtw-broken",
                CHOICE.replace("[Treat now?]", "[Treat, though broken?]").replace("    p = [0.1,0.1]\n", ""), view, edit);
        assertTrue(refused.tree().isEmpty(), refused.message());
        assertTrue(refused.message().startsWith("The tree was not written"), refused.message());
        assertTrue(!PrimitiveData.get().hasPublicId(LocalConcepts.derived(PublicIds.of(LocalConcepts.uuid("Treat, though broken?")), "decision-tree")),
                "nothing written for a block with a finding");
    }

    @Test
    void theBlocksIdIsReadFromItsInfo() {
        assertEquals(Optional.of("rda-site"), DecisionTreeWrites.blockId("decision-tree cohort=100 id=rda-site"));
        assertEquals(Optional.empty(), DecisionTreeWrites.blockId("decision-tree cohort=100"));
    }
}
