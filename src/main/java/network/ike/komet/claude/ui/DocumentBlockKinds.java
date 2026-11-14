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

import network.ike.komet.document.BlockRewriter;

import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.scene.Node;
import network.ike.komet.claude.calculator.CalculationText;
import network.ike.komet.claude.calculator.CalculatorText;
import network.ike.komet.claude.complexconcept.ComplexConceptText;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.decision.GraphletModelText;
import network.ike.komet.document.StructuredBlocks;
import network.ike.komet.document.StructuredBlockKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The decision blocks as structured kinds of a stored document (ike-lab-documents topic
 * {@code dev-decision-document}): a {@code [decision-tree]}, {@code [complex-concept]},
 * {@code [anf]}, {@code [calculator]} or {@code [calculate]} listing block in an AsciiDoc document
 * draws on the stamped surface as it draws in the assistant's transcript, through the same
 * renderer, over the document's {@link ProfileBlocks}, so a tree folds in another tree of the same
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
    public static String info(String parameters) {
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
        abstract FenceRenderer renderer(Context context, BlockRewriter rewriter);

        @Override
        public final String style() {
            return style;
        }

        @Override
        public final String name() {
            return name;
        }

        /**
         * The renderer for a block that continues a run of blocks of this kind: the kind's
         * renderer by default, which a kind that reads as a run tells of the blocks before.
         */
        FenceRenderer renderer(Context context, BlockRewriter rewriter, List<Source> before) {
            return renderer(context, rewriter);
        }

        @Override
        public final Node view(Source source, Context context) {
            return view(source, List.of(), context);
        }

        @Override
        public final Node view(Source source, List<Source> before, Context context) {
            String content = source.content();
            // a write back restates the block's text in the document: its content, and its attribute line when that changed
            BlockRewriter rewriter = context.save() == null ? null
                    : (info, literal, newInfo, newLiteral) -> context.save().accept(newInfo.equals(info)
                            ? source.withContent(newLiteral) : source.withParameters(attributeLine(newInfo), newLiteral));
            List<BlockPiece> pieces = renderer(context, rewriter, before)
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

        /** The same renderer draws the kind's fences in a passage, writing back once the passage sets a rewriter. */
        @Override
        public final Optional<FenceRenderer> fenceRenderer(Context context) {
            return Optional.of(renderer(context, null));
        }
    }

    /** What a block's previous text says between its fences. */
    private static Optional<String> content(Optional<String> previous) {
        return previous.map(text -> network.ike.komet.document.StructuredBlockKind.Source.of(text).content());
    }

    /** The first model the document's blocks have published, which a new rendering names. */
    static Optional<String> firstModel(StructuredBlockKind.Context context) {
        return context.blocks() == null ? Optional.empty() : ProfileBlocks.of(context.blocks()).models().keySet().stream().findFirst();
    }

    /** The skeleton of a new decision as the tree grammar states it: one choice, two alternatives, an outcome each. */
    static final String DECISION_SKELETON = """
            decision: k:Choice[The choice to make] @id=new-decision
              alternative: k:FirstAlternative[The first alternative] @id=new-first
                outcome: k:FirstOutcome[What follows from the first alternative] @id=new-first-outcome
              alternative: k:SecondAlternative[The second alternative] @id=new-second
                outcome: k:SecondOutcome[What follows from the second alternative] @id=new-second-outcome
            """;

    /**
     * {@code [decision-tree, model=…]}: a rendering of a graphlet model, the tree drawn and rolled
     * back with its ledger and sentences; it holds no structure and no number, so it writes nothing.
     */
    public static final class DecisionTree extends RenderedKind {
        public DecisionTree() {
            super(DecisionTreeText.TAG, "decision tree");
        }

        @Override
        FenceRenderer renderer(Context context, BlockRewriter rewriter) {
            DecisionTreeBlockRenderer renderer = new DecisionTreeBlockRenderer(context.view(), context.viewProperties(), context.base(), ProfileBlocks.of(context.blocks()));
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.document.DecisionFigure> figures(Source source, Context context) {
            String info = info(source.parameters());
            Optional<String> body = modelBody(info, source, context);
            return body.map(text -> BlockPrint.tree(info, text, context.view(), ProfileBlocks.of(context.blocks()))).orElse(List.of());
        }

        /** A rendering of the document's model when it has one, drawn at once; else a tree of its own, opened for editing. */
        @Override
        public Optional<Skeleton> skeleton(Context context) {
            Optional<String> model = firstModel(context);
            if (model.isPresent()) {
                return Optional.of(Skeleton.listing("[decision-tree, model=" + model.get() + ", cohort=100]", "", false));
            }
            return Optional.of(Skeleton.listing("[decision-tree, cohort=100, title=\"The choice to make\"]", DECISION_SKELETON, true));
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
            return ProfileBlocks.of(context.blocks()).model(model.get()).map(network.ike.komet.claude.decision.GraphletModel::body);
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
        FenceRenderer renderer(Context context, BlockRewriter rewriter) {
            GraphletModelBlockRenderer renderer = new GraphletModelBlockRenderer(context.view(), context.viewProperties(), context.base(), ProfileBlocks.of(context.blocks()));
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.document.DecisionFigure> figures(Source source, Context context) {
            String content = source.content();
            String body = content.endsWith("\n") ? content : content + "\n";
            String info = info(source.parameters());
            GraphletModelBlockRenderer.publish(info, body, ProfileBlocks.of(context.blocks()), null);
            return BlockPrint.model(info, body, context.view(), ProfileBlocks.of(context.blocks()));
        }

        /** A model of a new decision: the tree with one chance, and the parameter the chance reads. */
        @Override
        public Optional<Skeleton> skeleton(Context context) {
            String body = """
                    decision: k:Choice[The choice to make] @id=new-decision
                      parameter = [0.5,0.5] k:EventChance[Chance that the event happens] @id=new-event-chance basis=estimated
                      alternative: k:FirstAlternative[The first alternative] @id=new-first
                        chance: k:Event[Whether the event happens] @id=new-event
                          event: k:Happens[The event happens] @id=new-happens
                            p = from k:EventChance[] @id=new-event-chance
                            outcome: k:FirstOutcomeIfHappens[What follows when the event happens] @id=new-first-happens
                          residual: k:DoesNotHappen[The event does not happen] @id=new-does-not-happen
                            outcome: k:FirstOutcomeIfNot[What follows when it does not] @id=new-first-not
                      alternative: k:SecondAlternative[The second alternative] @id=new-second
                        outcome: k:SecondOutcome[What follows from the second alternative] @id=new-second-outcome
                    """;
            return Optional.of(Skeleton.listing("[graphlet-model, id=new-model]", body, true));
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
        FenceRenderer renderer(Context context, BlockRewriter rewriter) {
            TornadoBlockRenderer renderer = new TornadoBlockRenderer(context.view(), context.viewProperties(), context.base(), ProfileBlocks.of(context.blocks()));
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.document.DecisionFigure> figures(Source source, Context context) {
            String info = info(source.parameters());
            Optional<String> body = modelBody(info, source, context);
            return body.map(text -> BlockPrint.tornado(info, text, context.view(), ProfileBlocks.of(context.blocks()))).orElse(List.of());
        }

        /** The tornado of the document's model, drawn at once when the document has one. */
        @Override
        public Optional<Skeleton> skeleton(Context context) {
            Optional<String> model = firstModel(context);
            return Optional.of(Skeleton.listing("[tornado, model=" + model.orElse("model-id") + "]", "", model.isEmpty()));
        }
    }

    /** {@code [complex-concept]}: the complex concept's card. */
    public static final class ComplexConcept extends RenderedKind {
        public ComplexConcept() {
            super(ComplexConceptText.TAG, "complex concept");
        }

        @Override
        FenceRenderer renderer(Context context, BlockRewriter rewriter) {
            ComplexConceptBlockRenderer renderer = new ComplexConceptBlockRenderer(context.view(), context.viewProperties(), context.base(), ProfileBlocks.of(context.blocks()));
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.document.DecisionFigure> figures(Source source, Context context) {
            return BlockPrint.complexConcept(source.content(), context.view(), ProfileBlocks.of(context.blocks()));
        }

        /** A concept named and not yet defined, opened in its editor. */
        @Override
        public Optional<Skeleton> skeleton(Context context) {
            return Optional.of(Skeleton.listing("[complex-concept]", "concept: k:NewConcept[A new concept] @id=new-concept", true));
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

        /** A note's clinical elements, drawn in a passage's prose beneath the concept chips. */
        @Override
        public dev.ikm.komet.markdown.richtext.InlineDecorator decorate(dev.ikm.komet.markdown.richtext.InlineDecorator next, Context context) {
            return new ElementSpanDecorator(next, ProfileBlocks.of(context.blocks()).elementSpans());
        }

        /** Finds the element spans the passage's text holds. */
        @Override
        public void preparePassage(String markdown, Context context) {
            ProfileBlocks.of(context.blocks()).elementSpans().prepare(markdown);
        }

        @Override
        FenceRenderer renderer(Context context, BlockRewriter rewriter) {
            AnfBlockRenderer renderer = new AnfBlockRenderer(context.view(), context.viewProperties(), context.base(), ProfileBlocks.of(context.blocks()));
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.document.DecisionFigure> figures(Source source, Context context) {
            return figures(source, List.of(), context);
        }

        /** Statements one directly after another read as a run: a group is named once, above its first statement. */
        @Override
        public boolean readsAsRun() {
            return true;
        }

        /** A statement that follows another is drawn knowing the group of the one before it, which it does not name again. */
        @Override
        FenceRenderer renderer(Context context, BlockRewriter rewriter, List<Source> before) {
            AnfBlockRenderer renderer = new AnfBlockRenderer(context.view(), context.viewProperties(), context.base(), ProfileBlocks.of(context.blocks()));
            renderer.setRewriter(rewriter);
            renderer.setGroupBefore(before.isEmpty() ? null
                    : StatementLines.read(before.getLast().parameters(), List.of(before.getLast().content().split("\n", -1))).group());
            return renderer;
        }

        /**
         * A statement as its line: under its group's heading, which a statement about the group
         * of the statement before it does not print again. A block that names no topic prints
         * its fields, as before.
         */
        @Override
        public List<network.ike.komet.document.DecisionFigure> figures(Source source, List<Source> before, Context context) {
            AnfBlockRenderer.publish(info(source.parameters()), source.content(), context.view(), ProfileBlocks.of(context.blocks()));
            List<String> lines = List.of(source.content().split("\n", -1));
            StatementLines.Statement statement = StatementLines.read(source.parameters(), lines);
            if (statement.topic().isEmpty()) {
                return BlockPrint.fields(name(), lines);
            }
            boolean heading = before.isEmpty() || !StatementLines
                    .read(before.getLast().parameters(), List.of(before.getLast().content().split("\n", -1))).group().equals(statement.group());
            return List.of(StatementLines.figure(statement, heading));
        }

        /** A performance with its topic and result still to name, in the open-block form the lab documents take. */
        @Override
        public Optional<Skeleton> skeleton(Context context) {
            String body = """
                    topic:: candidate[What was observed] disp=PENDING
                    result:: [0,0] candidate[The scale it was observed on] disp=PENDING
                    """;
            return Optional.of(Skeleton.open("[anf,type=performance,id=new-statement]", body, true));
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

        /** A calculator with one output and one input, its equation still to write. */
        @Override
        public Optional<Skeleton> skeleton(Context context) {
            String body = """
                    calculator: k:NewCalculator[A new calculator] @id=new-calculator
                      output = k:Result[The result] @id=new-result in k:Unit[The unit of the result] @id=new-unit
                      input: k:Input[An input] @id=new-input topic=k:InputTopic[What the input measures] @id=new-input-topic in k:Unit[] @id=new-unit required
                    """;
            return Optional.of(Skeleton.listing("[calculator, title=\"A new calculator\"]", body, true));
        }

        @Override
        FenceRenderer renderer(Context context, BlockRewriter rewriter) {
            CalculatorBlockRenderer renderer = new CalculatorBlockRenderer(context.view(), context.viewProperties(), context.base(), ProfileBlocks.of(context.blocks()));
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.document.DecisionFigure> figures(Source source, Context context) {
            return BlockPrint.fields(name(), List.of(source.content().split("\n", -1)));
        }
    }

    /** {@code [calculate]}: a calculation, whose value a tree may read. */
    public static final class Calculation extends RenderedKind {
        public Calculation() {
            super(CalculationText.TAG, "calculation");
        }

        /** A calculation binding one input to a statement, the calculator and statement still to name. */
        @Override
        public Optional<Skeleton> skeleton(Context context) {
            String body = """
                    calculate: k:Result[The result] @id=new-result
                      bind = k:Input[An input] @id=new-input anf:statement-id
                    """;
            return Optional.of(Skeleton.listing("[calculate]", body, true));
        }

        @Override
        FenceRenderer renderer(Context context, BlockRewriter rewriter) {
            CalculatorBlockRenderer renderer = new CalculatorBlockRenderer(context.view(), context.viewProperties(), context.base(), ProfileBlocks.of(context.blocks()));
            renderer.setRewriter(rewriter);
            return renderer;
        }

        @Override
        public List<network.ike.komet.document.DecisionFigure> figures(Source source, Context context) {
            return BlockPrint.fields(name(), List.of(source.content().split("\n", -1)));
        }
    }

    /** A benefit-risk view, drawn from its text and edited as text; a proof of concept, so it writes nothing to the store. */
    private abstract static class BenefitRiskKind extends RenderedKind {
        private final BenefitRiskBlockRenderer.View view;
        private final String skeletonAttributes;
        private final String skeletonBody;

        BenefitRiskKind(String style, String name, BenefitRiskBlockRenderer.View view, String skeletonAttributes, String skeletonBody) {
            super(style, name);
            this.view = view;
            this.skeletonAttributes = skeletonAttributes;
            this.skeletonBody = skeletonBody;
        }

        /** The block's skeleton, opened for editing: every benefit-risk block is edited as its text. */
        @Override
        public Optional<Skeleton> skeleton(Context context) {
            return Optional.of(Skeleton.listing(skeletonAttributes, skeletonBody, true));
        }

        @Override
        FenceRenderer renderer(Context context, BlockRewriter rewriter) {
            return new BenefitRiskBlockRenderer(view, context.view(), context.viewProperties(), context.base(), ProfileBlocks.of(context.blocks()));
        }

        @Override
        public List<network.ike.komet.document.DecisionFigure> figures(Source source, Context context) {
            return BenefitRiskBlockRenderer.figures(view, info(source.parameters()), source.content(), ProfileBlocks.of(context.blocks()));
        }
    }

    /** {@code [value-tree]}: the objectives of a decision, a goal over domains over criteria and gates. */
    public static final class ValueTree extends BenefitRiskKind {
        public ValueTree() {
            super(network.ike.komet.claude.benefitrisk.ValueTree.TAG, "value tree", BenefitRiskBlockRenderer.View.TREE,
                    "[value-tree, id=new-value-tree]", """
                    goal: k:Goal[What the decision is for] @id=new-goal
                      domain: k:Favorable[Favorable effects] @id=new-favorable polarity=favorable
                        criterion: k:Benefit[A benefit, in its unit] @id=new-benefit better=higher
                      domain: k:Unfavorable[Unfavorable effects] @id=new-unfavorable polarity=unfavorable
                        criterion: k:Harm[A harm, in its unit] @id=new-harm better=lower
                    """);
        }
    }

    /** {@code [effects-table]}: one row per leaf of the tree it names, each effect computed from its arms. */
    public static final class EffectsTable extends BenefitRiskKind {
        public EffectsTable() {
            super(network.ike.komet.claude.benefitrisk.EffectsTable.TAG, "effects table", BenefitRiskBlockRenderer.View.TABLE,
                    "[effects-table, id=new-effects, tree=new-value-tree]", """
                    alternative: k:FirstAlternative[The first alternative] @id=new-first
                    alternative: k:SecondAlternative[The second alternative] @id=new-second
                    row: k:Benefit[] @id=new-benefit
                      arm: new-first mean=0 sd=1 n=1 source=source-id
                      arm: new-second mean=0 sd=1 n=1 source=source-id
                    """);
        }
    }

    /** {@code [forest-plot]}: the effects table it names, drawn on one axis; it holds nothing of its own. */
    public static final class ForestPlot extends BenefitRiskKind {
        public ForestPlot() {
            super("forest-plot", "forest plot", BenefitRiskBlockRenderer.View.FOREST, "[forest-plot, table=new-effects]", "");
        }
    }

    /** {@code [device-record]}: what a record of the Device Extension would state about a device. */
    public static final class DeviceRecord extends BenefitRiskKind {
        public DeviceRecord() {
            super(network.ike.komet.claude.benefitrisk.DeviceRecord.TAG, "device record", BenefitRiskBlockRenderer.View.DEVICE,
                    "[device-record, id=new-device]", """
                    device: k:Device[The device] @id=new-device
                      material: k:Material[A material the device is made of] @id=new-material
                      states = "What the device is for, and how it is used."
                    """);
        }
    }

    /** {@code [findings]}: a mock of the findings the Device Extension would hold. */
    public static final class Findings extends BenefitRiskKind {
        public Findings() {
            super(network.ike.komet.claude.benefitrisk.Findings.TAG, "findings", BenefitRiskBlockRenderer.View.FINDINGS,
                    "[findings, id=new-findings]", """
                    finding: k:Finding[What was found] @id=new-finding
                      statement = anf:statement-id
                      material: k:Material[The material the finding concerns] @id=new-material
                      source = "Who reported it, and when"
                    """);
        }
    }

    /** {@code [findings-lookup]}: which findings reach a device, as of a year every view then follows. */
    public static final class FindingsLookup extends BenefitRiskKind {
        public FindingsLookup() {
            super("findings-lookup", "findings lookup", BenefitRiskBlockRenderer.View.LOOKUP,
                    "[findings-lookup, device=new-device, findings=new-findings, year=2026]", "");
        }
    }

    /**
     * {@code [koncept-tree]}: the hierarchy under a concept, drawn as a tree of its children; the
     * concept is named by a token line, {@code k:sctid=…[label]}.
     */
    public static final class KonceptTree extends RenderedKind {
        public KonceptTree() {
            super(KonceptTreeBlockRenderer.TAG, "koncept tree");
        }

        @Override
        FenceRenderer renderer(Context context, BlockRewriter rewriter) {
            return context.viewProperties() == null
                    ? new KonceptTreeBlockRenderer(context.view(), context.base())
                    : new KonceptTreeBlockRenderer(context.viewProperties(), context.base());
        }

        /** A tree rooted at the SNOMED CT root, the concept to replace by the one the person means. */
        @Override
        public Optional<Skeleton> skeleton(Context context) {
            return Optional.of(Skeleton.listing("[koncept-tree]", "k:sctid=138875005[SNOMED CT Concept]", true));
        }
    }

    /** {@code [koncept-definition]}: a proposed definition, its necessary and sufficient sets over concepts. */
    public static final class KonceptDefinition extends RenderedKind {
        public KonceptDefinition() {
            super(network.ike.komet.claude.authoring.ProposedDefinition.TAG, "koncept definition");
        }

        @Override
        FenceRenderer renderer(Context context, BlockRewriter rewriter) {
            return new KonceptDefinitionBlockRenderer(context.view(), context.base());
        }

        /** A definition with a title and one necessary parent, to be refined. */
        @Override
        public Optional<Skeleton> skeleton(Context context) {
            String body = """
                    proposed: A new concept
                    necessary
                      k:sctid=404684003[Clinical finding]
                    """;
            return Optional.of(Skeleton.listing("[koncept-definition]", body, true));
        }
    }
}
