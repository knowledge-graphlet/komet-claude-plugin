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
package network.ike.komet.claude.doc;

import dev.ikm.komet.layout.PatternDefinitionSeeder;
import dev.ikm.komet.layout.PatternDefinitionTerms;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.boundary.CommitBoundary;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.KernelPattern;
import javafx.application.Platform;
import network.ike.komet.claude.stamp.VertexMaterializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A journal opens on a store that holds an assistant's conversation journal: the
 * pattern-definition seeding a journal runs projects knowledge patterns only, and leaves the
 * journal's own instance pattern alone, since knowledge never references instance data
 * (IKE-Network/ike-issues#1313). On the memory-only 64-bit store with the starter set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JournalPatternSeedingIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private ViewCalculator view;

    @BeforeAll
    void setupDatabase() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Started by another test class in this JVM.
        } catch (RuntimeException | Error noToolkit) {
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit);
        }
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "vertex");
        System.setProperty(CommitBoundary.MODE_PROPERTY, "fail");
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        view = Calculators.View.Default();
    }

    @AfterAll
    void teardownDatabase() {
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        System.clearProperty(CommitBoundary.MODE_PROPERTY);
        dev.ikm.tinkar.entity.boundary.ModuleKinds.clear();
        dev.ikm.tinkar.entity.boundary.EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    @Test
    void seedingSkipsAConversationJournalsInstancePattern() {
        PublicId anchor = new JournalStore(() -> view)
                .appendExchange(null, "Conversation before seeding", "What is a finding?", "A clinical observation.");
        long journalPattern = EntityHandle.get(EntityService.get().nidForPublicId(anchor)).expectSemantic().patternNid();

        onFx(() -> {
            PatternDefinitionSeeder.ensureSeeded(view);    // refused the whole commit before the fix
            return null;
        });

        long meaningAndPurpose = PatternDefinitionTerms.MEANING_AND_PURPOSE_PATTERN.nid();
        assertEquals(0, EntityService.get().semanticsForComponentOfPattern(journalPattern, meaningAndPurpose).count(),
                "the journal's instance pattern is not described by knowledge");
        assertEquals(1, EntityService.get().semanticsForComponentOfPattern(
                        KernelPattern.DESCRIPTION_PATTERN.nid(), meaningAndPurpose).count(),
                "a knowledge pattern still is");
    }

    private static <T> T onFx(java.util.function.Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e.getCause() == null ? e : e.getCause());
        }
    }
}
