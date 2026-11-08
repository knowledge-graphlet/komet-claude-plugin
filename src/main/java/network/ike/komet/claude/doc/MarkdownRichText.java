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











import network.ike.komet.claude.stamp.ConceptChipInlineDecorator;

import network.ike.komet.claude.stamp.BlockContext;
import network.ike.komet.claude.stamp.BlockRewriter;
import network.ike.komet.claude.stamp.StructuredBlockKind;
import network.ike.komet.claude.stamp.StructuredBlocks;

import dev.ikm.komet.framework.controls.KonceptLabelTypography;
import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.komet.markdown.richtext.DocumentSegment;
import dev.ikm.komet.markdown.richtext.InlineDecorator;
import dev.ikm.komet.markdown.richtext.MarkdownRichTextRenderer;
import dev.ikm.komet.markdown.richtext.MarkdownStyledModel;
import dev.ikm.komet.markdown.richtext.TableColumnWidths;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.scene.paint.Color;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds a view-only {@link StyledTextModel} for the assistant transcript: each {@link Entry}
 * contributes a coloured role label followed by its content, with Markdown rendered to styled
 * {@link RichParagraph} runs by the shared {@link MarkdownRichTextRenderer}.
 *
 * <p>The grounding behaviour lives in {@link ConceptChipInlineDecorator}, the
 * {@link dev.ikm.komet.markdown.richtext.InlineDecorator} handed to the renderer: any concept
 * identifier the assistant reports — an SCTID or a UUID — is followed by a
 * <em>concept chip</em> (LifeHash identicon + store-resolved name), existence-gated against
 * the live store and struck through when the component is inactive (#586). Block structure is
 * dispatched through the {@link StructuredBlockKind}s: a fenced block goes to the kind whose
 * {@linkplain StructuredBlockKind#fenceRenderer fence renderer} draws it, so a
 * {@code koncept-tree} fence builds a real interactive {@code TreeView} of Koncept chips
 * (#801/#805) and a {@code decision-tree} fence draws as the same block does in a stored document.
 * This class names no kind. The renderer is the generic, reusable Markdown engine; this class
 * only composes the role-labelled transcript.
 */
public final class MarkdownRichText {

    /** Default transcript base font size (px); overridable per-instance for zoom. */
    public static final double DEFAULT_BASE = 13;

    /**
     * A block node's property holding an {@code ObservableBooleanValue}: whether the block shows.
     * A surface that stacks blocks lays a hidden one out as absent, spacing and all, so a block
     * that hides itself, an element row at the Narrative level, leaves no gap.
     */
    public static final String BLOCK_SHOWN = "network.ike.komet.claude.block.shown";

    /** A block node's property, present when the block is a line of text that needs no expand-to-full-surface corner. */
    public static final String BLOCK_NO_EXPAND = "network.ike.komet.claude.block.no-expand";

    /** A transcript role, with its label and accent colour. */
    public enum Role {
        USER("You", Color.web("#1a56db")),
        ASSISTANT("Komet Assistant", Color.web("#b15c00")),
        ERROR("Error", Color.web("#b00020")),
        /** Komet itself speaking in a conversation: what it read in a block, sent back for correction. */
        KOMET("Komet", Color.web("#4a6b2a")),
        /** A document that is no one's turn — a page rendered from a store — with no role label at all. */
        DOCUMENT("", Color.BLACK);

        final String label;
        final Color color;

        Role(String label, Color color) {
            this.label = label;
            this.color = color;
        }

        /**
         * The role's display label (e.g. {@code "You"}).
         *
         * @return the label shown for turns of this role
         */
        public String label() {
            return label;
        }

        /**
         * The role's accent colour.
         *
         * @return the colour the role's label renders in
         */
        public Color color() {
            return color;
        }
    }

    /**
     * One transcript message.
     *
     * @param role     who is speaking
     * @param content  the message text (Markdown when {@code markdown} is true)
     * @param markdown whether to render {@code content} as Markdown
     */
    public record Entry(Role role, String content, boolean markdown) {

        /**
         * A person's turn: drawn as the plain text they typed, unless it carries a fenced block,
         * which is deliberate, so a pasted {@code decision-tree} or {@code koncept-tree} block
         * renders in the prompt as it does in a reply.
         */
        public static Entry user(String content) {
            return new Entry(Role.USER, content, fenced(content));
        }

        /** Whether a text opens a fenced code block: a line beginning with three backticks. */
        public static boolean fenced(String content) {
            if (content == null) {
                return false;
            }
            return content.lines().anyMatch(line -> line.startsWith("```"));
        }
    }

    /** Base body font size (px); the role label and the renderer both scale from it. */
    private final double base;
    /** Shared Markdown engine, wired with the concept-chip decorator for grounding. */
    private final MarkdownRichTextRenderer renderer;
    /** The inline chip decorator, held so chip typography can be forwarded (ike-issues#1050). */
    private final ConceptChipInlineDecorator decorator;
    /** What the blocks of this rendering make known to each other. */
    private final BlockContext context;
    /** The context the kinds read, over this rendering's view and blocks, writing nothing. */
    private final StructuredBlockKind.Context kindContext;
    /** The kinds found when this rendering was made, in the order they were found. */
    private final List<StructuredBlockKind> kinds;
    /** Each kind's fence renderer, asked once, so a renderer's state lasts as long as this rendering. */
    private final List<StructuredBlockKind.FenceRenderer> fences;

    /**
     * Equivalent to {@link #MarkdownRichText(ViewCalculator, double, String)} with the
     * platform-default prose font family.
     *
     * @param viewCalc the live view for resolving concept names for chips; if null, chips
     *                 fall back to a bare identicon
     * @param base     base body font size in px (see {@link #DEFAULT_BASE})
     */
    public MarkdownRichText(ViewCalculator viewCalc, double base) {
        this(viewCalc, base, null);
    }

    /**
     * Interactive-surface form: chips compute their logical-status cluster and carry the
     * definition popout (ike-issues#941). Prefer this wherever the surface has a
     * {@link ViewProperties}; the {@link ViewCalculator} forms stay for non-interactive
     * renderings (paged print) and viewless fallbacks.
     *
     * @param viewProperties the surface's view; if null, chips fall back to bare identicons
     * @param base           base body font size in px
     */
    public MarkdownRichText(ViewProperties viewProperties, double base) {
        this(viewProperties, base, null);
    }

    /**
     * Interactive-surface form with an explicit prose font family — see
     * {@link #MarkdownRichText(ViewProperties, double)}.
     *
     * @param viewProperties the surface's view; if null, chips fall back to bare identicons
     * @param base           base body font size in px
     * @param fontFamily     the base prose font family; {@code null} uses the platform default
     */
        public MarkdownRichText(ViewProperties viewProperties, double base, String fontFamily) {
        this(base, fontFamily, new ConceptChipInlineDecorator(viewProperties, base),
                viewProperties == null ? null : viewProperties.calculator(), viewProperties);
    }

    /**
     * @param viewCalc   the live view for resolving concept names for chips; if null, chips
     *                   fall back to a bare identicon
     * @param base       base body font size in px (see {@link #DEFAULT_BASE})
     * @param fontFamily the base prose font family (e.g. a serif family for a print rendering);
     *                   {@code null} uses the platform default. Code stays monospaced.
     */
        public MarkdownRichText(ViewCalculator viewCalc, double base, String fontFamily) {
        this(base, fontFamily, new ConceptChipInlineDecorator(viewCalc, base), viewCalc, null);
    }

    private MarkdownRichText(double base, String fontFamily, ConceptChipInlineDecorator decorator,
                             ViewCalculator view, ViewProperties viewProperties) {
        this.base = base;
        this.decorator = decorator;
        this.context = new BlockContext();
        this.kindContext = new StructuredBlockKind.Context(view, viewProperties, context, base, null);
        this.kinds = StructuredBlocks.kinds();
        this.fences = kinds.stream().map(kind -> kind.fenceRenderer(kindContext)).flatMap(Optional::stream).toList();
        // Each renderer answers only to its own tag and declines the rest, so the first that
        // renders a block wins (ike-issues#1292).
        BlockRenderer blocks = (info, literal, style) -> {
            for (StructuredBlockKind.FenceRenderer fence : fences) {
                List<BlockPiece> pieces = fence.render(info, literal, style);
                if (!pieces.isEmpty()) {
                    return pieces;
                }
            }
            return List.of();
        };
        // the kinds that mark prose draw first, and each stretch then goes to the chip decorator
        InlineDecorator inline = decorator;
        for (StructuredBlockKind kind : kinds) {
            inline = kind.decorate(inline, kindContext);
        }
        this.renderer = new MarkdownRichTextRenderer(base, fontFamily, inline, blocks);
    }

    /**
     * Sets the label typography every chip this renderer builds renders with — the inline chips
     * and the chips the kinds' blocks draw (ike-issues#1050). Applies to chips built after the
     * call; a surface that changes the setting re-renders, so no stale chip survives.
     *
     * @param typography the typography; {@code null} restores the default small caps
     */
    public void setChipTypography(KonceptLabelTypography typography) {
        decorator.setChipTypography(typography);
        fences.forEach(fence -> fence.setChipTypography(typography));
    }

    /** What the blocks of this rendering make known to each other. */
    public BlockContext blockContext() {
        return context;
    }

    /** Where a rendered block's write back goes, a decision tree's what-if among them; null offers the what-if alone. */
    public void setBlockRewriter(BlockRewriter rewriter) {
        fences.forEach(fence -> fence.setRewriter(rewriter));
    }

    /**
     * Sets the store for user-chosen table column widths on the underlying renderer
     * (ike-issues#1034): tables built afterwards render with the store's remembered widths and
     * report resize drags back to it.
     *
     * @param store the width store, or {@code null} to make column resizing session-local
     */
    public void setTableColumnWidths(TableColumnWidths store) {
        renderer.setColumnWidths(store);
    }

    /**
     * Builds the view-only model for the whole transcript.
     *
     * @param entries the conversation, in order
     * @return a model suitable for {@code RichTextArea.setModel(...)}
     */
    public StyledTextModel toModel(List<Entry> entries) {
        List<RichParagraph> paragraphs = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.role().label.isBlank()) {
                // Coloured, bold role label on its own line.
                RichParagraph.Builder label = RichParagraph.builder();
                label.addSegment(e.role().label, StyleAttributeMap.builder()
                        .setBold(true).setFontSize(base).setTextColor(e.role().color).build());
                paragraphs.add(label.build());
                plain.add(e.role().label);
            }

            if (e.markdown()) {
                prepare(e.content());
                renderer.render(e.content(), baseStyle(e.role()), paragraphs, plain);
            } else {
                renderer.renderPlainText(e.content(), baseStyle(e.role()), paragraphs, plain);
            }

            // Blank spacer between messages.
            paragraphs.add(RichParagraph.builder().build());
            plain.add("");
        }
        if (paragraphs.isEmpty()) {
            return MarkdownStyledModel.empty();
        }
        return new MarkdownStyledModel(paragraphs, plain);
    }

    /**
     * Renders one turn's content as {@link DocumentSegment}s — the block-stack projection consumed
     * by the Document surface: prose runs back per-block view-only {@code RichTextArea}s;
     * recognised fenced blocks and tables become direct stack children. The same decorator and
     * block renderer apply, so chips and the koncept-tree render identically to {@link #toModel}.
     *
     * @param entry the turn to render
     * @return the turn's segments in order; empty when the content renders to nothing
     */
    public List<DocumentSegment> toSegments(Entry entry) {
        if (entry.markdown()) {
            prepare(entry.content());
            return renderer.renderSegments(entry.content(), baseStyle(entry.role()));
        }
        List<RichParagraph> paragraphs = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        renderer.renderPlainText(entry.content(), baseStyle(entry.role()), paragraphs, plain);
        return paragraphs.isEmpty() ? List.of()
                : List.of(new DocumentSegment.ProseRun(paragraphs, plain));
    }

    /**
     * Renders a single Markdown string to a view-only model <em>without</em> a role label — for a
     * standalone document such as a prompt pane, rather than a labelled transcript turn. Concept
     * chips still apply where the text carries identifiers, via the same shared renderer.
     *
     * @param markdown the Markdown source (null is treated as empty)
     * @return a model suitable for {@code RichTextArea.setModel(...)}
     */
    public StyledTextModel renderMarkdown(String markdown) {
        List<RichParagraph> paragraphs = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        prepare(markdown);
        renderer.render(markdown == null ? "" : markdown,
                StyleAttributeMap.builder().setFontSize(base).build(), paragraphs, plain);
        if (paragraphs.isEmpty()) {
            return MarkdownStyledModel.empty();
        }
        return new MarkdownStyledModel(paragraphs, plain);
    }

    /** Readies the kinds for one passage's Markdown. */
    private void prepare(String markdown) {
        for (StructuredBlockKind kind : kinds) {
            kind.preparePassage(markdown, kindContext);
        }
    }

    private StyleAttributeMap baseStyle(Role role) {
        StyleAttributeMap.Builder b = StyleAttributeMap.builder().setFontSize(base);
        if (role == Role.ERROR) {
            b.setItalic(true).setTextColor(Role.ERROR.color);
        }
        return b.build();
    }
}
