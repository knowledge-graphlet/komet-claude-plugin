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
package network.ike.komet.claude.stamp;

import design.knowledge.stamp.adoc.dialect.Block;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.complexconcept.ComplexConceptText;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A saved structured block writes what it states (ike-lab-documents topic
 * {@code dev-decision-document}, "Versions"): an {@code anf} block, an open block whose content
 * sits in child blocks, is edited as its text and writes its statement as a statement of the
 * block, through the ANF statement actions; a {@code complex-concept} block writes its concept as
 * Propose does, once, and then versions of its specification. One author's edits before a commit
 * rewrite the pending version; after a commit the next edit is a new version, never a second
 * statement or specification.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructuredBlockWritesIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    /** A unit the starter set holds, by its UUID, so the statement's result resolves. */
    private static final String UNIT = IkeConcept.PERFORMANCE_CIRCUMSTANCE.publicId().asUuidArray()[0].toString();
    private static final String DOCUMENT = """
            :topic-id: writes-%s
            = Writes

            A paragraph before them.

            [complex-concept]
            ----
            concept: k:SiteDecision[Site decision for a first-of-a-kind device] @id=sbw-site-decision
              expansion: focused k:InspectionDecision[Clinical site inspection decision] @id=sbw-inspection-decision
                prerequisite: k:FirstOfAKind[First-of-a-kind device] @id=sbw-first-of-a-kind
                  value = [1,1] k:Presence[presence] @id=sbw-presence
            ----

            [anf,type=performance,id=sbw-problem-found]
            --
            topic::   candidate[material problem in the site's clinical data] disp=PENDING
            result::  [1,1] k:Unit[unit] @id=%s
            method::  candidate[clinical site inspection] disp=PENDING
            --

            A paragraph after them.
            """;

    @TempDir
    Path root;

    private EditCoordinateRecord edit;
    private ViewCalculator view;

    @BeforeAll
    void setup() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
        edit = Coordinates.Edit.Default();
        view = Calculators.View.Default();
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @Test
    void anAnfBlockIsEditedAsTextAndWritesItsStatement() throws Exception {
        Path dir = repository("anf");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("writes.adoc")).anchor();
        StoredBlock block = block(anchor, Block.Kind.OPEN, "[anf");
        StructuredBlockKind kind = StructuredBlocks.kindOf(block).orElseThrow();

        // the open block's content sits in a child block, and is still edited as the block's text
        String text = save(anchor, block, "clinical site inspection", "site visit");
        assertTrue(StructuredBlocks.source(GitDocumentMaterializer.load(anchor, true).orElseThrow(), block(anchor, Block.Kind.OPEN, "[anf"))
                .content().contains("candidate[site visit]"), "the edit is the block's text");

        String first = kind.write(StructuredBlockKind.Source.of(text), block.nid(), view, edit).orElseThrow();
        assertTrue(first.startsWith("The statement sbw-problem-found: Added ANF statement"), first);
        List<Long> statements = statements(block.nid());
        assertEquals(1, statements.size());
        long statement = statements.getFirst();
        assertEquals(record(block.nid()).versions().getFirst().stamp().moduleNid(), record(statement).versions().get(0).stamp().moduleNid(),
                "a statement about a stored document's block is data of the document, in its module");

        // a second save before a commit rewrites the pending version
        text = save(anchor, block, "site visit", "site inspection visit");
        String again = kind.write(StructuredBlockKind.Source.of(text), block.nid(), view, edit).orElseThrow();
        assertTrue(again.contains("replaced in an uncommitted version"), again);
        assertEquals(List.of(statement), statements(block.nid()), "the same statement, never a second one");
        assertEquals(1, record(statement).versions().size(), "the pending version is replaced, not added to");
        Transaction.forVersion(record(statement).versions().get(0)).orElseThrow().commit();

        // after the commit, the next save is a new version of the same statement
        text = save(anchor, block, "site inspection visit", "on-site inspection");
        String after = kind.write(StructuredBlockKind.Source.of(text), block.nid(), view, edit).orElseThrow();
        assertTrue(after.contains("replaced in an uncommitted version"), after);
        assertEquals(List.of(statement), statements(block.nid()));
        assertEquals(2, record(statement).versions().size(), "a new version after the commit");

        // a statement naming a concept the store lacks writes nothing
        String unknown = kind.write(StructuredBlockKind.Source.of(text.replace(UNIT, "00000000-0000-5000-8000-000000000001")),
                block.nid(), view, edit).orElseThrow();
        assertTrue(unknown.startsWith("The statement sbw-problem-found was not written"), unknown);
        assertEquals(2, record(statement).versions().size(), "nothing written");
        DocumentEditor.cancel(anchor);
    }

    @Test
    void aComplexConceptBlockProposesOnceAndThenVersionsItsSpecification() throws Exception {
        Path dir = repository("complex");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("writes.adoc")).anchor();
        StoredBlock block = block(anchor, Block.Kind.LISTING, "[complex-concept");
        StructuredBlockKind kind = StructuredBlocks.kindOf(block).orElseThrow();

        String text = save(anchor, block, "First-of-a-kind device]", "First of its kind device]");
        String first = kind.write(StructuredBlockKind.Source.of(text), block.nid(), view, edit).orElseThrow();
        assertTrue(first.startsWith("Proposed the complex concept Site decision for a first-of-a-kind device"), first);
        assertTrue(first.contains("with 3 concepts the knowledge base lacked"), first);
        long concept = PrimitiveData.nid(dev.ikm.tinkar.common.id.PublicIds.of(ComplexConceptText.localPublicId("sbw-site-decision")));
        List<Long> specifications = specifications(concept);
        assertEquals(1, specifications.size());
        long specification = specifications.getFirst();

        String again = kind.write(StructuredBlockKind.Source.of(text), block.nid(), view, edit).orElseThrow();
        assertTrue(again.startsWith("Rewrote the specification of Site decision"), again);
        assertEquals(List.of(specification), specifications(concept), "one specification, never a second");
        assertEquals(1, record(specification).versions().size());
        Transaction.forVersion(record(specification).versions().get(0)).orElseThrow().commit();

        text = save(anchor, block, "value = [1,1]", "value = [0,1]");
        String after = kind.write(StructuredBlockKind.Source.of(text), block.nid(), view, edit).orElseThrow();
        assertTrue(after.startsWith("Wrote a new version of the specification of Site decision"), after);
        assertEquals(List.of(specification), specifications(concept));
        assertEquals(2, record(specification).versions().size(), "a new version after the commit");

        // a block that is not yet a complete specification writes nothing
        String incomplete = kind.write(StructuredBlockKind.Source.of("[complex-concept]\n----\n"
                        + "concept: k:Bare[Bare concept] @id=sbw-bare\n----"), block.nid(), view, edit).orElseThrow();
        assertTrue(incomplete.startsWith("The complex concept is not written yet"), incomplete);
        DocumentEditor.cancel(anchor);
    }

    /** Saves the block with one phrase changed, as the card's Save does, and returns the saved text. */
    private String save(PublicId anchor, StoredBlock stale, String from, String to) {
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        StoredBlock block = document.blocks().get(stale.nid());
        StructuredBlockKind.Source source = StructuredBlocks.source(document, block);
        assertTrue(source.content().contains(from), source.content());
        String text = source.withContent(source.content().replace(from, to));
        DocumentEditor.editBlock(anchor, block.nid(), text, edit);
        return text;
    }

    private static StoredBlock block(PublicId anchor, Block.Kind kind, String style) {
        return GitDocumentMaterializer.load(anchor, true).orElseThrow().blocks().values().stream()
                .filter(b -> b.kind() == kind && b.parameters().startsWith(style)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + style + " block"));
    }

    private static List<Long> statements(long block) {
        return EntityService.get().semanticsForComponentOfPattern(block, IkePattern.ANF_STATEMENT_PATTERN.nid())
                .toList().stream().map(s -> s.nid()).toList();
    }

    private static List<Long> specifications(long concept) {
        return EntityService.get().semanticsForComponentOfPattern(concept, IkePattern.COMPLEX_CONCEPT_PATTERN.nid())
                .toList().stream().map(s -> s.nid()).toList();
    }

    private static SemanticRecord record(long nid) {
        return (SemanticRecord) EntityHandle.get(nid).expectEntity();
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("writes.adoc"), DOCUMENT.formatted(name, UNIT));
        git(dir, "add", "writes.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=2026-03-01T12:00:00", "-m", "Add the document");
        return dir;
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
