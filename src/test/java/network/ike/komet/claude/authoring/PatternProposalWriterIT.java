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

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.FieldDefinitionForEntity;
import dev.ikm.tinkar.entity.PatternEntity;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import javafx.application.Platform;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Synonym;
import network.ike.komet.claude.authoring.PatternProposalWriter.Draft;
import network.ike.komet.claude.authoring.PatternProposalWriter.Field;
import network.ike.komet.claude.authoring.PatternProposalWriter.Status;
import org.eclipse.collections.api.list.ImmutableList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PatternProposalWriter} against the starter set: a proposal is written whole and
 * uncommitted, one publish commits all of it, and a discard withdraws all of it
 * ({@code ikmdev/komet-desktop#209}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatternProposalWriterIT {

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
        // Without a data store root, the search provider names its Lucene directory after the JVM UUID
        // string, whose colons are not a legal Windows path.
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, tempDirectory());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
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
        List<Field> fields = List.of(
                new Field(KernelConcept.TEXT_FOR_DESCRIPTION, KernelConcept.LANGUAGE, KernelConcept.STRING),
                new Field(KernelConcept.DESCRIPTION_TYPE, KernelConcept.LANGUAGE, KernelConcept.COMPONENT_FIELD));
        Draft written = onFx(() -> PatternProposalWriter.write(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, "Proposed record (pattern)", KernelConcept.LANGUAGE,
                KernelConcept.ENGLISH_LANGUAGE, fields, List.of(new Synonym("Proposed register", false, false)),
                "Modelled on the description pattern."));
        long pattern = written.pattern().nid();

        assertTrue(entity(pattern).uncommitted(), "nothing is committed by writing");
        assertEquals(Status.OPEN, onFx(written::status));
        // FSN + the derived preferred synonym + one acceptable synonym, each with a dialect: 6; the note: 1.
        assertEquals(7, written.written());

        PatternEntityVersion version = (PatternEntityVersion) entity(pattern).versions().getLast();
        assertEquals(KernelConcept.LANGUAGE.nid(), version.semanticMeaningNid());
        assertEquals(KernelConcept.ENGLISH_LANGUAGE.nid(), version.semanticPurposeNid());
        ImmutableList<? extends FieldDefinitionForEntity> definitions = version.fieldDefinitions();
        assertEquals(2, definitions.size());
        assertEquals(KernelConcept.TEXT_FOR_DESCRIPTION.nid(), definitions.get(0).meaningNid());
        assertEquals(KernelConcept.STRING.nid(), definitions.get(0).dataTypeNid());
        assertEquals(KernelConcept.DESCRIPTION_TYPE.nid(), definitions.get(1).meaningNid());
        assertEquals(KernelConcept.LANGUAGE.nid(), definitions.get(1).purposeNid());
        assertEquals(KernelConcept.COMPONENT_FIELD.nid(), definitions.get(1).dataTypeNid());
        assertEquals(1, definitions.get(1).indexInPattern(), "fields keep the order given");

        List<SemanticEntity<SemanticEntityVersion>> descriptions = semantics(pattern, KernelPattern.DESCRIPTION_PATTERN.nid());
        Set<String> texts = new HashSet<>();
        for (SemanticEntity<SemanticEntityVersion> description : descriptions) {
            texts.add(String.valueOf(last(description).fieldValues().get(1)));
            assertEquals(1, semantics(description.nid(), KernelPattern.US_DIALECT_PATTERN.nid()).size(),
                    "each description has its US English acceptability");
        }
        assertEquals(Set.of("Proposed record (pattern)", "Proposed record", "Proposed register"), texts,
                "the name, the preferred synonym derived from it, and the proposed synonym");
        assertEquals(1, semantics(pattern, KernelPattern.COMMENT_PATTERN.nid()).size(), "the editorial note");

        Set<Transaction> transactions = pendingTransactions(pattern);
        assertEquals(1, transactions.size(), "the pattern and its names are one transaction");
        onFx(() -> {
            transactions.iterator().next().commit();
            return null;
        });
        assertFalse(entity(pattern).uncommitted(), "one publish commits the pattern");
        descriptions.forEach(description -> assertFalse(entity(description.nid()).uncommitted(),
                "and its names with it"));
        assertEquals(Status.PUBLISHED, onFx(written::status));
        assertFalse(onFx(() -> PatternProposalWriter.discard(written)), "a published proposal is left alone");
        assertTrue(entity(pattern) instanceof PatternEntity<?>, "what was published is a pattern");
    }

    @Test
    void aDiscardedProposalIsRolledBack() {
        Draft written = onFx(() -> PatternProposalWriter.write(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, "Discarded record (pattern)", KernelConcept.LANGUAGE,
                KernelConcept.ENGLISH_LANGUAGE, List.of(), List.of(), null));
        long pattern = written.pattern().nid();
        List<SemanticEntity<SemanticEntityVersion>> descriptions = semantics(pattern, KernelPattern.DESCRIPTION_PATTERN.nid());
        assertEquals(2, descriptions.size(), "the name and the synonym derived from it");

        assertTrue(onFx(() -> PatternProposalWriter.discard(written)));
        assertFalse(entity(pattern).uncommitted(), "nothing of the proposal is left uncommitted");
        descriptions.forEach(description -> assertTrue(entity(description.nid()).canceled(),
                "the names are rolled back with it"));
        assertEquals(Status.DISCARDED, onFx(written::status));
        assertFalse(onFx(() -> PatternProposalWriter.discard(written)), "a second discard finds nothing to withdraw");
    }

    private static File tempDirectory() {
        try {
            return Files.createTempDirectory("pattern-proposal-it").toFile();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Set<Transaction> pendingTransactions(long pattern) {
        Set<Transaction> transactions = new HashSet<>();
        List<Long> shown = new java.util.ArrayList<>(List.of(pattern));
        semantics(pattern, KernelPattern.DESCRIPTION_PATTERN.nid()).forEach(description -> shown.add(description.nid()));
        for (long nid : shown) {
            for (EntityVersion version : entity(nid).versions()) {
                if (version.uncommitted()) {
                    Transaction.forStamp(version.stamp().publicId()).ifPresent(transactions::add);
                }
            }
        }
        return transactions;
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
