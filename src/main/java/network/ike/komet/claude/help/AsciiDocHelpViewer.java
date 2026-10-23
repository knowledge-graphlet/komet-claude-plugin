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
package network.ike.komet.claude.help;

import dev.ikm.komet.layout.help.KlHelpProvider;
import dev.ikm.komet.layout.help.KlHelpViewer;
import dev.ikm.komet.markdown.richtext.InlineDecorator;
import dev.ikm.komet.markdown.richtext.InlinePiece;
import dev.ikm.komet.markdown.richtext.MarkdownRichTextRenderer;
import dev.ikm.komet.markdown.richtext.MarkdownStyledModel;
import dev.ikm.komet.markdown.richtext.RichTextSearch;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttribute;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledSegment;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;
import network.ike.komet.claude.ui.MarkdownEditPane;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shows a help book in a card's help drawer: one topic at a time in the rich text control every
 * read-only text in Komet uses, its cross references followed by a click, back and forward
 * through the topics visited, the book's contents, and a search over its topics. A topic is the
 * book's AsciiDoc, projected by {@link AsciiDocMarkdown}.
 */
final class AsciiDocHelpViewer implements KlHelpViewer {

    private static final Logger LOG = LoggerFactory.getLogger(AsciiDocHelpViewer.class);

    /** The character attribute that carries a link's destination. */
    static final StyleAttribute<String> LINK = StyleAttribute.character("network.ike.komet.claude.help.link", String.class);

    private static final double BASE_SIZE = 13;
    private static final double BLOCK_SPACING = 7;
    private static final Color LINK_COLOR = Color.web("#2f62c4");
    private static final StyleAttributeMap BASE_STYLE = StyleAttributeMap.builder().setFontSize(BASE_SIZE).build();

    /** Writes a link's text in the link colour, underlined, carrying its destination. */
    private static final InlineDecorator FOLLOWS_LINKS = new InlineDecorator() {
        @Override
        public List<InlinePiece> decorate(String text, StyleAttributeMap style) {
            return InlineDecorator.PLAIN.decorate(text, style);
        }

        @Override
        public StyleAttributeMap linkStyle(String destination, StyleAttributeMap style) {
            StyleAttributeMap.Builder builder = StyleAttributeMap.builder();
            if (style != null) {
                builder.merge(style);
            }
            return builder.set(LINK, destination).setTextColor(LINK_COLOR).setUnderline(true).build();
        }
    };

    /** A line of the list: a contents entry or a search hit. */
    private record Row(String topicId, String title, String detail, int depth) {
    }

    private final KlHelpProvider book;
    private final MarkdownRichTextRenderer renderer = new MarkdownRichTextRenderer(BASE_SIZE, FOLLOWS_LINKS);
    private final Map<String, String> markdown = new HashMap<>();
    private final Deque<String> back = new ArrayDeque<>();
    private final Deque<String> forward = new ArrayDeque<>();

    private final VBox root = new VBox(6);
    private final MarkdownEditPane page;
    private final ListView<Row> list = new ListView<>();
    private final TextField search = new TextField();
    private final ToggleButton contents = new ToggleButton();
    private final Button backButton = new Button();
    private final Button forwardButton = new Button();

    private HelpSearch index;
    private long selectionTicket;
    private String current;

