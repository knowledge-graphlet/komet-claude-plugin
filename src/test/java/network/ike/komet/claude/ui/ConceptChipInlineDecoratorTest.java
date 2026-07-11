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

import dev.ikm.komet.markdown.richtext.InlinePiece;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store-free tests for {@link ConceptChipInlineDecorator}'s token grammar and decomposition.
 * Chip building needs a datastore and a JavaFX toolkit (exercised by running Komet); here only the
 * {@code TOKEN} grammar and the no-store degradation — everything unresolvable stays literal — are
 * asserted. Node suppliers are never invoked.
 */
class ConceptChipInlineDecoratorTest {

    private static final String UUID_A = "e07f8c60-1234-1234-1234-1234567890ab";

    private static Matcher first(String text) {
        Matcher m = ConceptChipInlineDecorator.TOKEN.matcher(text);
        assertTrue(m.find(), "expected a token in: " + text);
        return m;
    }

    @Test
    void interchangeTokenKindsDecompose() {
        Matcher m = first("k:uuid=" + UUID_A + "[Multi-target NAA test]");
        assertEquals("uuid", m.group("kind"));
        assertEquals(UUID_A, m.group("kid"));
        assertEquals("Multi-target NAA test", m.group("klabel"));

        m = first("k:sctid=73211009[Diabetes mellitus]");
        assertEquals("sctid", m.group("kind"));
        assertEquals("73211009", m.group("kid"));
        assertEquals("Diabetes mellitus", m.group("klabel"));

        m = first("k:nid=-2147481234[Thing]");
        assertEquals("nid", m.group("kind"));
        assertEquals("-2147481234", m.group("kid"));

        m = first("k:id=" + UUID_A);
        assertEquals("id", m.group("kind"));
        assertEquals(UUID_A, m.group("kid"));
        assertNull(m.group("klabel"), "label is optional");
    }

    @Test
    void interchangeTokenConsumesItsEmbeddedIdInOneMatch() {
        Matcher m = ConceptChipInlineDecorator.TOKEN.matcher("k:uuid=" + UUID_A + "[X]");
        assertTrue(m.find());
        assertEquals(0, m.start(), "the token matches from k:, not from the embedded UUID");
        assertFalse(m.find(), "the embedded UUID is not matched a second time");
    }

    @Test
    void inlineGrammarIsTight() {
        // A detached bracket is prose, never swallowed as a label.
        Matcher m = first("k:sctid=73211009 [see note 3]");
        assertEquals("73211009", m.group("kid"));
        assertNull(m.group("klabel"), "a space before the bracket ends the token");

        // Sentence punctuation after the id stays outside the token.
        m = first("about k:sctid=73211009.");
        assertEquals("73211009", m.group("kid"));
        assertEquals('.', "about k:sctid=73211009.".charAt(m.end()), "the period stays prose");

        // Prose mentioning the k: convention without an id form does not match as interchange.
        Matcher none = ConceptChipInlineDecorator.TOKEN.matcher("the k: token convention");
        assertFalse(none.find(), "no identifier shapes at all → no match");
    }

    @Test
    void bareIdentifierFamiliesStillMatch() {
        assertEquals(UUID_A, first("see " + UUID_A + " here").group("uuid"));
        assertEquals("-42", first("component nid=-42 resolved").group("nid"));
        assertEquals("73211009", first("code 73211009 appears").group("sctid"));
    }

    @Test
    void withoutAStoreEverythingStaysLiteral() {
        // No datastore in unit tests: resolve() fails its existence gate, so every token — k: or
        // bare — degrades to literal text and the decomposition returns the input verbatim.
        ConceptChipInlineDecorator decorator = new ConceptChipInlineDecorator(null, 13);
        String text = "compare k:uuid=" + UUID_A + "[A] with 73211009 and nid=7 today";
        List<InlinePiece> pieces = decorator.decorate(text, StyleAttributeMap.EMPTY);

        StringBuilder plain = new StringBuilder();
        for (InlinePiece piece : pieces) {
            assertTrue(piece instanceof InlinePiece.TextRun, "no chips without a store");
            plain.append(((InlinePiece.TextRun) piece).text());
        }
        assertEquals(text, plain.toString(), "unresolvable tokens stay literal, nothing is lost");
    }
}
