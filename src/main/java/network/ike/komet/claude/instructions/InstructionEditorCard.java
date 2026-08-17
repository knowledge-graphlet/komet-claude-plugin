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
package network.ike.komet.claude.instructions;

import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.CardBlueprint;
import dev.ikm.komet.layout_engine.host.AbstractHostCard;
import dev.ikm.komet.layout_engine.host.KlCardProvider;
import dev.ikm.komet.preferences.KometPreferences;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import network.ike.komet.claude.instructions.InstructionSets.Frontmatter;
import network.ike.komet.claude.instructions.InstructionSets.InstructionSet;
import network.ike.komet.claude.ui.MarkdownEditPane;
import network.ike.komet.claude.ui.MarkdownRichText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Optional;
import java.util.UUID;

/**
 * The Instruction Editor card ({@code IKE-Network/ike-issues#1044}): the standardized,
 * <em>roleless</em> authoring tile for titled instruction sets — name and description (the
 * Agent Skills frontmatter surface) over a Markdown body in the view/edit pane. The rail lists
 * this tile's registered sets with New and Import…; the editor never asks whether a document is
 * a system prompt or a skill — that is the attachment role at the use site (the assistant's
 * settings pane selects; ike-issues#1042). Sets are payload files registered by preferences
 * entries ({@link InstructionSets}), so they ride the preferences git sync, and the tile carries
 * the {@code card-type} marker so use sites discover it journal-wide (the tile-store pickup
 * model). Assistant support inside this card is the recorded follow-on from the skills brief.
 */
public final class InstructionEditorCard extends AbstractHostCard {

    /** Menu label and card title. */
    static final String CARD_NAME = "Instruction Editor";

    /** Shared discovery key (the assistant tile marker uses the same literal). */
    public static final String CARD_TYPE_KEY = "card-type";

    /** This card kind's marker value — what use-site selectors discover tiles by. */
    public static final String CARD_TYPE_VALUE = "instruction-editor";

    /** Shared tile display-name key (the kind-plus-sequence naming convention). */
    public static final String TILE_LABEL_KEY = "tile-label";

    /** One-shot flag: the ghost examples were seeded (never re-seeded, so overwrites stick). */
    private static final String SEEDED_KEY = "instruction-sets-seeded";

    private static final Logger LOG = LoggerFactory.getLogger(InstructionEditorCard.class);

    private InstructionSets store;
    private ListView<InstructionSet> setList;
    private TextField nameField;
    private TextField descriptionField;
    private javafx.scene.control.ComboBox<InstructionCategory> categoryBox;
    private MarkdownEditPane bodyPane;
    private Button saveButton;
    private Button saveAsButton;
    private Label statusLine;
    private InstructionSet active;
    private String loadedName = "";
    private String loadedDescription = "";
    private InstructionCategory loadedCategory = InstructionCategory.GENERAL;
    private String loadedBody = "";
    private BorderPane content;
    private boolean railVisible = true;
    private SplitPane split;
    private VBox rail;
    private VBox editorPane;
    private VBox emptyState;
    private BorderPane editorHost;

    private InstructionEditorCard(KometPreferences preferences) {
        super(preferences);
    }

    private InstructionEditorCard(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
    }

    @Override
    protected String cardTitle() {
        return CARD_NAME;
    }

    /** Card-node preference for the editor's base font size (the settings pane's section). */
    private static final String PREF_EDITOR_FONT_SIZE =
            "network.ike.komet.claude.instructionEditor.fontSize";

    private double baseFontSize = 13;

    /** The transient rail toggle and the standardized settings sliders (ike-issues#1043). */
    @Override
    protected void buildToolbarControls(HBox toolBar) {
        Button toggleRail = new Button("\u2630");
        toggleRail.setTooltip(new Tooltip("Show/hide instruction sets"));
        toggleRail.setOnAction(e -> setRailVisible(!railVisible));
        toggleRail.getStyleClass().add("claude-card-toolbar-button");

        Button settings = new Button();
        settings.setGraphic(dev.ikm.komet.framework.graphics.Icon.PANEL_PREFERENCE_SLIDERS.makeIcon());
        settings.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        settings.setTooltip(new Tooltip("Instruction Editor settings"));
        settings.getStyleClass().addAll("claude-card-toolbar-button", "claude-card-settings-button");
        dev.ikm.komet.layout.controls.SettingsPanePopup pane =
                new dev.ikm.komet.layout.controls.SettingsPanePopup("Instruction Editor Settings");
        pane.addSection("Text size",
                () -> Math.round(baseFontSize) + " px",
                () -> textSizeSettingsContent(pane));
        pane.attachTo(settings, (javafx.scene.layout.Pane) fxObject());
        toolBar.getChildren().addAll(toggleRail, settings);
    }

