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

import design.knowledge.stamp.adoc.git.AnnotationCommit;
import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.LineRange;
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
import javafx.scene.control.TextArea;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.lang.ref.WeakReference;

/**
 * The STAMP Provenance card ({@code IKE-Network/ike-issues#468}): a committed AsciiDoc file
 * as a stack of lines — under semantic line breaks, one claim each — every line led by the
 * STAMP badge of the commit that introduced it, with a mark where someone has annotated it:
 * ✗ refuted, ✓ affirmed, … commented on. The badge is the same gray pentagon, identicon and
 * compact provenance a STAMP shows anywhere in Komet; its identicon is seeded by the STAMP
 * identity the adapter derives, so it is the identicon that STAMP's concept carries once
 * written to a store.
 *
 * <p>The card reads the file as committed at HEAD, never the working tree, so an annotation
 * made here is anchored exactly; a banner says when the working tree has uncommitted edits.
 * <em>Affirm</em>, <em>Refute</em> and <em>Comment</em> fold the ticked lines into ranges, hash
 * them over the committed text, ask for the annotation text (a refutation needs its reason),
 * and write the empty commit — signed by default — through the user's own {@code git}, so
 * whatever signing they have configured applies (IKE-Network/ike-issues#1300). The review queue
 * toggle tints the lines no one has affirmed yet; a refuted line stays in it.
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

    /** A report to put back on the status line once the next read of the file is shown. */
    private String reportAfterLoad;

    /** Live cards, most recently realized last, so the assistant's tools can find the one in use. */
    private static final List<WeakReference<StampProvenanceCard>> OPEN = new CopyOnWriteArrayList<>();

    /** @return the most recently realized card still alive, or {@code null} */
    public static StampProvenanceCard mostRecent() {
        StampProvenanceCard found = null;
        for (WeakReference<StampProvenanceCard> ref : OPEN) {
            StampProvenanceCard card = ref.get();
            if (card == null) {
                OPEN.remove(ref);
            } else {
                found = card;
            }
        }
        return found;
    }

    /** Re-reads the open file in every live card, after something committed to it; call on the FX thread. */
    public static void reloadAll() {
        for (WeakReference<StampProvenanceCard> ref : OPEN) {
            StampProvenanceCard card = ref.get();
            if (card == null) {
                OPEN.remove(ref);
            } else {
                card.reload();
            }
        }
    }

    /** @return the repository root of the open file, or {@code null} before a file is opened */
    public Path repoRoot() {
        return repoRoot;
    }

    /** @return the open file relative to its repository root */
    public Optional<Path> currentFile() {
        return Optional.ofNullable(relative);
    }

    /** @return whether this card shows exactly that file of that repository */
    public boolean showsFile(Path root, Path file) {
        return repoRoot != null && relative != null && repoRoot.equals(root) && relative.equals(file);
    }

    /**
     * The assistant's hand-off ({@link StampTools}): tick the lines of the given ranges and open
     * the same confirmation the kind's button opens, with the text prefilled for the person to
     * edit. The person's click is the annotation; nothing is written here.
     *
     * @param kind   the annotation kind
     * @param ranges 1-based inclusive line ranges
     * @param text   the annotation text the person gave, or {@code null}
     */
    public void proposeAnnotation(AnnotationKind kind, List<int[]> ranges, String text) {
        Platform.runLater(() -> {
            for (LineRow row : lineRows) {
                boolean in = false;
                for (int[] r : ranges) {
                    in |= row.line >= r[0] && row.line <= r[1];
                }
                row.tick.setSelected(in);
            }
            annotateSelected(kind, "The assistant prepared this " + displayName(kind).toLowerCase() + " at your request.", text);
        });
    }

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

    /** The annotation buttons' order: the two judgments, then the comment. */
    private static final List<AnnotationKind> TOOLBAR_ORDER =
            List.of(AnnotationKind.AFFIRMATION, AnnotationKind.REFUTATION, AnnotationKind.COMMENT);

    /**
     * @param kind an annotation kind
     * @return what a person reads for it: "Comment" for the kind whose birth name, "Uncategorized
     *         comment", only tells it apart from the judgments in the graph
     */
    static String displayName(AnnotationKind kind) {
        return kind == AnnotationKind.COMMENT ? "Comment" : kind.label();
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
        List<Button> annotate = new ArrayList<>();
        for (AnnotationKind kind : TOOLBAR_ORDER) {
            Button button = new Button(kind == AnnotationKind.COMMENT ? "Comment…" : kind.verb() + "…");
            button.setTooltip(new Tooltip(switch (kind) {
                case AFFIRMATION -> "Record that you have reviewed the ticked lines and stand behind them";
                case REFUTATION -> "Record that you have reviewed the ticked lines and dispute them, with your reason";
                case COMMENT -> "Record a comment on the ticked lines, with no stance";
            } + ", as an empty commit with Annotation: trailers"));
            button.getStyleClass().add("claude-card-toolbar-button");
            button.setOnAction(e -> annotateSelected(kind, null, null));
            annotate.add(button);
        }
        Button materialize = new Button("Materialize");
        materialize.setTooltip(new Tooltip("Write this file's whole history into the knowledge base as a STAMPed document"));
        materialize.getStyleClass().add("claude-card-toolbar-button");
        materialize.setOnAction(e -> materialize());
        queueToggle.setTooltip(new Tooltip("Tint the lines no one has affirmed yet"));
        queueToggle.setSelected(preferences().getBoolean(PREF_QUEUE, false));
        queueToggle.selectedProperty().addListener((obs, was, is) -> {
            preferences().putBoolean(PREF_QUEUE, is);
            syncPreferences("review queue toggle");
            retint();
        });
        toolBar.getChildren().addAll(open, refresh);
        toolBar.getChildren().addAll(annotate);
        toolBar.getChildren().addAll(materialize, queueToggle);
    }

    @Override
    protected void renderContent() {
        if (built) {
            return;
        }
        built = true;
        OPEN.add(new WeakReference<>(this));
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

    /** Flushes the card's preferences now, so a crash before the journal closes does not lose them. */
    private void syncPreferences(String what) {
        try {
            preferences().sync();
        } catch (Exception e) {
            LOG.warn("Could not sync the STAMP Provenance card preferences after the {}", what, e);
        }
    }

    /**
     * Writes the open file's history into the open knowledge base through the
     * {@link GitDocumentMaterializer}, off the FX thread, and tells any STAMPed Document card to
     * refresh its list. Incremental: a second run writes only the commits the store lacks.
     */
    public void materialize() {
        if (repoRoot == null || relative == null) {
            statusLine.setText("Open a committed .adoc file first.");
            return;
        }
        Path root = repoRoot;
        Path file = relative;
        statusLine.setText("Materializing " + file + " into the knowledge base …");
        record Materialized(GitDocumentMaterializer.Result result, DocumentEditor.Settlement settlement) { }
        CompletableFuture.supplyAsync(() -> {
            try {
                GitDocumentMaterializer.Result result = GitDocumentMaterializer.materialize(root, root.resolve(file));
                // A commit that arrived from elsewhere (a pull, an edit outside Komet) settles or
                // carries forward an edit pending in Komet, as the card's own commit does.
                return new Materialized(result, DocumentEditor.settle(result.anchor()));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((materialized, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                statusLine.setText("Materialization failed: " + failure.getMessage());
                LOG.warn("Materializing {} failed", file, failure);
                return;
            }
            GitDocumentMaterializer.Result result = materialized.result();
            DocumentEditor.Settlement settlement = materialized.settlement();
            statusLine.setText("Materialized '" + result.name() + "': " + result.commits() + " commit(s) written, " + result.lines()
                    + " line(s), " + result.blocks() + " block version(s), " + result.references() + " reference(s), "
                    + result.annotations() + " annotation(s). "
                    + (settlement.settled() || settlement.rebased() ? "Edit in Komet: " + StampedDocumentCard.describe(settlement)
                    : "Open it in the STAMPed Document card."));
            reportAfterLoad = statusLine.getText();             // kept through the re-read below
            reload();                                            // show the commits just read in
            StampedDocumentCard card = StampedDocumentCard.mostRecent();
            if (card != null) {
                card.refresh();
            }
        }));
    }

    private void reload() {
        if (repoRoot != null && relative != null) {
            open(repoRoot.resolve(relative));
        }
    }

    /**
     * Shows a committed AsciiDoc file: resolves its repository, reads its STAMPs at HEAD and
     * renders the lines. Public so the assistant's tools and UI automation can open a file
     * without the file chooser; must be called on the JavaFX application thread.
     *
     * @param file the file to show, inside a git working tree
     */
    public void open(Path file) {
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
            syncPreferences("open file");
            show(loaded.workingTreeDiffers());
            if (reportAfterLoad != null) {
                statusLine.setText(reportAfterLoad);
                reportAfterLoad = null;
            }
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
        Map<Integer, List<Note>> notes = new HashMap<>();
        for (StampRecord r : byTime) {
            r.introduced().forEach((int line) -> { if (line < introducer.length) introducer[line] = r; });
            r.affirmed().forEach((int line) -> notes.computeIfAbsent(line, k -> new ArrayList<>()).add(new Note(r, AnnotationKind.AFFIRMATION)));
            r.refuted().forEach((int line) -> notes.computeIfAbsent(line, k -> new ArrayList<>()).add(new Note(r, AnnotationKind.REFUTATION)));
            r.commented().forEach((int line) -> notes.computeIfAbsent(line, k -> new ArrayList<>()).add(new Note(r, AnnotationKind.COMMENT)));
        }
        for (int line = 1; line <= headLines.size(); line++) {
            LineRow row = new LineRow(line, headLines.get(line - 1), introducer[line], notes.get(line),
                    introducer[line] == null ? null : PALETTE[order.get(introducer[line].id()) % PALETTE.length]);
            lineRows.add(row);
            rows.getChildren().add(row.node);
        }
        retint();
        int affirmed = (int) lineRows.stream().filter(r -> r.has(AnnotationKind.AFFIRMATION)).count();
        int refuted = (int) lineRows.stream().filter(r -> r.has(AnnotationKind.REFUTATION)).count();
        int problems = bridge.stamps().problems().size();
        statusLine.setText(relative + " @ " + headRevision.substring(0, Math.min(7, headRevision.length())) + " on " + branchName()
                + " — " + headLines.size() + " lines, " + byTime.size() + " STAMPs, " + affirmed + " affirmed"
                + (refuted == 0 ? "" : ", " + refuted + " refuted")
                + (problems == 0 ? "" : ", " + problems + " annotation(s) not applied"));
        banner.setText(workingTreeDiffers
                ? "The working tree has uncommitted edits to this file. Showing the file as committed at HEAD; commit before annotating the edits."
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
            Color tint = queue && row.introducer != null && !row.has(AnnotationKind.AFFIRMATION) ? QUEUE : row.tint;
            row.node.setBackground(tint == null ? Background.EMPTY : new Background(new BackgroundFill(tint, new CornerRadii(3), Insets.EMPTY)));
        }
    }

    /** One annotation of one line: the annotating commit's record and the kind. */
    private record Note(StampRecord stamp, AnnotationKind kind) { }

    /** One line of the file: tick box, STAMP badge, annotation mark, text. */
    private final class LineRow {
        final int line;
        final StampRecord introducer;
        final List<Note> notes;
        final Color tint;
        final CheckBox tick = new CheckBox();
        final HBox node = new HBox(8);

        LineRow(int line, String text, StampRecord introducer, List<Note> notes, Color tint) {
            this.line = line;
            this.introducer = introducer;
            this.notes = notes;
            this.tint = tint;
            node.setAlignment(Pos.CENTER_LEFT);
            node.setPadding(new Insets(1, 6, 1, 4));
            Label number = new Label(Integer.toString(line));
            number.setMinWidth(34);
            number.setAlignment(Pos.CENTER_RIGHT);
            number.setStyle("-fx-text-fill: #8a8782;");
            Node badge = introducer == null ? new Label() : badge(introducer);
            // A refutation outranks an affirmation, and either outranks a comment.
            Label mark = has(AnnotationKind.REFUTATION) ? new Label("✗")
                    : has(AnnotationKind.AFFIRMATION) ? new Label("✓")
                    : has(AnnotationKind.COMMENT) ? new Label("…") : new Label(" ");
            mark.setMinWidth(16);
            mark.setStyle(has(AnnotationKind.REFUTATION) ? "-fx-text-fill: #c62828; -fx-font-weight: bold;"
                    : has(AnnotationKind.AFFIRMATION) ? "-fx-text-fill: #2e7d32; -fx-font-weight: bold;"
                    : "-fx-text-fill: #8a8782; -fx-font-weight: bold;");
            if (notes != null) {
                mark.setTooltip(new Tooltip(annotationText(notes)));
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
            Tooltip.install(view, new Tooltip(stampText(r, terms)
                    + (has(AnnotationKind.AFFIRMATION) ? "" : "\nNot yet affirmed.")
                    + (notes == null ? "" : "\n" + annotationText(notes))));
            return view;
        }

        boolean has(AnnotationKind kind) {
            return notes != null && notes.stream().anyMatch(n -> n.kind() == kind);
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

    private static String annotationText(List<Note> notes) {
        StringBuilder text = new StringBuilder();
        for (Note n : notes) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            StampRecord a = n.stamp();
            text.append(switch (n.kind()) {
                case AFFIRMATION -> "✓ Affirmed";
                case REFUTATION -> "✗ Refuted";
                case COMMENT -> "… Commented on";
            }).append(" by ").append(a.author().name()).append(" on ").append(MOMENT.format(a.time()))
                    .append(a.signed() ? ", signed commit " : ", unsigned commit ").append(a.commitSha(), 0, 7)
                    .append(": ").append(a.summary());
        }
        return text.toString();
    }

    // ── annotating ────────────────────────────────────────────────────────────

    /**
     * Annotates the ticked lines with {@code kind}: folds them into ranges, hashes them over the
     * committed text, asks for the annotation text and the signing choice, and writes the commit.
     *
     * @param kind        the annotation kind
     * @param headerNote  a line for the dialog header, or {@code null}
     * @param initialText text to prefill, or {@code null}
     */
    private void annotateSelected(AnnotationKind kind, String headerNote, String initialText) {
        if (bridge == null) {
            return;
        }
        List<Integer> selected = lineRows.stream().filter(r -> r.tick.isSelected()).map(r -> r.line).toList();
        if (selected.isEmpty()) {
            statusLine.setText("Tick the lines you have reviewed, then choose Affirm, Refute or Comment.");
            return;
        }
        List<Annotation> annotations = new ArrayList<>();
        int from = selected.get(0);
        int previous = from;
        for (int i = 1; i <= selected.size(); i++) {
            if (i == selected.size() || selected.get(i) != previous + 1) {
                annotations.add(Annotation.of(kind, LineRange.of(relative, from, previous, headRevision, headLines)));
                if (i < selected.size()) {
                    from = selected.get(i);
                }
            }
            if (i < selected.size()) {
                previous = selected.get(i);
            }
        }
        String trailers = String.join("\n", annotations.stream().map(Annotation::trailer).toList());
        String noun = selected.size() + " line" + (selected.size() == 1 ? "" : "s");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(kind.verb() + " Lines");
        confirm.setHeaderText((headerNote == null ? "" : headerNote + "\n")
                + "Record your " + displayName(kind).toLowerCase() + (kind.judgment() ? " of " : " on ") + noun + " as an empty commit?");
        TextArea text = new TextArea(initialText == null ? "" : initialText);
        text.setPromptText(kind == AnnotationKind.REFUTATION ? "Why do you dispute these lines? (required)" : "Annotation text (optional)");
        text.setWrapText(true);
        text.setPrefRowCount(4);
        Label details = new Label(AnnotationCommit.subject(annotations) + "\n\n" + trailers
                + "\n\nThe text becomes the commit body. Signing (-S) gives the annotation non-repudiation.");
        details.setWrapText(true);
        confirm.getDialogPane().setContent(new VBox(8, text, details));
        ButtonType signed = new ButtonType("Signed Commit");
        ButtonType unsigned = new ButtonType("Unsigned Commit");
        confirm.getButtonTypes().setAll(signed, unsigned, ButtonType.CANCEL);
        if (kind == AnnotationKind.REFUTATION) {                 // a refutation without a reason is weak
            for (ButtonType type : List.of(signed, unsigned)) {
                confirm.getDialogPane().lookupButton(type).disableProperty().bind(text.textProperty().isEmpty());
            }
        }
        confirm.initOwner(host.getScene() == null ? null : host.getScene().getWindow());
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return;
        }
        boolean sign = choice.get() == signed;
        String body = text.getText();
        Path root = repoRoot;
        statusLine.setText("Writing annotation commit …");
        CompletableFuture.supplyAsync(() -> {
            try {
                return AnnotationCommit.write(root, annotations, body, sign);
            } catch (IOException e) {
                return new AnnotationCommit.Result(false, "", e.getMessage());
            }
        }).thenAccept(result -> Platform.runLater(() -> {
            if (result.ok()) {
                statusLine.setText(displayName(kind) + " recorded as commit " + result.sha().substring(0, 7) + "; re-reading.");
                reload();
            } else {
                statusLine.setText(displayName(kind) + " not written: " + result.output());
                LOG.warn("Annotation commit failed in {}: {}", root, result.output());
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
