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
import jfx.incubator.scene.control.richtext.model.StyledTextModel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Track-changes rendering of a Markdown <em>source</em> revision ({@code
 * IKE-Network/ike-issues#1044}, the drafting review surface): a word-level diff of base
 * against revised text, deletions struck through in red where they were removed, insertions
 * underlined in green — the source with its changes marked, not a rendered-document redline.
 * Paragraph structure follows the revised text; a deleted line break renders as a struck
 * {@code ⏎} so removals never disturb the revision's layout.
 */
public final class MarkdownDiff {

    /** What happened to a run of text between base and revised. */
    enum Kind { EQUAL, INSERTED, DELETED }

    /** One diff run: consecutive tokens sharing a {@link Kind}, joined. */
    record Run(String text, Kind kind) {
    }

    private static final Color INSERT_COLOR = Color.web("#116329");
    private static final Color DELETE_COLOR = Color.web("#a40e26");
    private static final Pattern TOKENS = Pattern.compile("\\r?\\n|[^\\s]+|[ \\t]+");

    private MarkdownDiff() {
    }

    /**
     * The word-level diff as styled paragraphs.
     *
     * @param base     the pre-revision source (null is treated as empty)
     * @param revised  the revised source (null is treated as empty)
     * @param fontSize the base font size, matching the editor's current setting
     * @return a model suitable for {@code RichTextArea.setModel(...)}
     */
    public static StyledTextModel model(String base, String revised, double fontSize) {
        StyleAttributeMap equal = StyleAttributeMap.builder()
                .setFontSize(fontSize).build();
        StyleAttributeMap inserted = StyleAttributeMap.builder()
                .setFontSize(fontSize).setTextColor(INSERT_COLOR).setUnderline(true).build();
        StyleAttributeMap deleted = StyleAttributeMap.builder()
                .setFontSize(fontSize).setTextColor(DELETE_COLOR).setStrikeThrough(true).build();

        List<RichParagraph> paragraphs = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        RichParagraph.Builder paragraph = RichParagraph.builder();
        StringBuilder plainLine = new StringBuilder();
        boolean any = false;
        for (Run run : runs(base, revised)) {
            StyleAttributeMap style = switch (run.kind()) {
                case EQUAL -> equal;
                case INSERTED -> inserted;
                case DELETED -> deleted;
            };
            // Split the run on its line breaks so paragraph boundaries land exactly where the
            // revised text (or a struck removal marker) puts them.
            String[] lines = run.text().split("\\r?\\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    if (run.kind() == Kind.DELETED) {
                        // A removed line break: visible, struck, inline — no new paragraph.
                        paragraph.addSegment("⏎", deleted);
                        plainLine.append("⏎");
                    } else {
                        paragraphs.add(paragraph.build());
                        plain.add(plainLine.toString());
                        paragraph = RichParagraph.builder();
                        plainLine = new StringBuilder();
                    }
                }
                if (!lines[i].isEmpty()) {
                    paragraph.addSegment(lines[i], style);
                    plainLine.append(lines[i]);
                }
                any = true;
            }
        }
        if (!any) {
            return MarkdownStyledModel.empty();
        }
        paragraphs.add(paragraph.build());
        plain.add(plainLine.toString());
        return new MarkdownStyledModel(paragraphs, plain);
    }

    /**
     * The diff as runs — the testable seam beneath the styled model: tokens (words, whitespace,
     * line breaks) aligned by longest common subsequence, consecutive same-kind tokens joined.
     */
    static List<Run> runs(String base, String revised) {
        List<String> a = tokens(base);
        List<String> b = tokens(revised);
        // Longest-common-subsequence table; instruction documents are small, so the quadratic
        // table stays comfortably in memory.
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Run> runs = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        Kind pendingKind = null;
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            Kind kind;
            String token;
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                kind = Kind.EQUAL;
                token = a.get(i);
                i++;
                j++;
            } else if (j < b.size() && (i >= a.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                kind = Kind.INSERTED;
                token = b.get(j);
                j++;
            } else {
                kind = Kind.DELETED;
                token = a.get(i);
                i++;
            }
            if (pendingKind != null && pendingKind != kind) {
                runs.add(new Run(pending.toString(), pendingKind));
                pending = new StringBuilder();
            }
            pendingKind = kind;
            pending.append(token);
        }
        if (pendingKind != null) {
            runs.add(new Run(pending.toString(), pendingKind));
        }
        return runs;
    }

    /** Words, whitespace runs, and line breaks — every character lands in exactly one token. */
    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        Matcher matcher = TOKENS.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }
}