    /** The text-size section: live adjustment of the body editor and its rendered view. */
    private javafx.scene.Node textSizeSettingsContent(
            dev.ikm.komet.layout.controls.SettingsPanePopup pane) {
        Label current = new Label(Math.round(baseFontSize) + " px");
        Button down = new Button("A\u2212");
        down.setOnAction(e -> {
            adjustFont(-1);
            current.setText(Math.round(baseFontSize) + " px");
            pane.refreshSummaries();
        });
        Button up = new Button("A+");
        up.setOnAction(e -> {
            adjustFont(1);
            current.setText(Math.round(baseFontSize) + " px");
            pane.refreshSummaries();
        });
        HBox row = new HBox(8, down, up, current);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Applies and persists the base font size: the raw editor's font, the view re-rendered. */
    private void adjustFont(int delta) {
        baseFontSize = Math.max(9, Math.min(28, baseFontSize + delta));
        preferences().putDouble(PREF_EDITOR_FONT_SIZE, baseFontSize);
        applyFont();
    }

    private void applyFont() {
        if (bodyPane != null) {
            bodyPane.rawEditor().setStyle("-fx-font-size: " + Math.round(baseFontSize) + "px;");
            bodyPane.setText(bodyPane.getText());   // re-render the at-rest view at the new base
        }
    }

    /** Shows or hides the instruction-set rail — full-tile content view (KEC 2026-08-17). */
    private void setRailVisible(boolean visible) {
        railVisible = visible;
        if (split == null) {
            return;
        }
        boolean present = split.getItems().contains(rail);
        if (visible && !present) {
            split.getItems().add(0, rail);
            split.setDividerPositions(0.28);
        } else if (!visible && present) {
            split.getItems().remove(rail);
        }
    }

    @Override
    protected void renderContent() {
        if (content == null) {
            buildBody();
            // Tile identity: the marker makes this tile discoverable by use-site selectors
            // (the assistant's system-prompt section); the display name follows the
            // kind-plus-sequence convention, minted once.
            preferences().put(CARD_TYPE_KEY, CARD_TYPE_VALUE);
            ensureTileLabel();
            seedExamplesOnce();
            refreshSets(null);
        }
    }

    /**
     * Seeds the ghost examples on a tile's first open (KEC 2026-08-17): real, overwritable
     * sets — the bundled assistant instruction layer as a System Prompt example, and an
     * authored sample Skill showing the form. Seeded exactly once (the flag, not the set
     * count), so a user who overwrites or clears them is never fought.
     */
    private void seedExamplesOnce() {
        if (!preferences().get(SEEDED_KEY, "").isBlank()) {
            // Forward-compatibility: tiles seeded before the read-only ruling carry writable
            // ghosts — mark the two known system defaults read-only once.
            for (InstructionSet set : store.list()) {
                if (!set.readOnly()
                        && ("Komet Assistant default".equals(set.name())
                                || "Terminology answer style".equals(set.name()))
                        && set.description().startsWith("Example")) {
                    store.markReadOnly(set.id());
                }
            }
            return;
        }
        preferences().put(SEEDED_KEY, "true");
        store.create("Komet Assistant default",
                "System default — the bundled assistant instruction layer; Save as… to copy.",
                InstructionCategory.SYSTEM_PROMPT, true,
                bundledDefaultInstructions());
        store.create("Terminology answer style",
                "System default — an example skill; Save as… to make your own copy.",
                InstructionCategory.SKILL, true,
                """
                Use this skill when answers will be pasted into documents, issues, or reviews.

                ## Instructions

                - Lead with the answer in one sentence, then the supporting concepts as a table.
                - Reference every concept as a Koncept Badge; label identifier columns as what \
                they actually hold.
                - Prefer the knowledge base's fully-qualified names; give colloquial synonyms in \
                parentheses.
                - Close with a one-line caveat when edition or version differences could matter.
                """);
        try {
            preferences().sync();
        } catch (Exception e) {
            LOG.warn("Could not sync the example seed flag", e);
        }
    }

    /** The bundled assistant instruction layer — the System Prompt ghost example's body. */
    private static String bundledDefaultInstructions() {
        try (java.io.InputStream in = InstructionEditorCard.class.getResourceAsStream(
                "/network/ike/komet/claude/system-prompt-instructions.md")) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            LOG.warn("Could not read the bundled instruction layer for the example seed", e);
            return "";
        }
    }

