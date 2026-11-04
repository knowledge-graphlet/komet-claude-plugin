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
import network.ike.komet.claude.stamp.StructuredBlocks;
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
 * Each kind is edited as its text; what the reader tries in the drawing, a decision tree's
 * what-if, an ANF statement or a complex concept edited on its card, is written back as an
 * uncommitted edit of the block's lines, through the surface's save, where the surface edits.
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

    /**
     * An info string as a block's attribute line, the inverse of {@link #info}: the words,
     * separated by commas. {@code decision-tree health-price=50000 title="Inspect, or not?"}
     * reads {@code [decision-tree, health-price=50000, title="Inspect, or not?"]}.
     *
     * @param info the info string
     * @return the attribute line
     */
    static String attributeLine(String info) {
        return "[" + String.join(", ", network.ike.komet.claude.block.Tokens.words(info == null ? "" : info)) + "]";
    }

    /** A kind drawn by one of the transcript's block renderers. */
    private abstract static class RenderedKind implements StructuredBlockKind {
        private final String style;
        private final String name;

        RenderedKind(String style, String name) {
            this.style = style;
            this.name = name;
        }

        /** The renderer, over the document's context, writing back through the rewriter when there is one. */
        abstract BlockRenderer renderer(Context context, BlockRewriter rewriter);

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
            // a write back restates the block's text in the document: its content, and its attribute line when that changed
            BlockRewriter rewriter = context.save() == null ? null
                    : (info, literal, newInfo, newLiteral) -> context.save().accept(newInfo.equals(info)
                            ? source.withContent(newLiteral) : source.withParameters(attributeLine(newInfo), newLiteral));
            List<BlockPiece> pieces = renderer(context, rewriter)
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

    /** What a block's previous text says between its fences. */
    private static Optional<String> content(Optional<String> previous) {
        return previous.map(text -> network.ike.komet.claude.stamp.StructuredBlockKind.Source.of(text).content());
    }

    /** {@code [decision-tree]}: the tree drawn and rolled back, its ledger, sentences and tornado. */
    public static final class DecisionTree extends RenderedKind {
        public DecisionTree() {
            super(DecisionTreeText.TAG, "decision tree");
        }

        @Override
        BlockRenderer renderer(Context context, BlockRewriter rewriter) {
            DecisionTreeBlockRenderer renderer = new DecisionTreeBlockRenderer(context.view(), context.viewProperties(), context.base(), context.blocks());
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.claude.decision.render.DecisionFigure> figures(Source source, Context context) {
            String content = source.content();
            return BlockPrint.tree(info(source.parameters()), content.endsWith("\n") ? content : content + "\n", context.view(), context.blocks());
        }

        /** The tree, as a version of its tree semantic. */
        @Override
        public Optional<String> write(Source source, long block, Optional<String> previous, ViewCalculator view, dev.ikm.tinkar.coordinate.edit.EditCoordinate edit) {
            return Optional.of(network.ike.komet.claude.decision.DecisionTreeWrites
                    .write(info(source.parameters()), source.content(), view, edit, StructuredBlocks.context(block, content(previous))).message());
        }
    }

    /** {@code [complex-concept]}: the complex concept's card. */
    public static final class ComplexConcept extends RenderedKind {
        public ComplexConcept() {
            super(ComplexConceptText.TAG, "complex concept");
        }

        @Override
        BlockRenderer renderer(Context context, BlockRewriter rewriter) {
            ComplexConceptBlockRenderer renderer = new ComplexConceptBlockRenderer(context.view(), context.viewProperties(), context.base(), context.blocks());
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.claude.decision.render.DecisionFigure> figures(Source source, Context context) {
            return BlockPrint.complexConcept(source.content(), context.view(), context.blocks());
        }

        /** The complex concept: its first version, as Propose writes it, then versions of its specification. */
        @Override
        public Optional<String> write(Source source, long block, Optional<String> previous, ViewCalculator view, dev.ikm.tinkar.coordinate.edit.EditCoordinate edit) {
            return Optional.of(network.ike.komet.claude.complexconcept.ComplexConceptWrites
                    .write(source.content(), view, edit, StructuredBlocks.context(block, content(previous))).message());
        }
    }

    /** {@code [anf]}: the statement, folded, which a tree's node may name. */
    public static final class Anf extends RenderedKind {
        public Anf() {
            super("anf", "ANF statement");
        }

        @Override
        BlockRenderer renderer(Context context, BlockRewriter rewriter) {
            AnfBlockRenderer renderer = new AnfBlockRenderer(context.view(), context.viewProperties(), context.base(), context.blocks());
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.claude.decision.render.DecisionFigure> figures(Source source, Context context) {
            return BlockPrint.fields(name(), List.of(source.content().split("\n", -1)));
        }

        /**
         * The statement, as a statement of its block, through the ANF statement actions; a block
         * that only marks an element of the prose, with no content, states none.
         */
        @Override
        public Optional<String> write(Source source, long block, Optional<String> previous, ViewCalculator view, dev.ikm.tinkar.coordinate.edit.EditCoordinate edit) {
            if (source.content().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(network.ike.komet.claude.anf.AnfBlockWrites.write(source.lines(),
                    network.ike.komet.claude.decision.DecisionTreeWrites.blockId(info(source.parameters())).orElse(""), block, view, edit));
        }
    }

    /** {@code [calculator]}: a calculator's method, which a calculation evaluates. */
    public static final class Calculator extends RenderedKind {
        public Calculator() {
            super(CalculatorText.TAG, "calculator");
        }

        @Override
        BlockRenderer renderer(Context context, BlockRewriter rewriter) {
            CalculatorBlockRenderer renderer = new CalculatorBlockRenderer(context.view(), context.viewProperties(), context.base(), context.blocks());
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.claude.decision.render.DecisionFigure> figures(Source source, Context context) {
            return BlockPrint.fields(name(), List.of(source.content().split("\n", -1)));
        }
    }

    /** {@code [calculate]}: a calculation, whose value a tree may read. */
    public static final class Calculation extends RenderedKind {
        public Calculation() {
            super(CalculationText.TAG, "calculation");
        }

        @Override
        BlockRenderer renderer(Context context, BlockRewriter rewriter) {
            CalculatorBlockRenderer renderer = new CalculatorBlockRenderer(context.view(), context.viewProperties(), context.base(), context.blocks());
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.claude.decision.render.DecisionFigure> figures(Source source, Context context) {
            return BlockPrint.fields(name(), List.of(source.content().split("\n", -1)));
        }
    }
}
