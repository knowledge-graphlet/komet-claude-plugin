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
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.scene.Node;
import network.ike.komet.claude.calculator.CalculationText;
import network.ike.komet.claude.calculator.CalculatorText;
import network.ike.komet.claude.complexconcept.ComplexConceptText;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.stamp.StructuredBlockKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The decision blocks as structured kinds of a stored document (ike-lab-documents topic
 * {@code dev-decision-document}): a {@code [decision-tree]}, {@code [complex-concept]},
 * {@code [anf]}, {@code [calculator]} or {@code [calculate]} listing block in an AsciiDoc document
 * draws on the stamped surface as it draws in the assistant's transcript, through the same
 * renderer, over the document's {@link BlockContext}, so a tree folds in another tree of the same
 * document, finds the statements its nodes name and reads a calculator's value.
 *
 * <p>The block's attribute line becomes the renderer's info string, {@code [decision-tree,
 * cohort=100]} the info {@code decision-tree cohort=100}, and its content the renderer's literal.
 * Each kind is edited as its text for now; a renderer's write back is not offered here, since
 * nothing yet writes a version from this surface.
 */
public final class DocumentBlockKinds {

    private DocumentBlockKinds() {
    }

    /**
     * A block's attribute line as a fence's info string: the attributes, separated by commas in
     * AsciiDoc, separated by spaces; a quoted value keeps its quotes, commas and all.
     * {@code [decision-tree, cohort=100, title="Inspect, or not?"]} reads
     * {@code decision-tree cohort=100 title="Inspect, or not?"}.
     *
     * @param parameters the block's attribute line, verbatim
     * @return the info string
     */
    static String info(String parameters) {
        String line = parameters == null ? "" : parameters.strip();
        if (line.startsWith("[") && line.endsWith("]")) {
            line = line.substring(1, line.length() - 1);
        }
        List<String> attributes = new ArrayList<>();
        StringBuilder attribute = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                attributes.add(attribute.toString().strip());
                attribute.setLength(0);
                continue;
            }
            attribute.append(c);
        }
        attributes.add(attribute.toString().strip());
        attributes.removeIf(String::isEmpty);
        return String.join(" ", attributes);
    }

    /** A kind drawn by one of the transcript's block renderers. */
    private abstract static class RenderedKind implements StructuredBlockKind {
        private final String style;
        private final String name;

        RenderedKind(String style, String name) {
            this.style = style;
            this.name = name;
        }

        /** The renderer, over the document's context. */
        abstract BlockRenderer renderer(ViewCalculator view, double base, BlockContext blocks);

        @Override
        public final String style() {
            return style;
        }

        @Override
        public final String name() {
            return name;
        }

        @Override
        public final Node view(Source source, Context context) {
            String content = source.content();
            List<BlockPiece> pieces = renderer(context.view(), context.base(), context.blocks())
                    .render(info(source.parameters()), content.endsWith("\n") ? content : content + "\n", null);
            if (pieces.isEmpty()) {
                throw new IllegalArgumentException("not a " + name + " block the surface can draw");
            }
            return pieces.getFirst().node().get();
        }

        @Override
        public final Optional<Editor> editor(Source source, Context context) {
            return Optional.empty();
        }
    }

    /** {@code [decision-tree]}: the tree drawn and rolled back, its ledger, sentences and tornado. */
    public static final class DecisionTree extends RenderedKind {
        public DecisionTree() {
            super(DecisionTreeText.TAG, "decision tree");
        }

        @Override
        BlockRenderer renderer(ViewCalculator view, double base, BlockContext blocks) {
            return new DecisionTreeBlockRenderer(view, base, blocks);
        }
    }

    /** {@code [complex-concept]}: the complex concept's card. */
    public static final class ComplexConcept extends RenderedKind {
        public ComplexConcept() {
            super(ComplexConceptText.TAG, "complex concept");
        }

        @Override
        BlockRenderer renderer(ViewCalculator view, double base, BlockContext blocks) {
            return new ComplexConceptBlockRenderer(view, base, blocks);
        }
    }

    /** {@code [anf]}: the statement, folded, which a tree's node may name. */
    public static final class Anf extends RenderedKind {
        public Anf() {
            super("anf", "ANF statement");
        }

        @Override
        BlockRenderer renderer(ViewCalculator view, double base, BlockContext blocks) {
            return new AnfBlockRenderer(view, base, blocks);
        }
    }

    /** {@code [calculator]}: a calculator's method, which a calculation evaluates. */
    public static final class Calculator extends RenderedKind {
        public Calculator() {
            super(CalculatorText.TAG, "calculator");
        }

        @Override
        BlockRenderer renderer(ViewCalculator view, double base, BlockContext blocks) {
            return new CalculatorBlockRenderer(view, base, blocks);
        }
    }

    /** {@code [calculate]}: a calculation, whose value a tree may read. */
    public static final class Calculation extends RenderedKind {
        public Calculation() {
            super(CalculationText.TAG, "calculation");
        }

        @Override
        BlockRenderer renderer(ViewCalculator view, double base, BlockContext blocks) {
            return new CalculatorBlockRenderer(view, base, blocks);
        }
    }
}
