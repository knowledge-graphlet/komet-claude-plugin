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
package network.ike.komet.claude.doc;

import network.ike.komet.claude.ui.KonceptChipGestures;
import dev.ikm.komet.layout.expand.KlExpandable;
import dev.ikm.komet.markdown.richtext.DocumentSegment;
import dev.ikm.komet.markdown.richtext.MarkdownStyledModel;
import dev.ikm.komet.markdown.richtext.TableColumnWidths;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import jfx.incubator.scene.control.richtext.RichTextArea;
import network.ike.komet.claude.ui.MarkdownRichText;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds a conversation turn's {@link DocumentBlock}s for the {@link DocumentSurface} stack: a
 * role-header block, then one block per {@link DocumentSegment} — a prose run becomes a view-only
 * {@code RichTextArea} sized to its content; a native block (koncept-tree, table) becomes a direct
 * stack child, exactly the node the {@code BlockRenderer} built. Chips, trees, and tables render
 * through the same {@link MarkdownRichText} wiring as the single-area transcript, so the stack
 * changes packaging, never rendering.
 *
 * <p>Holds no card state — a later Scriptorium host reuses it unchanged. Must be used on the
 * JavaFX application thread (it builds nodes).
 */
public final class BlockFactory {

    /** The shared per-turn renderer (decorator + block renderer wired). */
    private final MarkdownRichText markdown;
    /** Base body font size (px); the header label scales from it. */
    private final double base;
    /** Base prose font family, or {@code null} for the platform default. */
    private final String fontFamily;
    /** Whether native block figures carry the {@code KlExpandable} expand-to-full-surface affordance. */
    private final boolean expandableFigures;

    /**
     * Equivalent to {@link #BlockFactory(ViewCalculator, double, String)} with the platform-default
     * prose font family (the on-screen surface).
     *
     * @param viewCalc the live view for resolving concept names for chips; may be {@code null}
     *                 (chips fall back to bare identicons)
     * @param base     base body font size in px
     */
    public BlockFactory(ViewCalculator viewCalc, double base) {
        this(viewCalc, base, null);
    }

    /**
     * Interactive-surface form: chips compute their logical-status cluster and carry the
     * definition popout (ike-issues#941). Print rendering stays on the {@link ViewCalculator}
     * forms — a popout affordance has no place on a printed page.
     *
     * @param viewProperties the surface's view; may be {@code null} (chips fall back to bare
     *                       identicons)
     * @param base           base body font size in px
     */
    public BlockFactory(ViewProperties viewProperties, double base) {
        this(new MarkdownRichText(viewProperties, base, null), base, null, true);
    }

    /**
     * Creates a factory for an interactive on-screen surface, rendering at the given view, font size,
     * and prose font family.
     *
     * @param viewCalc   the live view for resolving concept names for chips; may be {@code null}
     *                   (chips fall back to bare identicons)
     * @param base       base body font size in px
     * @param fontFamily the base prose font family (e.g. a serif family for a paged print
     *                   rendering); {@code null} uses the platform default. Code stays monospaced.
     */
    public BlockFactory(ViewCalculator viewCalc, double base, String fontFamily) {
        this(viewCalc, base, fontFamily, true);
    }

    /**
     * A factory for paged/print output: identical rendering, except native block figures carry no
     * expand affordance — an on-screen-only control has no place on a printed page, and it would
     * perturb the block's measured height during pagination.
     *
     * @param viewCalc   the view for resolving concept names for chips; may be {@code null}
     * @param base       base body font size in px
     * @param fontFamily the base prose font family; {@code null} uses the platform default
     * @return a factory that builds inert (non-expandable) block figures
     */
    public static BlockFactory forPrint(ViewCalculator viewCalc, double base, String fontFamily) {
        return new BlockFactory(viewCalc, base, fontFamily, false);
    }

    private BlockFactory(ViewCalculator viewCalc, double base, String fontFamily, boolean expandableFigures) {
        this(new MarkdownRichText(viewCalc, base, fontFamily), base, fontFamily, expandableFigures);
    }

    private BlockFactory(MarkdownRichText markdown, double base, String fontFamily,
                         boolean expandableFigures) {
        this.markdown = markdown;
        this.base = base;
        this.fontFamily = fontFamily;
        this.expandableFigures = expandableFigures;
    }

