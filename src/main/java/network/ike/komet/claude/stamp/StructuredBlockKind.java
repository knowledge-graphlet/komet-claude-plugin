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

        /** @return the content between the block's fences, one source line per text line */
        public String content() {
            int open = -1;
            int close = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (isFence(lines.get(i))) {
                    if (open < 0) {
                        open = i;
                    } else {
                        close = i;
                    }
                }
            }
            if (open < 0) {
                return String.join("\n", lines);
            }
            return String.join("\n", lines.subList(open + 1, close < 0 ? lines.size() : close));
        }

        /**
         * The block's whole text with new content between its fences: what an editor's save
         * writes back. The attribute line and fences are kept as they were.
         *
         * @param content the new content
         * @return the block's text
         */
        public String withContent(String content) {
            int open = -1;
            int close = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (isFence(lines.get(i))) {
                    if (open < 0) {
                        open = i;
                    } else {
                        close = i;
                    }
                }
            }
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

        private static boolean isFence(String line) {
            String stripped = line.strip();
            return stripped.length() >= 4 && stripped.chars().allMatch(c -> c == '-');
        }
    }

    /**
     * What a structured block's control may read: the view its names and concepts are read
     * under. Writes go through the editor's text, never through the context.
     *
     * @param view the view of the surface showing the block
     */
    record Context(ViewCalculator view) { }

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
