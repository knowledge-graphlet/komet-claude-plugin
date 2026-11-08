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
import network.ike.komet.claude.stamp.DecisionFigure;

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
     * What the block is on a page: what it says, drawn as figures both Typst and the Page layout
     * show, since a control's sliders and fields mean nothing on paper.
     *
     * @param source  the block's source
     * @param context what the surface offers
     * @return the figures in order; none prints the block as its source
     */
    default List<DecisionFigure> figures(Source source, Context context) {
        return List.of();
    }

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
     * The kind's name as the surface's Insert menu shows it: its name with a capital first letter.
     *
     * @return the menu entry
     */
    default String menuName() {
        String name = name();
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * What Insert adds for this kind: the skeleton of a new block, attribute line and fences
     * included, or empty when blocks of this kind are not inserted from the menu
     * (ike-lab-documents topic {@code dev-document-edit-menu}). A kind reads the document's other
     * blocks to name what the new block draws: a tornado names the model the document already has.
     *
     * @param context what the document's blocks have made known
     * @return the skeleton
     */
    default Optional<Skeleton> skeleton(Context context) {
        return Optional.empty();
    }

    /**
     * The renderer that draws a Markdown fence of this kind in a passage, as the assistant's
     * transcript shows a {@code ```decision-tree} block: the same reading as {@link #view}, for a
     * fence rather than a stored block. A passage renderer asks each kind once and keeps the
     * renderer for its life, so state a renderer keeps in the context lasts across the passage's
     * blocks. Empty when blocks of this kind are never written as a fence.
     *
     * @param context the passage's context, writing nothing
     * @return the renderer
     */
    default Optional<FenceRenderer> fenceRenderer(Context context) {
        return Optional.empty();
    }

    /**
     * Marks a passage's prose for this kind, as the ANF kind draws a note's clinical elements in
     * its text. Called once per passage renderer, in the order the kinds are found; each kind wraps
     * the decorator the kinds before it made.
     *
     * @param next    the decorator beneath, which every stretch of prose still goes to
     * @param context the passage's context
     * @return the decorator to use; {@code next} when the kind marks no prose
     */
    default dev.ikm.komet.markdown.richtext.InlineDecorator decorate(dev.ikm.komet.markdown.richtext.InlineDecorator next, Context context) {
        return next;
    }

    /**
     * Readies this kind for one passage's Markdown, before it is drawn: the ANF kind finds the
     * element spans the passage's text holds.
     *
     * @param markdown the passage's text
     * @param context  the passage's context
     */
    default void preparePassage(String markdown, Context context) {
    }

    /** A renderer of fences of one kind, which a passage renderer forwards its settings to. */
    interface FenceRenderer extends dev.ikm.komet.markdown.richtext.BlockRenderer {

        /**
         * Sets the label typography the renderer's concept chips render with.
         *
         * @param typography the typography; {@code null} restores the default
         */
        default void setChipTypography(dev.ikm.komet.framework.controls.KonceptLabelTypography typography) {
        }

        /**
         * Sets where a block's write back goes, a decision tree's what-if among them.
         *
         * @param rewriter the rewriter; {@code null} offers no write back
         */
        default void setRewriter(BlockRewriter rewriter) {
        }
    }

    /**
     * A new block of a kind, as Insert adds it.
     *
     * @param text             the block's whole text: attribute line, fences and content
     * @param opensForEditing  whether the surface opens the new block for editing at once; a block
     *                         that draws at once from its attributes alone, such as a tornado over
     *                         a model the document has, is inserted and left
     */
    record Skeleton(String text, boolean opensForEditing) {

        /** A listing block of a style, with its content between four-dash fences. */
        public static Skeleton listing(String attributes, String content, boolean opensForEditing) {
            return new Skeleton(attributes + "\n----\n" + (content.isEmpty() ? "" : content.endsWith("\n") ? content : content + "\n") + "----", opensForEditing);
        }

        /** An open block of a style, with its content between two-dash fences, the form the ANF blocks take. */
        public static Skeleton open(String attributes, String content, boolean opensForEditing) {
            return new Skeleton(attributes + "\n--\n" + (content.endsWith("\n") ? content : content + "\n") + "--", opensForEditing);
        }
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

        static boolean isFence(String line) {
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
     * @param viewProperties the surface's interactive view, by which the concepts a block shows
     *               carry their status and definition popout; null where it has none
     * @param blocks what the blocks of the shown document know of each other: the trees an
     *               outcome may fold in, the statements a node names, the calculators and their
     *               values; one per document shown
     * @param base   the surface's base font size, in points
     * @param save   saves the block's whole new text, attribute line and fences included, as an
     *               uncommitted edit of the document; null where the surface may not edit, and
     *               a control then offers no write back
     */
    record Context(ViewCalculator view, dev.ikm.komet.framework.view.ViewProperties viewProperties, BlockContext blocks, double base,
                   java.util.function.Consumer<String> save) {
        /** The default base size, when the surface gives none. */
        public static final double DEFAULT_BASE = 14;

        /** A context with no interactive view: the concepts a block shows carry no status or popout. */
        public Context(ViewCalculator view, BlockContext blocks, double base, java.util.function.Consumer<String> save) {
            this(view, null, blocks, base, save);
        }

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
