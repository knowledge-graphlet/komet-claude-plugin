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

import dev.ikm.komet.markdown.richtext.MarkdownStyledModel;
import javafx.scene.paint.Color;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledSegment;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Track changes over the <em>rendered</em> document ({@code IKE-Network/ike-issues#1044}, the
 * drafting review surface): both versions render through the normal Markdown pipeline —
 * headings, tables, concept chips — and the revision is shown fully rendered with the changes
 * marked in place: inserted content under a green highlight (its own styling kept), deleted
 * content injected at its anchor as struck red text (a struck {@code ⏎} standing in for a
 * removed paragraph break). The word-level alignment comes from {@link MarkdownDiff#runs},
 * applied to the two renderings' plain text.
 */
public final class RenderedDiff {

    private static final Color INSERT_HIGHLIGHT = Color.web("#c7ecc9");
    private static final Color DELETE_COLOR = Color.web("#a40e26");

    /** One deletion to inject: the struck text at a plain-text anchor of the revision. */
    private record Injection(int anchor, String text) {
    }

    private RenderedDiff() {
    }

    /**
     * The rendered revision with its changes marked against the rendered base.
     *
     * @param base     the pre-revision rendering
     * @param revised  the revision's rendering
     * @param fontSize the base font size, for the injected deletion text
     * @return a model suitable for {@code RichTextArea.setModel(...)}
     */
    public static StyledTextModel model(StyledTextModel base, StyledTextModel revised,
                                        double fontSize) {
        String plainBase = joinedPlain(base);
        String plainRevised = joinedPlain(revised);
        List<MarkdownDiff.Run> runs = MarkdownDiff.runs(plainBase, plainRevised);

        // Walk the runs once: inserted ranges in revision coordinates, deletions anchored there.
        List<int[]> insertedRanges = new ArrayList<>();
        List<Injection> injections = new ArrayList<>();
        int offsetRevised = 0;
        for (MarkdownDiff.Run run : runs) {
            switch (run.kind()) {
                case EQUAL -> offsetRevised += run.text().length();
                case INSERTED -> {
                    insertedRanges.add(new int[] {offsetRevised, offsetRevised + run.text().length()});
                    offsetRevised += run.text().length();
                }
                case DELETED -> {
                    String struck = run.text().replace("\n", " ⏎ ").strip();
                    if (!struck.isEmpty()) {
                        injections.add(new Injection(offsetRevised, struck));
                    }
                }
            }
        }

        StyleAttributeMap deleted = StyleAttributeMap.builder()
                .setFontSize(fontSize).setTextColor(DELETE_COLOR).setStrikeThrough(true).build();

        List<RichParagraph> paragraphs = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        int paragraphStart = 0;
        int injectionIndex = 0;
        for (int i = 0; i < revised.size(); i++) {
            String text = revised.getPlainText(i);
            int paragraphEnd = paragraphStart + text.length();
            RichParagraph source = revised.getParagraph(i);

            // A REGION paragraph (a table) is copied whole; a deletion anchored inside it
            // lands as its own struck paragraph after it.
            if (isRegion(source)) {
                paragraphs.add(source);
                plain.add(text);
                while (injectionIndex < injections.size()
                        && injections.get(injectionIndex).anchor() <= paragraphEnd) {
                    RichParagraph.Builder struck = RichParagraph.builder();
                    struck.addSegment(injections.get(injectionIndex).text(), deleted);
                    paragraphs.add(struck.build());
                    plain.add(injections.get(injectionIndex).text());
                    injectionIndex++;
                }
                paragraphStart = paragraphEnd + 1;
                continue;
            }

            RichParagraph.Builder builder = RichParagraph.builder();
            StringBuilder builtText = new StringBuilder();
            int localOffset = 0;
            // Injections shift everything after them; highlights are adjusted by the total
            // injected length that precedes each range.
            List<int[]> localHighlights = new ArrayList<>();
            for (int[] range : insertedRanges) {
                int start = Math.max(range[0], paragraphStart);
                int end = Math.min(range[1], paragraphEnd);
                if (start < end) {
                    localHighlights.add(new int[] {start - paragraphStart, end - paragraphStart});
                }
            }
            int emitted = 0;
            for (int s = 0; s < source.getSegmentCount(); s++) {
                StyledSegment segment = source.getSegment(s);
                int segmentLength = segment.getTextLength();
                // Any deletions anchored before this segment's end are injected first, at
                // their exact offset, splitting the segment when needed.
                int segmentStart = emitted;
                int consumed = 0;
                while (injectionIndex < injections.size()
                        && injections.get(injectionIndex).anchor() - paragraphStart
                                <= segmentStart + segmentLength
                        && injections.get(injectionIndex).anchor() <= paragraphEnd) {
                    int local = injections.get(injectionIndex).anchor() - paragraphStart;
                    int split = Math.max(0, Math.min(local - segmentStart, segmentLength));
                    if (split > consumed && segment.getType() == StyledSegment.Type.TEXT) {
                        StyledSegment head = segment.subSegment(consumed, split);
                        builder.addSegment(head.getText(), head.getStyleAttributeMap(null));
                        builtText.append(head.getText());
                    }
                    consumed = Math.max(consumed, split);
                    String struckText = injections.get(injectionIndex).text();
                    builder.addSegment(struckText, deleted);
                    for (int h = 0; h < localHighlights.size(); h++) {
                        int[] range = localHighlights.get(h);
                        if (range[0] >= builtText.length()) {
                            range[0] += struckText.length();
                            range[1] += struckText.length();
                        } else if (range[1] > builtText.length()) {
                            range[1] += struckText.length();
                        }
                    }
                    builtText.append(struckText);
                    injectionIndex++;
                }
                if (segment.getType() == StyledSegment.Type.TEXT) {
                    if (consumed < segmentLength) {
                        StyledSegment tail = segment.subSegment(consumed, segmentLength);
                        builder.addSegment(tail.getText(), tail.getStyleAttributeMap(null));
                        builtText.append(tail.getText());
                    }
                } else if (segment.getType() == StyledSegment.Type.INLINE_NODE) {
                    builder.addInlineNode(segment.getInlineNodeGenerator());
                    builtText.append(" ".repeat(Math.max(0, segmentLength)));
                }
                emitted = segmentStart + segmentLength;
            }
            // Deletions anchored at the very end of the paragraph.
            while (injectionIndex < injections.size()
                    && injections.get(injectionIndex).anchor() <= paragraphEnd) {
                String struckText = injections.get(injectionIndex).text();
                builder.addSegment(struckText, deleted);
                builtText.append(struckText);
                injectionIndex++;
            }
            for (int[] range : localHighlights) {
                int start = Math.max(0, Math.min(range[0], builtText.length()));
                int end = Math.max(start, Math.min(range[1], builtText.length()));
                if (end > start) {
                    builder.addHighlight(start, end - start, INSERT_HIGHLIGHT);
                }
            }
            paragraphs.add(builder.build());
            plain.add(builtText.toString());
            paragraphStart = paragraphEnd + 1;
        }
        if (paragraphs.isEmpty()) {
            return MarkdownStyledModel.empty();
        }
        return new MarkdownStyledModel(paragraphs, plain);
    }

    private static String joinedPlain(StyledTextModel model) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < model.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(model.getPlainText(i));
        }
        return text.toString();
    }

    private static boolean isRegion(RichParagraph paragraph) {
        for (int i = 0; i < paragraph.getSegmentCount(); i++) {
            if (paragraph.getSegment(i).getType() == StyledSegment.Type.REGION) {
                return true;
            }
        }
        return false;
    }
}
