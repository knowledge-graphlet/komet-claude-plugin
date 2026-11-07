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
package network.ike.komet.claude.tools;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.Symbols;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import network.ike.komet.claude.anthropic.AnthropicTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the assistant's {@code change_history} tool against the starter set
 * (knowledge-graphlet/komet-claude-plugin#1): it reads a concept, a pattern or a semantic as
 * given, shows a changed field before and after, and answers an unknown identifier without
 * writing anything.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChangeHistoryIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** A comment written twice on a starter-set concept, so it has a field that changed. */
    private final PublicId comment = PublicIds.newRandom();

    private AnthropicTool changeHistory;

    @BeforeAll
    void setup() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);

        long first = System.currentTimeMillis() - 60_000;
        write("first wording", first);
        write("second wording", first + 1_000);

        ViewCalculator view = ViewCalculatorWithCache.getCalculator(Coordinates.View.DefaultView());
        changeHistory = new GraphTools(() -> view).tools().stream()
                .filter(tool -> tool.name().equals("change_history"))
                .findFirst().orElse(null);
        assertNotNull(changeHistory, "change_history is registered");
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @Test
    void aSemanticIsReadAsGivenWithItsFieldBeforeAndAfter() {
        String out = run(comment.asUuidArray()[0].toString());
        assertTrue(out.startsWith("Changes for "), () -> "a change history, got: " + out);
        assertTrue(out.contains("semantic: "), () -> "the semantic itself, not its concept: " + out);
        assertTrue(out.contains("first wording " + Symbols.HEAVY_TRIANGLE_HEADED_RIGHTWARDS_ARROW + " second wording"),
                () -> "the changed comment, before and after: " + out);
        assertTrue(out.indexOf("second wording") < out.lastIndexOf("first wording"),
                () -> "newest version first: " + out);
    }

    @Test
    void aConceptAndAPatternAreReadAsThemselves() {
        String concept = run(uuidOf(KernelConcept.ENGLISH_LANGUAGE));
        assertTrue(concept.startsWith("Changes for concept "), () -> concept);
        String pattern = run(uuidOf(KernelPattern.COMMENT_PATTERN));
        assertTrue(pattern.startsWith("Changes for pattern "), () -> pattern);
    }

    @Test
    void anUnknownIdentifierIsAnsweredWithoutWritingIt() {
        UUID unknown = UUID.randomUUID();
        String out = run(unknown.toString());
        assertTrue(out.startsWith("No component found"), () -> out);
        assertFalse(PrimitiveData.get().hasPublicId(PublicIds.of(unknown)), "no nid was minted for it");
    }

    private void write(String text, long time) {
        try (StampedWriter writer = StampedWriter.open("change_history fixture", State.ACTIVE, time,
                KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE, KernelConcept.DEVELOPMENT_PATH)) {
            writer.semantic(comment, KernelPattern.COMMENT_PATTERN.publicId(),
                    KernelConcept.ENGLISH_LANGUAGE.publicId(), text);
            writer.commit();
        }
    }

    private String run(String id) {
        return changeHistory.execute(Map.of("id", id));
    }

    private static String uuidOf(EntityFacade facade) {
        return facade.publicId().asUuidArray()[0].toString();
    }
}