    private void buildBody() {
        store = new InstructionSets(preferences());

        Label railTitle = new Label("Instruction sets");
        railTitle.setStyle("-fx-font-weight: bold;");
        Button newButton = new Button("+ New");
        newButton.setMinWidth(Region.USE_PREF_SIZE);
        newButton.setTooltip(new Tooltip("Create a new titled instruction set"));
        newButton.setOnAction(e -> newSet());
        Button importButton = new Button("Import…");
        importButton.setMinWidth(Region.USE_PREF_SIZE);
        importButton.setTooltip(new Tooltip("Import a SKILL.md-form instruction file"));
        importButton.setOnAction(e -> importSet());
        // Title and actions on separate rows: cramming them into one line ellipsized every
        // label into dot-stubs at rail width (KEC 2026-08-17 first-run review).
        HBox railActions = new HBox(6, newButton, importButton);
        railActions.setAlignment(Pos.CENTER_LEFT);
        VBox railHeader = new VBox(4, railTitle, railActions);
        railHeader.setPadding(new Insets(6));

        setList = new ListView<>();
        setList.setPrefWidth(210);
        setList.setCellFactory(lv -> new ListCell<>() {
            private final Label nameLabel = new Label();
            private final Label descriptionLabel = new Label();
            private final VBox row = new VBox(1, nameLabel, descriptionLabel);
            {
                nameLabel.setWrapText(true);
                descriptionLabel.setWrapText(true);
                setPrefWidth(0);
                maxWidthProperty().bind(lv.widthProperty().subtract(16));
                // Fills flip with selection AND focus: light fills belong only on the focused
                // selection highlight — the unfocused (grey) selection keeps dark text, or the
                // row reads blank (KEC 2026-08-17).
                Runnable syncFills = () -> {
                    boolean lit = isSelected() && lv.isFocused();
                    nameLabel.setStyle(lit
                            ? "-fx-font-weight: bold; -fx-text-fill: white;"
                            : "-fx-font-weight: bold; -fx-text-fill: #1a1a1a;");
                    descriptionLabel.setStyle(lit
                            ? "-fx-text-fill: #dce4f0; -fx-font-size: 11;"
                            : "-fx-text-fill: #6a6a6a; -fx-font-size: 11;");
                };
                syncFills.run();
                selectedProperty().addListener((obs, was, is) -> syncFills.run());
                lv.focusedProperty().addListener((obs, was, is) -> syncFills.run());
            }
            @Override
            protected void updateItem(InstructionSet set, boolean empty) {
                super.updateItem(set, empty);
                if (empty || set == null) {
                    setGraphic(null);
                    return;
                }
                nameLabel.setText(set.name());
                descriptionLabel.setText(set.category() == InstructionCategory.GENERAL
                        ? set.description()
                        : "[" + set.category().display() + "] " + set.description());
                setGraphic(row);
            }
        });
        setList.getSelectionModel().selectedItemProperty().addListener((obs, was, sel) -> {
            if (sel != null && sel != active) {
                openSet(sel);
            }
        });
        rail = new VBox(railHeader, setList);
        VBox.setVgrow(setList, Priority.ALWAYS);
        rail.setMinWidth(160);

        nameField = new TextField();
        nameField.setPromptText("Name (the set's title)");
        descriptionField = new TextField();
        descriptionField.setPromptText("Description — when to use this instruction set");
        // The standard-category dropdown (KEC 2026-08-17): a closed, enum-backed list. Category
        // CLASSIFIES intent and drives which selectors surface the set; attachment at the use
        // site stays the enforcing act, so the editor remains roleless.
        categoryBox = new javafx.scene.control.ComboBox<>();
        categoryBox.getItems().setAll(InstructionCategory.values());
        categoryBox.getSelectionModel().select(InstructionCategory.GENERAL);
        categoryBox.setTooltip(new Tooltip(
                "What this set is intended as — filters where selectors offer it"));
        baseFontSize = preferences().getDouble(PREF_EDITOR_FONT_SIZE, 13);
        // The renderer is built per render call so the settings pane's text size applies live.
        bodyPane = new MarkdownEditPane("", true,
                md -> new MarkdownRichText(safeViewProperties(), baseFontSize).renderMarkdown(md));
        VBox.setVgrow(bodyPane, Priority.ALWAYS);
        // Koncept drop-in (ike-issues#1042, knowledge-referencing instructions): a concept or
        // pattern dragged from anywhere in Komet drops into the raw editor as its k: token;
        // the rendered view shows it as a live badge.
        network.ike.komet.claude.ui.KonceptTokenDrop.install(bodyPane.rawEditor(),
                () -> safeViewProperties() == null ? null : safeViewProperties().calculator());

        statusLine = new Label("");
        Region buttonSpacer = new Region();
        HBox.setHgrow(buttonSpacer, Priority.ALWAYS);
        saveButton = new Button("Save");
        saveButton.setOnAction(e -> saveActive());
        saveAsButton = new Button("Save as…");
        saveAsButton.setTooltip(new Tooltip("Save a copy under a new title"));
        saveAsButton.setOnAction(e -> saveAsCopy());
        HBox buttons = new HBox(8, statusLine, buttonSpacer, saveButton, saveAsButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        nameField.textProperty().addListener((obs, was, is) -> updateChangeState());
        descriptionField.textProperty().addListener((obs, was, is) -> updateChangeState());
        categoryBox.valueProperty().addListener((obs, was, is) -> updateChangeState());
        bodyPane.rawEditor().textProperty().addListener((obs, was, is) -> updateChangeState());

        HBox metaRow = new HBox(8, descriptionField, categoryBox);
        HBox.setHgrow(descriptionField, Priority.ALWAYS);
        editorPane = new VBox(6, nameField, metaRow, bodyPane, buttons);
        editorPane.setPadding(new Insets(8));

        // First-run empty state: say what the card is for and offer the two first actions —
        // never a blank editor staring back (KEC 2026-08-17).
        Label emptyTitle = new Label("No instruction sets yet");
        emptyTitle.setStyle("-fx-font-size: 16; -fx-font-weight: bold;");
        Label emptyBody = new Label("""
                A titled instruction set is a named, described Markdown instruction document \
                in the portable SKILL.md form. Sets authored here can be selected as an \
                assistant card's system prompt, or included as skills. Create one, or import \
                an existing SKILL.md file.""");
        emptyBody.setWrapText(true);
        emptyBody.setMaxWidth(420);
        Button emptyNew = new Button("+ New instruction set");
        emptyNew.setOnAction(e -> newSet());
        Button emptyImport = new Button("Import…");
        emptyImport.setOnAction(e -> importSet());
        HBox emptyActions = new HBox(8, emptyNew, emptyImport);
        emptyActions.setAlignment(Pos.CENTER);
        VBox empty = new VBox(10, emptyTitle, emptyBody, emptyActions);
        empty.setAlignment(Pos.CENTER);
        empty.setPadding(new Insets(24));
        emptyState = empty;

        editorHost = new BorderPane(emptyState);

        split = new SplitPane(rail, editorHost);
        SplitPane.setResizableWithParent(rail, Boolean.FALSE);
        split.setDividerPositions(0.28);
        applyFont();

        content = new BorderPane(split);
        // The assistant card's footprint (ike-issues#1045): sibling cards open at sibling sizes.
        content.setPrefSize(900, 680);
        setCardContent(content);
    }

    /** The card's view, or {@code null} before bind — chips then degrade to presentation. */
    private dev.ikm.komet.framework.view.ViewProperties safeViewProperties() {
        try {
            return getCardViewProperties();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reloads the rail, keeping (or setting) the selection; empty swaps in the first-run state. */
    private void refreshSets(InstructionSet select) {
        InstructionSet target = select != null ? select
                : setList.getSelectionModel().getSelectedItem();
        setList.getItems().setAll(store.list());
        editorHost.setCenter(setList.getItems().isEmpty() ? emptyState : editorPane);
        if (setList.getItems().isEmpty()) {
            active = null;
            return;
        }
        if (target != null) {
            setList.getItems().stream()
                    .filter(set -> set.id().equals(target.id()))
                    .findFirst()
                    .ifPresent(set -> setList.getSelectionModel().select(set));
        } else {
            setList.getSelectionModel().select(0);
        }
    }

    /** Loads a set into the editor: index entries name the surface, the payload gives the body. */
    private void openSet(InstructionSet set) {
        active = set;
        nameField.setText(set.name());
        descriptionField.setText(set.description());
        categoryBox.getSelectionModel().select(set.category());
        Optional<Frontmatter> parsed = store.read(set);
        bodyPane.setText(parsed.map(Frontmatter::body).orElse(""));
        loadedName = set.name();
        loadedDescription = set.description();
        loadedCategory = set.category();
        loadedBody = bodyPane.getText();
        updateChangeState();
    }

    /**
     * Recomputes the unsaved-changes state: the status line carries the signal, Save enables
     * only for a changed, writable set (Save as… stays always available — copying an unchanged
     * set is a legitimate duplicate gesture), and a read-only system default routes every
     * change through Save as….
     */
    private void updateChangeState() {
        if (active == null) {
            saveButton.setDisable(true);
            statusLine.setText("");
            return;
        }
        boolean changed = !loadedName.equals(nameField.getText() == null ? "" : nameField.getText())
                || !loadedDescription.equals(
                        descriptionField.getText() == null ? "" : descriptionField.getText())
                || loadedCategory != categoryBox.getValue()
                || !loadedBody.equals(bodyPane.getText());
        saveButton.setDisable(active.readOnly() || !changed);
        if (active.readOnly()) {
            statusLine.setText(changed
                    ? "\u25cf System default — Save as\u2026 to keep your changes"
                    : "System default — use Save as\u2026 to make your copy");
        } else {
            statusLine.setText(changed ? "\u25cf Unsaved changes" : "");
        }
    }

    /** Creates a fresh set on the skill scaffold — seeding is invocation context, not a mode. */
    private void newSet() {
        String workingName = "Untitled instruction set";
        store.create(workingName, "", InstructionCategory.GENERAL,
                        InstructionSets.skillScaffold(workingName))
                .ifPresent(set -> refreshSets(set));
    }

    /** Imports a portable SKILL.md-form file and selects its registration. */
    private void importSet() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import instruction set");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Markdown instruction files", "*.md"));
        File chosen = chooser.showOpenDialog(fxObject().getScene().getWindow());
        if (chosen == null) {
            return;
        }
        Optional<InstructionSet> imported = store.importFile(chosen.toPath());
        if (imported.isPresent()) {
            refreshSets(imported.get());
        } else {
            statusLine.setText("Import failed — see the log");
        }
    }

