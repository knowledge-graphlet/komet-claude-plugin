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

import dev.ikm.komet.markdown.richtext.DocumentSegment;
import dev.ikm.komet.markdown.richtext.MarkdownRichTextRenderer;
import dev.ikm.komet.markdown.richtext.MarkdownStyledModel;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.scene.paint.Color;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a view-only {@link StyledTextModel} for the assistant transcript: each {@link Entry}
 * contributes a coloured role label followed by its content, with Markdown rendered to styled
 * {@link RichParagraph} runs by the shared {@link MarkdownRichTextRenderer}.
 *
 * <p>The grounding behaviour lives in {@link ConceptChipInlineDecorator}, the
 * {@link dev.ikm.komet.markdown.richtext.InlineDecorator} handed to the renderer: any concept
 * identifier the assistant reports — an SCTID, UUID, or {@code nid=…} — is followed by a
 * <em>concept chip</em> (LifeHash identicon + store-resolved name), existence-gated against
 * the live store and struck through when the component is inactive (#586). Block structure is
 * dispatched the same way: a {@code koncept-tree} fenced block routes to
 * {@link KonceptTreeBlockRenderer}, which builds a real interactive {@code TreeView} of Koncept
 * chips rather than ASCII box art (#801/#805). The renderer is the generic, reusable Markdown
 * engine; this class only composes the role-labelled transcript.
 */
public final class MarkdownRichText {

    /** Default transcript base font size (px); overridable per-instance for zoom. */
    public static final double DEFAULT_BASE = 13;

    /** A transcript role, with its label and accent colour. */
    public enum Role {
        USER("You", Color.web("#1a56db")),
        ASSISTANT("Komet Assistant", Color.web("#b15c00")),
        ERROR("Error", Color.web("#b00020"));

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
    }

    /** Base body font size (px); the role label and the renderer both scale from it. */
    private final double base;
    /** Shared Markdown engine, wired with the concept-chip decorator for grounding. */
    private final MarkdownRichTextRenderer renderer;

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
        this(base, fontFamily,
                new ConceptChipInlineDecorator(viewProperties, base),
                new KonceptTreeBlockRenderer(viewProperties, base));
    }

    /**
     * @param viewCalc   the live view for resolving concept names for chips; if null, chips
     *                   fall back to a bare identicon
     * @param base       base body font size in px (see {@link #DEFAULT_BASE})
     * @param fontFamily the base prose font family (e.g. a serif family for a print rendering);
     *                   {@code null} uses the platform default. Code stays monospaced.
     */
    public MarkdownRichText(ViewCalculator viewCalc, double base, String fontFamily) {
        this(base, fontFamily,
                new ConceptChipInlineDecorator(viewCalc, base),
                new KonceptTreeBlockRenderer(viewCalc, base));
    }

    private MarkdownRichText(double base, String fontFamily,
                             ConceptChipInlineDecorator decorator, KonceptTreeBlockRenderer trees) {
        this.base = base;
        this.renderer = new MarkdownRichTextRenderer(base, fontFamily, decorator, trees);
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
            // Coloured, bold role label on its own line.
            RichParagraph.Builder label = RichParagraph.builder();
            label.addSegment(e.role().label, StyleAttributeMap.builder()
                    .setBold(true).setFontSize(base).setTextColor(e.role().color).build());
            paragraphs.add(label.build());
            plain.add(e.role().label);

            if (e.markdown()) {
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
        renderer.render(markdown == null ? "" : markdown,
                StyleAttributeMap.builder().setFontSize(base).build(), paragraphs, plain);
        if (paragraphs.isEmpty()) {
            return MarkdownStyledModel.empty();
        }
        return new MarkdownStyledModel(paragraphs, plain);
    }

    private StyleAttributeMap baseStyle(Role role) {
        StyleAttributeMap.Builder b = StyleAttributeMap.builder().setFontSize(base);
        if (role == Role.ERROR) {
            b.setItalic(true).setTextColor(Role.ERROR.color);
        }
        return b.build();
    }
}
