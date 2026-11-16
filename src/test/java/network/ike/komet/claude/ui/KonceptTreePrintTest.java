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

import network.ike.komet.document.DecisionFigure;
import network.ike.komet.document.DecisionFigure.Circle;
import network.ike.komet.document.DecisionFigure.Polyline;
import network.ike.komet.document.DecisionFigure.Text;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store-free tests for a {@code koncept-tree} block on a page: one line to a concept, indented beneath its
 * parent and joined to it, with a concept the knowledge base has told apart from a name that has none.
 */
class KonceptTreePrintTest {

    private static final String TREE = """
            k:sctid=49872002[Virus (organism)]
              k:sctid=840533007[Severe acute respiratory syndrome coronavirus 2 (organism)]
                k:id=lineage-b117[Lineage B.1.1.7]
              k:sctid=407479009[Influenza A virus (organism)]
            """;

    private static DecisionFigure figure() {
        List<DecisionFigure> figures = KonceptTreePrint.figures(KonceptTreeBlockRenderer.parse(TREE),
                node -> node.kind().equals("sctid"));
        assertEquals(1, figures.size(), "a tree is one figure");
        return figures.getFirst();
    }

    private static List<Text> names(DecisionFigure figure) {
        return figure.marks().stream().filter(Text.class::isInstance).map(Text.class::cast).toList();
    }

    @Test
    void eachConceptIsALineNamedWithoutItsTag() {
        List<String> names = names(figure()).stream().map(Text::text).toList();
        assertEquals(List.of("Virus", "Severe acute respiratory syndrome coronavirus 2", "Lineage B.1.1.7", "Influenza A virus"), names);
    }

    @Test
    void aChildStandsToTheRightOfItsParentAndBelowIt() {
        List<Text> names = names(figure());
        assertEquals(KonceptTreePrint.INDENT, names.get(1).x() - names.get(0).x(), 0.001);
        assertEquals(2 * KonceptTreePrint.INDENT, names.get(2).x() - names.get(0).x(), 0.001);
        assertEquals(names.get(1).x(), names.get(3).x(), 0.001, "a second child stands under the first");
        for (int i = 1; i < names.size(); i++) {
            assertTrue(names.get(i).top() > names.get(i - 1).top(), "lines run down the page in the order written");
        }
    }

    @Test
    void everyChildIsJoinedToItsParent() {
        long branches = figure().marks().stream().filter(Polyline.class::isInstance).count();
        assertEquals(3, branches, "three of the four lines have a parent");
    }

    @Test
    void aNameWithNoConceptIsHollowAndItalic() {
        DecisionFigure figure = figure();
        List<Circle> dots = figure.marks().stream().filter(Circle.class::isInstance).map(Circle.class::cast).toList();
        assertEquals(List.of("concept-mark", "concept-mark", "words-mark", "concept-mark"), dots.stream().map(Circle::role).toList());
        List<Text> names = names(figure);
        assertTrue(names.get(2).italic(), "words with no concept are set in italics");
        assertFalse(names.get(1).italic());
    }

    @Test
    void aLongNameWrapsAndTheNextLineClearsIt() {
        String longName = "k:id=x[" + "A very long name of a concept ".repeat(8).strip() + "]\n  k:id=y[Child]\n";
        DecisionFigure figure = KonceptTreePrint.figures(KonceptTreeBlockRenderer.parse(longName), node -> false).getFirst();
        List<Text> names = names(figure);
        assertTrue(names.size() > 2, "the long name takes more than one line");
        Text child = names.getLast();
        assertEquals("Child", child.text());
        assertTrue(child.top() > names.get(names.size() - 2).top(), "the child begins beneath the last line of its parent's name");
        assertTrue(figure.height() > child.top(), "the figure is tall enough for every line");
    }

    @Test
    void textThatIsNotATreeGivesNoFigure() {
        assertTrue(KonceptTreePrint.figures("not a token line").isEmpty(), "the block then prints as its source");
        assertTrue(KonceptTreePrint.figures((String) null).isEmpty());
    }
}