    /**
     * Saves a copy under a managed title: proposed as "<name> (copy)", user-confirmed, and
     * made unique against this tile's registrations — the explicit path off a read-only
     * system default, and a general duplicate gesture.
     */
    private void saveAsCopy() {
        if (active == null) {
            return;
        }
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog(uniqueTitle(
                        (nameField.getText() == null || nameField.getText().isBlank()
                                ? "Untitled" : nameField.getText().strip()) + " (copy)"));
        dialog.setTitle("Save as");
        dialog.setHeaderText("Save a copy under a new title.");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().isBlank()) {
            return;
        }
        store.create(uniqueTitle(result.get().strip()),
                        descriptionField.getText() == null ? "" : descriptionField.getText().strip(),
                        categoryBox.getValue() == null ? InstructionCategory.GENERAL
                                : categoryBox.getValue(),
                        bodyPane.getText())
                .ifPresent(copy -> refreshSets(copy));
    }

    /** The title, suffixed (2), (3), … until it collides with no registered set's name. */
    private String uniqueTitle(String proposed) {
        java.util.Set<String> names = store.list().stream()
                .map(InstructionSet::name)
                .collect(java.util.stream.Collectors.toSet());
        if (!names.contains(proposed)) {
            return proposed;
        }
        int n = 2;
        while (names.contains(proposed + " (" + n + ")")) {
            n++;
        }
        return proposed + " (" + n + ")";
    }

    /** Persists the editor's state into the active registration. */
    private void saveActive() {
        if (active == null || active.readOnly()) {
            return;
        }
        Optional<InstructionSet> saved = store.save(active,
                nameField.getText() == null || nameField.getText().isBlank()
                        ? "Untitled" : nameField.getText().strip(),
                descriptionField.getText() == null ? "" : descriptionField.getText().strip(),
                categoryBox.getValue() == null ? InstructionCategory.GENERAL : categoryBox.getValue(),
                bodyPane.getText());
        if (saved.isPresent()) {
            active = saved.get();
            loadedName = active.name();
            loadedDescription = active.description();
            loadedCategory = active.category();
            loadedBody = bodyPane.getText();
            refreshSets(active);
            updateChangeState();
            statusLine.setText("Saved");
        } else {
            statusLine.setText("Save failed — see the log");
        }
    }

    /** Mints the tile's display name once: kind plus sequence, past every sibling editor tile. */
    private void ensureTileLabel() {
        String current = preferences().get(TILE_LABEL_KEY, "");
        if (!current.isBlank()) {
            return;
        }
        int next = 1;
        java.util.regex.Pattern named = java.util.regex.Pattern.compile("Instruction Editor (\\d+)");
        try {
            KometPreferences parent = preferences().parent();
            if (parent != null) {
                for (KometPreferences child : parent.children()) {
                    if (CARD_TYPE_VALUE.equals(child.get(CARD_TYPE_KEY, ""))) {
                        java.util.regex.Matcher m = named.matcher(child.get(TILE_LABEL_KEY, ""));
                        if (m.matches()) {
                            next = Math.max(next, Integer.parseInt(m.group(1)) + 1);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not enumerate instruction-editor tiles for naming", e);
        }
        preferences().put(TILE_LABEL_KEY, "Instruction Editor " + next);
    }

    /**
     * ServiceLoader provider contributing {@link InstructionEditorCard} to the Journal workspace.
     */
    public static final class Factory implements KlCardProvider {

        /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
        public Factory() {
        }

        @Override
        public String cardName() {
            return CARD_NAME;
        }

        @Override
        public AbstractHostCard createCard(KometPreferences windowPreferences, UUID journalTopic) {
            KlPreferencesFactory cardPreferencesFactory =
                    KlPreferencesFactory.create(windowPreferences, InstructionEditorCard.class);
            InstructionEditorCard card = new CardFactory().create(cardPreferencesFactory);
            card.setJournalTopic(journalTopic);
            return card;
        }

        @Override
        public AbstractHostCard restoreCard(KometPreferences windowPreferences) {
            KometPreferences cardNode =
                    windowPreferences.node(InstructionEditorCard.class.getSimpleName());
            InstructionEditorCard card = new CardFactory().restore(cardNode);
            card.revert();
            return card;
        }
    }

    /** Blueprint factory building the card shell (the {@code KlArea.Factory} the base needs). */
    private static final class CardFactory implements CardBlueprint.Factory<InstructionEditorCard> {

        @Override
        public InstructionEditorCard restore(KometPreferences preferences) {
            return new InstructionEditorCard(preferences);
        }

        @Override
        public InstructionEditorCard create(KlPreferencesFactory preferencesFactory,
                                            AreaGridSettings areaGridSettings) {
            InstructionEditorCard card = new InstructionEditorCard(preferencesFactory, this);
            card.setAreaLayout(areaGridSettings.with(this.getClass()));
            return card;
        }
    }
}
