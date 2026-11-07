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

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * One panel for choosing where a document's repository is, or where a new one goes, laid out as
 * the Finder's open panel is: the toolbar in the title bar, places at the side, a column for each
 * folder down to the one chosen, New Folder and the action at the bottom. The window itself is
 * the platform's ({@link StageStyle#EXTENDED}), with its own controls, shadow and resizing, and the
 * accent is the person's. Everything inside is JavaFX: the system choosers show no message on
 * macOS and cannot refuse a folder or rename their button, which is everything this choice needs.
 * What selecting a folder would do comes from {@link DocumentRepository#plan}, so the action
 * button always names it.
 */
final class FolderPanel {

    private static final PseudoClass REFUSED = PseudoClass.getPseudoClass("refused");
    private static final PseudoClass HEADING = PseudoClass.getPseudoClass("heading");
    private static final PseudoClass TRAIL = PseudoClass.getPseudoClass("trail");

    /**
     * A line of the side list.
     *
     * @param name what it is called
     * @param icon its icon's code, or {@code null} for a section heading
     * @param path the folder, or {@code null} for a section heading
     */
    record Place(String name, String icon, Path path) {

        static Place heading(String name) {
            return new Place(name, null, null);
        }
    }

    private final Function<Path, DocumentRepository.Plan> planner;
    private final List<Place> places;
    private final DialogPane pane = new DialogPane();
    private final HBox columns = new HBox();
    private final ScrollPane scroller = new ScrollPane(columns);
    private final ListView<Place> sidebar = new ListView<>();
    private final MenuButton location = new MenuButton();
    private final Label note = new Label();
    private final TextField typed = new TextField();
    private final Button backButton = new Button("Back");
    private final Button forwardButton = new Button("Forward");
    private final Deque<Path> back = new ArrayDeque<>();
    private final Deque<Path> forward = new ArrayDeque<>();
    private final ButtonType choose = new ButtonType("Choose", ButtonBar.ButtonData.OK_DONE);
    private final ButtonType newFolder = new ButtonType("New Folder", ButtonBar.ButtonData.LEFT);
    private Path chosen;
    private long reading;
    private boolean drawing;

    private FolderPanel(String message, Path start, List<Place> places, Function<Path, DocumentRepository.Plan> planner) {
        this.planner = planner;
        this.places = places;
        pane.setId("folderPanel");
        pane.getStyleClass().add("folder-panel");
        URL css = FolderPanel.class.getResource("/network/ike/komet/claude/stamp/folder-panel.css");
        if (css != null) {
            pane.getStylesheets().add(css.toExternalForm());
        }
        pane.setStyle("-panel-accent: " + web(Platform.getPreferences().getAccentColor()) + ";");
        pane.getButtonTypes().setAll(newFolder, ButtonType.CANCEL, choose);
        for (ButtonType type : pane.getButtonTypes()) {
            ButtonBar.setButtonUniformSize(pane.lookupButton(type), false);   // the platform's buttons fit their words
        }
        pane.lookupButton(newFolder).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();                                      // it makes a folder; the panel stays open
            makeFolder();
        });
        pane.lookupButton(newFolder).setId("folderPanelNewFolder");
        pane.lookupButton(choose).setId("folderPanelChoose");
        pane.setContent(content(message));
        pane.setPrefSize(820, 500);
        KeyCombination goTo = new KeyCodeCombination(KeyCode.G, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
        pane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (goTo.match(e)) {                              // the Finder's Go to Folder
                e.consume();
                typed.requestFocus();
            }
        });
        go(start, false);
    }

    /**
     * Shows the panel and waits.
     *
     * @param owner   the window it belongs to
     * @param title   its title
     * @param message what it is asking, in a sentence
     * @param start   the folder to start at, which need not exist yet
     * @param places  the side list
     * @param planner what selecting a folder would do
     * @return the folder chosen, or empty when the person cancelled
     */
    static Optional<Path> show(Window owner, String title, String message, Path start, List<Place> places,
                               Function<Path, DocumentRepository.Plan> planner) {
        FolderPanel panel = new FolderPanel(message, start, places, planner);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.initStyle(StageStyle.EXTENDED);
        dialog.setDialogPane(panel.pane);
        dialog.setResizable(true);
        dialog.initOwner(owner);
        return dialog.showAndWait().filter(panel.choose::equals).map(type -> panel.chosen);
    }

    private Region content(String message) {
        Label asked = new Label(message);
        asked.setId("folderPanelMessage");
        asked.getStyleClass().add("folder-panel-message");
        asked.setWrapText(true);
        asked.setMinHeight(Region.USE_PREF_SIZE);
        asked.setMaxWidth(Double.MAX_VALUE);
        asked.setAlignment(Pos.CENTER);

        backButton.setId("folderPanelBack");
        backButton.setOnAction(e -> step(back, forward));
        Icons.iconOnly(backButton, "mdi2c-chevron-left", "the folder before this one");
        forwardButton.setId("folderPanelForward");
        forwardButton.setOnAction(e -> step(forward, back));
        Icons.iconOnly(forwardButton, "mdi2c-chevron-right", "the folder after this one");
        HBox history = new HBox(backButton, forwardButton);
        history.getStyleClass().add("folder-panel-history");
        history.setAlignment(Pos.CENTER_LEFT);

        location.setId("folderPanelLocation");
        location.setMinWidth(220);
        location.setPrefWidth(260);

        typed.setId("folderPanelPath");
        typed.setPromptText("Go to folder");
        typed.setPrefWidth(170);
        typed.setOnAction(e -> folderOf(typed.getText()).ifPresent(folder -> {
            typed.clear();
            go(folder, true);
        }));

        HeaderBar toolbar = new HeaderBar();
        toolbar.getStyleClass().add("folder-panel-toolbar");
        toolbar.setLeft(history);
        toolbar.setCenter(location);
        toolbar.setRight(typed);
        HeaderBar.setAlignment(history, Pos.CENTER_LEFT);
        HeaderBar.setAlignment(location, Pos.CENTER);
        HeaderBar.setAlignment(typed, Pos.CENTER_RIGHT);

        sidebar.setId("folderPanelSidebar");
        sidebar.getItems().setAll(places);
        sidebar.setCellFactory(list -> new PlaceCell());
        sidebar.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
            if (!drawing && is != null && is.path() != null) {
                go(is.path(), true);
            }
        });
        sidebar.setPrefWidth(190);
        sidebar.setMinWidth(190);

        columns.getStyleClass().add("folder-panel-columns");
        columns.widthProperty().addListener((obs, was, is) -> scroller.setHvalue(scroller.getHmax()));   // the last column stays in view
        scroller.setFitToHeight(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.getStyleClass().add("folder-panel-scroller");

        note.setId("folderPanelNote");
        note.getStyleClass().add("folder-panel-note");
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE);
        note.setMaxWidth(Double.MAX_VALUE);

        VBox top = new VBox(toolbar, asked);
        top.getStyleClass().add("folder-panel-top");
        BorderPane layout = new BorderPane(scroller, top, null, note, sidebar);
        layout.getStyleClass().add("folder-panel-layout");
        return layout;
    }

    /** Moves to a folder, and has the columns, the location and the action follow. */
    private void go(Path folder, boolean remember) {
        Path target = folder.toAbsolutePath().normalize();
        if (remember && chosen != null && !chosen.equals(target)) {
            back.push(chosen);
            forward.clear();
        }
        chosen = target;
        Path shown = nearestFolder(target);
        List<Path> chain = chain(baseOf(shown, places), shown);
        long ticket = ++reading;
        CompletableFuture.supplyAsync(() -> chain.stream().map(FolderPanel::listing).toList())
                .thenAccept(listings -> Platform.runLater(() -> {
                    if (ticket == reading) {                  // a later move has overtaken this one
                        draw(chain, listings);
                    }
                }));
        located(target);
        drawing = true;
        sidebar.getSelectionModel().clearSelection();
        places.stream().filter(place -> target.equals(place.path())).findFirst().ifPresent(sidebar.getSelectionModel()::select);
        drawing = false;
        backButton.setDisable(back.isEmpty());
        forwardButton.setDisable(forward.isEmpty());
        act();
    }

    /** The location menu: the folder's name, and every folder above it to move up to, nearest first. */
    private void located(Path target) {
        location.setText(nameOf(target));
        location.setGraphic(Icons.of("mdi2f-folder"));
        location.setTooltip(new Tooltip(target.toString()));
        List<MenuItem> items = new ArrayList<>();
        for (Path folder = target; folder != null; folder = folder.getParent()) {
            Path step = folder;
            MenuItem item = new MenuItem(nameOf(step), Icons.of("mdi2f-folder"));
            item.setOnAction(e -> go(step, true));
            items.add(item);
        }
        location.getItems().setAll(items);
    }

    /** Names what the action would do with the folder chosen, or why it will not. */
    private void act() {
        DocumentRepository.Plan plan = planner.apply(chosen);
        boolean refused = plan.placement() == DocumentRepository.Placement.REFUSED;
        note.setText(plan.note());
        note.pseudoClassStateChanged(REFUSED, refused);
        Button action = (Button) pane.lookupButton(choose);
        action.setText(plan.action());
        action.setDisable(refused);
    }

    private void step(Deque<Path> from, Deque<Path> to) {
        if (!from.isEmpty()) {
            to.push(chosen);
            go(from.pop(), false);
        }
    }

    /** Keeps the columns that still apply and adds the rest, so a click does not replace the list it landed on. */
    private void draw(List<Path> chain, List<List<Path>> listings) {
        drawing = true;
        int keep = 0;
        while (keep < columns.getChildren().size() && keep < chain.size()
                && chain.get(keep).equals(columns.getChildren().get(keep).getUserData())) {
            keep++;
        }
        columns.getChildren().remove(keep, columns.getChildren().size());
        for (int i = keep; i < chain.size(); i++) {
            columns.getChildren().add(column(chain.get(i), listings.get(i)));
        }
        for (int i = 0; i < chain.size(); i++) {
            @SuppressWarnings("unchecked")
            ListView<Path> list = (ListView<Path>) columns.getChildren().get(i);
            if (i + 1 < chain.size()) {
                list.getSelectionModel().select(chain.get(i + 1));
            } else {
                list.getSelectionModel().clearSelection();
            }
            list.pseudoClassStateChanged(TRAIL, i + 2 < chain.size());   // the way here is grey; only the choice is the accent
        }
        drawing = false;
    }

    private ListView<Path> column(Path folder, List<Path> entries) {
        ListView<Path> list = new ListView<>();
        list.setUserData(folder);
        list.getStyleClass().add("folder-column");
        list.getItems().setAll(entries);
        list.setCellFactory(view -> new EntryCell());
        list.setPrefWidth(230);
        list.setMinWidth(230);
        list.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
            if (!drawing && is != null && Files.isDirectory(is)) {
                go(is, true);
            }
        });
        return list;
    }

    /** New Folder: asks for a name and makes it where the panel stands, as the Finder's panel does, whatever is chosen next. */
    private void makeFolder() {
        Path parent = nearestFolder(chosen);
        TextInputDialog ask = new TextInputDialog("untitled folder");
        ask.setTitle("New Folder");
        ask.setHeaderText("Name of the new folder in " + nameOf(parent));
        ask.setGraphic(null);
        ask.initOwner(pane.getScene().getWindow());
        ask.showAndWait().ifPresent(name -> {
            try {
                Path made = made(parent, name);
                columns.getChildren().clear();                // the folder's own listing has changed
                go(made, true);
            } catch (IOException | IllegalArgumentException e) {
                note.setText("Could not make the folder: " + e.getMessage());
                note.pseudoClassStateChanged(REFUSED, true);
            }
        });
    }

    /**
     * Makes a folder directly inside another.
     *
     * @param parent the folder it goes in
     * @param name   its name
     * @return the folder made
     * @throws IOException              if it cannot be made, or already exists
     * @throws IllegalArgumentException if the name would put it anywhere else
     */
    static Path made(Path parent, String name) throws IOException {
        String trimmed = name.strip();
        try {
            Path target = parent.resolve(trimmed).normalize();
            if (trimmed.isEmpty() || !parent.normalize().equals(target.getParent())) {
                throw new IllegalArgumentException("\"" + name + "\" is not a name for a folder.");
            }
            return Files.createDirectory(target);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("\"" + name + "\" is not a name for a folder.", e);
        }
    }

    /**
     * The side list: the person's usual folders, the document library, the folder that matters
     * to this choice, and the mounted volumes. The library is listed even before it exists,
     * since choosing it is how it comes to.
     *
     * @param home    the home folder
     * @param library the document library
     * @param known   the working tree the card knows or the file's own folder, or {@code null}
     * @param volumes the folder holding mounted volumes
     * @return the lines, with their headings
     */
    static List<Place> places(Path home, Path library, Path known, Path volumes) {
        List<Place> places = new ArrayList<>();
        places.add(Place.heading("Komet"));
        places.add(new Place("Documents library", "mdi2s-source-repository", library));
        if (known != null && !known.equals(library) && Files.isDirectory(known)) {
            places.add(new Place(nameOf(known), Files.exists(known.resolve(".git")) ? "mdi2s-source-repository" : "mdi2f-folder-outline", known));
        }
        places.add(Place.heading("Favorites"));
        places.add(new Place(nameOf(home), "mdi2h-home-outline", home));
        for (String name : List.of("Desktop", "Documents", "Downloads")) {
            if (Files.isDirectory(home.resolve(name))) {
                places.add(new Place(name, "mdi2f-folder-outline", home.resolve(name)));
            }
        }
        List<Path> mounted = new ArrayList<>();
        if (Files.isDirectory(volumes)) {
            try (var entries = Files.list(volumes)) {
                entries.filter(Files::isDirectory).filter(entry -> !entry.getFileName().toString().startsWith("."))
                        .sorted(Comparator.comparing(entry -> entry.getFileName().toString().toLowerCase()))
                        .forEach(mounted::add);
            } catch (IOException e) {
                mounted.clear();
            }
        }
        if (!mounted.isEmpty()) {
            places.add(Place.heading("Locations"));
            mounted.forEach(volume -> places.add(new Place(nameOf(volume), "mdi2h-harddisk", volume)));
        }
        return List.copyOf(places);
    }

    /**
     * The folders from one down to another.
     *
     * @param base   the first folder, or {@code null} for the top of the file system
     * @param target the last folder
     * @return the folders in order; from the top when {@code base} is not above {@code target}
     */
    static List<Path> chain(Path base, Path target) {
        List<Path> folders = new ArrayList<>();
        for (Path folder = target; folder != null; folder = folder.getParent()) {
            folders.addFirst(folder);
            if (folder.equals(base)) {
                break;
            }
        }
        return folders;
    }

    /** @return the deepest place at or above a folder, or the top of the file system */
    static Path baseOf(Path folder, List<Place> places) {
        return places.stream().map(Place::path).filter(path -> path != null && folder.startsWith(path))
                .max(Comparator.comparingInt(Path::getNameCount)).orElse(folder.getRoot());
    }

    /** @return the folder itself, or the nearest one above it that exists */
    static Path nearestFolder(Path path) {
        Path folder = path;
        while (folder.getParent() != null && !Files.isDirectory(folder)) {
            folder = folder.getParent();
        }
        return folder;
    }

    /**
     * Reads a typed folder.
     *
     * @param text what was typed, with a leading {@code ~} read as the home folder
     * @return the folder, or empty when the text is not a full path
     */
    static Optional<Path> folderOf(String text) {
        String path = text.strip();
        if (path.equals("~") || path.startsWith("~/")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        try {
            Path folder = Path.of(path);
            return path.isEmpty() || !folder.isAbsolute() ? Optional.empty() : Optional.of(folder);
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static String nameOf(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    /** A color as CSS writes it. */
    private static String web(Color color) {
        return String.format("#%02x%02x%02x", Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }

    private static List<Path> listing(Path folder) {
        try {
            return DocumentLibrary.children(folder);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static Node icon(String code) {
        return Icons.of(code);
    }

    /** A line of the side list: a place with its icon, or a heading that cannot be selected. */
    private static final class PlaceCell extends ListCell<Place> {

        @Override
        protected void updateItem(Place place, boolean empty) {
            super.updateItem(place, empty);
            boolean heading = !empty && place != null && place.path() == null;
            pseudoClassStateChanged(HEADING, heading);
            setDisable(heading);
            setText(empty || place == null ? null : place.name());
            setGraphic(empty || place == null || place.icon() == null ? null : icon(place.icon()));
            setTooltip(empty || place == null || place.path() == null ? null : new Tooltip(place.path().toString()));
        }
    }

    /** A line of a column: a folder with its chevron, a repository, or a document shown for orientation only. */
    private static final class EntryCell extends ListCell<Path> {

        private final Label name = new Label();
        private final Region spring = new Region();
        private final Node chevron = icon("mdi2c-chevron-right");
        private final HBox row = new HBox(6);

        EntryCell() {
            setPrefWidth(0);                                  // the column's width decides, so a long name ellipsizes
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            name.setMinWidth(0);
            HBox.setHgrow(spring, Priority.ALWAYS);
            chevron.getStyleClass().add("folder-chevron");
            row.setAlignment(Pos.CENTER_LEFT);
            row.prefWidthProperty().bind(widthProperty().subtract(16));
            row.maxWidthProperty().bind(widthProperty().subtract(16));
        }

        @Override
        protected void updateItem(Path path, boolean empty) {
            super.updateItem(path, empty);
            if (empty || path == null) {
                setGraphic(null);
                setDisable(false);
                return;
            }
            boolean folder = Files.isDirectory(path);
            setDisable(!folder);
            name.setText(path.getFileName().toString());
            Node kind = icon(!folder ? "mdi2f-file-document-outline"
                    : Files.exists(path.resolve(".git")) ? "mdi2s-source-repository" : "mdi2f-folder");
            if (folder) {
                row.getChildren().setAll(kind, name, spring, chevron);
            } else {
                row.getChildren().setAll(kind, name);
            }
            setGraphic(row);
        }
    }
}
