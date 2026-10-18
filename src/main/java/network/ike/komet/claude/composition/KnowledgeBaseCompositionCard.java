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
package network.ike.komet.claude.composition;

import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.CardBlueprint;
import dev.ikm.komet.layout_engine.host.AbstractHostCard;
import dev.ikm.komet.layout_engine.host.KlCardProvider;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.terms.KernelPattern;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Assembly;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Changes;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Composition;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Export;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Input;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.ReleaseVersion;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Release;
import network.ike.komet.claude.stamp.StampNames;
import java.text.NumberFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * What the open knowledge base is made of (IKE-Network/ike-issues#1304):
 * <ul>
 * <li>the releases it holds, in the order they arrived, each with what its import wrote and the
 * importers that have read it;</li>
 * <li>the latest assembly's inputs and their checksums;</li>
 * <li>every assembly build, newest first, with what changed from the one before;</li>
 * <li>the exports taken from it.</li>
 * </ul>
 * Everything shown is read from the registrations the IKE Registrar wrote into the store; see
 * {@link KnowledgeBaseComposition}.
 *
 * <p>The card is about the store, not a concept, so it ignores the journal topic.
 */
public final class KnowledgeBaseCompositionCard extends AbstractHostCard {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseCompositionCard.class);

    static final String CARD_NAME = "Knowledge Base Composition";

    /** Release dates are a source's effective dates, midnights in UTC: shown in the system zone they fall a day early. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz").withZone(ZoneId.systemDefault());
    private static final Font HEADING = Font.font(null, FontWeight.BOLD, 14);
    private static final Font PROSE = Font.font(13);
    private static final Font MONO = Font.font("Menlo", 12);
    private static final Font SMALL = Font.font(11);
    /** Hex digits of a checksum shown before the ellipsis; the tooltip holds the whole of it. */
    private static final int CHECKSUM_SHOWN = 12;

    /** Live cards, most recently realized last, so tools and automation can find the one in use. */
    private static final List<WeakReference<KnowledgeBaseCompositionCard>> OPEN = new CopyOnWriteArrayList<>();

    /** @return the most recently realized card still alive, or {@code null} */
    public static KnowledgeBaseCompositionCard mostRecent() {
        KnowledgeBaseCompositionCard found = null;
        for (WeakReference<KnowledgeBaseCompositionCard> ref : OPEN) {
            KnowledgeBaseCompositionCard card = ref.get();
            if (card == null) {
                OPEN.remove(ref);
            } else {
                found = card;
            }
        }
        return found;
    }

    private final BorderPane host = new BorderPane();
    private final VBox stack = new VBox(8);
    private final Label statusLine = new Label("Reading the knowledge base's composition…");
    private Composition shown;
    private boolean built;

    private KnowledgeBaseCompositionCard(KometPreferences preferences) {
        super(preferences);
    }

    private KnowledgeBaseCompositionCard(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
    }

    @Override
    protected String cardTitle() {
        return CARD_NAME;
    }

    /** @return the composition shown, once one is read */
    public Optional<Composition> shown() {
        return Optional.ofNullable(shown);
    }

    @Override
    protected void buildToolbarControls(HBox toolBar) {
        Button refresh = new Button("Refresh");
        refresh.setId("kbCompositionRefresh");
        refresh.setTooltip(new Tooltip("Re-read the knowledge base's composition records"));
        refresh.getStyleClass().add("claude-card-toolbar-button");
        refresh.setOnAction(e -> refresh());
        Button copy = new Button("Copy summary");
        copy.setId("kbCompositionCopy");
        copy.setTooltip(new Tooltip("Copy the composition as plain text, to paste into an issue or a note"));
        copy.getStyleClass().add("claude-card-toolbar-button");
        copy.setOnAction(e -> {
            if (shown != null) {
                ClipboardContent content = new ClipboardContent();
                content.putString(KnowledgeBaseComposition.text(view(), shown));
                Clipboard.getSystemClipboard().setContent(content);
                statusLine.setText("Copied the composition summary.");
            }
        });
        toolBar.getChildren().addAll(refresh, copy);
    }

    @Override
    protected void renderContent() {
        if (built) {
            return;
        }
        built = true;
        OPEN.add(new WeakReference<>(this));
        statusLine.setPadding(new Insets(4, 8, 4, 8));
        statusLine.setWrapText(true);
        stack.setPadding(new Insets(10, 14, 10, 10));
        stack.setFillWidth(true);
        ScrollPane scroll = new ScrollPane(stack);
        scroll.setFitToWidth(true);
        host.setCenter(scroll);
        host.setBottom(statusLine);
        setCardContent(host);
        refresh();
    }

    /** Re-reads the store's composition records off the FX thread and redraws the card. */
    public void refresh() {
        CompletableFuture.supplyAsync(KnowledgeBaseComposition::read).whenComplete((found, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                statusLine.setText("Could not read the knowledge base's composition: " + failure.getMessage());
                LOG.warn("Reading the knowledge base composition failed", failure);
                return;
            }
            show(found);
        }));
    }

    private void show(Composition composition) {
        shown = composition;
        stack.getChildren().clear();
        ViewCalculator view = view();
        switch (composition.recorded()) {
            case REGISTRY_ABSENT -> {
                stack.getChildren().add(prose("This knowledge base's starter set has no registry patterns, so it cannot"
                        + " record what it is made of. Knowledge bases built on a starter set with the release, assembly"
                        + " and export registrations (IKE-Network/ike-issues#1304) can."));
                statusLine.setText("No registry patterns in this knowledge base.");
                return;
            }
            case NOT_RECORDED -> {
                stack.getChildren().add(prose("This knowledge base holds no registrations. It declares the registry, but"
                        + " nothing that built or loaded it registered what it read."));
                statusLine.setText("No registrations.");
                return;
            }
            case RECORDED -> { }
        }
        composition.latest().ifPresent(latest -> stack.getChildren().add(summary(latest, composition)));

        stack.getChildren().add(heading("Releases"));
        if (composition.releases().isEmpty()) {
            stack.getChildren().add(prose("No release registrations."));
        }
        List<String> releaseLabels = KnowledgeBaseComposition.fieldLabels(view, KernelPattern.RELEASE_REGISTRATION_PATTERN);
        for (Release release : composition.releases()) {
            TitledPane pane = release(view, release, releaseLabels);
            // A store of one release shows it open: there is nothing else to choose between.
            pane.setExpanded(composition.releases().size() == 1);
            stack.getChildren().add(pane);
        }

        composition.latest().ifPresent(latest -> {
            stack.getChildren().add(heading("Assembly inputs"));
            stack.getChildren().add(inputs(latest.inputs()));
            stack.getChildren().add(heading("Assembly history"));
            List<Assembly> assemblies = composition.assemblies();
            for (int i = 0; i < assemblies.size(); i++) {
                stack.getChildren().add(assembly(view, assemblies.get(i),
                        i + 1 < assemblies.size() ? assemblies.get(i + 1) : null));
            }
        });
        if (!composition.exports().isEmpty()) {
            stack.getChildren().add(heading("Exports"));
            for (Export export : composition.exports()) {
                stack.getChildren().add(export(view, export));
            }
        }
        statusLine.setText(composition.releases().size() + " release(s), " + composition.assemblies().size()
                + " assembly build(s), " + composition.exports().size() + " export(s).");
    }

    private Node summary(Assembly latest, Composition composition) {
        Label label = new Label("Assembled " + MOMENT.format(latest.assembled())
                + (latest.classifier().isBlank() ? ", not classified" : ", classified by " + latest.classifier())
                + " — " + latest.inputs().size() + " input(s), " + composition.releases().size() + " release(s)");
        label.setFont(HEADING);
        label.setWrapText(true);
        label.setTooltip(new Tooltip(stampText(view(), latest.stamp())));
        label.setId("kbCompositionSummary");
        return label;
    }

    private TitledPane release(ViewCalculator view, Release release, List<String> labels) {
        ReleaseVersion now = release.latest();
        NumberFormat counts = NumberFormat.getIntegerInstance();
        GridPane grid = grid();
        int row = 0;
        row = row(grid, row, label(labels, 2, "Release artifact"), mono(now.artifact()));
        if (!now.resolvedBeyondArtifact().isEmpty()) {
            row = row(grid, row, label(labels, 3, "Resolved version"), mono(now.resolvedBeyondArtifact()));
        }
        row = row(grid, row, label(labels, 4, "Origin checksum"), checksum(now.checksum()));
        row = row(grid, row, label(labels, 5, "Importer"), prose(now.importer()));
        if (!now.options().isEmpty()) {
            row = row(grid, row, label(labels, 6, "Importer options"), mono(now.options()));
        }
        row = row(grid, row, "Modules written", prose(names(view, KnowledgeBaseComposition.modules(now.stampsWritten()))
                + "  (" + now.stampsWritten().size() + " stamp" + (now.stampsWritten().size() == 1 ? "" : "s") + ")"));
        row = row(grid, row, "Components", prose(counts.format(now.written()) + " written, "
                + counts.format(now.versioned()) + " versioned, " + counts.format(now.unchanged()) + " unchanged, "
                + counts.format(now.retired()) + " retired"));
        row = row(grid, row, label(labels, 12, "Base releases"),
                prose(now.baseReleases().isEmpty() ? "none: the first release loaded" : names(view, now.baseReleases())));
        VBox readers = new VBox(2);
        for (ReleaseVersion version : release.versions().reversed()) {
            // The first reading is stamped at the publisher's release date, a UTC midnight; a later
            // importer's reading, when it ran.
            Instant read = Instant.ofEpochMilli(version.stamp().time());
            String when = read.equals(version.releaseDate()) ? DAY.format(read) + " (release date)" : MOMENT.format(read);
            Label line = new Label(when + "  " + version.importer());
            line.setFont(SMALL);
            line.setTooltip(new Tooltip(stampText(view, version.stamp())));
            readers.getChildren().add(line);
        }
        row(grid, row, "Read by", readers);

        TitledPane pane = new TitledPane(now.importerName() + " — " + now.releaseName()
                + " (" + DAY.format(now.releaseDate()) + ")"
                + (release.versions().size() > 1 ? " · read by " + release.versions().size() + " importer versions" : ""),
                grid);
        pane.setExpanded(false);
        pane.setAnimated(false);
        pane.setTooltip(new Tooltip(stampText(view, now.stamp())));
        return pane;
    }

    private Node export(ViewCalculator view, Export export) {
        VBox box = new VBox(2);
        Label line = new Label(MOMENT.format(Instant.ofEpochMilli(export.stamp().time())) + "  ·  " + export.file()
                + "  ·  " + NumberFormat.getIntegerInstance().format(export.components()) + " components");
        line.setFont(PROSE);
        line.setTooltip(new Tooltip(stampText(view, export.stamp())));
        box.getChildren().add(line);
        if (!export.selection().isBlank()) {
            box.getChildren().add(small(export.selection()));
        }
        Label checksum = checksum(export.checksum());
        checksum.setFont(SMALL);
        box.getChildren().add(checksum);
        box.setPadding(new Insets(0, 0, 4, 8));
        return box;
    }

    private Node inputs(List<Input> inputs) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(3);
        ColumnConstraints roles = new ColumnConstraints();
        roles.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints artifacts = new ColumnConstraints();
        artifacts.setHgrow(Priority.ALWAYS);
        ColumnConstraints checksums = new ColumnConstraints();
        checksums.setMinWidth(Region.USE_PREF_SIZE);
        grid.getColumnConstraints().addAll(roles, artifacts, checksums);
        int row = 0;
        for (Input input : inputs) {
            Label role = new Label(input.role());
            role.setFont(SMALL);
            grid.add(role, 0, row);
            Label artifact = mono(input.artifact());
            artifact.setTooltip(new Tooltip(input.resolvedBeyondArtifact().isEmpty() ? input.artifact()
                    : input.artifact() + "\nresolved to " + input.resolvedBeyondArtifact()));
            grid.add(artifact, 1, row);
            grid.add(checksum(input.checksum()), 2, row);
            row++;
        }
        return grid;
    }

    private Node assembly(ViewCalculator view, Assembly assembly, Assembly previous) {
        VBox box = new VBox(2);
        Label line = new Label(MOMENT.format(assembly.assembled()) + "  ·  " + assembly.inputs().size() + " input(s)"
                + (assembly.classifier().isBlank() ? "" : "  ·  " + assembly.classifier()));
        line.setFont(PROSE);
        line.setTooltip(new Tooltip(stampText(view, assembly.stamp())));
        box.getChildren().add(line);
        if (previous == null) {
            box.getChildren().add(small("the first assembly registered"));
        } else {
            Changes changes = KnowledgeBaseComposition.changes(previous, assembly);
            if (changes.none()) {
                box.getChildren().add(small("a rebuild from the same inputs"));
            }
            changes.added().forEach(i -> box.getChildren().add(small("+ " + i.role() + " " + i.artifact())));
            changes.removed().forEach(i -> box.getChildren().add(small("− " + i.role() + " " + i.artifact())));
            changes.changed().forEach(i -> box.getChildren().add(small("≠ " + i.artifact() + ": new checksum " + shortChecksum(i.checksum()))));
            if (!changes.releasesAdded().isEmpty()) {
                box.getChildren().add(small("new releases: " + names(view, changes.releasesAdded())));
            }
        }
        box.setPadding(new Insets(0, 0, 4, 8));
        return box;
    }

    /** The components' names; a release registration is named by its importer and release. */
    private static String names(ViewCalculator view, List<Long> nids) {
        return nids.stream().map(nid -> KnowledgeBaseComposition.name(view, nid)).collect(Collectors.joining(", "));
    }

    private static String stampText(ViewCalculator view, StampEntity<?> stamp) {
        return "Status: " + stamp.state().name().toLowerCase()
                + "\nTime: " + MOMENT.format(Instant.ofEpochMilli(stamp.time()))
                + "\nAuthor: " + StampNames.name(view, stamp.authorNid())
                + "\nModule: " + StampNames.name(view, stamp.moduleNid())
                + "\nPath: " + StampNames.name(view, stamp.pathNid());
    }

    private static String label(List<String> labels, int index, String fallback) {
        return index < labels.size() ? labels.get(index) : fallback;
    }

    private static String shortChecksum(String checksum) {
        String prefix = "sha256:";
        if (checksum.startsWith(prefix) && checksum.length() > prefix.length() + CHECKSUM_SHOWN) {
            return checksum.substring(0, prefix.length() + CHECKSUM_SHOWN) + "…";
        }
        return checksum;
    }

    private static Label checksum(String checksum) {
        Label label = mono(shortChecksum(checksum));
        if (!checksum.isEmpty()) {
            label.setTooltip(new Tooltip(checksum));
        }
        return label;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(3);
        ColumnConstraints names = new ColumnConstraints();
        names.setMinWidth(110);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(names, values);
        return grid;
    }

    private static int row(GridPane grid, int row, String name, Node value) {
        Label label = new Label(name);
        label.setFont(SMALL);
        grid.add(label, 0, row);
        grid.add(value, 1, row);
        return row + 1;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setFont(HEADING);
        label.setPadding(new Insets(8, 0, 0, 0));
        return label;
    }

    private static Label prose(String text) {
        Label label = new Label(text);
        label.setFont(PROSE);
        label.setWrapText(true);
        return label;
    }

    private static Label mono(String text) {
        Label label = new Label(text);
        label.setFont(MONO);
        return label;
    }

    private static Label small(String text) {
        Label label = new Label(text);
        label.setFont(SMALL);
        label.setWrapText(true);
        return label;
    }

    /** The card's view, or the default one before the card is hosted. */
    private ViewCalculator view() {
        return getCardViewProperties() != null ? getCardViewProperties().calculator() : Calculators.View.Default();
    }

    // ── factories ─────────────────────────────────────────────────────────────

    /** Journal "+" menu entry; {@link java.util.ServiceLoader} instantiates it via the no-arg constructor. */
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
            KlPreferencesFactory cardPreferencesFactory = KlPreferencesFactory.create(windowPreferences, KnowledgeBaseCompositionCard.class);
            KnowledgeBaseCompositionCard card = new CardFactory().create(cardPreferencesFactory);
            card.setJournalTopic(journalTopic);
            return card;
        }

        @Override
        public AbstractHostCard restoreCard(KometPreferences windowPreferences) {
            KometPreferences cardNode = windowPreferences.node(KnowledgeBaseCompositionCard.class.getSimpleName());
            KnowledgeBaseCompositionCard card = new CardFactory().restore(cardNode);
            card.revert();
            return card;
        }
    }

    /** Blueprint factory building the card shell (the {@code KlArea.Factory} the base needs). */
    private static final class CardFactory implements CardBlueprint.Factory<KnowledgeBaseCompositionCard> {
        @Override
        public KnowledgeBaseCompositionCard restore(KometPreferences preferences) {
            return new KnowledgeBaseCompositionCard(preferences);
        }

        @Override
        public KnowledgeBaseCompositionCard create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
            KnowledgeBaseCompositionCard card = new KnowledgeBaseCompositionCard(preferencesFactory, this);
            card.setAreaLayout(areaGridSettings.with(this.getClass()));
            return card;
        }
    }
}
