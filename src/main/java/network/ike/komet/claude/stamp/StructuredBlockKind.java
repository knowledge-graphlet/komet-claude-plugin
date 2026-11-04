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
package network.ike.komet.claude.stamp;

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.scene.Node;
import network.ike.komet.claude.ui.BlockContext;

import java.util.List;
import java.util.Optional;

/**
 * A kind of structured block on the document surface: a block whose source is a structure (an
 * ANF statement, a complex concept) rather than prose, read through its own control and edited in
 * its own editor (IKE-Network/ike-issues#1312, the structured editors of #812).
 *
 * <p>A structured block is still AsciiDoc in its document: a listing block whose style names the
 * kind, its content the structure's declarative source, as {@code [anf,type=performance]} over a
 * {@code ----} fence. AsciiDoc keeps a listing's content verbatim, so the block's lines are plain
 * lines of the document: they materialize, version, carry annotations and commit to git as any
 * other lines do, and an edit in the structured editor is an edit of that text, saved through the
 * same pending edit as an in-place text edit. The kind is the reading of that text, never a
 * second record of it.</p>
 *
 * <p>Kinds are found through {@link java.util.ServiceLoader} ({@code provides} in a module
 * descriptor) and through {@link StructuredBlocks#register}. A kind never writes to the store.</p>
 */
public interface StructuredBlockKind {

    /**
     * The AsciiDoc block style that names this kind: the first positional attribute of the
     * block's attribute line, {@code anf} for {@code [anf,type=performance]}. Compared without
     * regard to case.
     *
     * @return the style
     */
    String style();

    /**
     * The kind's name, for the surface's status line and menus.
     *
     * @return the name
     */
    String name();

    /**
     * The control that reads a block of this kind in the Reading view.
     *
     * @param source  the block's source
     * @param context what the control may read
     * @return the control
     */
    Node view(Source source, Context context);

    /**
     * An editor for a block of this kind, or empty when blocks of this kind are edited as text.
     *
     * @param source  the block's source
     * @param context what the editor may read
     * @return the editor
     */
    Optional<Editor> editor(Source source, Context context);

    /**
     * Writes the knowledge a block of this kind states, after the block's text was saved: a
     * decision tree as a version of its tree semantic, an ANF statement as a statement of its block,
     * a complex concept as a version of its specification. The versions are uncommitted, by the edit's
     * author, kept or cancelled with the document's edit. A kind whose blocks state nothing the
     * store keeps writes nothing.
     *
     * @param source the block's source as saved
     * @param block  the nid of the block's element semantic, which an ANF statement references
     * @param previous the block's text before this edit, empty for a new block
     * @param view   the view its identifiers are read under
     * @param edit   whose change it is, in which module and on which path
     * @return a sentence for the person, empty when the kind writes nothing
     */
    default Optional<String> write(Source source, long block, Optional<String> previous, ViewCalculator view, dev.ikm.tinkar.coordinate.edit.EditCoordinate edit) {
        return Optional.empty();
    }

    /**
     * A structured block's source as its document holds it.
     *
     * @param parameters the block's attribute line, verbatim, as {@code [anf,type=performance]}
     * @param lines      every line of the block in document order: the attribute line, the
     *                   fences and the content between them
     */
    record Source(String parameters, List<String> lines) {

        /** Copies the lines. */
        public Source {
            lines = List.copyOf(lines);
        }

        /**
         * A block's source from its whole text, as a save writes it: the attribute line is the
         * last line before the opening fence that is one.
         *
         * @param text the block's text, one line per line
         * @return the source
         */
        public static Source of(String text) {
            List<String> lines = List.of(text.split("\n", -1));
            String parameters = "";
            for (String line : lines) {
                String stripped = line.strip();
                if (isFence(line)) {
                    break;
                }
                if (stripped.startsWith("[") && stripped.endsWith("]")) {
                    parameters = stripped;
                }
            }
            return new Source(parameters, lines);
        }

        /** @return the content between the block's fences, one source line per text line */
        public String content() {
            int[] fences = fences();
            if (fences[0] < 0) {
                return String.join("\n", lines);
            }
            return String.join("\n", lines.subList(fences[0] + 1, fences[1] < 0 ? lines.size() : fences[1]));
        }

