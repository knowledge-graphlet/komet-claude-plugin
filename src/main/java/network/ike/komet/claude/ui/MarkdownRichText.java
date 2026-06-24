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

import dev.ikm.komet.markdown.richtext.MarkdownRichTextRenderer;
import dev.ikm.komet.markdown.richtext.MarkdownStyledModel;
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
 * the live store and struck through when the component is inactive (#586). The renderer is the
 * generic, reusable Markdown engine; this class only composes the role-labelled transcript.
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
     * @param viewCalc the live view for resolving concept names for chips; if null, chips
     *                 fall back to a bare identicon
     * @param base     base body font size in px (see {@link #DEFAULT_BASE})
     */
    public MarkdownRichText(ViewCalculator viewCalc, double base) {
        this.base = base;
        this.renderer = new MarkdownRichTextRenderer(base, new ConceptChipInlineDecorator(viewCalc, base));
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
