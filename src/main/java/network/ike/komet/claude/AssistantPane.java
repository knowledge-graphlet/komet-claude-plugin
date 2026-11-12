package network.ike.komet.claude;

import dev.ikm.komet.kview.mvvm.view.journal.JournalKlContext;
import dev.ikm.komet.layout.help.KlHelp;
import dev.ikm.komet.layout.help.KlHelpProvider;
import dev.ikm.komet.layout.help.KlHelpTopic;
import dev.ikm.komet.layout.help.KlHelpViewer;
import dev.ikm.komet.layout.sidebar.KlAssistantPane;
import dev.ikm.komet.layout.sidebar.KlAssistantPaneProvider;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Komet Assistant as a window's sidebar shows it: a {@link ClaudeCard} without its tab, toolbar and
 * conversations rail, under a header in the light knowledge-layout window chrome.
 *
 * <p>The header carries the control that collapses the sidebar, New conversation, History, Settings and a
 * More menu with the card's remaining actions (coordinates, document pairing, find, expand and collapse,
 * save, open in a card, help). A strip under it names the window the assistant is helping with, and a row
 * names the conversation on screen. History, Settings and Help each swap the chat for a view of their own
 * with a Back control.
 *
 * <p>The card is the same one a journal's workspace shows, so conversations, settings and tools behave
 * alike in both; the pane only arranges it.
 */
