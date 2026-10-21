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
package network.ike.komet.claude.doc.print;

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import network.ike.komet.claude.doc.BlockFactory;
import network.ike.komet.claude.doc.DocumentBlock;
import network.ike.komet.claude.doc.print.settings.DocumentTheme;
import network.ike.komet.claude.ui.MarkdownRichText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What the {@link PagedDocumentEngine} paginates: fresh {@link DocumentBlock}s built for a
 * theme each time the engine lays out, since a node belongs to one scene. A conversation's
 * source builds its turns through a print {@link BlockFactory}; a stamped document's builds
 * its stored blocks as the Reading view draws them. Each block says which section it falls
 * under, so a page's running head can name the section at its top, and whether it keeps
 * together and keeps with the block after it.
 */
@FunctionalInterface
public interface PrintSource {

    /** Nothing to print: one blank page. */
    PrintSource EMPTY = (theme, viewCalc) -> List.of();

    /**
     * One block to paginate.
     *
     * @param block        the block, freshly built
     * @param section      the section the block falls under, named by the running head of the page it tops; may be blank
     * @param atomic       whether the block must not be split across a page boundary
     * @param keepWithNext whether the block must stay on the page the next block starts on (a heading)
     */
    record PrintBlock(DocumentBlock block, String section, boolean atomic, boolean keepWithNext) {

        public PrintBlock {
            Objects.requireNonNull(block, "block is null");
            section = section == null ? "" : section;
        }

        /**
         * A conversation block: headers, trees and tables keep together, prose breaks at the band.
         *
         * @param block the block
         * @return the print block, with no section
         */
        public static PrintBlock of(DocumentBlock block) {
            return new PrintBlock(block, "", block.richText() == null, false);
        }
    }

    /**
     * Builds the blocks for a theme.
     *
     * @param theme    the print theme: base font size and prose family
     * @param viewCalc the view for resolving concept chips; may be {@code null}
     * @return the blocks in order
     */
    List<PrintBlock> blocks(DocumentTheme theme, ViewCalculator viewCalc);

    /**
     * A conversation's turns, built through a print-configured {@link BlockFactory}.
     *
     * @param turns the turns in order
     * @return the source
     */
    static PrintSource ofTurns(List<MarkdownRichText.Entry> turns) {
        List<MarkdownRichText.Entry> snapshot = List.copyOf(Objects.requireNonNull(turns, "turns is null"));
        return (theme, viewCalc) -> {
            BlockFactory factory = BlockFactory.forPrint(viewCalc, theme.baseFontSize(), theme.fontFamily());
            List<PrintBlock> blocks = new ArrayList<>();
            for (MarkdownRichText.Entry turn : snapshot) {
                for (DocumentBlock block : factory.turnBlocks(turn)) {
                    blocks.add(PrintBlock.of(block));
                }
            }
            return blocks;
        };
    }
}
