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

import dev.ikm.komet.markdown.richtext.BlockPiece;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store-free tests for {@link KonceptTreeBlockRenderer}'s dispatch, info-string tag matching, and
 * indentation parsing. The {@link TreeView} and its chips are built by a deferred supplier that
 * these tests never invoke (that path needs a datastore and a JavaFX toolkit and is exercised by
 * running Komet), so parsing and dispatch are all that is asserted here.
 */
class KonceptTreeBlockRendererTest {

    private static final KonceptTreeBlockRenderer RENDERER = new KonceptTreeBlockRenderer(null, 13);

    private static final String TREE = """
            k:sctid=772222008[Medical devices]
              k:sctid=118956008[Microbiology device]
                k:uuid=e07f8c60-1234-1234-1234-1234567890ab[Multi-target NAA test]
            """;

    @Test
    void firstWordExtractsTheLanguageTag() {
        assertEquals("koncept-tree", KonceptTreeBlockRenderer.firstWord("koncept-tree"));
        assertEquals("koncept-tree", KonceptTreeBlockRenderer.firstWord("koncept-tree title=Foo"));
        assertEquals("koncept-tree", KonceptTreeBlockRenderer.firstWord("  koncept-tree  "));
        assertEquals("dot", KonceptTreeBlockRenderer.firstWord("dot"));
        assertNull(KonceptTreeBlockRenderer.firstWord(""));
        assertNull(KonceptTreeBlockRenderer.firstWord(null));
    }

    @Test
    void recognisedTagYieldsOnePieceProjectingTheSource() {
        List<BlockPiece> pieces = RENDERER.render(TAG, TREE, StyleAttributeMap.EMPTY);
        assertEquals(1, pieces.size(), "a well-formed koncept-tree collapses to one atomic block");
        // The plain projection is the source (trailing newline stripped), so a copy round-trips.
        assertEquals(TREE.stripTrailing(), pieces.get(0).plainText());
    }

    @Test
    void wrongTagIsDeclinedSoItFallsThroughToPreformatted() {
        assertTrue(RENDERER.render("dot", TREE, StyleAttributeMap.EMPTY).isEmpty());
        assertTrue(RENDERER.render("java", "int x = 1;", StyleAttributeMap.EMPTY).isEmpty());
        assertTrue(RENDERER.render(null, TREE, StyleAttributeMap.EMPTY).isEmpty());
        assertTrue(RENDERER.render("", TREE, StyleAttributeMap.EMPTY).isEmpty());
    }

    @Test
    void malformedBodyIsDeclinedEvenUnderTheRightTag() {
        // Tagged koncept-tree but the body is prose, not k: tokens — must fall through, not render
        // a half-tree.
        String prose = "This is not a tree.\nJust some text.";
        assertTrue(RENDERER.render(TAG, prose, StyleAttributeMap.EMPTY).isEmpty());
        // A single stray non-token line aborts the whole parse.
        String mixed = "k:sctid=772222008[Medical devices]\n  oops not a token";
        assertTrue(RENDERER.render(TAG, mixed, StyleAttributeMap.EMPTY).isEmpty());
    }

    @Test
    void parseCarriesIndentationKindValueAndLabel() {
        List<KonceptTreeBlockRenderer.ParsedNode> nodes = KonceptTreeBlockRenderer.parse(TREE);
        assertEquals(3, nodes.size());

        assertEquals(0, nodes.get(0).indent());
        assertEquals("sctid", nodes.get(0).kind());
        assertEquals("772222008", nodes.get(0).value());
        assertEquals("Medical devices", nodes.get(0).label());

        assertEquals(2, nodes.get(1).indent(), "two spaces = one level deeper");
        assertEquals(4, nodes.get(2).indent());
        assertEquals("uuid", nodes.get(2).kind());
        assertEquals("e07f8c60-1234-1234-1234-1234567890ab", nodes.get(2).value());
    }

    @Test
    void parseSkipsBlankLinesAndToleratesSpacesAroundEquals() {
        String body = "k:sctid = 772222008 [Medical devices]\n\n  k:uuid=abcd[Child]\n";
        List<KonceptTreeBlockRenderer.ParsedNode> nodes = KonceptTreeBlockRenderer.parse(body);
        assertEquals(2, nodes.size(), "blank line skipped");
        assertEquals("772222008", nodes.get(0).value(), "value trimmed around '='");
        assertEquals("Medical devices", nodes.get(0).label());
    }

    @Test
    void parseReturnsEmptyForBlankOrTaglessBody() {
        assertTrue(KonceptTreeBlockRenderer.parse("").isEmpty());
        assertTrue(KonceptTreeBlockRenderer.parse("   \n  \n").isEmpty());
    }

    /** The info-string tag under test, referenced by name so a rename fails the test, not silently. */
    private static final String TAG = KonceptTreeBlockRenderer.TAG;
}
