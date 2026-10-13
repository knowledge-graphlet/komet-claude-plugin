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

import dev.ikm.komet.terms.KometTerm;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelTerm;
import javafx.application.Platform;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Synonym;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Written;
import network.ike.komet.claude.authoring.ProposedDefinition.Built;
import network.ike.komet.claude.koncept.ComponentText;
import network.ike.komet.claude.koncept.ConceptDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A proposal written by {@link ConceptProposalWriter}, against the starter data
 * ({@code IKE-Network/ike-issues#1292}): everything lands uncommitted in one transaction, so
 * committing that transaction — what a KL window's Publish does for versions it did not save —
 * commits the whole proposal, and discarding it rolls the whole proposal back.
 *
 * <p>The composer requires the JavaFX application thread, so the toolkit is started here; where
 * it cannot start (a headless agent), the class is skipped rather than failed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConceptProposalWriterIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

    private ViewCalculator view;

    @BeforeAll
    void setup() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Started by another test class in this JVM.
        } catch (RuntimeException | Error noToolkit) {
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit);
        }
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        view = Calculators.View.Default();
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @Test
    void theWholeProposalIsUncommittedAndOnePublishCommitsAllOfIt() {
        Built built = definition();
        Written written = onFx(() -> ConceptProposalWriter.write(view, KernelTerm.USER, KernelTerm.PRIMORDIAL_MODULE,
                KernelTerm.DEVELOPMENT_PATH, "Proposed language (qualifier value)", built.tree(),
                List.of(new Synonym("Proposed tongue", false, false)), "Modelled on English language."));
        long concept = written.concept().nid();

        assertTrue(entity(concept).uncommitted(), "nothing is committed by writing");
        // FSN + the derived preferred synonym + one acceptable synonym, each with a dialect: 6;
        // the stated definition and the note: 2.
        assertEquals(8, written.written());

        List<SemanticEntity<SemanticEntityVersion>> descriptions = semantics(concept, KernelTerm.DESCRIPTION_PATTERN.nid());
        Set<String> texts = new HashSet<>();
        for (SemanticEntity<SemanticEntityVersion> description : descriptions) {
            texts.add(String.valueOf(last(description).fieldValues().get(1)));
            List<SemanticEntity<SemanticEntityVersion>> dialects =
                    semantics(description.nid(), KernelTerm.US_DIALECT_PATTERN.nid());
            assertEquals(1, dialects.size(), "each description has its US English acceptability");
        }
        assertEquals(Set.of("Proposed language (qualifier value)", "Proposed language", "Proposed tongue"), texts,
                "the name, the preferred synonym derived from it, and the proposed synonym");
        assertTrue(preferredTexts(descriptions).contains("Proposed language"),
                "with no preferred synonym given, the name without its tag is preferred");
        assertFalse(preferredTexts(descriptions).contains("Proposed tongue"));

        DiTreeEntity stored = (DiTreeEntity) last(semantics(concept, KernelTerm.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid())
                .getFirst()).fieldValues().getFirst();
        assertArrayEquals(new long[]{KernelTerm.LANGUAGE.nid()},
                ConceptDefinition.of(stored).orElseThrow().supertypes(), "the stated definition is the block's");

        String note = String.valueOf(last(semantics(concept, KernelTerm.COMMENT_PATTERN.nid()).getFirst()).fieldValues()
                .stream().filter(String.class::isInstance).findFirst().orElseThrow());
        assertTrue(note.startsWith("Modelled on English language."), note);

        // Publish, as a KL window does for versions saved by another composer: commit the
        // transaction of the concept's stamp. One transaction holds it all, dialects included.
        Set<Transaction> transactions = new HashSet<>();
        for (EntityVersion version : entity(concept).versions()) {
            Transaction.forStamp(version.stamp().publicId()).ifPresent(transactions::add);
        }
        assertEquals(1, transactions.size(), "the proposal is one transaction");
        onFx(() -> {
            transactions.forEach(Transaction::commit);
            return null;
        });

        assertFalse(entity(concept).uncommitted(), "the concept is committed");
        for (SemanticEntity<SemanticEntityVersion> description : descriptions) {
            assertFalse(entity(description.nid()).uncommitted());
            assertFalse(entity(semantics(description.nid(), KernelTerm.US_DIALECT_PATTERN.nid()).getFirst().nid()).uncommitted(),
                    "a dialect the window does not show is committed with the rest");
        }
    }

    @Test
    void aDiscardedProposalIsRolledBackAndAPublishedOneIsLeftAlone() {
        Written written = onFx(() -> ConceptProposalWriter.write(view, KernelTerm.USER, KernelTerm.PRIMORDIAL_MODULE,
                KernelTerm.DEVELOPMENT_PATH, "Discarded language (qualifier value)", definition().tree(), List.of(), null));

        assertTrue(onFx(() -> ConceptProposalWriter.discard(written)));
        assertFalse(entity(written.concept().nid()).uncommitted(), "nothing of the proposal is left uncommitted");
        assertFalse(onFx(() -> ConceptProposalWriter.discard(written)), "a second discard finds nothing to withdraw");
    }

    @Test
    void aSemanticTagIsDroppedFromTheDerivedSynonym() {
        assertEquals("Eosinophilic esophagitis", ConceptProposalWriter.withoutSemanticTag("Eosinophilic esophagitis (disorder)"));
        assertEquals("No tag here", ConceptProposalWriter.withoutSemanticTag("No tag here"));
    }

    private Built definition() {
        return ProposedDefinition.build(ProposedDefinition.parse(
                "proposed: x\nnecessary\n  " + ComponentText.badge(view, KernelTerm.LANGUAGE.nid()).orElseThrow() + "\n"));
    }

    private Set<String> preferredTexts(List<SemanticEntity<SemanticEntityVersion>> descriptions) {
        Set<String> preferred = new HashSet<>();
        for (SemanticEntity<SemanticEntityVersion> description : descriptions) {
            Object acceptability = last(semantics(description.nid(), KernelTerm.US_DIALECT_PATTERN.nid()).getFirst())
                    .fieldValues().getFirst();
            if (acceptability instanceof EntityFacade facade && facade.nid() == KernelTerm.PREFERRED.nid()) {
                preferred.add(String.valueOf(last(description).fieldValues().get(1)));
            }
            assertTrue(acceptability instanceof EntityFacade facade
                    && (facade.nid() == KernelTerm.PREFERRED.nid() || facade.nid() == KometTerm.ACCEPTABLE.nid()));
        }
        return preferred;
    }

    private static Entity<?> entity(long nid) {
        return EntityHandle.get(nid).expectEntity();
    }

    private static List<SemanticEntity<SemanticEntityVersion>> semantics(long component, long pattern) {
        return EntityService.get().semanticsForComponentOfPattern(component, pattern).toList();
    }

    private static SemanticEntityVersion last(SemanticEntity<SemanticEntityVersion> semantic) {
        return semantic.versions().getLast();
    }

    private static <T> T onFx(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e.getCause() == null ? e : e.getCause());
        }
    }
}
