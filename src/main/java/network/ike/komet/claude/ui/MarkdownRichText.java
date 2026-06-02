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

import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Renders a Markdown string into a {@link RichTextArea} as styled text
 * segments, using a CommonMark parse and a visitor that maps inline/block
 * structure to {@link StyleAttributeMap} runs.
 *
 * <p>Intentionally lightweight: it covers the elements an assistant reply
 * actually uses — headings, paragraphs, bold/italic, inline and fenced code,
 * bullet/ordered lists, block quotes, links, and rules. It does not attempt
 * full CommonMark fidelity (e.g. tables, nested images). Text not recognized as
 * markup is emitted verbatim, so a plain-text reply renders as plain text.
 *
 * <p>All appends mutate the area's model, so callers must invoke
 * {@link #append} on the JavaFX Application Thread.
 */
public final class MarkdownRichText {

    private static final Parser PARSER = Parser.builder().build();

    /** Body font size, in points. */
    private static final double BASE = 13;
    /** Monospace family for code spans/blocks. */
    private static final String MONO = "monospace";

    private MarkdownRichText() {
    }

    /**
     * Parses {@code markdown} and appends it, styled, to {@code area}.
     *
     * @param area     the transcript to append into (mutated on the FX thread)
     * @param markdown the Markdown source; null/blank is a no-op
     */
    public static void append(RichTextArea area, String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return;
        }
        Node document = PARSER.parse(markdown);
        document.accept(new RenderVisitor(area));
    }

    /** Walks the CommonMark AST, emitting styled runs into the area. */
    private static final class RenderVisitor extends AbstractVisitor {

        private final RichTextArea area;
        private int bold;
        private int italic;
        private int headingLevel;
        private int listDepth;
        /** Per-list marker state: {@code null} = bullet, else a 1-element counter. */
        private final Deque<int[]> listCounters = new ArrayDeque<>();

        private RenderVisitor(RichTextArea area) {
            this.area = area;
        }

        private double currentSize() {
            return switch (headingLevel) {
                case 1 -> 19;
                case 2 -> 17;
                case 3 -> 15;
                case 4 -> 14;
                default -> BASE;
            };
        }

        private void emit(String text) {
            emit(text, false, currentSize());
        }

        private void emit(String text, boolean mono, double size) {
            if (text == null || text.isEmpty()) {
                return;
            }
            StyleAttributeMap.Builder b = StyleAttributeMap.builder();
            if (bold > 0 || headingLevel > 0) {
                b.setBold(true);
            }
            if (italic > 0) {
                b.setItalic(true);
            }
            if (mono) {
                b.setFontFamily(MONO);
            }
            b.setFontSize(size);
            area.appendText(text, b.build());
        }

        @Override
        public void visit(Text text) {
            emit(text.getLiteral());
        }

        @Override
        public void visit(StrongEmphasis node) {
            bold++;
            visitChildren(node);
            bold--;
        }

        @Override
        public void visit(Emphasis node) {
            italic++;
            visitChildren(node);
            italic--;
        }

        @Override
        public void visit(Code node) {
            emit(node.getLiteral(), true, currentSize());
        }

        @Override
        public void visit(FencedCodeBlock node) {
            emitCodeBlock(node.getLiteral());
        }

        @Override
        public void visit(IndentedCodeBlock node) {
            emitCodeBlock(node.getLiteral());
        }

        private void emitCodeBlock(String literal) {
            String code = literal == null ? "" : literal;
            while (code.endsWith("\n")) {
                code = code.substring(0, code.length() - 1);
            }
            emit(code, true, BASE);
            emit("\n\n");
        }

        @Override
        public void visit(Heading node) {
            headingLevel = node.getLevel();
            visitChildren(node);
            headingLevel = 0;
            emit("\n\n");
        }

        @Override
        public void visit(Paragraph node) {
            visitChildren(node);
            emit(listDepth > 0 ? "\n" : "\n\n");
        }

        @Override
        public void visit(BulletList node) {
            listCounters.push(new int[]{Integer.MIN_VALUE}); // sentinel = bullet
            listDepth++;
            visitChildren(node);
            listDepth--;
            listCounters.pop();
            if (listDepth == 0) {
                emit("\n");
            }
        }

        @Override
        public void visit(OrderedList node) {
            listCounters.push(new int[]{1});
            listDepth++;
            visitChildren(node);
            listDepth--;
            listCounters.pop();
            if (listDepth == 0) {
                emit("\n");
            }
        }

        @Override
        public void visit(ListItem node) {
            emit("  ".repeat(Math.max(0, listDepth - 1)));
            int[] counter = listCounters.peek();
            if (counter == null || counter[0] == Integer.MIN_VALUE) {
                emit("• ");
            } else {
                emit(counter[0]++ + ". ");
            }
            visitChildren(node);
        }

        @Override
        public void visit(BlockQuote node) {
            italic++;
            visitChildren(node);
            italic--;
        }

        @Override
        public void visit(ThematicBreak node) {
            emit("\n──────────\n\n");
        }

        @Override
        public void visit(SoftLineBreak node) {
            emit(" ");
        }

        @Override
        public void visit(HardLineBreak node) {
            emit("\n");
        }

        @Override
        public void visit(Link node) {
            visitChildren(node);
            String url = node.getDestination();
            if (url != null && !url.isBlank()) {
                emit(" (" + url + ")");
            }
        }

        @Override
        public void visit(Image node) {
            String url = node.getDestination();
            emit("[image" + (url == null || url.isBlank() ? "" : ": " + url) + "]");
        }
    }
}