public final class AssistantPane implements KlAssistantPane {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantPane.class);

    /** One settings section's row: its summary supplier and the label showing it. */
    private record SettingsRow(Supplier<String> summary, Label value) {
    }

    private final ClaudeCard card;
    private final BorderPane root = new BorderPane();
    private final Label conversationTitle = new Label();
    private final HBox titleRow;

    private final ToggleGroup views = new ToggleGroup();
    private final ToggleButton historyButton;
    private final ToggleButton settingsButton;

    private final TextField historySearch = new TextField();
    private final Label historyCount = new Label();
    private final HBox historyFooter = new HBox(6);
    private final VBox historyView;

    private final Label settingsTitle = new Label();
    private final VBox settingsList = new VBox();
    private final StackPane settingsBody = new StackPane(settingsList);
    private final List<SettingsRow> settingsRows = new ArrayList<>();
    private final VBox settingsView;

    private final VBox helpView;
    private KlHelpViewer helpViewer;

    AssistantPane(KlAssistantPaneProvider.Host host) {
        card = ClaudeCard.forPane(host.preferences(), host.journalTopic(), this::getHostName);
        card.setSeedParentContext(new JournalKlContext(host.viewProperties().nodeView()));
        card.onPaneReady(this::cardReady);
        card.onPaneChanged(this::refreshTitle);

        root.getStyleClass().add("assistant-pane");
        root.getStylesheets().add(AssistantPane.class.getResource("assistant-pane.css").toExternalForm());

        // ---- Header ----
        Button collapse = iconButton(PaneIcons.COLLAPSE, "Hide Komet Assistant");
        collapse.setId("assistantSidebarCollapse");
        collapse.getStyleClass().add("assistant-pane-collapse");
        collapse.setOnAction(e -> host.collapse().run());

        Region mark = new Region();
        mark.getStyleClass().add("assistant-pane-mark");
        Label name = new Label(ClaudeCard.CARD_NAME);
        name.getStyleClass().add("assistant-pane-name");
        name.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button newConversation = iconButton(PaneIcons.PLUS, "New conversation");
        newConversation.setId("assistantPaneNew");
        newConversation.setOnAction(e -> {
            card.paneNewConversation();
            views.selectToggle(null);
        });
        historyButton = viewToggle(PaneIcons.HISTORY, "History: all conversations", "assistantPaneHistory");
        settingsButton = viewToggle(PaneIcons.GEAR, "Assistant settings", "assistantPaneSettings");

        HBox header = new HBox(4, collapse, mark, name, spacer, newConversation, historyButton, settingsButton,
                moreMenu());
        HBox.setMargin(mark, new javafx.geometry.Insets(0, 2, 0, 6));
        header.getStyleClass().add("assistant-pane-header");
        header.setAlignment(Pos.CENTER_LEFT);

        // ---- Context strip and the conversation on screen ----
        Label helpingWith = new Label("Helping with");
        Label hostLabel = new Label();
        hostLabel.getStyleClass().add("assistant-pane-host-name");
        hostLabel.textProperty().bind(hostName);
        HBox context = new HBox(5, helpingWith, hostLabel);
        context.getStyleClass().add("assistant-pane-context");
        context.setAlignment(Pos.CENTER_LEFT);

        conversationTitle.getStyleClass().add("assistant-pane-conversation-title");
        conversationTitle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(conversationTitle, Priority.ALWAYS);
        Button rename = new Button();
        rename.setGraphic(PaneIcons.line(PaneIcons.PENCIL, 12));
        rename.setId("assistantPaneRename");
        rename.getStyleClass().add("assistant-pane-row-action");
        rename.setTooltip(new Tooltip("Rename this conversation"));
        rename.setFocusTraversable(false);
        rename.setOnAction(e -> card.paneRenameConversation());
        titleRow = new HBox(6, conversationTitle, rename);
        titleRow.getStyleClass().add("assistant-pane-title-row");
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // ---- History view: search, the conversations, the Trash ----
        historySearch.setId("assistantPaneHistorySearch");
        historySearch.setPromptText("Search conversations");
        historySearch.getStyleClass().add("assistant-pane-search-field");
        HBox.setHgrow(historySearch, Priority.ALWAYS);
        HBox searchBox = new HBox(6, PaneIcons.line(PaneIcons.SEARCH, 13), historySearch);
        searchBox.getStyleClass().add("assistant-pane-search");
        searchBox.setAlignment(Pos.CENTER_LEFT);
        VBox searchRow = new VBox(searchBox);
        searchRow.getStyleClass().add("assistant-pane-search-row");

        historyCount.getStyleClass().add("assistant-pane-count");
        historyFooter.getStyleClass().add("assistant-pane-view-footer");
        historyFooter.setAlignment(Pos.CENTER_LEFT);
        historyView = new VBox(viewHead(new Label("Conversations"), () -> views.selectToggle(null)), searchRow);
        historyView.getStyleClass().add("assistant-pane-view");

        // ---- Settings view: a row per section, each opening the section's own content ----
        settingsList.getStyleClass().add("assistant-pane-settings-list");
        VBox.setVgrow(settingsBody, Priority.ALWAYS);
        settingsView = new VBox(viewHead(settingsTitle, this::settingsBack), settingsBody);
        settingsView.getStyleClass().add("assistant-pane-view");

        // ---- Help view: the help viewer, made the first time it is asked for ----
        helpView = new VBox(viewHead(new Label("Help"), () -> show(null)));
        helpView.getStyleClass().add("assistant-pane-view");

        Region cardNode = card.fxObject();
        cardNode.setMinWidth(0);
        root.setTop(new VBox(header, context, titleRow));
        root.setCenter(new StackPane(cardNode, historyView, settingsView, helpView));

        // Subscribed last: a value subscription runs at once, and show() reads the nodes built above.
        views.selectedToggleProperty().subscribe(toggle -> {
            if (toggle == historyButton) {
                show(historyView);
                historySearch.requestFocus();
            } else if (toggle == settingsButton) {
                showSettingsList();
                show(settingsView);
            } else {
                historySearch.clear();
                show(null);
            }
        });

        // Realization is deferred a pulse; cardReady() then fills the history and settings views.
        card.knowledgeLayoutBind();
    }

    /***************************************************************************
     *                                                                         *
     * Public API                                                              *
     *                                                                         *
     **************************************************************************/

    @Override
    public Region fxObject() {
        return root;
    }

    @Override
    public void shutdown() {
        card.paneShutdown();
    }

    /***************************************************************************
     *                                                                         *
     * Properties                                                              *
     *                                                                         *
     **************************************************************************/

    // -- host name
    /**
     * The name of the window the pane is in, shown in the strip under the header and told to the assistant.
     */
    private final StringProperty hostName = new SimpleStringProperty(this, "hostName", "");
    @Override
    public StringProperty hostNameProperty() { return hostName; }
    @Override
    public String getHostName() { return hostName.get(); }
    @Override
    public void setHostName(String value) { hostName.set(value); }

    /***************************************************************************
     *                                                                         *
     * Implementation                                                          *
     *                                                                         *
     **************************************************************************/

    /** The card's body exists: its conversations, Trash and settings sections fill the pane's views. */
    private void cardReady() {
        ListView<?> conversations = card.getPaneConversationList();
        conversations.setMinWidth(0);
        conversations.setMaxWidth(Double.MAX_VALUE);
        conversations.getStyleClass().add("assistant-pane-conversations");
        VBox.setVgrow(conversations, Priority.ALWAYS);
        // Choosing a conversation returns to the chat it opens in.
        conversations.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && conversations.getSelectionModel().getSelectedItem() != null) {
                views.selectToggle(null);
            }
        });
        conversations.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                views.selectToggle(null);
            }
        });

        Button trash = card.getPaneTrashButton();
        trash.getStyleClass().add("assistant-pane-link");
        trash.setMaxWidth(Region.USE_PREF_SIZE);
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        historyFooter.getChildren().setAll(PaneIcons.line(PaneIcons.TRASH, 13), trash, footerSpacer, historyCount);
        historyView.getChildren().addAll(conversations, historyFooter);

        Runnable count = () -> {
            int size = card.getPaneConversations().size();
            historyCount.setText(size + (size == 1 ? " conversation" : " conversations"));
        };
        card.getPaneConversations().subscribe(count);
        count.run();
        historySearch.textProperty().subscribe(card::paneFilterConversations);

        card.addSettingsSections(this::addSettingsRow, new ClaudeCard.SettingsSurface() {
            @Override
            public void refreshSummaries() {
                AssistantPane.this.refreshSummaries();
            }

            @Override
            public void hide() {
                views.selectToggle(null);
            }
        });

        refreshTitle();
        // Saved once made, so the next opening of this window restores the same card and conversations.
        card.save();
    }

    /** Shows one of the pane's views in place of the chat; {@code null} shows the chat. */
    private void show(Node view) {
        card.fxObject().setVisible(view == null);
        historyView.setVisible(view == historyView);
        settingsView.setVisible(view == settingsView);
        helpView.setVisible(view == helpView);
        titleRow.setVisible(view == null);
        titleRow.setManaged(view == null);
    }

    private void refreshTitle() {
        conversationTitle.setText(card.getPaneConversationTitle());
    }

    /*******************************************************************************
     *  Settings view                                                              *
     ******************************************************************************/

    /** Adds one section's row to the settings list: its title, its current value and a chevron. */
    private void addSettingsRow(String title, Supplier<String> summary, Supplier<Node> content) {
        Label key = new Label(title);
        key.getStyleClass().add("assistant-pane-setting-key");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label value = new Label(summary.get());
        value.getStyleClass().add("assistant-pane-setting-value");
        HBox row = new HBox(8, key, spacer, value, PaneIcons.line(PaneIcons.CHEVRON, 12));
        row.getStyleClass().add("assistant-pane-setting-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> {
            Node sectionContent = content.get();
            VBox.setVgrow(sectionContent, Priority.ALWAYS);
            VBox section = new VBox(sectionContent);
            section.getStyleClass().add("assistant-pane-setting-content");
            settingsTitle.setText(title);
            settingsBody.getChildren().setAll(section);
        });
        settingsRows.add(new SettingsRow(summary, value));
        settingsList.getChildren().add(row);
    }

    private void refreshSummaries() {
        for (SettingsRow row : settingsRows) {
            row.value().setText(row.summary().get());
        }
    }

    private void showSettingsList() {
        refreshSummaries();
        settingsTitle.setText("Assistant Settings");
        settingsBody.getChildren().setAll(settingsList);
    }

    /** Back from a section's content to the list of sections, and from the list to the chat. */
    private void settingsBack() {
        if (settingsBody.getChildren().contains(settingsList)) {
            views.selectToggle(null);
        } else {
            showSettingsList();
        }
    }

    /*******************************************************************************
     *  More menu and Help view                                                    *
     ******************************************************************************/

    /** The header's More menu: the card's actions that have no control of their own in the pane. */
    private Button moreMenu() {
        // A plain button showing a popup menu, not a MenuButton: a MenuButton reserves room for its arrow,
        // which pushes the glyph off the centre of the hover plate.
        Button button = iconButton(PaneIcons.MORE, "More");
        button.getGraphic().getStyleClass().add("assistant-pane-dots-icon");
        button.setId("assistantPaneMore");
        ContextMenu more = new ContextMenu();
        button.setOnAction(e -> more.show(button, Side.BOTTOM, 0, 2));

        more.getItems().add(menuItem("Coordinates…", card::paneShowCoordinates));
        MenuItem stopPairing = menuItem("Stop pairing the document", card::paneStopPairing);
        if (card.isPaneInJournal()) {
            more.getItems().addAll(menuItem("Pair a document…", card::panePairDocument), stopPairing);
        }
        // No accelerator on the item: a menu's accelerator is the whole window's, and Ctrl+F is already
        // the card's own shortcut while the pointer is in the transcript.
        MenuItem find = menuItem("Find in conversation", () -> {
            views.selectToggle(null);
            show(null);
            card.paneFind();
        });
        more.getItems().addAll(new SeparatorMenuItem(), find,
                menuItem("Expand all exchanges", () -> card.paneSetAllExchangesCollapsed(false)),
                menuItem("Collapse all exchanges", () -> card.paneSetAllExchangesCollapsed(true)),
                menuItem("Save transcript…", card::paneSaveTranscript));
        List<MenuItem> last = new ArrayList<>();
        if (card.isPaneInJournal()) {
            last.add(menuItem("Open in a card", card::paneOpenInCard));
        }
        helpBook().ifPresent(book -> last.add(menuItem("Help", () -> showHelp(book))));
        if (!last.isEmpty()) {
            more.getItems().add(new SeparatorMenuItem());
            more.getItems().addAll(last);
        }
        more.setOnShowing(e -> stopPairing.setDisable(!card.paneHasPairedDocument()));
        return button;
    }

    /** The help book answering for the assistant's topic; empty when no help plugin provides it. */
    private Optional<KlHelpProvider> helpBook() {
        try {
            return card.getPaneHelpTopic().flatMap(topic -> KlHelp.book(topic.bookId()));
        } catch (RuntimeException e) {
            // Help comes from a plugin; without it the menu has no Help item.
            LOG.warn("Help for the assistant pane is unavailable: {}", e.toString());
            return Optional.empty();
        }
    }

    private void showHelp(KlHelpProvider book) {
        if (helpViewer == null) {
            helpViewer = KlHelp.viewer(book);
            Region viewer = helpViewer.fxObject();
            viewer.setMinWidth(0);
            VBox.setVgrow(viewer, Priority.ALWAYS);
            helpView.getChildren().add(viewer);
            helpViewer.show(card.getPaneHelpTopic().map(KlHelpTopic::topicId).orElseThrow());
        }
        views.selectToggle(null);
        show(helpView);
    }

    /*******************************************************************************
     *  Shared pieces                                                              *
     ******************************************************************************/

    /** The head of a view that replaces the chat: Back, then the view's title. */
    private static HBox viewHead(Label title, Runnable back) {
        Button backButton = new Button("Back");
        backButton.setGraphic(PaneIcons.line(PaneIcons.BACK, 13));
        backButton.getStyleClass().add("assistant-pane-back");
        backButton.setFocusTraversable(false);
        backButton.setOnAction(e -> back.run());
        title.getStyleClass().add("assistant-pane-view-title");
        HBox head = new HBox(8, backButton, title);
        head.getStyleClass().add("assistant-pane-view-head");
        head.setAlignment(Pos.CENTER_LEFT);
        return head;
    }

    private ToggleButton viewToggle(String icon, String tooltip, String id) {
        ToggleButton toggle = new ToggleButton();
        toggle.setGraphic(PaneIcons.line(icon, 16));
        toggle.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        toggle.setId(id);
        toggle.getStyleClass().add("assistant-pane-button");
        toggle.setTooltip(new Tooltip(tooltip));
        toggle.setFocusTraversable(false);
        toggle.setToggleGroup(views);
        return toggle;
    }

    private static Button iconButton(String icon, String tooltip) {
        Button button = new Button();
        button.setGraphic(PaneIcons.line(icon, 16));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().add("assistant-pane-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        return button;
    }

    private static MenuItem menuItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }

    /** ServiceLoader provider contributing the assistant to every window's sidebar. */
    public static final class Provider implements KlAssistantPaneProvider {

        /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
        public Provider() {
        }

        @Override
        public KlAssistantPane create(Host host) {
            return new AssistantPane(host);
        }
    }
}
