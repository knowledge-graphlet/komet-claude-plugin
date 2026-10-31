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
package network.ike.komet.claude.anf.element;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import network.ike.komet.claude.anf.AnfLiftListener;
import network.ike.komet.claude.anf.ScoringFixtures;
import network.ike.komet.claude.semantic.TestApiKey;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The first part of the fixture score, live: each scored narrative of the ANF fixtures is isolated
 * by the real model and the isolation scored against the fixture's expected elements by
 * {@link LiftScore}. Gated on an API key ({@link TestApiKey}), so it skips where none is configured.
 * Each narrative's report is written to {@code target/lift-scores/}; the test fails only when an
 * isolation records no elements or finds none of the expected ones, since the model's isolation
 * varies from run to run and the report is the measure.
 *
 * <p>The second part needs each element lifted against a store that carries the fixtures' SNOMED CT
 * concepts, which the build's test stores do not; until one does, every matched element scores
 * {@code not lifted} here and the statement part is proven by {@link LiftScoreTest} alone.
 */
@Tag("external") // TestTags.EXTERNAL: needs an Anthropic API key; run with -Pexternal
class ElementIsolationScoreIT {

    private static final String MODEL = "claude-opus-4-8";
    private static final Path REPORTS = Path.of("target", "lift-scores");

    @Test
    void theFixturesNarrativesIsolateToTheirExpectedElements() throws IOException {
        Optional<String> apiKey = TestApiKey.resolve();
        assumeTrue(apiKey.isPresent(), "No Anthropic API key (system property / env / Komet user preferences) — skipping live isolation.");
        Files.createDirectories(REPORTS);
        ElementIsolation isolation = new ElementIsolation(apiKey.get(), MODEL);
        StringBuilder all = new StringBuilder();
        for (ScoringFixtures.Narrative narrative : ScoringFixtures.narratives()) {
            LiftScore.Expectation expectation = ScoringFixtures.expectation(narrative, identifier -> Optional.empty());
            ElementIsolation.Result result = isolation.isolate(expectation.narrative(), new AnfLiftListener() {
            });
            String where = narrative.fixture() + " " + narrative.id();
            assertTrue(result.elements().isPresent(), where + " isolates: " + result.assistantText());
            ElementSet isolated = result.elements().get();
            LiftScore.Report report = LiftScore.score(expectation, isolated);

            StringBuilder text = new StringBuilder(where).append('\n').append(report.summary()).append("isolated:\n");
            for (ClinicalElement element : isolated.elements()) {
                text.append("  ").append(element.id()).append(": ").append(element.sentence())
                        .append("  ").append(element.quotes(isolated.narrative())).append('\n');
            }
            all.append(text).append('\n');
            Files.writeString(REPORTS.resolve(narrative.fixture().replace(".adoc", "") + "-" + narrative.id() + ".txt"),
                    text, StandardCharsets.UTF_8);
            assertTrue(report.recall() > 0, where + " finds an expected element\n" + text);
        }
        System.out.println(all);
    }
}
