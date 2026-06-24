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
package network.ike.komet.claude.semantic;

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.TinkarTerm;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end smoke test of {@link SemanticLift} against the live store and the real Anthropic API.
 * It is <em>gated on an API key</em> ({@link TestApiKey}); without one — on CI, or a machine that
 * has not configured Komet — it skips rather than fails, so the build stays green everywhere while
 * a developer with a key gets real coverage of the whole pipeline (prompt assembled from the
 * pattern's field definitions, the tool loop, and live grounding through {@link SemanticGrounding}).
 *
 * <p>The starter set carries no SNOMED/LOINC/RxNorm and a limited search index, so this asserts the
 * pipeline <em>completes and produces structure or guidance</em> — not specific clinical groundings.
 * Richer, deterministic lift assertions arrive when full terminologies are loadable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticLiftIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/tinkar-starter-data-reasoned-pb.zip");
    private static final String LIFT_MODEL = "claude-opus-4-8";

    private ViewCalculator view;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(),
                "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        // No CachingService.clearAll() — see SemanticGroundingIT for the rationale (avoids
        // ExecutorProvider.reset()'s concrete-Controller lookup; each *IT runs in its own fork).
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertTrue(count > 0, "Should load entities from the starter-data protobuf file");
        view = Calculators.View.Default();
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void liftsAgainstAStarterPatternWhenAKeyIsAvailable() {
        Optional<String> apiKey = TestApiKey.resolve();
        assumeTrue(apiKey.isPresent(),
                "No Anthropic API key (system property / env / Komet user preferences) — skipping live lift.");

        int patternNid = TinkarTerm.DESCRIPTION_PATTERN.nid();
        SemanticLift lift = new SemanticLift(view, apiKey.get(), LIFT_MODEL, patternNid);

        SemanticLift.Result result = lift.lift(
                "Record an English-language description with the text 'diabetes mellitus'.");

        assertNotNull(result, "the lift must always return a result");
        // With only starter metadata and a limited search index, grounding may be partial; assert the
        // pipeline ran end-to-end (an instance landed, or the model returned a message) rather than
        // over-asserting which components were grounded.
        assertTrue(result.lifted()
                        || (result.assistantText() != null && !result.assistantText().isBlank()),
                "the lift should produce at least one instance or a non-empty message");
    }
}
