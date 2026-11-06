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

import dev.ikm.komet.layout.tray.KlSidebarTray;
import dev.ikm.komet.layout_engine.host.MakeCardWindowEvent;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.events.EvtBusFactory;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import network.ike.komet.claude.doc.ReadingMeasure;
import network.ike.komet.claude.ui.MarkdownEditPane;
import network.ike.komet.claude.ui.MarkdownRichText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The journal's Documents tray: the folders of {@link DocumentLibrary} as a tree over a detail of
 * what is selected, and Open in Journal, which shows a document on a card of this journal. A
 * document that lives elsewhere joins the tree as its repository's folder when its card asks to
 * be shown here. The detail is read-only rich text, so it can be selected and copied.
 */
final class DocumentNavigatorTray implements KlSidebarTray {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentNavigatorTray.class);

    /** The sidebar toggle's id, which a card selects to bring this tray out. */
    static final String TOGGLE_ID = "stampedDocumentNavigatorToggle";

    private static final String TRAY_ID = "stampedDocumentNavigatorTray";

    /** The most height a card opened from here asks for, so a long document opens tall enough to read. */
    private static final double OPENED_HEIGHT = 900;

    private final UUID journalTopic;
    private final VBox root = new VBox();
    private final TreeItem<Path> tops = new TreeItem<>();
    private final TreeView<Path> tree = new TreeView<>(tops);
    private final Set<Path> elsewhere = new LinkedHashSet<>();
    private final MarkdownEditPane detail;
    private final Label status = new Label();
    private final Button open = button("Open in Journal", "stampedDocumentNavigatorOpen", "Show the document on a card in this journal");
    private final Button finder = button("Show in Finder", "stampedDocumentNavigatorFinder", "Show the file or folder in the Finder");
    private final VBox emptyState = new VBox();
    /** Advanced at each selection; a detail read for an older one is dropped. */
    private int reading;

    DocumentNavigatorTray(UUID journalTopic) {
        this.journalTopic = journalTopic;
        root.setId(TRAY_ID);
        root.getProperties().put(DocumentNavigatorTray.class, this);
        root.getStyleClass().addAll("unpublished-changes-container", "trash-container");
        root.setPrefWidth(372);

        Label title = new Label("Documents");
        title.getStyleClass().add("title-label");
        HBox titleRow = new HBox(title);
        titleRow.getStyleClass().add("title-row");
        Label subtitle = new Label("Stamped documents in " + DocumentLibrary.shownRoot());
        subtitle.getStyleClass().add("subtitle-label");
        subtitle.setWrapText(true);
        VBox header = new VBox(titleRow, subtitle);
        header.getStyleClass().add("header");

        tree.setId("stampedDocumentNavigatorTree");
        tree.setShowRoot(false);
        tree.setCellFactory(view -> new EntryCell());
        tree.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> select(is == null ? null : is.getValue()));
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelected();
            }
        });
        detail = new MarkdownEditPane("", false,
                markdown -> new MarkdownRichText(Calculators.View.Default(), MarkdownRichText.DEFAULT_BASE).renderMarkdown(markdown));
        detail.setId("stampedDocumentNavigatorDetail");
        SplitPane split = new SplitPane(tree, detail);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.6);

        Label emptyTitle = new Label("No documents yet");
        emptyTitle.getStyleClass().add("empty-title");
        Label emptyText = new Label(DocumentLibrary.shownRoot() + " is one git repository for your stamped documents, "
                + "outside the synced tree. Create it here; nothing is committed until you add a document.");
        emptyText.getStyleClass().add("empty-text");
        emptyText.setWrapText(true);
        Button create = button("Create " + DocumentLibrary.shownRoot(), "stampedDocumentNavigatorCreate", "Make the folder and its repository, through your own git");
        create.setOnAction(e -> createLibrary());
        emptyState.getChildren().addAll(emptyTitle, emptyText, create);
        emptyState.getStyleClass().add("empty-state");
        StackPane area = new StackPane(split, emptyState);
        area.getStyleClass().add("changes-area");
        VBox.setVgrow(area, Priority.ALWAYS);

        open.setDisable(true);
        open.setOnAction(e -> openSelected());
        finder.setDisable(true);
        finder.setOnAction(e -> selected().ifPresent(DocumentNavigatorTray::showInFinder));
        Button refresh = button("Refresh", "stampedDocumentNavigatorRefresh", "Read the folders again");
        refresh.setOnAction(e -> shown());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(open, finder, spacer, refresh);
        buttons.getStyleClass().add("footer-buttons");
        status.getStyleClass().add("subtitle-label");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        VBox footer = new VBox(buttons, status);
        footer.getStyleClass().add("footer");

        root.getChildren().addAll(header, area, footer);
        URL css = DocumentNavigatorTray.class.getResource("/dev/ikm/komet/kview/mvvm/view/kview.css");
        if (css != null) {
            root.getStylesheets().add(css.toExternalForm());
        }
        shown();
    }

    @Override
    public Pane fxObject() {
        return root;
    }

    /** Reads the folders again, keeping what was selected. */
    @Override
    public void shown() {
        Optional<Path> kept = selected();
        List<TreeItem<Path>> read = new ArrayList<>();
        if (Files.isDirectory(DocumentLibrary.root())) {
            read.add(new Entry(DocumentLibrary.root()));
        }
        elsewhere.stream().filter(Files::isDirectory).forEach(folder -> read.add(new Entry(folder)));
        tops.getChildren().setAll(read);
        read.stream().findFirst().ifPresent(top -> top.setExpanded(true));
        emptyState.setVisible(read.isEmpty());
        emptyState.setManaged(read.isEmpty());
        kept.filter(Files::exists).ifPresent(this::reveal);
        selected().ifPresent(this::select);                    // what the detail says may have changed too
    }

    private Optional<Path> selected() {
        return Optional.ofNullable(tree.getSelectionModel().getSelectedItem()).map(TreeItem::getValue);
    }

    private void createLibrary() {
        status.setText("Creating " + DocumentLibrary.shownRoot() + " …");
        CompletableFuture.runAsync(() -> {
            try {
                DocumentLibrary.ensure();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((done, failure) -> Platform.runLater(() -> {
            status.setText(failure == null ? "Created " + DocumentLibrary.shownRoot() + "." : "Not created: " + message(failure));
            shown();
        }));
    }

    /** Reads what the detail says of a selection off the FX thread. */
    private void select(Path path) {
        int asked = ++reading;
        open.setDisable(path == null || !DocumentLibrary.isDocument(path));
        finder.setDisable(path == null);
        if (path == null) {
            detail.showText("");
            return;
        }
        status.setText("");
        CompletableFuture.supplyAsync(() -> {
            try {
                DocumentLibrary.Details details = DocumentLibrary.details(path);
                return DocumentLibrary.markdown(details, details.folder() ? Optional.empty() : stored(details));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((text, failure) -> Platform.runLater(() -> {
            if (asked != reading) {
                return;
            }
            if (failure != null) {
                LOG.warn("Reading {} failed", path, failure);
                detail.showText("Could not read " + path + ": " + message(failure));
                return;
            }
            detail.showText(text);
        }));
    }

    /** What the knowledge base holds of a file, read under the identity the file materializes as. */
    private static Optional<DocumentLibrary.Stored> stored(DocumentLibrary.Details details) {
        return details.inRepository().flatMap(relative -> {
            try {
                PublicId anchor = GitDocumentMaterializer.documentAnchor(details.repository().get(), relative.toString().replace('\\', '/'));
                return GitDocumentMaterializer.load(anchor).map(document -> new DocumentLibrary.Stored(document.lines().size(),
                        (int) document.lines().stream().filter(line -> !line.affirmers().isEmpty()).count()));
            } catch (RuntimeException e) {
                LOG.debug("The store has nothing for {}", relative, e);
                return Optional.empty();
            }
        });
    }

    private void openSelected() {
        selected().filter(DocumentLibrary::isDocument).ifPresent(this::open);
    }

    /** Brings a card showing the file forward, or opens one in this journal. */
    private void open(Path file) {
        StampedDocumentCard showing = StampedDocumentCard.showingFile(file);
        if (showing != null) {
            showing.fxObject().toFront();
            return;
        }
        status.setText("Opening " + file.getFileName() + " …");
        EvtBusFactory.getDefaultEvtBus().publish(journalTopic, new MakeCardWindowEvent(this, MakeCardWindowEvent.CREATE_NEW,
                StampedDocumentCard.Factory.class.getName(), null, ReadingMeasure.documentWindow(MarkdownRichText.DEFAULT_BASE),
                Math.min(root.getHeight(), OPENED_HEIGHT),
                card -> Platform.runLater(() -> {
                    status.setText("");
                    ((StampedDocumentCard) card).openReading(file);
                })));
    }

    /**
     * Selects a file in the tree, opening the folders above it. A file outside every folder shown
     * brings its repository's folder into the tree, or its own folder when it has no repository.
     *
     * @param file the file
     */
    void reveal(Path file) {
        Path target = file.toAbsolutePath().normalize();
        if (tops.getChildren().stream().noneMatch(top -> target.startsWith(top.getValue()))) {
            elsewhere.add(design.knowledge.stamp.adoc.git.GitRepo.findRoot(target).map(found -> found.toAbsolutePath().normalize()).orElse(target.getParent()));
            shown();
        }
        TreeItem<Path> at = tops.getChildren().stream().filter(top -> target.startsWith(top.getValue())).findFirst().orElse(null);
        while (at != null && !at.getValue().equals(target)) {
            at.setExpanded(true);
            at = at.getChildren().stream().filter(child -> target.startsWith(child.getValue())).findFirst().orElse(null);
        }
        if (at == null) {
            status.setText(file.getFileName() + " is hidden, or not a document the tree lists.");
            return;
        }
        tree.getSelectionModel().select(at);
        tree.scrollTo(Math.max(0, tree.getRow(at) - 2));
    }

    /**
     * Brings the Documents tray of the journal a card is in out, and selects a file there.
     *
     * @param from a node of the card
     * @param file the file
     * @return whether the journal has the tray: a card in a window of its own does not
     */
    static boolean reveal(Node from, Path file) {
        Scene scene = from == null ? null : from.getScene();
        if (scene == null || !(scene.lookup("#" + TOGGLE_ID) instanceof ToggleButton toggle)
                || scene.lookup("#" + TRAY_ID) == null
                || !(scene.lookup("#" + TRAY_ID).getProperties().get(DocumentNavigatorTray.class) instanceof DocumentNavigatorTray tray)) {
            return false;
        }
        toggle.setSelected(true);
        tray.reveal(file);
        return true;
    }

    /**
     * Shows a file or folder in the Finder, off the FX thread: selected in its folder where the
     * desktop can do that, else its folder opened.
     *
     * @param path the file or folder
     */
    static void showInFinder(Path path) {
        File file = path.toFile();
        CompletableFuture.runAsync(() -> {
            try {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                    desktop.browseFileDirectory(file);
                } else {
                    desktop.open(file.isDirectory() ? file : file.getParentFile());
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not show {} in the Finder", path, e);
            }
        });
    }

    private static String message(Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage();
    }

    private static Button button(String text, String id, String tip) {
        Button button = new Button(text);
        button.setId(id);
        button.getStyleClass().add("discard-all-button");
        button.setTooltip(new Tooltip(tip));
        return button;
    }

    /** A folder or document, its children read the first time they are asked for. */
    private static final class Entry extends TreeItem<Path> {

        private final boolean leaf;
        private boolean read;

        Entry(Path path) {
            super(path);
            leaf = !Files.isDirectory(path);
        }

        @Override
        public boolean isLeaf() {
            return leaf;
        }

        @Override
        public ObservableList<TreeItem<Path>> getChildren() {
            if (!read) {
                read = true;
                try {
                    super.getChildren().setAll(DocumentLibrary.children(getValue()).stream().<TreeItem<Path>>map(Entry::new).toList());
                } catch (IOException e) {
                    LOG.warn("Could not list {}", getValue(), e);
                }
            }
            return super.getChildren();
        }
    }

    /** A line of the tree: the name with a folder or file icon, the whole path as its tooltip. */
    private final class EntryCell extends TreeCell<Path> {

        EntryCell() {
            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !isEmpty() && DocumentLibrary.isDocument(getItem())) {
                    open(getItem());
                }
            });
        }

        @Override
        protected void updateItem(Path path, boolean empty) {
            super.updateItem(path, empty);
            if (empty || path == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            boolean top = getTreeItem() != null && getTreeItem().getParent() == tops;
            setText(path.getFileName() == null ? path.toString() : path.getFileName().toString());
            setGraphic(Icons.of(Files.isDirectory(path) ? (top ? "mdi2s-source-repository" : "mdi2f-folder-outline") : "mdi2f-file-document-outline"));
            setTooltip(new Tooltip(path.toString()));
        }
    }
}
