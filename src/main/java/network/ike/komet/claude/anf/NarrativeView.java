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
package network.ike.komet.claude.anf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.SelectionSegment;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.RichTextModel;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.anf.element.ClinicalElement;
import network.ike.komet.claude.anf.element.ElementColours;
import network.ike.komet.claude.anf.element.ElementSet;
import network.ike.komet.claude.stamp.Span;

/**
 * The note being lifted, one editable text with its clinical elements drawn on it
 * ({@code dev-anf-element-isolation}, KEC 2026-10-31): each element's words underlined in its
 * colour, the selected element's words bold, the words no element covers shaded, and the words
 * marked not clinical quiet, so the
 * narrative and its elements are one view rather than the note and a marked copy of it. The text
 * grows to its content, so the lift region's one scroll bar scrolls it with everything else, and a
 * click on an element's words selects the element.
 */
final class NarrativeView {

    private static final Color SHADE = Color.web("#7a776d");
    private static final Color NOT_CLINICAL = Color.web("#9a9a9a");
    private static final StyleAttributeMap PLAIN = StyleAttributeMap.builder().setFontSize(13).build();

    private final RichTextModel model = new RichTextModel();
    private final RichTextArea area = new RichTextArea(model);
    private final Label prompt = new Label("Paste or dictate a clinical narrative, then isolate its elements…");
    private final StackPane node = new StackPane(area, prompt);
    private boolean applying;
    private Runnable onEdited = () -> { };
    private IntConsumer onClicked = offset -> { };

    NarrativeView() {
        area.setId("narrative");
        area.setWrapText(true);
        area.setUseContentHeight(true);
        area.setMinHeight(64);
        area.setHighlightCurrentParagraph(false);
        prompt.setMouseTransparent(true);
        prompt.setStyle("-fx-text-fill: #9a948a; -fx-padding: 6 10 6 10;");
        StackPane.setAlignment(prompt, Pos.TOP_LEFT);
        model.addListener(change -> {
            if (change.isEdit()) {
                prompt.setVisible(getText().isEmpty());
                if (!applying) {
                    onEdited.run();
                }
            }
        });
        area.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            SelectionSegment selection = area.getSelection();
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1
                    && (selection == null || selection.isCollapsed())) {
                TextPos at = area.getTextPosition(e.getScreenX(), e.getScreenY());
                if (at != null) {
                    onClicked.accept(offsetOf(at));
                }
            }
        });
    }

    Region node() {
        return node;
    }

    /** Runs when the author edits the text, never when it is set or marked. */
    void setOnEdited(Runnable action) {
        this.onEdited = action;
    }

    /** Takes the offset of a click in the text that selects nothing. */
    void setOnClicked(IntConsumer action) {
        this.onClicked = action;
    }

    void setDisable(boolean disable) {
        area.setEditable(!disable);
    }

    /** The text, its paragraphs joined by newlines. */
    String getText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < area.getParagraphCount(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(area.getPlainText(i));
        }
        return text.toString();
    }

    void setText(String text) {
        applying = true;
        try {
            area.replaceText(TextPos.ZERO, area.getDocumentEnd(), text == null ? "" : text);
            area.setStyle(TextPos.ZERO, area.getDocumentEnd(), PLAIN);
        } finally {
            applying = false;
        }
        prompt.setVisible(getText().isEmpty());
    }

    /** The span the author has selected, trimmed of whitespace; empty when nothing is. */
    Optional<Span> selection() {
        SelectionSegment selection = area.getSelection();
        if (selection == null || selection.isCollapsed()) {
            return Optional.empty();
        }
        String text = getText();
        int start = offsetOf(selection.getMin());
        int end = Math.min(offsetOf(selection.getMax()), text.length());
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return start < end ? Optional.of(new Span(start, end)) : Optional.empty();
    }

    /**
     * Draws an element set on the text: every span underlined in its element's colour, the
     * selected element's spans bold, and the stretches no element or not-clinical mark covers
     * shaded. A set read from another text than the one shown draws nothing.
     */
    void mark(ElementSet set, Optional<String> selectedId) {
        String text = getText();
        applying = true;
        try {
            area.setStyle(TextPos.ZERO, area.getDocumentEnd(), PLAIN);
            if (set == null || !set.narrative().equals(text.strip()) || text.isEmpty()) {
                return;
            }
            int lead = text.indexOf(set.narrative());
            for (Span gap : set.uncovered()) {
                style(lead, gap, StyleAttributeMap.builder().merge(PLAIN).setTextColor(SHADE).build());
            }
            for (Span quiet : set.notClinical()) {
                style(lead, quiet, StyleAttributeMap.builder().merge(PLAIN).setTextColor(NOT_CLINICAL).build());
            }
            List<ClinicalElement> elements = set.elements();
            int selected = -1;
            for (int i = 0; i < elements.size(); i++) {
                if (selectedId.filter(elements.get(i).id()::equals).isPresent()) {
                    selected = i;
                } else {
                    markElement(lead, elements.get(i), i, false);
                }
            }
            // last, so words it shares with another element (an age and a sex in "62-year-old man") show it
            if (selected >= 0) {
                markElement(lead, elements.get(selected), selected, true);
            }
        } finally {
            applying = false;
        }
    }

    private void markElement(int lead, ClinicalElement element, int index, boolean selected) {
        Color colour = Color.web(ElementColours.of(index));
        StyleAttributeMap.Builder style = StyleAttributeMap.builder().merge(PLAIN)
                .setUnderline(true).setTextColor(colour.darker());
        if (selected) {
            // bold, not a background: the area's background is a paragraph attribute and tints the whole line
            style.setBold(true);
        }
        StyleAttributeMap attributes = style.build();
        element.spans().forEach(span -> style(lead, span, attributes));
    }

    private void style(int lead, Span span, StyleAttributeMap attributes) {
        area.setStyle(positionOf(lead + span.start()), positionOf(lead + span.end()), attributes);
    }

    // ── Offsets and positions ────────────────────────────────────────────────

    private List<Integer> paragraphStarts() {
        List<Integer> starts = new ArrayList<>();
        int at = 0;
        for (int i = 0; i < area.getParagraphCount(); i++) {
            starts.add(at);
            at += area.getPlainText(i).length() + 1;
        }
        return starts;
    }

    private int offsetOf(TextPos pos) {
        List<Integer> starts = paragraphStarts();
        return pos.index() < starts.size() ? starts.get(pos.index()) + pos.offset() : getText().length();
    }

    private TextPos positionOf(int offset) {
        List<Integer> starts = paragraphStarts();
        for (int i = starts.size() - 1; i >= 0; i--) {
            if (offset >= starts.get(i)) {
                return TextPos.ofLeading(i, Math.min(offset - starts.get(i), area.getPlainText(i).length()));
            }
        }
        return TextPos.ZERO;
    }
}
