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

    private static final Logger LOG = LoggerFactory.getLogger(InstructionEditorCard.class);

    private InstructionSets store;
    private ListView<InstructionSet> setList;
    private TextField nameField;
    private TextField descriptionField;
    private MarkdownEditPane bodyPane;
    private Label statusLine;
    private InstructionSet active;
    private BorderPane content;

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

    @Override
    protected void renderContent() {
        if (content == null) {
            buildBody();
            // Tile identity: the marker makes this tile discoverable by use-site selectors
            // (the assistant's system-prompt section); the display name follows the
            // kind-plus-sequence convention, minted once.
            preferences().put(CARD_TYPE_KEY, CARD_TYPE_VALUE);
            ensureTileLabel();
            refreshSets(null);
        }
    }

    private void buildBody() {
        store = new InstructionSets(preferences());

        Label railTitle = new Label("Instruction sets");
        railTitle.setStyle("-fx-font-weight: bold;");
        Region railSpacer = new Region();
        HBox.setHgrow(railSpacer, Priority.ALWAYS);
        Button newButton = new Button("+ New");
        newButton.setTooltip(new Tooltip("Create a new titled instruction set"));
        newButton.setOnAction(e -> newSet());
        Button importButton = new Button("Import…");
        importButton.setTooltip(new Tooltip("Import a SKILL.md-form instruction file"));
        importButton.setOnAction(e -> importSet());
        HBox railHeader = new HBox(6, railTitle, railSpacer, newButton, importButton);
        railHeader.setAlignment(Pos.CENTER_LEFT);
        railHeader.setPadding(new Insets(6));

        setList = new ListView<>();
        setList.setPrefWidth(210);
        setList.setCellFactory(lv -> new ListCell<>() {
            private final Label nameLabel = new Label();
            private final Label descriptionLabel = new Label();
            private final VBox row = new VBox(1, nameLabel, descriptionLabel);
            {
                nameLabel.setStyle("-fx-font-weight: bold;");
                nameLabel.setWrapText(true);
                descriptionLabel.setWrapText(true);
                descriptionLabel.setStyle("-fx-text-fill: #6a6a6a; -fx-font-size: 11;");
                setPrefWidth(0);
                maxWidthProperty().bind(lv.widthProperty().subtract(16));
            }
            @Override
            protected void updateItem(InstructionSet set, boolean empty) {
                super.updateItem(set, empty);
                if (empty || set == null) {
                    setGraphic(null);
                    return;
                }
                nameLabel.setText(set.name());
                descriptionLabel.setText(set.description());
                setGraphic(row);
            }
        });
        setList.getSelectionModel().selectedItemProperty().addListener((obs, was, sel) -> {
            if (sel != null && sel != active) {
                openSet(sel);
            }
        });
        VBox rail = new VBox(railHeader, setList);
        VBox.setVgrow(setList, Priority.ALWAYS);
        rail.setMinWidth(160);

        nameField = new TextField();
        nameField.setPromptText("Name (the set's title)");
        descriptionField = new TextField();
        descriptionField.setPromptText("Description — when to use this instruction set");
        MarkdownRichText renderer = new MarkdownRichText(safeViewProperties(),
                MarkdownRichText.DEFAULT_BASE);
        bodyPane = new MarkdownEditPane("", true, renderer::renderMarkdown);
        VBox.setVgrow(bodyPane, Priority.ALWAYS);

        statusLine = new Label("");
        Region buttonSpacer = new Region();
        HBox.setHgrow(buttonSpacer, Priority.ALWAYS);
        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> saveActive());
        HBox buttons = new HBox(8, statusLine, buttonSpacer, saveButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox editor = new VBox(6, nameField, descriptionField, bodyPane, buttons);
        editor.setPadding(new Insets(8));

        SplitPane split = new SplitPane(rail, editor);
        SplitPane.setResizableWithParent(rail, Boolean.FALSE);
        split.setDividerPositions(0.28);

        content = new BorderPane(split);
        content.setPrefSize(860, 620);
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

    /** Reloads the rail, keeping (or setting) the selection. */
    private void refreshSets(InstructionSet select) {
        InstructionSet target = select != null ? select
                : setList.getSelectionModel().getSelectedItem();
        setList.getItems().setAll(store.list());
        if (target != null) {
            setList.getItems().stream()
                    .filter(set -> set.id().equals(target.id()))
                    .findFirst()
                    .ifPresent(set -> setList.getSelectionModel().select(set));
        } else if (!setList.getItems().isEmpty()) {
            setList.getSelectionModel().select(0);
        }
    }

    /** Loads a set into the editor: index entries name the surface, the payload gives the body. */
    private void openSet(InstructionSet set) {
        active = set;
        nameField.setText(set.name());
        descriptionField.setText(set.description());
        Optional<Frontmatter> parsed = store.read(set);
        bodyPane.setText(parsed.map(Frontmatter::body).orElse(""));
        statusLine.setText("");
    }

    /** Creates a fresh set on the skill scaffold — seeding is invocation context, not a mode. */
    private void newSet() {
        String workingName = "Untitled instruction set";
        store.create(workingName, "", InstructionSets.skillScaffold(workingName))
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

    /** Persists the editor's state into the active registration. */
    private void saveActive() {
        if (active == null) {
            return;
        }
        Optional<InstructionSet> saved = store.save(active,
                nameField.getText() == null || nameField.getText().isBlank()
                        ? "Untitled" : nameField.getText().strip(),
                descriptionField.getText() == null ? "" : descriptionField.getText().strip(),
                bodyPane.getText());
        if (saved.isPresent()) {
            active = saved.get();
            statusLine.setText("Saved");
            refreshSets(active);
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