    AsciiDocHelpViewer(KlHelpProvider book) {
        this.book = book;
        renderer.setBlockSpacing(BLOCK_SPACING);
        page = new MarkdownEditPane("", false, this::render);

        Label title = new Label("Help · " + book.title());
        title.getStyleClass().add("help-book-title");
        title.setStyle("-fx-text-fill: #5f6b7a; -fx-font-size: 11;");

        tool(backButton, "mdi2a-arrow-left", "Back to the topic before");
        tool(forwardButton, "mdi2a-arrow-right", "Forward again");
        tool(contents, "mdi2f-format-list-bulleted", "The book's contents");
        backButton.setOnAction(event -> step(back, forward));
        forwardButton.setOnAction(event -> step(forward, back));
        contents.selectedProperty().addListener((obs, was, is) -> {
            if (is) {
                search.clear();
                showContents();
            } else if (search.getText().isBlank()) {
                hideList();
            }
        });

        backButton.setId("helpBack");
        forwardButton.setId("helpForward");
        contents.setId("helpContents");
        search.setId("helpSearch");
        list.setId("helpList");
        search.setPromptText("Search help");
        search.textProperty().addListener((obs, was, is) -> searchFor(is));
        search.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                search.clear();
            } else if (event.getCode() == KeyCode.ENTER && !list.getItems().isEmpty()) {
                open(list.getItems().getFirst());
            } else if (event.getCode() == KeyCode.DOWN && list.isVisible() && !list.getItems().isEmpty()) {
                list.requestFocus();
                list.getSelectionModel().selectFirst();
            }
        });
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox bar = new HBox(4, backButton, forwardButton, contents, search);
        bar.setAlignment(Pos.CENTER_LEFT);

        list.setCellFactory(view -> new RowCell());
        list.setOnKeyPressed(event -> {
            Row row = list.getSelectionModel().getSelectedItem();
            if (row != null && event.getCode() == KeyCode.ENTER) {
                open(row);
            }
        });
        hideList();

        RichTextArea text = page.renderedView();
        text.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.isStillSincePress()) {
                linkAt(text, event).ifPresent(this::follow);
            }
        });
        text.addEventHandler(MouseEvent.MOUSE_MOVED,
                event -> text.setCursor(linkAt(text, event).isPresent() ? Cursor.HAND : Cursor.TEXT));

        StackPane body = new StackPane(page, list);
        VBox.setVgrow(body, Priority.ALWAYS);
        root.getChildren().addAll(title, bar, body);
        root.setPadding(new Insets(8, 10, 8, 10));
        updateButtons();
    }

    @Override
    public Region fxObject() {
        return root;
    }

    @Override
    public void show(String topicId) {
        go(topicId);
    }

    /** Shows a topic as a new step: the one shown before is a step back. */
    private void go(String topicId) {
        if (current != null && !current.equals(topicId)) {
            back.push(current);
            forward.clear();
        }
        display(topicId);
    }

    /** Steps back or forward: the shown topic moves to the other stack. */
    private void step(Deque<String> from, Deque<String> to) {
        if (from.isEmpty()) {
            return;
        }
        if (current != null) {
            to.push(current);
        }
        display(from.pop());
    }

    private void display(String topicId) {
        current = topicId;
        page.showText(markdownOf(topicId));
        selectWhenLaidOut(TextPos.ZERO, TextPos.ZERO);
        contents.setSelected(false);
        hideList();
        updateButtons();
    }

    private void updateButtons() {
        backButton.setDisable(back.isEmpty());
        forwardButton.setDisable(forward.isEmpty());
    }

    private String markdownOf(String topicId) {
        return markdown.computeIfAbsent(topicId, id -> book.source(id)
                .map(AsciiDocMarkdown::markdown)
                .orElse("# No such topic\n\n" + book.title() + " has no topic named `" + id + "`.\n"));
    }

    private StyledTextModel render(String text) {
        List<RichParagraph> paragraphs = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        renderer.render(text == null ? "" : text, BASE_STYLE, paragraphs, plain);
        return paragraphs.isEmpty() ? MarkdownStyledModel.empty() : new MarkdownStyledModel(paragraphs, plain);
    }

    // ── links ────────────────────────────────────────────────────────────────

    /** The destination of the link under the pointer, if it is over one. */
    private static Optional<String> linkAt(RichTextArea text, MouseEvent event) {
        TextPos pos = text.getTextPosition(event.getScreenX(), event.getScreenY());
        StyledTextModel model = text.getModel();
        if (pos == null || model == null || pos.index() >= model.size()) {
            return Optional.empty();
        }
        RichParagraph paragraph = model.getParagraph(pos.index());
        int at = pos.charIndex();
        int start = 0;
        for (int i = 0; i < paragraph.getSegmentCount(); i++) {
            StyledSegment segment = paragraph.getSegment(i);
            int end = start + segment.getTextLength();
            if (at >= start && at < end) {
                StyleAttributeMap attributes = segment.getStyleAttributeMap(null);
                return attributes == null ? Optional.empty() : Optional.ofNullable(attributes.get(LINK));
            }
            start = end;
        }
        return Optional.empty();
    }

    /** Follows a link: a cross reference to its topic, an address to the system's browser. */
    private void follow(String destination) {
        String lower = destination.toLowerCase(Locale.ROOT);
        if (lower.startsWith(AsciiDocMarkdown.XREF)) {
            go(AsciiDocMarkdown.topicOf(destination.substring(AsciiDocMarkdown.XREF.length())));
        } else if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
            Thread.ofVirtual().name("help-link").start(() -> {
                try {
                    Desktop.getDesktop().browse(URI.create(destination));
                } catch (Exception e) {
                    LOG.warn("Cannot open {} in the browser: {}", destination, e.toString());
                }
            });
        } else {
            go(AsciiDocMarkdown.topicOf(destination));
        }
    }

    // ── contents and search ──────────────────────────────────────────────────

    private void showContents() {
        List<Row> rows = book.contents().stream()
                .map(entry -> new Row(entry.topicId(), entry.title(), "", entry.depth()))
                .toList();
        list.setPlaceholder(new Label(book.title() + " has no contents."));
        showList(rows);
    }

    private void searchFor(String query) {
        if (query == null || query.isBlank()) {
            if (!contents.isSelected()) {
                hideList();
            }
            return;
        }
        contents.setSelected(false);
        List<Row> rows = index().find(query).stream()
                .map(hit -> new Row(hit.topicId(), hit.title(), hit.snippet(), 1))
                .toList();
        list.setPlaceholder(new Label("No topic uses “" + query.strip() + "”."));
        showList(rows);
    }

    /** The search over the book, made on the first search from every topic in its contents. */
    private HelpSearch index() {
        if (index == null) {
            List<HelpSearch.Page> pages = new ArrayList<>();
            for (KlHelpProvider.Entry entry : book.contents()) {
                List<RichParagraph> paragraphs = new ArrayList<>();
                List<String> plain = new ArrayList<>();
                new MarkdownRichTextRenderer(BASE_SIZE, InlineDecorator.PLAIN)
                        .render(markdownOf(entry.topicId()), BASE_STYLE, paragraphs, plain);
                // The topic's title heads its text; a snippet that repeated it would say nothing.
                if (!plain.isEmpty() && plain.getFirst().strip().equals(entry.title())) {
                    plain.removeFirst();
                }
                pages.add(new HelpSearch.Page(entry.topicId(), entry.title(), String.join("\n", plain)));
            }
            index = new HelpSearch(pages);
        }
        return index;
    }

    /** Opens a row's topic; from a search, with the first use of the first word selected. */
    private void open(Row row) {
        String query = search.getText();
        go(row.topicId());
        if (query != null && !query.isBlank()) {
            String word = query.strip().split("\\s+")[0];
            RichTextArea text = page.renderedView();
            RichTextSearch.findAll(text.getModel(), word).stream().findFirst().ifPresent(match ->
                    selectWhenLaidOut(TextPos.ofLeading(match.paragraphIndex(), match.start()),
                            TextPos.ofLeading(match.paragraphIndex(), match.end())));
        }
    }

    /**
     * Selects a range once the topic's text has joined layout, two pulses after it is shown, so
     * the selection scrolls into view against settled heights; a later selection supersedes it.
     */
    private void selectWhenLaidOut(TextPos anchor, TextPos caret) {
        long ticket = ++selectionTicket;
        Platform.runLater(() -> Platform.runLater(() -> {
            if (ticket == selectionTicket) {
                page.renderedView().select(anchor, caret);
            }
        }));
    }

    private void showList(List<Row> rows) {
        list.getItems().setAll(rows);
        list.setVisible(true);
        list.setManaged(true);
        page.setVisible(false);
    }

    private void hideList() {
        list.setVisible(false);
        list.setManaged(false);
        page.setVisible(true);
    }

    private static void tool(ButtonBase button, String icon, String tip) {
        FontIcon glyph = new FontIcon(icon);
        glyph.setIconSize(16);
        glyph.setIconColor(Color.web("#34465f"));
        button.setGraphic(glyph);
        button.setTooltip(new Tooltip(tip));
        button.setFocusTraversable(false);
    }

    /** A list line: the title, and beneath it a search hit's snippet, indented by its depth; a click opens it. */
    private final class RowCell extends ListCell<Row> {
        RowCell() {
            setOnMouseClicked(event -> {
                if (!isEmpty() && getItem() != null && event.getButton() == MouseButton.PRIMARY) {
                    open(getItem());
                }
            });
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label title = new Label(row.title());
            title.setStyle(row.depth() <= 1 ? "-fx-font-weight: bold;" : "");
            VBox lines = new VBox(2, title);
            if (!row.detail().isBlank()) {
                Label detail = new Label(row.detail());
                detail.setWrapText(true);
                detail.setStyle("-fx-text-fill: #5f6b7a; -fx-font-size: 11;");
                detail.setMaxWidth(380);
                lines.getChildren().add(detail);
            }
            lines.setPadding(new Insets(2, 0, 2, 14 * Math.max(0, row.depth() - 1)));
            setText(null);
            setGraphic(lines);
        }
    }
}