    /**
     * The base body font size this factory renders at, for hosts that
     * size their own chrome (exchange headers) relative to the prose.
     *
     * @return base body font size in px
     */
    public double baseFontSize() {
        return base;
    }

    /**
     * Sets the store for user-chosen table column widths (ike-issues#1034) on this factory's
     * Markdown engine — blocks built afterwards render tables with remembered widths and report
     * resize drags back to the store.
     *
     * @param store the width store, or {@code null} to make column resizing session-local
     */
    public void setTableColumnWidths(TableColumnWidths store) {
        markdown.setTableColumnWidths(store);
    }

    /**
     * Content-height {@code RichTextArea}s silently stop growing at the incubator's 10,000&nbsp;px
     * safeguard, so a prose run longer than this many paragraphs is split across several stacked
     * areas — invisible at the seam, immune to the cap.
     */
    private static final int MAX_PARAGRAPHS_PER_BLOCK = 250;

    /**
     * Builds one turn's blocks: the role header, then the turn's content segments.
     *
     * @param turn the turn to render
     * @return the turn's blocks, in order (never empty — the header is always present)
     */
    public List<DocumentBlock> turnBlocks(MarkdownRichText.Entry turn) {
        List<DocumentBlock> blocks = new ArrayList<>();
        blocks.add(headerBlock(turn.role()));
        for (DocumentSegment segment : markdown.toSegments(turn)) {
            switch (segment) {
                case DocumentSegment.ProseRun run -> {
                    for (DocumentSegment.ProseRun chunk : chunk(run)) {
                        blocks.add(proseBlock(chunk));
                    }
                }
                case DocumentSegment.NodeBlock node -> blocks.add(nodeBlock(node));
            }
        }
        return blocks;
    }

    /** Splits an over-long prose run into safeguard-safe chunks (most runs pass through whole). */
    private static List<DocumentSegment.ProseRun> chunk(DocumentSegment.ProseRun run) {
        if (run.paragraphs().size() <= MAX_PARAGRAPHS_PER_BLOCK) {
            return List.of(run);
        }
        List<DocumentSegment.ProseRun> chunks = new ArrayList<>();
        for (int from = 0; from < run.paragraphs().size(); from += MAX_PARAGRAPHS_PER_BLOCK) {
            int to = Math.min(from + MAX_PARAGRAPHS_PER_BLOCK, run.paragraphs().size());
            chunks.add(new DocumentSegment.ProseRun(
                    run.paragraphs().subList(from, to), run.plain().subList(from, to)));
        }
        return chunks;
    }

    /** The coloured, bold role label that opens every turn (find parity with the old transcript). */
    private DocumentBlock headerBlock(MarkdownRichText.Role role) {
        Label label = new Label(role.label());
        label.setFont(Font.font(fontFamily, FontWeight.BOLD, base));
        label.setTextFill(roleColor(role));
        label.getStyleClass().add("doc-turn-header");
        return DocumentBlock.of(label, role.label());
    }

    /** A prose run as a view-only, content-height {@code RichTextArea} over its own model. */
    private DocumentBlock proseBlock(DocumentSegment.ProseRun run) {
        MarkdownStyledModel model = new MarkdownStyledModel(run.paragraphs(), run.plain());
        RichTextArea area = new RichTextArea();
        // Chips drag on a single gesture (knowledge-graphlet/komet-claude-plugin#5).
        KonceptChipGestures.install(area);
        area.setEditable(false);
        area.setWrapText(true);
        area.setUseContentHeight(true);
        area.setModel(model);
        area.getStyleClass().add("doc-prose-block");
        return DocumentBlock.prose(area, model, String.join("\n", run.plain()));
    }

