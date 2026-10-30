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

import dev.ikm.tinkar.common.id.LongIdList;
import dev.ikm.tinkar.common.id.LongIdSet;
import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.PatternFacade;
import javafx.application.Platform;
import network.ike.komet.claude.authoring.PatternProposalWriter.Field;
import network.ike.komet.claude.authoring.SemanticProposalWriter.Draft;
import network.ike.komet.claude.authoring.SemanticProposalWriter.Status;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SemanticProposalWriter} against the starter set: a semantic of a pattern with a field of
 * every kind the assistant can value is written whole and uncommitted, one publish commits all of
 * it, a discard withdraws all of it, and a semantic can sit on another semantic
 * ({@code ikmdev/komet-desktop#210}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticProposalWriterIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

    private ViewCalculator view;

    /** A published pattern with a string, a component, a component set, an integer, a float, a boolean and a component list. */
    private PatternFacade pattern;

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
        pattern = publishedPattern();
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @Test
    void theWholeProposalIsUncommittedAndOnePublishCommitsAllOfIt() {
        EntityFacade device = KernelConcept.ENGLISH_LANGUAGE;
        List<Object> values = List.of(
                "Waived",
                EntityProxy.Concept.make(KernelConcept.LANGUAGE.nid()),
                LongIds.set.of(KernelConcept.USER.nid(), KernelConcept.DEVELOPMENT_PATH.nid()),
                403,
                1.5F,
                Boolean.TRUE,
                LongIds.list.of(KernelConcept.PRIMORDIAL_MODULE.nid(), KernelConcept.USER.nid()));
        Draft written = onFx(() -> SemanticProposalWriter.write(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, device, pattern, values, "Values from the device's labelling."));
        long semantic = written.semantic().nid();

        assertTrue(entity(semantic).uncommitted(), "nothing is committed by writing");
        assertEquals(Status.OPEN, onFx(written::status));
        assertEquals(1, written.written(), "the editorial note");

        SemanticEntityVersion version = (SemanticEntityVersion) entity(semantic).versions().getLast();
        assertEquals(pattern.nid(), version.patternNid());
        assertEquals(device.nid(), version.referencedComponentNid());
        ImmutableList<Object> fields = version.fieldValues();
        assertEquals(7, fields.size());
        assertEquals("Waived", fields.get(0));
        assertEquals(KernelConcept.LANGUAGE.nid(), ((EntityFacade) fields.get(1)).nid());
        LongIdSet set = assertInstanceOf(LongIdSet.class, fields.get(2));
        assertTrue(set.contains(KernelConcept.USER.nid()) && set.contains(KernelConcept.DEVELOPMENT_PATH.nid()));
        assertEquals(403, fields.get(3));
        assertEquals(1.5F, fields.get(4));
        assertEquals(Boolean.TRUE, fields.get(5));
        LongIdList list = assertInstanceOf(LongIdList.class, fields.get(6));
        assertEquals(KernelConcept.PRIMORDIAL_MODULE.nid(), list.get(0), "a list keeps its order");
        assertEquals(KernelConcept.USER.nid(), list.get(1));
        assertEquals(1, semantics(semantic, KernelPattern.COMMENT_PATTERN.nid()).size(), "the editorial note");

        Set<Transaction> transactions = pendingTransactions(semantic);
        assertEquals(1, transactions.size(), "the semantic and its note are one transaction");
        onFx(() -> {
            transactions.iterator().next().commit();
            return null;
        });
        assertFalse(entity(semantic).uncommitted(), "one publish commits the semantic");
        assertFalse(entity(semantics(semantic, KernelPattern.COMMENT_PATTERN.nid()).getFirst().nid()).uncommitted(),
                "and its note with it");
        assertEquals(Status.PUBLISHED, onFx(written::status));
        assertFalse(onFx(() -> SemanticProposalWriter.discard(written)), "a published proposal is left alone");
    }

    @Test
    void aSemanticCanSitOnAnotherSemantic() {
        Draft onConcept = onFx(() -> SemanticProposalWriter.write(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, KernelConcept.LANGUAGE, pattern, List.of(
                        "Test performed", EntityProxy.Concept.make(KernelConcept.USER.nid()), LongIds.set.empty(),
                        0, 0F, Boolean.FALSE, LongIds.list.empty()), null));
        assertEquals(0, onConcept.written(), "no note, nothing beside the semantic");
        Draft onSemantic = onFx(() -> SemanticProposalWriter.write(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, onConcept.semantic(), KernelPattern.COMMENT_PATTERN,
                List.of("Detected"), null));
        SemanticEntityVersion version = (SemanticEntityVersion) entity(onSemantic.semantic().nid()).versions().getLast();
        assertEquals(onConcept.semantic().nid(), version.referencedComponentNid(), "the reference component is the semantic");
        assertEquals("Detected", version.fieldValues().get(0));
        assertTrue(onFx(() -> SemanticProposalWriter.discard(onSemantic)));
        assertTrue(onFx(() -> SemanticProposalWriter.discard(onConcept)));
    }

    @Test
    void aDiscardedProposalIsRolledBack() {
        Draft written = onFx(() -> SemanticProposalWriter.write(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, KernelConcept.LANGUAGE, pattern, List.of(
                        "Discarded", EntityProxy.Concept.make(KernelConcept.USER.nid()), LongIds.set.empty(),
                        0, 0F, Boolean.FALSE, LongIds.list.empty()), "A note that goes with it."));
        long semantic = written.semantic().nid();
        List<SemanticEntity<SemanticEntityVersion>> notes = semantics(semantic, KernelPattern.COMMENT_PATTERN.nid());
        assertEquals(1, notes.size());

        assertTrue(onFx(() -> SemanticProposalWriter.discard(written)));
        assertFalse(entity(semantic).uncommitted(), "nothing of the proposal is left uncommitted");
        assertTrue(entity(semantic).canceled(), "the semantic is rolled back");
        assertTrue(entity(notes.getFirst().nid()).canceled(), "the note is rolled back with it");
        assertEquals(Status.DISCARDED, onFx(written::status));
        assertFalse(onFx(() -> SemanticProposalWriter.discard(written)), "a second discard finds nothing to withdraw");
    }

    @Test
    void aValueCountThatDoesNotMatchThePatternIsRefused() {
        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> onFxUnwrapped(() -> SemanticProposalWriter.write(view, KernelConcept.USER,
                        KernelConcept.PRIMORDIAL_MODULE, KernelConcept.DEVELOPMENT_PATH, KernelConcept.LANGUAGE, pattern,
                        List.of("only one"), null)));
        assertTrue(refused.getMessage().contains("7 field(s)"), refused.getMessage());
    }

    /** A pattern with one field of each kind the assistant values, proposed and published. */
    private PatternFacade publishedPattern() {
        List<Field> fields = List.of(
                new Field(KernelConcept.TEXT_FOR_DESCRIPTION, KernelConcept.LANGUAGE, KernelConcept.STRING),
                new Field(KernelConcept.DESCRIPTION_TYPE, KernelConcept.LANGUAGE, KernelConcept.COMPONENT_FIELD),
                new Field(KernelConcept.PRIMORDIAL_MODULE, KernelConcept.LANGUAGE, KernelConcept.COMPONENT_ID_SET_FIELD),
                new Field(KernelConcept.INTEGER_FIELD, KernelConcept.LANGUAGE, KernelConcept.INTEGER_FIELD),
                new Field(KernelConcept.FLOAT_FIELD, KernelConcept.LANGUAGE, KernelConcept.FLOAT_FIELD),
                new Field(KernelConcept.BOOLEAN_FIELD, KernelConcept.LANGUAGE, KernelConcept.BOOLEAN_FIELD),
                new Field(KernelConcept.DEVELOPMENT_PATH, KernelConcept.LANGUAGE, KernelConcept.COMPONENT_ID_LIST_FIELD));
        PatternProposalWriter.Draft proposed = onFx(() -> PatternProposalWriter.write(view, KernelConcept.USER,
                KernelConcept.PRIMORDIAL_MODULE, KernelConcept.DEVELOPMENT_PATH, "Every kind of field (pattern)",
                KernelConcept.LANGUAGE, KernelConcept.ENGLISH_LANGUAGE, fields, List.of(), null));
        long nid = proposed.pattern().nid();
        Set<Transaction> transactions = pendingTransactions(nid);
        assertEquals(1, transactions.size());
        onFx(() -> {
            transactions.iterator().next().commit();
            return null;
        });
        assertFalse(entity(nid).uncommitted(), "the pattern the semantics instantiate is published");
        return EntityProxy.Pattern.make(nid);
    }

    private static File tempDirectory() {
        try {
            return Files.createTempDirectory("semantic-proposal-it").toFile();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Set<Transaction> pendingTransactions(long component) {
        Set<Transaction> transactions = new HashSet<>();
        for (EntityVersion version : entity(component).versions()) {
            if (version.uncommitted()) {
                Transaction.forStamp(version.stamp().publicId()).ifPresent(transactions::add);
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

    private static <T> T onFx(Supplier<T> work) {
        try {
            return onFxUnwrapped(work);
        } catch (RuntimeException e) {
            throw new AssertionError(e);
        }
    }

    /** Runs on the FX thread, rethrowing a runtime exception the work threw as itself. */
    private static <T> T onFxUnwrapped(Supplier<T> work) {
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
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(e.getCause());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
