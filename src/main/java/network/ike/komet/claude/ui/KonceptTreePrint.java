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
import network.ike.komet.document.DecisionFigure.Mark;
import network.ike.komet.document.DecisionFigure.Polyline;
import network.ike.komet.document.DecisionFigure.Rect;
import network.ike.komet.document.DecisionFigure.Stroke;
import network.ike.komet.document.DecisionFigure.Text;
import network.ike.komet.document.DecisionFigureText;
import network.ike.komet.document.TextFigure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * A {@code koncept-tree} block on a page: each concept on a line of its own, indented beneath its
 * parent and joined to it by a rule. A concept the knowledge base has is marked with a filled dot.
 * A name with no concept behind it is marked with a hollow dot and set in italics, so a reader of
 * the page can tell the two apart as a reader of the screen can, where the first is a chip and the
 * second is plain words.
 *
 * <p>The page shows the name the author wrote in the token's brackets, without the tag a
 * terminology writes after a name, as every other concept on a page is shown.
 */
final class KonceptTreePrint {

    private static final double SIZE = 10;
    private static final double LINE = DecisionFigureText.lineHeight(SIZE);
    /** How far a child stands to the right of its parent. */
    static final double INDENT = 14;
    private static final double INSET = 6;
    private static final double DOT = 2.2;
    /** From the centre of a row's dot to the start of its name. */
    private static final double NAME_GAP = 7;
    private static final Stroke RULE = Stroke.solid(DecisionFigureText.CAPTION, 0.5);

    private KonceptTreePrint() {
    }

    /**
     * The figure of a tree's text, asking the open knowledge base whether it has each concept.
     *
     * @param body the block's content: one {@code k:} token to a line, indentation giving the parent
     * @return one figure, or none when the text is not a well-formed tree, which then prints as its source
     */
    static List<DecisionFigure> figures(String body) {
        return figures(KonceptTreeBlockRenderer.parse(body == null ? "" : body),
                node -> KonceptTreeBlockRenderer.resolvePid(node.kind(), node.value()) != null);
    }

    /**
     * The figure of a parsed tree.
     *
     * @param nodes           the tree's lines in order
     * @param inKnowledgeBase whether the knowledge base has the concept a line names
     * @return one figure, or none for an empty tree
     */
    static List<DecisionFigure> figures(List<KonceptTreeBlockRenderer.ParsedNode> nodes,
                                        Predicate<KonceptTreeBlockRenderer.ParsedNode> inKnowledgeBase) {
        if (nodes.isEmpty()) {
            return List.of();
        }
        List<Mark> marks = new ArrayList<>();
        // The dot of the last line seen at each depth, as its parent's children are joined to it.
        Deque<double[]> parents = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        double y = INSET;
        for (KonceptTreeBlockRenderer.ParsedNode node : nodes) {
            while (!indents.isEmpty() && indents.peek() >= node.indent()) {
                indents.pop();
                parents.pop();
            }
            double dotX = INSET + DOT + parents.size() * INDENT;
            double dotY = y + LINE / 2;
            if (!parents.isEmpty()) {
                double[] parent = parents.peek();
                marks.add(new Polyline(new double[] {parent[0], parent[1] + DOT, parent[0], dotY, dotX - DOT, dotY}, RULE, "branch"));
            }
            boolean known = inKnowledgeBase.test(node);
            marks.add(new Circle(dotX, dotY, DOT, known ? DecisionFigureText.INK : "#FFFFFF",
                    Stroke.solid(DecisionFigureText.INK, 0.6), known ? "concept-mark" : "words-mark"));
            double nameX = dotX + NAME_GAP;
            for (String line : DecisionFigureText.wrap(name(node), SIZE, TextFigure.WIDTH - INSET - nameX)) {
                marks.add(new Text(nameX, y, line, SIZE, false, !known, DecisionFigureText.INK, known ? "concept" : "words"));
                y += LINE;
            }
            parents.push(new double[] {dotX, dotY});
            indents.push(node.indent());
        }
        marks.addFirst(new Rect(0, 0, TextFigure.WIDTH, y + INSET, "#F7F7F7", 1, null, "ground"));
        return List.of(new DecisionFigure(TextFigure.WIDTH, y + INSET, "koncept-tree", marks));
    }

    /** The name a line is shown by: the author's words in brackets, or the identifier when there are none. */
    private static String name(KonceptTreeBlockRenderer.ParsedNode node) {
        String label = node.label();
        return label == null || label.isBlank() ? node.value() : BlockPrint.untagged(label);
    }
}
