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

import design.knowledge.stamp.adoc.git.AffirmationCommit;
import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.Affirmation;
import design.knowledge.stamp.adoc.model.StampId;
import design.knowledge.stamp.adoc.model.StampRecord;
import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.CardBlueprint;
import dev.ikm.komet.layout_engine.host.AbstractHostCard;
import dev.ikm.komet.layout_engine.host.KlCardProvider;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.tinkar.common.id.PublicIds;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import network.ike.docs.konceptcore.KonceptKind;
import network.ike.komet.claude.koncept.KonceptBadge;
import network.ike.komet.claude.koncept.KonceptIdenticon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The STAMP Provenance card ({@code IKE-Network/ike-issues#468}): a committed AsciiDoc file
 * as a stack of lines — under semantic line breaks, one claim each — every line led by the
 * STAMP badge of the commit that introduced it, with a check mark where someone has affirmed
 * it. The badge is the same gray pentagon, identicon and compact provenance a STAMP shows
 * anywhere in Komet; its identicon is seeded by the STAMP identity the adapter derives, so it
 * is the identicon that STAMP's concept carries once written to a store.
 *
 * <p>The card reads the file as committed at HEAD, never the working tree, so an affirmation
 * made here is anchored exactly; a banner says when the working tree has uncommitted edits.
 * <em>Affirm Selected</em> folds the ticked lines into ranges, hashes them over the committed
 * text, and writes the empty commit — signed by default — through the user's own
 * {@code git}, so whatever signing they have configured applies. The review queue toggle
 * tints the lines no one has affirmed yet.
 */
public final class StampProvenanceCard extends AbstractHostCard {

    private static final Logger LOG = LoggerFactory.getLogger(StampProvenanceCard.class);

    static final String CARD_NAME = "STAMP Provenance";

    private static final String PREF_FILE = "network.ike.komet.claude.stamp.file";
    private static final String PREF_QUEUE = "network.ike.komet.claude.stamp.reviewQueue";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz").withZone(ZoneId.systemDefault());

    /** Pastel row tints cycled in STAMP time order, and the review-queue tint. */
    private static final Color[] PALETTE = {
            Color.web("#E3F2FD"), Color.web("#E8F5E9"), Color.web("#FFF8E1"), Color.web("#F3E5F5"),
            Color.web("#FFEBEE"), Color.web("#E0F7FA"), Color.web("#FBE9E7"), Color.web("#F1F8E9"),
    };
    private static final Color QUEUE = Color.web("#FFE082");

    private final BorderPane host = new BorderPane();
    private final VBox rows = new VBox(1);
    private final Label banner = new Label();
    private final Label statusLine = new Label("Open a committed .adoc file from a git repository.");
    private final CheckBox queueToggle = new CheckBox("Review queue");
    private final List<LineRow> lineRows = new ArrayList<>();
    private final Map<StampId, Image> badges = new HashMap<>();

    private Path repoRoot;
    private Path relative;
    private GitStampBridge bridge;
    private String headRevision = "";
    private List<String> headLines = List.of();
    private boolean built;

    private StampProvenanceCard(KometPreferences preferences) {
        super(preferences);
    }

    private StampProvenanceCard(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
    }

    @Override
    protected String cardTitle() {
        return CARD_NAME;
    }

    @Override
    protected void buildToolbarControls(HBox toolBar) {
        Button open = new Button("Open…");
        open.setTooltip(new Tooltip("Choose a committed .adoc file in a git repository"));
        open.getStyleClass().add("claude-card-toolbar-button");
        open.setOnAction(e -> chooseFile());
        Button refresh = new Button("Refresh");
        refresh.setTooltip(new Tooltip("Re-read the file's STAMPs from the repository"));
        refresh.getStyleClass().add("claude-card-toolbar-button");
        refresh.setOnAction(e -> reload());
        Button affirm = new Button("Affirm Selected…");
        affirm.setTooltip(new Tooltip("Record your review of the ticked lines as an empty signed commit with Affirms: trailers"));
        affirm.getStyleClass().add("claude-card-toolbar-button");
        affirm.setOnAction(e -> affirmSelected());
        queueToggle.setTooltip(new Tooltip("Tint the lines no one has affirmed yet"));
        queueToggle.setSelected(preferences().getBoolean(PREF_QUEUE, false));
        queueToggle.selectedProperty().addListener((obs, was, is) -> {
            preferences().putBoolean(PREF_QUEUE, is);
            retint();
        });
        toolBar.getChildren().addAll(open, refresh, affirm, queueToggle);
    }

    @Override
    protected void renderContent() {
        if (built) {
            return;
        }
        built = true;
        banner.setWrapText(true);
        banner.setPadding(new Insets(4, 8, 4, 8));
        banner.setVisible(false);
        banner.setManaged(false);
        statusLine.setPadding(new Insets(4, 8, 4, 8));
        rows.setPadding(new Insets(6));
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        host.setTop(banner);
        host.setCenter(scroll);
        host.setBottom(statusLine);
        setCardContent(host);
        String last = preferences().get(PREF_FILE, "");
        if (!last.isBlank() && Files.isRegularFile(Path.of(last))) {
            open(Path.of(last));
        }
    }

    // ── loading ───────────────────────────────────────────────────────────────

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open a committed AsciiDoc file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("AsciiDoc", "*.adoc", "*.asciidoc"));
        String last = preferences().get(PREF_FILE, "");
        if (!last.isBlank()) {
            File dir = Path.of(last).getParent().toFile();
            if (dir.isDirectory()) {
                chooser.setInitialDirectory(dir);
            }
        }
        File chosen = chooser.showOpenDialog(host.getScene() == null ? null : host.getScene().getWindow());
        if (chosen != null) {
            open(chosen.toPath());
        }
    }

    private void reload() {
        if (repoRoot != null && relative != null) {
            open(repoRoot.resolve(relative));
        }
    }

    private void open(Path file) {
        Optional<Path> root = GitRepo.findRoot(file);
        if (root.isEmpty()) {
            statusLine.setText(file + " is not inside a git working tree.");
            return;
        }
        Path rel = root.get().relativize(file.toAbsolutePath().normalize());
        statusLine.setText("Reading STAMPs of " + rel + " …");
        CompletableFuture.supplyAsync(() -> {
            try (GitRepo repo = GitRepo.open(root.get())) {
                Loaded loaded = new Loaded(GitStampBridge.read(root.get(), rel), repo.headRevision(), repo.headLines(rel),
                        workingTreeDiffers(file, repo.headLines(rel)));
                return loaded;
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }).whenComplete((loaded, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                statusLine.setText("Cannot read STAMPs of " + rel + ": " + cause.getMessage());
                LOG.warn("STAMP read failed for {}", file, cause);
                return;
            }
            repoRoot = root.get();
            relative = rel;
            bridge = loaded.bridge();
            headRevision = loaded.headRevision();
            headLines = loaded.headLines();
            preferences().put(PREF_FILE, file.toAbsolutePath().normalize().toString());
            show(loaded.workingTreeDiffers());
        }));
    }

    private record Loaded(GitStampBridge bridge, String headRevision, List<String> headLines, boolean workingTreeDiffers) { }

    private static boolean workingTreeDiffers(Path file, List<String> headLines) {
        try {
            return !Files.readAllLines(file).equals(headLines);
        } catch (IOException e) {
            return true;
        }
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    private void show(boolean workingTreeDiffers) {
        rows.getChildren().clear();
        lineRows.clear();
        badges.clear();
        Map<StampId, Integer> order = new HashMap<>();
        List<StampRecord> byTime = new ArrayList<>(bridge.stamps().stamps().values());
        byTime.sort(Comparator.comparing(StampRecord::time));
        for (int i = 0; i < byTime.size(); i++) {
            order.put(byTime.get(i).id(), i);
        }
        StampRecord[] introducer = new StampRecord[headLines.size() + 1];
        Map<Integer, List<StampRecord>> affirmers = new HashMap<>();
        for (StampRecord r : byTime) {
            r.introduced().forEach((int line) -> { if (line < introducer.length) introducer[line] = r; });
            r.affirmed().forEach((int line) -> affirmers.computeIfAbsent(line, k -> new ArrayList<>()).add(r));
        }
        for (int line = 1; line <= headLines.size(); line++) {
            LineRow row = new LineRow(line, headLines.get(line - 1), introducer[line], affirmers.get(line),
                    introducer[line] == null ? null : PALETTE[order.get(introducer[line].id()) % PALETTE.length]);
            lineRows.add(row);
            rows.getChildren().add(row.node);
        }
        retint();
        int affirmed = (int) lineRows.stream().filter(r -> r.affirmers != null).count();
        int problems = bridge.stamps().problems().size();
        statusLine.setText(relative + " @ " + headRevision.substring(0, Math.min(7, headRevision.length())) + " on " + branchName()
                + " — " + headLines.size() + " lines, " + byTime.size() + " STAMPs, " + affirmed + " affirmed"
                + (problems == 0 ? "" : ", " + problems + " affirmation(s) not applied"));
        banner.setText(workingTreeDiffers
                ? "The working tree has uncommitted edits to this file. Showing the file as committed at HEAD; commit before affirming the edits."
                : problems > 0 ? String.join("\n", bridge.stamps().problems()) : "");
        banner.setVisible(!banner.getText().isEmpty());
        banner.setManaged(banner.isVisible());
    }

    private String branchName() {
        try (GitRepo repo = GitRepo.open(repoRoot)) {
            return repo.branch();
        } catch (IOException e) {
            return "?";
        }
    }

    private void retint() {
        boolean queue = queueToggle.isSelected();
        for (LineRow row : lineRows) {
            Color tint = queue && row.introducer != null && row.affirmers == null ? QUEUE : row.tint;
            row.node.setBackground(tint == null ? Background.EMPTY : new Background(new BackgroundFill(tint, new CornerRadii(3), Insets.EMPTY)));
        }
    }

    /** One line of the file: tick box, STAMP badge, affirmation mark, text. */
    private final class LineRow {
        final int line;
        final StampRecord introducer;
        final List<StampRecord> affirmers;
        final Color tint;
        final CheckBox tick = new CheckBox();
        final HBox node = new HBox(8);

        LineRow(int line, String text, StampRecord introducer, List<StampRecord> affirmers, Color tint) {
            this.line = line;
            this.introducer = introducer;
            this.affirmers = affirmers;
            this.tint = tint;
            node.setAlignment(Pos.CENTER_LEFT);
            node.setPadding(new Insets(1, 6, 1, 4));
            Label number = new Label(Integer.toString(line));
            number.setMinWidth(34);
            number.setAlignment(Pos.CENTER_RIGHT);
            number.setStyle("-fx-text-fill: #8a8782;");
            Node badge = introducer == null ? new Label() : badge(introducer);
            Label mark = new Label(affirmers == null ? " " : "✓");
            mark.setMinWidth(16);
            mark.setStyle("-fx-text-fill: #2e7d32; -fx-font-weight: bold;");
            if (affirmers != null) {
                mark.setTooltip(new Tooltip(affirmationText(affirmers)));
            }
            Label body = new Label(text.isEmpty() ? " " : text);
            body.setWrapText(true);
            body.setFont(Font.font("Menlo", 12));
            HBox.setHgrow(body, Priority.ALWAYS);
            node.getChildren().addAll(tick, number, badge, mark, body);
        }

        private Node badge(StampRecord r) {
            GitStampTerms.GitStamp terms = bridge.terms().get(r.id());
            Image image = badges.computeIfAbsent(r.id(), id -> renderBadge(r, terms));
            ImageView view = new ImageView(image);
            view.setPreserveRatio(true);
            view.setFitHeight(image.getHeight() / KonceptBadge.SCALE);
            Tooltip.install(view, new Tooltip(stampText(r, terms) + (affirmers == null ? "\nNot yet affirmed." : "\n" + affirmationText(affirmers))));
            return view;
        }
    }

    private static Image renderBadge(StampRecord r, GitStampTerms.GitStamp terms) {
        UUID seed = terms.stamp().publicId().asUuidArray()[0];
        String label = r.status().name().toLowerCase() + " · " + DAY.format(r.time()) + " · " + terms.author().description();
        byte[] png = KonceptBadge.png(KonceptIdenticon.png(PublicIds.of(seed).idString(), KonceptIdenticon.DISPLAY_MODULE_SIZE), label, KonceptKind.STAMP);
        return new Image(new ByteArrayInputStream(png));
    }

    private static String stampText(StampRecord r, GitStampTerms.GitStamp terms) {
        return "STAMP " + r.commitSha().substring(0, 7) + "  " + r.summary()
                + "\nStatus: " + r.status().name().toLowerCase()
                + "\nTime: " + MOMENT.format(r.time())
                + "\nAuthor: " + terms.author().description() + " <" + r.author().email() + ">"
                + "\nModule: " + terms.module().description()
                + "\nPath: " + terms.path().description()
                + "\nIntroduced " + r.introduced().getCardinality() + " line(s)";
    }

    private static String affirmationText(List<StampRecord> affirmers) {
        StringBuilder text = new StringBuilder();
        for (StampRecord a : affirmers) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("✓ Affirmed by ").append(a.author().name()).append(" on ").append(MOMENT.format(a.time()))
                    .append(a.signed() ? ", signed commit " : ", unsigned commit ").append(a.commitSha(), 0, 7);
        }
        return text.toString();
    }

    // ── affirming ─────────────────────────────────────────────────────────────

    private void affirmSelected() {
        if (bridge == null) {
            return;
        }
        List<Integer> selected = lineRows.stream().filter(r -> r.tick.isSelected()).map(r -> r.line).toList();
        if (selected.isEmpty()) {
            statusLine.setText("Tick the lines you have reviewed, then Affirm Selected.");
            return;
        }
        List<Affirmation> affirmations = new ArrayList<>();
        int from = selected.get(0);
        int previous = from;
        for (int i = 1; i <= selected.size(); i++) {
            if (i == selected.size() || selected.get(i) != previous + 1) {
                affirmations.add(Affirmation.of(relative, from, previous, headRevision, headLines));
                if (i < selected.size()) {
                    from = selected.get(i);
                }
            }
            if (i < selected.size()) {
                previous = selected.get(i);
            }
        }
        String trailers = String.join("\n", affirmations.stream().map(Affirmation::trailer).toList());
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Affirm Lines");
        confirm.setHeaderText("Record your review of " + selected.size() + " line" + (selected.size() == 1 ? "" : "s") + " as an empty commit?");
        confirm.setContentText(AffirmationCommit.subject(affirmations) + "\n\n" + trailers + "\n\nSigning (-S) gives the affirmation non-repudiation.");
        ButtonType signed = new ButtonType("Signed Commit");
        ButtonType unsigned = new ButtonType("Unsigned Commit");
        confirm.getButtonTypes().setAll(signed, unsigned, ButtonType.CANCEL);
        confirm.initOwner(host.getScene() == null ? null : host.getScene().getWindow());
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return;
        }
        boolean sign = choice.get() == signed;
        Path root = repoRoot;
        statusLine.setText("Writing affirmation commit …");
        CompletableFuture.supplyAsync(() -> {
            try {
                return AffirmationCommit.write(root, affirmations, sign);
            } catch (IOException e) {
                return new AffirmationCommit.Result(false, "", e.getMessage());
            }
        }).thenAccept(result -> Platform.runLater(() -> {
            if (result.ok()) {
                statusLine.setText("Affirmed as commit " + result.sha().substring(0, 7) + "; re-reading.");
                reload();
            } else {
                statusLine.setText("Affirmation not written: " + result.output());
                LOG.warn("Affirmation commit failed in {}: {}", root, result.output());
            }
        }));
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
            KlPreferencesFactory cardPreferencesFactory = KlPreferencesFactory.create(windowPreferences, StampProvenanceCard.class);
            StampProvenanceCard card = new CardFactory().create(cardPreferencesFactory);
            card.setJournalTopic(journalTopic);
            return card;
        }

        @Override
        public AbstractHostCard restoreCard(KometPreferences windowPreferences) {
            KometPreferences cardNode = windowPreferences.node(StampProvenanceCard.class.getSimpleName());
            StampProvenanceCard card = new CardFactory().restore(cardNode);
            card.revert();
            return card;
        }
    }

    /** Blueprint factory building the card shell (the {@code KlArea.Factory} the base needs). */
    private static final class CardFactory implements CardBlueprint.Factory<StampProvenanceCard> {
        @Override
        public StampProvenanceCard restore(KometPreferences preferences) {
            return new StampProvenanceCard(preferences);
        }

        @Override
        public StampProvenanceCard create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
            StampProvenanceCard card = new StampProvenanceCard(preferencesFactory, this);
            card.setAreaLayout(areaGridSettings.with(this.getClass()));
            return card;
        }
    }
}
