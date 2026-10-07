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
        assertEquals("73211009", first("code 73211009 appears").group("sctid"));
    }

    @Test
    void anEarlierNidTokenIsConsumedWholeAndIsNotAnInterchangeKind() {
        // Text stored before IKE-Network/ike-issues#1170 can hold k:nid=…[Label]. The legacy
        // group takes the whole token, so nothing in it is offered for resolution.
        String token = "k:nid=-2147481234[Thing]";
        Matcher m = first(token);
        assertEquals(token, m.group("legacy"));
        assertNull(m.group("kind"), "nid is not an interchange kind");
        assertNull(m.group("kid"), "a nid token has no id to resolve");
        assertFalse(m.find(), "nothing inside the token is matched a second time");

        // Without a label the token ends at the last digit, exactly as it did before.
        m = first("see k:nid=-42 today");
        assertEquals("k:nid=-42", m.group("legacy"));
    }

    @Test
    void theDigitsOfABareNidAreNeverReadAsAnSctid() {
        // A nid can be positive and long enough to look like an SCTID. The legacy group takes
        // "nid=" together with its digits, so the number never reaches the sctid alternative —
        // in a store that holds SNOMED CT it would otherwise get the badge of an unrelated concept.
        Matcher m = first("component nid=73211009 resolved");
        assertEquals("nid=73211009", m.group("legacy"));
        assertNull(m.group("sctid"));
        assertFalse(m.find(), "the digits are not matched a second time as an SCTID");

        m = first("component nid=-42 resolved");
        assertEquals("nid=-42", m.group("legacy"));
    }

    @Test
    void earlierNidFormsStayLiteralBesideOtherTokens() {
        // Store-free: here every token is literal. That the nid forms stay literal in a live
        // store, where the UUID beside them gets its badge, is asserted by NidInTextIT.
        ConceptChipInlineDecorator decorator = new ConceptChipInlineDecorator(
                (dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator) null, 13);
        String text = "k:nid=-42[Thing] then nid=73211009, then k:uuid=" + UUID_A + "[A].";
        StringBuilder plain = new StringBuilder();
        for (InlinePiece piece : decorator.decorate(text, StyleAttributeMap.EMPTY)) {
            assertTrue(piece instanceof InlinePiece.TextRun, "no badges without a store");
            plain.append(((InlinePiece.TextRun) piece).text());
        }
        assertEquals(text, plain.toString(), "nothing is lost or reordered");
    }

    @Test
    void withoutAStoreEverythingStaysLiteral() {
        // No datastore in unit tests: resolve() fails its existence gate, so every token — k: or
        // bare — degrades to literal text and the decomposition returns the input verbatim.
        ConceptChipInlineDecorator decorator = new ConceptChipInlineDecorator(
                (dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator) null, 13);
        String text = "compare k:uuid=" + UUID_A + "[A] with 73211009 and nid=7 today";
        List<InlinePiece> pieces = decorator.decorate(text, StyleAttributeMap.EMPTY);

        StringBuilder plain = new StringBuilder();
        for (InlinePiece piece : pieces) {
            assertTrue(piece instanceof InlinePiece.TextRun, "no chips without a store");
            plain.append(((InlinePiece.TextRun) piece).text());
        }
        assertEquals(text, plain.toString(), "unresolvable tokens stay literal, nothing is lost");
    }

    @Test
    void multiLineForCellsRewrapsNodeRunsAndPassesTextThrough() {
        // The cell path (IKE-Network/ike-issues#1036): text pieces pass through as the same
        // instances; node pieces are rewrapped — same plain-text projection (cell copy still
        // round-trips interchange), delegating supplier (a non-badge node comes through
        // unchanged; a KonceptBadge is flipped multi-line, exercised by running Komet since
        // chip building needs a store and a toolkit).
        InlinePiece.TextRun text = new InlinePiece.TextRun("Flu A, ", StyleAttributeMap.EMPTY);
        javafx.scene.layout.Region stub = new javafx.scene.layout.Region();
        InlinePiece.NodeRun node = new InlinePiece.NodeRun(() -> stub, "k:uuid=" + UUID_A + "[X]");

        List<InlinePiece> out = ConceptChipInlineDecorator.multiLineForCells(List.of(text, node));

        assertEquals(2, out.size());
        assertTrue(out.get(0) == text, "text pieces pass through untouched");
        InlinePiece.NodeRun rewrapped = (InlinePiece.NodeRun) out.get(1);
        assertEquals(node.plainText(), rewrapped.plainText(), "projection preserved for cell copy");
        assertTrue(rewrapped.node().get() == stub, "a non-badge node materialises unchanged");
    }

    @Test
    void decorateForCellDecomposesLikeDecorate() {
        // Same decomposition as decorate; only chip materialisation differs (multi-line form).
        ConceptChipInlineDecorator decorator = new ConceptChipInlineDecorator(
                (dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator) null, 13);
        String text = "compare k:uuid=" + UUID_A + "[A] with 73211009 today";
        List<InlinePiece> cell = decorator.decorateForCell(text, StyleAttributeMap.EMPTY);

        StringBuilder plain = new StringBuilder();
        for (InlinePiece piece : cell) {
            plain.append(piece.plainText());
        }
        assertEquals(text, plain.toString(), "the cell path loses nothing either");
    }
}
