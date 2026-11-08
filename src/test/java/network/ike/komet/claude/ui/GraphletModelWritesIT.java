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
package network.ike.komet.claude.ui;

import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.PublicIdentifierRecord;
import dev.ikm.tinkar.entity.RecordListBuilder;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.SemanticRecordBuilder;
import dev.ikm.tinkar.entity.SemanticVersionRecord;
import dev.ikm.tinkar.entity.SemanticVersionRecordBuilder;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.claude.anf.AddAnfStatement;
import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.anf.AnfProjection;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.document.LocalConcepts;
import network.ike.komet.claude.decision.BlockSweep;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionElement;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeEdits;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.decision.DecisionTreeWrites;
import network.ike.komet.claude.decision.ExternalQuantities;
import network.ike.komet.claude.decision.LinkedTrees;
import network.ike.komet.claude.decision.Rollback;
import network.ike.komet.claude.decision.ViewIdentities;
import network.ike.komet.claude.tools.GraphTools;
import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The site inspection model written as a version of its graph in the document's module
 * ({@code dev-decision-point-knowledge}, "The value a tree runs at, and the model that holds it",
 * settled with KEC 2026-11-06): its six statements are added to anf blocks of the document, the
 * model's parameters read them, the stored parameter holds the statement under the Statement key
 * with its value and its scale and names no concept, the stored model rolls back as the text
 * does, and a value kept from the tornado is a new version of the same semantic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphletModelWritesIT {
    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** An anf block of the corpus: its attribute line and the lines between its delimiters. */
    private static final Pattern ANF_BLOCK = Pattern.compile("(?m)^\\[anf,([^\\]]*)]\\n--\\n(.*?)\\n--\\n", Pattern.DOTALL);

    private EditCoordinateRecord edit;
    private ViewCalculator view;
    private DecisionTerms terms;

    /** The module the document's blocks are written in: never the edit's default. */
    private long module;
    private long document;
    private String modelInfo;
    private String modelBody;
    private final Map<String, AnfStatement> statements = new LinkedHashMap<>();
    private final Map<String, Long> blocks = new LinkedHashMap<>();

    @BeforeAll
    void startStore() throws IOException {
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
        terms = DecisionTerms.fromBindings();

        module = concept("The site inspection document's module");
        document = concept("The site inspection document");
        assertNotEquals(edit.getDefaultModuleNid(), module);

        String corpus;
        try (InputStream in = GraphletModelWritesIT.class.getResourceAsStream("/decision/bimo-site-model.adoc")) {
            corpus = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher anf = ANF_BLOCK.matcher(corpus);
        while (anf.find()) {
            String info = "anf," + anf.group(1);
            String name = AnfBlockRenderer.idOf(info).orElseThrow();
            long block = block(name);
            blocks.put(name, block);
            AnfStatement statement = AnfAdoc.parse(AnfBlockRenderer.asAdoc(info, anf.group(2) + "\n"), id -> GraphTools.resolveConcept(id, view));
            statements.put(name, statement);
            // the anf block's save: the statement as a semantic about the block, in the block's module
            KometActionResult added = new AddAnfStatement().perform(AddAnfStatement.input(block, AnfProjection.toTree(statement)), view, edit);
            assertTrue(added.completed(), added.message());
        }
        assertEquals(6, statements.size(), "the corpus states the six site statements");
        int fence = corpus.indexOf("[graphlet-model");
        modelInfo = corpus.substring(fence + 1, corpus.indexOf("]", fence)).replace(",", "");
        modelBody = corpus.substring(corpus.indexOf("----\n", fence) + 5, corpus.lastIndexOf("----"));
    }

    @AfterAll
    void stopStore() {
        PrimitiveData.stop();
    }

    /** A committed concept with a name, for the document and its module. */
    private long concept(String name) {
        PublicId id = PublicIds.of(LocalConcepts.uuid(name));
        StampedWriter writer = StampedWriter.open(name, State.ACTIVE, ConceptFacade.make(edit.getAuthorNidForChanges()),
                ConceptFacade.make(edit.getDefaultModuleNid()), ConceptFacade.make(edit.getDefaultPathNid()));
        long nid = writer.concept(id);
        writer.fullyQualifiedName(id, name);
        writer.commit();
        return nid;
    }

    /** A committed document block in the document's module, as a block of a stored document is. */
    private long block(String name) {
        Transaction transaction = Transaction.make("A block for " + name);
        StampEntity<?> stamp = transaction.getStamp(State.ACTIVE, edit.getAuthorNidForChanges(), module, edit.getDefaultPathNid());
        long nid = EntityService.get().nidForSemantic(IkePattern.DOCUMENT_BLOCK_PATTERN.publicId(), PublicIds.newRandom());
        PublicIdentifierRecord identifier = PublicIdentifierRecord.make(PrimitiveData.publicId(nid));
        RecordListBuilder<SemanticVersionRecord> versions = RecordListBuilder.make();
        SemanticRecord semantic = SemanticRecordBuilder.builder()
                .nid(nid)
                .mostSignificantBits(identifier.mostSignificantBits())
                .leastSignificantBits(identifier.leastSignificantBits())
                .additionalUuidLongs(identifier.additionalUuidLongs())
                .patternNid(IkePattern.DOCUMENT_BLOCK_PATTERN.nid())
                .referencedComponentNid(document)
                .versions(versions)
                .build();
        versions.add(SemanticVersionRecordBuilder.builder().chronology(semantic).stampNid(stamp.nid())
                .fieldValues(Lists.immutable.empty()).build());
        SemanticRecord built = SemanticRecordBuilder.builder(semantic).versions(versions.toImmutable()).build();
        transaction.addComponent(built);
        EntityService.get().putEntity(built);
        transaction.commit();
        return nid;
    }

    private DecisionTreeWrites.Document siteDocument() {
        return ModelDocument.of(statements, blocks, OptionalLong.of(module), view);
    }

    private static SemanticRecord record(PublicId semantic) {
        return (SemanticRecord) EntityHandle.get(PrimitiveData.nid(semantic)).orNull();
    }

    private DecisionTree storedTree(SemanticVersionRecord version) {
        return new DecisionTree((DiTreeEntity) version.fieldValues().get(0), terms);
    }

    /** The statement the anf block of a name holds. */
    private long statementNid(String name) {
        return PrimitiveData.nid(ModelDocument.statementOf(blocks.get(name), view));
    }

    /** The parameter of a stored model that reads a statement. */
    private static DecisionElement.Parameter parameterReading(DecisionTree tree, long statement) {
        for (DecisionElement.Parameter parameter : tree.nodesOfType(DecisionElement.Parameter.class)) {
            if (parameter.statement().orElse(0) == statement) {
                return parameter;
            }
        }
        throw new AssertionError("no parameter reads statement " + statement);
    }

    private static double rootValue(DecisionTree tree) {
        return Rollback.of(tree, BlockSweep.coordinate(tree, 100_000), LinkedTrees.none()).rootValue().orElseThrow().midpoint();
    }

    @Test
    void theModelIsWrittenInTheDocumentsModuleWithItsParametersReadingTheStatementsAndANewVersionPerValue() {
        DecisionTreeWrites.Written first = DecisionTreeWrites.write(modelInfo, modelBody, view, edit,
                new LocalConcepts.Context(false, LocalConcepts.Names.NONE, Optional.empty()), siteDocument());
        assertTrue(first.tree().isPresent(), first.message());
        assertTrue(first.message().startsWith("Wrote the model of Inspect the site? as its first version"), first.message());
        PublicId semantic = first.tree().get();
        SemanticRecord written = record(semantic);
        assertEquals(IkePattern.DECISION_TREE_PATTERN.nid(), written.patternNid());
        assertEquals(1, written.versions().size());
        SemanticVersionRecord version = written.versions().get(0);
        assertEquals(module, version.stamp().moduleNid(), "the model is written in the document's module, not the edit's default");

        // each parameter holds its statement, its value and its scale, and names no concept
        DecisionTree stored = storedTree(version);
        long prior = statementNid("est-material-problem");
        DecisionElement.Parameter priorParameter = parameterReading(stored, prior);
        assertTrue(priorParameter.concept().isEmpty(), "a statement's parameter names no concept");
        assertEquals(OptionalLong.of(prior), priorParameter.quantity(), "the parameter's quantity is the statement it reads");
        assertEquals(Bounds.point(0.15), priorParameter.measure().bounds(), "the value the tree runs at");
        assertEquals(terms.shareAtNode(), priorParameter.measure().semanticNid(), "a share is read as the share at a node");
        DecisionElement.Parameter costParameter = parameterReading(stored, statementNid("est-inspection-cost"));
        assertEquals(Bounds.point(60_000), costParameter.measure().bounds());
        assertEquals(PrimitiveData.nid(LocalConcepts.identity("US dollar", view)), costParameter.measure().semanticNid(),
                "a candidate scale is the concept its label derives, as a bare tree's would be");
        assertEquals(6, stored.nodesOfType(DecisionElement.Parameter.class).size());
        for (DecisionElement.Parameter parameter : stored.nodesOfType(DecisionElement.Parameter.class)) {
            assertTrue(parameter.statement().isPresent(), "every parameter of the site model reads a statement");
        }
        assertFalse(PrimitiveData.get().hasPublicId(PublicIds.of(LocalConcepts.uuid(
                "material problem in the site's clinical data among Site inspection decision for a first-of-a-kind submission with a conduct concern from an inexperienced applicant"))),
                "no concept is minted for a statement");

        // a measure that reads the parameter is derived from the same statement
        boolean derived = false;
        for (DecisionElement element : stored.nodes()) {
            if (element instanceof DecisionElement.Probability p && p.measure().derived()
                    && p.measure().derivedFrom().orElse(0) == prior) {
                derived = true;
            }
        }
        assertTrue(derived, "p = from anf:est-material-problem reads the prior's statement");

        // the stored model rolls back as the text does
        BlockIdentities ids = new BlockIdentities(new ViewIdentities(view));
        DecisionTree parsed = DecisionTreeText.parse(modelBody, terms, ids, ExternalQuantities.none(),
                siteDocument().measures().apply(ids)).tree().orElseThrow();
        assertEquals(rootValue(parsed), rootValue(stored), 0.5);
        assertEquals(250_625, rootValue(stored), 0.5);

        // "Use this value" at 30%: the restated text, written after a commit, is a new version of the same model
        Transaction.forVersion(version).orElseThrow().commit();
        String restated = DecisionTreeEdits.restate(modelBody, 2, Bounds.point(0.3)).orElseThrow();
        assertTrue(restated.contains("parameter = [0.3,0.3] anf:est-material-problem"), restated);
        DecisionTreeWrites.Written second = DecisionTreeWrites.write(modelInfo, restated, view, edit,
                new LocalConcepts.Context(false, LocalConcepts.Names.NONE, Optional.of(modelBody)), siteDocument());
        assertEquals(Optional.of(semantic), second.tree(), second.message());
        assertTrue(second.message().startsWith("Wrote a new version of the model of Inspect the site?"), second.message());
        SemanticRecord rewritten = record(semantic);
        assertEquals(2, rewritten.versions().size(), "a new version of the same model, never a second one");
        DecisionTree now = storedTree(rewritten.versions().get(1));
        DecisionElement.Parameter priorNow = parameterReading(now, prior);
        assertEquals(Bounds.point(0.3), priorNow.measure().bounds());
        UUID was = stored.vertex(priorParameter.vertexIndex()).vertexId().asUuid();
        assertEquals(was, now.vertex(priorNow.vertexIndex()).vertexId().asUuid(), "the parameter keeps its identity");
        assertEquals(428_750, rootValue(now), 0.5, "the stored model runs at the kept value");
    }

    @Test
    void aModelThatReadsAStatementTheDocumentLacksIsHeld() {
        DecisionTreeWrites.Written held = DecisionTreeWrites.write("graphlet-model id=gmw-missing",
                modelBody.replace("[Inspect the site?]", "[Inspect the other site?]").replace("anf:est-material-problem", "anf:est-missing"),
                view, edit, LocalConcepts.Context.ALONE, siteDocument());
        assertTrue(held.tree().isEmpty(), held.message());
        assertTrue(held.message().contains("anf:est-missing"), held.message());
        assertTrue(held.message().startsWith("The model was not written"), held.message());
    }
}
