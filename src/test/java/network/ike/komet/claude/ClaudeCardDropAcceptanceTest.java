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
package network.ike.komet.claude;

import dev.ikm.komet.framework.dnd.KometClipboard;
import javafx.scene.input.DataFormat;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the compose area takes as a koncept chip. A pattern drag was refused because the guard
 * accepted only concept proxies (knowledge-graphlet/komet-claude-plugin#4).
 */
class ClaudeCardDropAcceptanceTest {

    @Test
    void aConceptProxyIsAccepted() {
        assertTrue(ClaudeCard.acceptsKoncept(Set.of(KometClipboard.KOMET_CONCEPT_PROXY)));
        assertTrue(ClaudeCard.acceptsKoncept(Set.of(KometClipboard.KOMET_CONCEPT_VERSION_PROXY)));
    }

    @Test
    void aMultiConceptDragIsAccepted() {
        assertTrue(ClaudeCard.acceptsKoncept(Set.of(KometClipboard.KOMET_CONCEPT_LIST)));
    }

    @Test
    void aPatternProxyIsAccepted() {
        // The regression: a pattern dragged from the pattern navigator.
        assertTrue(ClaudeCard.acceptsKoncept(Set.of(KometClipboard.KOMET_PATTERN_PROXY)));
        assertTrue(ClaudeCard.acceptsKoncept(Set.of(KometClipboard.KOMET_PATTERN_VERSION_PROXY)));
    }

    @Test
    void everyPatternTypeTheClipboardAdvertisesIsAccepted() {
        for (DataFormat format : KometClipboard.PATTERN_TYPES) {
            assertTrue(ClaudeCard.acceptsKoncept(Set.of(format)), format + " should be droppable");
        }
    }

    @Test
    void semanticsAndStampsRemainRefused() {
        // Deliberate scope: whether these belong in a prompt is its own question, not a side
        // effect of admitting patterns.
        for (DataFormat format : KometClipboard.SEMANTIC_TYPES) {
            assertFalse(ClaudeCard.acceptsKoncept(Set.of(format)), format + " should not be droppable");
        }
        for (DataFormat format : KometClipboard.STAMP_TYPES) {
            assertFalse(ClaudeCard.acceptsKoncept(Set.of(format)), format + " should not be droppable");
        }
    }

    @Test
    void anUnrelatedDragIsRefused() {
        assertFalse(ClaudeCard.acceptsKoncept(Set.of(DataFormat.FILES)));
        assertFalse(ClaudeCard.acceptsKoncept(Set.of(DataFormat.PLAIN_TEXT)));
    }

    @Test
    void aDragCarryingBothTextAndAPatternIsAccepted() {
        // A real dragboard advertises several formats at once — the koncept must still win over
        // the plain-text PublicId that rides along with it.
        assertTrue(ClaudeCard.acceptsKoncept(
                Set.of(DataFormat.PLAIN_TEXT, KometClipboard.KOMET_PATTERN_PROXY)));
    }

    @Test
    void noFormatsAtAllIsRefused() {
        assertFalse(ClaudeCard.acceptsKoncept(Set.of()));
        assertFalse(ClaudeCard.acceptsKoncept(null));
    }
}
