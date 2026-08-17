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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;

import java.util.function.Function;

/**
 * A Markdown snippet with a <b>view/edit toggle</b>: at rest the snippet shows properly
 * <em>rendered</em> (through the caller's Markdown pipeline — concept chips and tables
 * included); the toggle flips to a raw-text editor and back, re-rendering on return. The
 * house pattern for every place a Markdown or AsciiDoc snippet is shown and possibly edited
 * (first uses: the system-prompt dialog's instruction layer and tool contract,
 * {@code IKE-Network/ike-issues#1039}); the standardization across surfaces is tracked
 * separately.
 *
 * <p>A non-editable pane is just the rendered view — no toggle, no editor chrome. The raw
 * text is always the source of truth ({@link #getText()} answers it regardless of mode).
 */
public final class MarkdownEditPane extends BorderPane {

    private final Function<String, StyledTextModel> renderer;
    private final TextArea editor = new TextArea();
    private final RichTextArea view = new RichTextArea();
    private final ToggleButton mode;

    /**
     * Creates the pane.
     *
     * @param markdown the initial Markdown source; {@code null} is treated as empty
     * @param editable whether the pane offers the edit half of the toggle
     * @param renderer the Markdown pipeline producing the rendered model (chips, tables)
     */
    public MarkdownEditPane(String markdown, boolean editable,
                            Function<String, StyledTextModel> renderer) {
        this.renderer = renderer;
        editor.setText(markdown == null ? "" : markdown);
        editor.setWrapText(true);
        view.setEditable(false);
        view.setWrapText(true);
        if (editable) {
            mode = new ToggleButton("Edit");
            mode.setTooltip(new javafx.scene.control.Tooltip(
                    "Toggle between the rendered view and the raw Markdown editor"));
            mode.selectedProperty().addListener((obs, was, editing) -> applyMode());
            HBox bar = new HBox(mode);
            bar.setAlignment(Pos.CENTER_RIGHT);
            bar.setPadding(new Insets(0, 0, 4, 0));
            setTop(bar);
        } else {
            mode = null;
        }
        applyMode();
    }

    /** Applies the current mode: the editor when toggled to edit, the re-rendered view at rest. */
    private void applyMode() {
        boolean editing = mode != null && mode.isSelected();
        if (mode != null) {
            mode.setText(editing ? "View" : "Edit");
        }
        if (editing) {
            setCenter(editor);
            editor.requestFocus();
        } else {
            view.setModel(renderer.apply(editor.getText()));
            setCenter(view);
        }
    }

    /**
     * The raw Markdown source — the editor's current buffer, whichever mode is showing.
     *
     * @return the source text, never {@code null}
     */
    public String getText() {
        String text = editor.getText();
        return text == null ? "" : text;
    }

    /**
     * Replaces the raw Markdown source, re-rendering if the view is at rest.
     *
     * @param markdown the new source; {@code null} is treated as empty
     */
    public void setText(String markdown) {
        editor.setText(markdown == null ? "" : markdown);
        if (mode == null || !mode.isSelected()) {
            view.setModel(renderer.apply(editor.getText()));
        }
    }

    /**
     * The raw-editor half of the toggle — the seam for host-installed input behaviors such as
     * the Koncept token drop ({@link KonceptTokenDrop}); the buffer stays managed by this pane.
     *
     * @return the raw Markdown editor (never {@code null})
     */
    public TextArea rawEditor() {
        return editor;
    }
}