    /**
     * A native block node (koncept-tree VBox, table grid) as a direct stack child. Such a block is a
     * <em>figure</em> — content the surface bounds to its own width — so on an interactive surface it
     * adopts {@link KlExpandable}: a hover-revealed corner icon promotes it to the full surface at its
     * natural size, where a wide tree or table can finally be read whole.
     */
    private DocumentBlock nodeBlock(DocumentSegment.NodeBlock segment) {
        Supplier<Node> supplier = segment.node();
        Node node = supplier == null ? null : supplier.get();
        if (node == null) {
            // A misbehaving supplier never breaks the stack: degrade to the plain projection.
            Label fallback = new Label(segment.plainText());
            fallback.setFont(Font.font("monospace", base));
            return DocumentBlock.of(fallback, segment.plainText());
        }
        Region figure;
        if (node instanceof GridPane table) {
            // A wide table must not widen the whole stack (the surface never scrolls horizontally,
            // so an over-wide stack would clip every prose block). The table gets its own
            // horizontal-only scroll container — the one sanctioned exception for content with a
            // hard minimum width — and, unstretched, a narrow table hugs its content.
            ScrollPane tableScroll = new ScrollPane(table);
            // Fitted width: the table lays out at the viewport width, so
            // over-wide tables compress — cell TextFlows wrap harder —
            // and every column stays visible (ike-issues#1030/#1031:
            // whole columns hid behind the scrollbar in a narrow card).
            // ScrollPane floors fitted content at its MINIMUM width, so
            // the horizontal bar still appears in the one sanctioned
            // case: per-column minimum floors that genuinely exceed the
            // card. (Content maxWidth is ignored by non-fitted
            // ScrollPane layout — a bound maxWidth clamp does nothing.)
            tableScroll.setFitToWidth(true);
            // fitToHeight would make the grid's height FOLLOW the wrapper
            // (circular with the pref-height binding below); the grid must
            // own its content height for the block to expand to it.
            tableScroll.setFitToHeight(false);
            tableScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            tableScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            tableScroll.getStyleClass().add("doc-table-block");
            // Tables never scroll vertically (KEC invariant): the block's
            // preferred height tracks the grid's laid-out height, so the
            // stack gives the table its full height and only the outer
            // surface scrolls. Without this the wrapper took an arbitrary
            // default viewport height and the table panned inside it.
            // The renderer's inline-path width clamp (host minus margin)
            // is wrong inside this fitted wrapper: it reserved a dead
            // right gutter that read as scrollbar space (KEC review).
            // Registered after the renderer's own parent listener, this
            // runs second and clears the clamp for the doc path.
            table.parentProperty().addListener((obs, was, parent) -> {
                table.maxWidthProperty().unbind();
                table.setMaxWidth(Double.MAX_VALUE);
            });
            table.maxWidthProperty().unbind();
            table.setMaxWidth(Double.MAX_VALUE);
            tableScroll.prefHeightProperty().bind(javafx.beans.binding.Bindings
                    .createDoubleBinding(
                            () -> table.getBoundsInLocal().getHeight()
                                    + tableScroll.getInsets().getTop()
                                    + tableScroll.getInsets().getBottom() + 2,
                            table.boundsInLocalProperty(),
                            tableScroll.insetsProperty()));
            figure = tableScroll;
        } else if (node instanceof Region region) {
            figure = region;
        } else {
            return DocumentBlock.of(node, segment.plainText()); // not a region: nothing to decorate
        }
        if (!expandableFigures) {
            return DocumentBlock.of(figure, segment.plainText());
        }
        return DocumentBlock.of(expandableFigure(figure, supplier), segment.plainText());
    }

    /**
     * Decorates a block figure with the expand-to-full-surface affordance. The promoted view is built
     * from a <em>fresh</em> node off the same supplier, so the live block in the stack is never
     * reparented into the overlay (and reappears intact when it collapses).
     */
    private static Region expandableFigure(Region figure, Supplier<Node> supplier) {
        KlExpandable expandable = new KlExpandable() {
            @Override
            public Region figureNode() {
                return figure;
            }

            @Override
            public Region expandedView() {
                return enlarged(supplier.get());
            }
        };
        return expandable.withExpandAffordance();
    }

    /**
     * The full-surface view of a block figure: the node at its natural size in a pannable viewport.
     * A koncept-tree binds its max width to its parent, so the holder — sized to the tree's preferred
     * width rather than the viewport's — is what lifts the surface's width clamp, letting a deep tree's
     * long rows extend and scroll instead of being cut off at the transcript's edge.
     */
    private static Region enlarged(Node fresh) {
        Node content = fresh == null ? new Label("") : fresh;
        StackPane holder = new StackPane(content);
        StackPane.setAlignment(content, Pos.TOP_LEFT);
        holder.setPadding(new Insets(16));
        ScrollPane scroll = new ScrollPane(holder);
        scroll.setPannable(true);
        scroll.setFitToWidth(false);
        scroll.setFitToHeight(false);
        scroll.getStyleClass().add("doc-figure-enlarged");
        return scroll;
    }

    private static Color roleColor(MarkdownRichText.Role role) {
        return role.color();
    }
}