        /**
         * The block's whole text with new content between its fences: what an editor's save
         * writes back. The attribute line and fences are kept as they were.
         *
         * @param content the new content
         * @return the block's text
         */
        public String withContent(String content) {
            int[] fences = fences();
            int open = fences[0];
            int close = fences[1];
            if (open < 0) {
                return content;
            }
            StringBuilder text = new StringBuilder();
            for (String line : lines.subList(0, open + 1)) {
                text.append(line).append('\n');
            }
            text.append(content);
            if (!content.endsWith("\n")) {
                text.append('\n');
            }
            String fence = lines.get(open);
            text.append(close < 0 ? fence : lines.get(close));
            return text.toString();
        }

        /**
         * The block's whole text with a new attribute line and new content: what a write back
         * saves when the reader changed the block's parameters too, a decision tree's health
         * price among them. The lines before the fence other than the attribute line, a title
         * among them, are kept.
         *
         * @param newParameters the new attribute line, {@code [decision-tree, health-price=50000]}
         * @param content       the new content
         * @return the block's text
         */
        public String withParameters(String newParameters, String content) {
            String text = withContent(content);
            if (parameters == null || parameters.isBlank() || newParameters == null || newParameters.equals(parameters)) {
                return text;
            }
            List<String> restated = new java.util.ArrayList<>(List.of(text.split("\n", -1)));
            int open = fences()[0];
            for (int i = 0; i < restated.size() && (open < 0 || i < open); i++) {
                if (restated.get(i).strip().equals(parameters.strip())) {
                    restated.set(i, newParameters);
                    break;
                }
            }
            return String.join("\n", restated);
        }

        /**
         * The opening fence and the last line that closes it, by index; -1 where there is none.
         * A listing opens with four dashes or more, an open block with two, and the block closes
         * with the line that opened it, so a {@code --} line inside a listing is content.
         */
        private int[] fences() {
            int open = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (isFence(lines.get(i))) {
                    open = i;
                    break;
                }
            }
            int close = -1;
            if (open >= 0) {
                String fence = lines.get(open).strip();
                for (int i = lines.size() - 1; i > open; i--) {
                    if (lines.get(i).strip().equals(fence)) {
                        close = i;
                        break;
                    }
                }
            }
            return new int[] {open, close};
        }

        private static boolean isFence(String line) {
            String stripped = line.strip();
            return (stripped.length() >= 4 || stripped.length() == 2) && stripped.chars().allMatch(c -> c == '-');
        }
    }

    /**
     * What a structured block's control may read, and where it writes: the view its names and
     * concepts are read under, what the document's other blocks have made known, the surface's
     * base size, and the save that restates the block's text. A control writes only through its
     * block's text: an editor's content, or a write back of what the reader tried, as the save.
     *
     * @param view   the view of the surface showing the block
     * @param blocks what the blocks of the shown document know of each other: the trees an
     *               outcome may fold in, the statements a node names, the calculators and their
     *               values; one per document shown
     * @param base   the surface's base font size, in points
     * @param save   saves the block's whole new text, attribute line and fences included, as an
     *               uncommitted edit of the document; null where the surface may not edit, and
     *               a control then offers no write back
     */
    record Context(ViewCalculator view, BlockContext blocks, double base, java.util.function.Consumer<String> save) {
        /** The default base size, when the surface gives none. */
        public static final double DEFAULT_BASE = 14;

        /** A context over a view alone: a block reads no other block and writes nothing. */
        public Context(ViewCalculator view) {
            this(view, new BlockContext(), DEFAULT_BASE, null);
        }

        /** A context that writes nothing. */
        public Context(ViewCalculator view, BlockContext blocks, double base) {
            this(view, blocks, base, null);
        }
    }

    /** An open editor of one structured block. */
    interface Editor {

        /** @return the editor's control, shown in place of the block while it is open */
        Node node();

        /**
         * The block's content as the editor now holds it, in the kind's declarative source form:
         * the text a save writes between the block's fences.
         *
         * @return the content
         * @throws IllegalStateException with a message for the person when the editor holds no
         *                               content that can be saved, such as an incomplete structure
         */
        String content();
    }
}
