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
package network.ike.komet.claude.decision.render;

import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.decision.BlockSweep;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionElement.Chance;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeSensitivity;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Swing;
import network.ike.komet.claude.decision.Rollback;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scan tree and its tornado as SVG: the emitted document parses, its marks carry the roles
 * the live diagram's parts have, and the chosen alternative is the heavy one.
 */
class DecisionTreeSvgTest {

    static final DecisionTerms T = DecisionTerms.synthetic();
    static String body;
    static BlockIdentities ids;
    static DecisionTreeSensitivity sweep;
    static DecisionTree tree;
    static Rollback rollback;

    @BeforeAll
    static void sweepTheScanTree() throws Exception {
        try (InputStream in = DecisionTreeSvgTest.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            body = text.substring(text.indexOf("----\n") + 5, text.lastIndexOf("----"));
        }
        ids = BlockIdentities.storeFree();
        sweep = BlockSweep.sweep(body, T, ids, 100_000, BlockSweep.DEFAULT_STEPS).orElseThrow();
        tree = sweep.tree();
        rollback = sweep.base();
    }

    static Document parse(String svg) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    /** The elements whose class holds every one of the given words. */
    static List<Element> withRole(Document doc, String tag, String... words) {
        List<Element> found = new ArrayList<>();
        NodeList all = doc.getElementsByTagName(tag);
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            Set<String> classes = Set.of(element.getAttribute("class").split(" "));
            boolean every = true;
            for (String word : words) {
                every &= classes.contains(word);
            }
            if (every) {
                found.add(element);
            }
        }
        return found;
    }

    static String texts(Document doc, String role) {
        StringBuilder sb = new StringBuilder();
        for (Element text : withRole(doc, "text", role)) {
            sb.append(text.getTextContent()).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("The tree has a square for each decision, a circle for each chance, a triangle for each outcome, and an elbow for each branch")
    void glyphsAndRuns() throws Exception {
        String svg = DecisionTreeSvg.tree(tree, rollback, ids, Optional.of(sweep));
        Document doc = parse(svg);
        assertEquals("decision-tree", doc.getDocumentElement().getAttribute("class"));
        assertEquals(2, withRole(doc, "rect", "glyph", "decision").size(), "the scan decision and the retreat decision");
        assertEquals(4, withRole(doc, "circle", "glyph", "chance").size());
        assertEquals(7, withRole(doc, "polygon", "glyph", "outcome").size());
        assertEquals(12, withRole(doc, "polyline", "run").size(), "two alternatives, two retreat alternatives, four chances of two events");
        for (Element run : withRole(doc, "polyline", "run")) {
            assertEquals(4, run.getAttribute("points").split(" ").length, "an elbow of four points");
        }
        double width = Double.parseDouble(doc.getDocumentElement().getAttribute("width"));
        double height = Double.parseDouble(doc.getDocumentElement().getAttribute("height"));
        assertTrue(width > 500, "wide enough for five columns of labels: " + width);
        assertEquals(6 + 6 + 7 * (2 * 17 + 10), height, 1e-9, "seven rows");
        assertTrue(svg.contains("⇒ 3,880"), "the root's rolled-back value is on the figure");
        assertTrue(svg.contains("⇒ 4,188"), "and the rejected alternative's");
        assertTrue(texts(doc, "measure").contains("money 700"), "a stated cost reads as its chip does");
        assertTrue(texts(doc, "measure").contains("p (residual)"));
        assertFalse(svg.contains("javafx"), "no toolkit");
    }

    @Test
    @DisplayName("The chosen alternative is drawn heavy and marked, the rejected one barred, and a measure that flips the root is marked")
    void chosenAndRejected() throws Exception {
        Document doc = parse(DecisionTreeSvg.tree(tree, rollback, ids, Optional.of(sweep)));
        List<Element> chosen = withRole(doc, "polyline", "run", "chosen");
        assertEquals(2, chosen.size(), "scan at the root, and one of the retreat alternatives");
        for (Element run : chosen) {
            assertEquals("2.6", run.getAttribute("stroke-width"));
            assertEquals(DecisionFigureText.CHOSEN, run.getAttribute("stroke"));
        }
        List<Element> plain = withRole(doc, "polyline", "run");
        plain.removeAll(chosen);
        for (Element run : plain) {
            assertEquals("1.2", run.getAttribute("stroke-width"));
        }
        assertEquals(4, withRole(doc, "polyline", "barred").size(), "two rejected alternatives, two bars each");
        assertEquals(2, withRole(doc, "text", "chosen").size());
        assertTrue(texts(doc, "chosen").contains("✓ chosen"));
        assertFalse(withRole(doc, "text", "flip-mark").isEmpty(), "the scan's cost flips the root, so its chip is marked");
        Document unswept = parse(DecisionTreeSvg.tree(tree, rollback, ids, Optional.empty()));
        assertTrue(withRole(unswept, "text", "flip-mark").isEmpty(), "no sweep, no marks");
    }

    @Test
    @DisplayName("A folded chance is a terminal saying what is folded, with nothing beneath it")
    void folded() throws Exception {
        Chance mra = tree.nodesOfType(Chance.class).get(0);
        Document doc = parse(DecisionTreeSvg.tree(tree, rollback, ids, Set.of(mra.vertexIndex())::contains,
                Optional.empty(), "$"));
        assertEquals(1, withRole(doc, "circle", "glyph", "chance", "folded").size());
        assertEquals(4, withRole(doc, "polyline", "run").size());
        assertTrue(texts(doc, "folded").contains("nodes folded"));
        assertTrue(texts(doc, "value").contains("⇒ $3,880"), "a symbol goes before every value");
    }

    @Test
    @DisplayName("The tornado has one bar per swept measure, longest first, hollow for an assumed range, with the root's flips ticked")
    void tornado() throws Exception {
        String svg = DecisionTreeSvg.tornado(sweep, ids);
        Document doc = parse(svg);
        assertEquals("decision-tornado", doc.getDocumentElement().getAttribute("class"));
        long swept = sweep.swings().stream().filter(s -> s.width() > 0).count();
        assertEquals(swept, withRole(doc, "text", "bar-name").size());
        assertTrue(withRole(doc, "rect", "bar", "stated").isEmpty(), "every measure in the block is a point");
        List<Element> hollow = withRole(doc, "rect", "bar", "assumed");
        assertTrue(hollow.size() >= swept, "at least one stretch per bar");
        for (Element bar : hollow) {
            assertEquals("none", bar.getAttribute("fill"));
            assertFalse(bar.getAttribute("stroke").isEmpty());
        }
        assertFalse(withRole(doc, "rect", "bar", "other").isEmpty(), "beyond a flip the bar changes colour");
        assertFalse(withRole(doc, "polyline", "tick", "root").isEmpty());
        String thresholds = texts(doc, "threshold");
        assertTrue(thresholds.contains("flips at 1,008"), thresholds);
        assertTrue(texts(doc, "axis").contains("base 3,880"));
        assertEquals(DecisionTreeFigures.nameOf(tree, ids, sweep.swings().get(0).measureVertex()),
                withRole(doc, "text", "bar-name").get(0).getTextContent(), "the longest swing is drawn first");
        assertTrue(texts(doc, "bar-name").contains("price of a healthy year"));
        assertTrue(texts(doc, "footnote").contains("One value at a time"));
        assertEquals(DecisionFigureText.value(1008.4), "1,008");
        assertEquals("0.2140", DecisionFigureText.value(0.214));
    }

    @Test
    @DisplayName("A stated interval draws a solid bar")
    void statedBarIsSolid() throws Exception {
        String widened = body.replace("parameter = [0.9,0.9] k:Sensitivity", "parameter = [0.8,0.95] k:Sensitivity");
        DecisionTreeSensitivity again = BlockSweep.sweep(widened, T, BlockIdentities.storeFree(), 100_000, 4).orElseThrow();
        Document doc = parse(DecisionTreeSvg.tornado(again, ids, 500, "$"));
        List<Element> solid = withRole(doc, "rect", "bar", "stated");
        assertFalse(solid.isEmpty());
        assertEquals("0.85", solid.get(0).getAttribute("fill-opacity"));
        assertTrue(texts(doc, "axis").contains("base $"));
        assertEquals("500", doc.getDocumentElement().getAttribute("width"));
    }

    @Test
    @DisplayName("Label widths are estimated, so the geometry is the same wherever it is drawn")
    void widthsAreEstimates() {
        assertTrue(DecisionFigureText.width("scan", 10.8, false) < DecisionFigureText.width("no scan", 10.8, false));
        assertTrue(DecisionFigureText.width("MMM", 10, false) > DecisionFigureText.width("iii", 10, false));
        String fitted = DecisionFigureText.fit("abcdefghij", 10, false, 25);
        assertTrue(fitted.endsWith("…") && fitted.length() < 6, fitted);
        assertTrue(DecisionFigureText.width(fitted, 10, false) <= 25);
        assertEquals(List.of("one two", "three"), DecisionFigureText.wrap("one two three", 10, 45));
        Alternative scan = tree.nodesOfType(Alternative.class).get(0);
        Swing cost = sweep.swing(scan.costs().get(0).vertexIndex()).orElseThrow();
        assertEquals(Bounds.of(350, 1_400), cost.range());
    }
}
