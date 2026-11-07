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
import network.ike.komet.claude.decision.GraphletModelText;
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

    /**
     * {@code [decision-tree, model=…]}: a rendering of a graphlet model, the tree drawn and rolled
     * back with its ledger and sentences; it holds no structure and no number, so it writes nothing.
     */
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
            String info = info(source.parameters());
            Optional<String> body = modelBody(info, source, context);
            return body.map(text -> BlockPrint.tree(info, text, context.view(), context.blocks())).orElse(List.of());
        }

        /** A block with a body of its own, as the assistant's replies state one, is written as its tree. */
        @Override
        public Optional<String> write(Source source, long block, Optional<String> previous, ViewCalculator view, dev.ikm.tinkar.coordinate.edit.EditCoordinate edit) {
            if (GraphletModelText.modelReference(info(source.parameters())).isPresent()) {
                return Optional.empty();
            }
            return Optional.of(network.ike.komet.claude.decision.DecisionTreeWrites
                    .write(info(source.parameters()), source.content(), view, edit, StructuredBlocks.context(block, content(previous)),
                            ModelDocument.of(block, view)).message());
        }
    }

    /** The text a rendering reads: the model it names, when the document has it, else the block's own content. */
    static Optional<String> modelBody(String info, StructuredBlockKind.Source source, StructuredBlockKind.Context context) {
        Optional<String> model = GraphletModelText.modelReference(info);
        if (model.isPresent()) {
            return context.blocks().model(model.get()).map(network.ike.komet.claude.decision.GraphletModel::body);
        }
        String content = source.content();
        return Optional.of(content.endsWith("\n") ? content : content + "\n");
    }

    /**
     * {@code [graphlet-model]}: the model of a decision, structure and parameters as one versioned
     * property graph ({@code dev-decision-point-knowledge}, 2026-11-06). The block publishes its
     * model for the document's renderings, and on a page prints its parameters.
     */
    public static final class GraphletModel extends RenderedKind {
        public GraphletModel() {
            super(GraphletModelText.TAG, "graphlet model");
        }

        @Override
        BlockRenderer renderer(Context context, BlockRewriter rewriter) {
            GraphletModelBlockRenderer renderer = new GraphletModelBlockRenderer(context.view(), context.viewProperties(), context.base(), context.blocks());
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.claude.decision.render.DecisionFigure> figures(Source source, Context context) {
            String content = source.content();
            String body = content.endsWith("\n") ? content : content + "\n";
            String info = info(source.parameters());
            GraphletModelBlockRenderer.publish(info, body, context.blocks(), null);
            return BlockPrint.model(info, body, context.view(), context.blocks());
        }

        /**
         * The model, as a version of its graph in the document's module, one version per value the
         * person commits: a parameter that reads one of the document's statements holds the
         * statement the anf block of that name writes ({@link ModelDocument}), its value and its
         * scale. The text, which is the canonical form, is saved regardless.
         */
        @Override
        public Optional<String> write(Source source, long block, Optional<String> previous, ViewCalculator view, dev.ikm.tinkar.coordinate.edit.EditCoordinate edit) {
            return Optional.of(network.ike.komet.claude.decision.DecisionTreeWrites
                    .write(info(source.parameters()), source.content(), view, edit, StructuredBlocks.context(block, content(previous)),
                            ModelDocument.of(block, view)).message());
        }
    }

    /** {@code [tornado, model=…]}: a rendering of a graphlet model as its one-way sensitivity. */
    public static final class Tornado extends RenderedKind {
        public Tornado() {
            super(TornadoBlockRenderer.TAG, "tornado");
        }

        @Override
        BlockRenderer renderer(Context context, BlockRewriter rewriter) {
            TornadoBlockRenderer renderer = new TornadoBlockRenderer(context.view(), context.viewProperties(), context.base(), context.blocks());
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.claude.decision.render.DecisionFigure> figures(Source source, Context context) {
            String info = info(source.parameters());
            Optional<String> body = modelBody(info, source, context);
            return body.map(text -> BlockPrint.tornado(info, text, context.view(), context.blocks())).orElse(List.of());
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
            AnfBlockRenderer.publish(info(source.parameters()), source.content(), context.view(), context.blocks());
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

    /** A benefit-risk view, drawn from its text and edited as text; a proof of concept, so it writes nothing to the store. */
    private abstract static class BenefitRiskKind extends RenderedKind {
        private final BenefitRiskBlockRenderer.View view;

        BenefitRiskKind(String style, String name, BenefitRiskBlockRenderer.View view) {
            super(style, name);
            this.view = view;
        }

        @Override
        BlockRenderer renderer(Context context, BlockRewriter rewriter) {
            return new BenefitRiskBlockRenderer(view, context.view(), context.viewProperties(), context.base(), context.blocks());
        }

        @Override
        public List<network.ike.komet.claude.decision.render.DecisionFigure> figures(Source source, Context context) {
            return BenefitRiskBlockRenderer.figures(view, info(source.parameters()), source.content(), context.blocks());
        }
    }

    /** {@code [value-tree]}: the objectives of a decision, a goal over domains over criteria and gates. */
    public static final class ValueTree extends BenefitRiskKind {
        public ValueTree() {
            super(network.ike.komet.claude.benefitrisk.ValueTree.TAG, "value tree", BenefitRiskBlockRenderer.View.TREE);
        }
    }

    /** {@code [effects-table]}: one row per leaf of the tree it names, each effect computed from its arms. */
    public static final class EffectsTable extends BenefitRiskKind {
        public EffectsTable() {
            super(network.ike.komet.claude.benefitrisk.EffectsTable.TAG, "effects table", BenefitRiskBlockRenderer.View.TABLE);
        }
    }

    /** {@code [forest-plot]}: the effects table it names, drawn on one axis; it holds nothing of its own. */
    public static final class ForestPlot extends BenefitRiskKind {
        public ForestPlot() {
            super("forest-plot", "forest plot", BenefitRiskBlockRenderer.View.FOREST);
        }
    }

    /** {@code [device-record]}: what a record of the Device Extension would state about a device. */
    public static final class DeviceRecord extends BenefitRiskKind {
        public DeviceRecord() {
            super(network.ike.komet.claude.benefitrisk.DeviceRecord.TAG, "device record", BenefitRiskBlockRenderer.View.DEVICE);
        }
    }

    /** {@code [findings]}: a mock of the findings the Device Extension would hold. */
    public static final class Findings extends BenefitRiskKind {
        public Findings() {
            super(network.ike.komet.claude.benefitrisk.Findings.TAG, "findings", BenefitRiskBlockRenderer.View.FINDINGS);
        }
    }

    /** {@code [findings-lookup]}: which findings reach a device, as of a year every view then follows. */
    public static final class FindingsLookup extends BenefitRiskKind {
        public FindingsLookup() {
            super("findings-lookup", "findings lookup", BenefitRiskBlockRenderer.View.LOOKUP);
        }
    }
}
