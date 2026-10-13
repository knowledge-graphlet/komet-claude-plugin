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

import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.dialect.Reference;
import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.CardBlueprint;
import dev.ikm.komet.layout_engine.host.AbstractHostCard;
import dev.ikm.komet.layout_engine.host.KlCardProvider;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.StampEntity;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import network.ike.komet.claude.koncept.KonceptIdenticon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * The store-backed Document surface ({@code IKE-Network/ike-issues#468}): renders a document
 * the {@link GitDocumentMaterializer} wrote — never the file — from the graph alone: the block
 * structure the parse produced over the lines, each block led by the STAMP badges of the
 * commits that introduced its lines and a mark when every line of it is affirmed, with the
 * inline references on its lines beneath it. Git is not consulted; what this card shows is
 * what the knowledge base holds, which is the point of the round trip.
 *
 * <p>A document is chosen by name from the ones the store holds (every anchor with a git-source
 * semantic). The card keeps the chosen name in its preferences and restores it.
 */
public final class StampedDocumentCard extends AbstractHostCard {

    private static final Logger LOG = LoggerFactory.getLogger(StampedDocumentCard.class);

    static final String CARD_NAME = "STAMPed Document";

    private static final String PREF_DOCUMENT = "network.ike.komet.claude.stamp.document";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz").withZone(ZoneId.systemDefault());
    private static final Pattern MARKER = Pattern.compile("^\\s*(\\*+|-|\\.+|\\d+\\.)\\s+");
    private static final Pattern DELIMITER_OR_META = Pattern.compile("^(-{4,}|\\.{4,}|={4,}|_{4,}|\\*{4,}|--|\\|===|/{4,}|\\+{4,}|\\[[^\\]]*\\]|\\.[^\\s.].*)$");
    private static final Font PROSE = Font.font(14);
    private static final Font MONO = Font.font("Menlo", 12);
    private static final Font SMALL = Font.font(11);
    private static final double GUTTER_WIDTH = 132;

    /** Live cards, most recently realized last, so tools and automation can find the one in use. */
    private static final List<WeakReference<StampedDocumentCard>> OPEN = new CopyOnWriteArrayList<>();

    /** @return the most recently realized card still alive, or {@code null} */
    public static StampedDocumentCard mostRecent() {
        StampedDocumentCard found = null;
        for (WeakReference<StampedDocumentCard> ref : OPEN) {
            StampedDocumentCard card = ref.get();
            if (card == null) {
                OPEN.remove(ref);
            } else {
                found = card;
            }
        }
        return found;
    }

    private final BorderPane host = new BorderPane();
    private final VBox stack = new VBox(6);
    private final Label statusLine = new Label("Choose a document the store holds.");
    private final ChoiceBox<String> documents = new ChoiceBox<>();
    private final Map<Long, Image> badges = new HashMap<>();
    private Map<String, PublicId> anchors = new LinkedHashMap<>();
    private GitDocumentMaterializer.StoredDocument shown;
    private boolean built;

    private StampedDocumentCard(KometPreferences preferences) {
        super(preferences);
    }

    private StampedDocumentCard(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
    }

    @Override
    protected String cardTitle() {
        return CARD_NAME;
    }

    /** @return the name of the document shown, if one is */
    public Optional<String> currentDocument() {
        return Optional.ofNullable(documents.getValue());
    }

    /** @return the document as loaded from the store, if one is shown */
    public Optional<GitDocumentMaterializer.StoredDocument> shown() {
        return Optional.ofNullable(shown);
    }

    @Override
    protected void buildToolbarControls(HBox toolBar) {
        documents.setId("stampedDocumentChooser");
        documents.setTooltip(new Tooltip("The documents the knowledge base holds"));
        documents.setMaxWidth(360);
        documents.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
            if (is != null && anchors.containsKey(is)) {
                preferences().put(PREF_DOCUMENT, is);
                syncPreferences();
                open(anchors.get(is));
            }
        });
        Button refresh = new Button("Refresh");
        refresh.setTooltip(new Tooltip("Re-read the documents the store holds, and the one shown"));
        refresh.getStyleClass().add("claude-card-toolbar-button");
        refresh.setOnAction(e -> refresh());
        toolBar.getChildren().addAll(documents, refresh);
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

    /** Re-reads the store's document list and reopens the chosen document. */
    public void refresh() {
        String wanted = documents.getValue() != null ? documents.getValue() : preferences().get(PREF_DOCUMENT, "");
        CompletableFuture.supplyAsync(GitDocumentMaterializer::storedDocuments).whenComplete((found, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                statusLine.setText("Could not list the store's documents: " + failure.getMessage());
                LOG.warn("Listing stored documents failed", failure);
                return;
            }
            anchors = found;
            documents.getItems().setAll(found.keySet());
            if (found.isEmpty()) {
                stack.getChildren().clear();
                shown = null;
                statusLine.setText("The knowledge base holds no materialized documents yet. Materialize one from the STAMP Provenance card.");
            } else if (found.containsKey(wanted)) {
                if (wanted.equals(documents.getValue())) {
                    open(found.get(wanted));
                } else {
                    documents.setValue(wanted);
                }
            } else {
                statusLine.setText(found.size() + " document(s) in the store; choose one.");
            }
        }));
    }

    /**
     * Shows a document by its anchor, reading it from the store off the FX thread.
     *
     * @param anchor the document anchor's id
     */
    public void open(PublicId anchor) {
        statusLine.setText("Reading from the store …");
        CompletableFuture.supplyAsync(() -> GitDocumentMaterializer.load(anchor)).whenComplete((loaded, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                statusLine.setText("Could not read the document: " + failure.getMessage());
                LOG.warn("Loading {} failed", anchor, failure);
                return;
            }
            if (loaded.isEmpty()) {
                statusLine.setText("The store holds no manifest for that document.");
                return;
            }
            show(loaded.get());
        }));
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    private void show(GitDocumentMaterializer.StoredDocument document) {
        shown = document;
        stack.getChildren().clear();
        badges.clear();
        Map<Long, GitDocumentMaterializer.StoredLine> lines = new HashMap<>();
        for (GitDocumentMaterializer.StoredLine line : document.lines()) {
            lines.put(line.nid(), line);
        }
        Context context = new Context(document, lines);
        if (document.root() == null) {
            // Materialized before the structure existed: show the lines as one run.
            for (GitDocumentMaterializer.StoredLine line : document.lines()) {
                stack.getChildren().add(blockRow(List.of(line), mono(line.text()), context, 0));
            }
            statusLine.setText(status(document, "no structure; re-materialize to add it"));
            return;
        }
        render(document.root(), context, 0, 0);
        int affirmed = (int) document.lines().stream().filter(l -> !l.affirmers().isEmpty()).count();
        int references = document.references().values().stream().mapToInt(List::size).sum();
        statusLine.setText(status(document, document.blocks().size() + " blocks, " + references + " references, " + affirmed + " affirmed lines"));
    }

    /** The card's view, or the default one before the card is hosted. */
    private ViewCalculator view() {
        return getCardViewProperties() != null ? getCardViewProperties().calculator() : Calculators.View.Default();
    }

    private String status(GitDocumentMaterializer.StoredDocument document, String tail) {
        String name = StampNames.name(view(), PrimitiveData.nid(document.anchor()));
        String commit = document.commit().isEmpty() ? "?" : document.commit().substring(0, Math.min(7, document.commit().length()));
        return name + " @ " + commit + " — " + document.lines().size() + " lines, " + document.manifestVersions() + " manifest versions, " + tail;
    }

    /** What rendering needs beside the block: the lines by nid and the document. */
    private record Context(GitDocumentMaterializer.StoredDocument document, Map<Long, GitDocumentMaterializer.StoredLine> lines) {
        GitDocumentMaterializer.StoredBlock block(long nid) {
            return document.blocks().get(nid);
        }
    }

    /** Renders one block and its members into the stack. */
    private void render(GitDocumentMaterializer.StoredBlock block, Context context, int depth, int ordinal) {
        List<GitDocumentMaterializer.StoredLine> own = new ArrayList<>();
        List<GitDocumentMaterializer.StoredBlock> children = new ArrayList<>();
        for (long nid : block.members()) {
            GitDocumentMaterializer.StoredLine line = context.lines().get(nid);
            if (line != null) {
                own.add(line);
            } else {
                GitDocumentMaterializer.StoredBlock child = context.block(nid);
                if (child != null) {
                    children.add(child);
                }
            }
        }
        switch (block.kind()) {
            case DOCUMENT -> {
                String title = context.document().attributes().isEmpty() ? "" : context.document().attributes().getFirst();
                List<GitDocumentMaterializer.StoredLine> heading = own.stream().filter(l -> l.text().startsWith("= ")).toList();
                if (!title.isEmpty() || !heading.isEmpty()) {
                    Label label = new Label(title.isEmpty() ? heading.getFirst().text().substring(2) : title);
                    label.setFont(Font.font("System", FontWeight.BOLD, 24));
                    label.setWrapText(true);
                    stack.getChildren().add(blockRow(heading.isEmpty() ? own : heading, label, context, depth));
                }
                List<GitDocumentMaterializer.StoredLine> front = own.stream().filter(l -> !l.text().startsWith("= ")).toList();
                if (!front.isEmpty()) {
                    Label label = mono(front.stream().map(GitDocumentMaterializer.StoredLine::text).reduce((a, b) -> a + "\n" + b).orElse(""));
                    label.setStyle("-fx-text-fill: #8a8782;");
                    stack.getChildren().add(blockRow(front, label, context, depth));
                }
                renderChildren(children, context, depth);
            }
            case SECTION -> {
                Label label = new Label(block.title());
                label.setFont(Font.font("System", FontWeight.BOLD, Math.max(13, 21 - 2 * block.level())));
                label.setWrapText(true);
                List<GitDocumentMaterializer.StoredLine> heading = own.stream().filter(l -> l.text().startsWith("=")).toList();
                stack.getChildren().add(blockRow(heading.isEmpty() ? own : heading, label, context, depth));
                renderChildren(children, context, depth);
            }
            case PARAGRAPH -> stack.getChildren().add(blockRow(own, prose(joined(own, true)), context, depth, references(own, context)));
            case ADMONITION -> {
                String label = block.parameters().replaceAll("[\\[\\]]", "");
                String text = joined(own, true).replaceFirst("^(NOTE|TIP|WARNING|IMPORTANT|CAUTION):\\s*", "");
                Label body = prose(label + " — " + text);
                body.setStyle("-fx-background-color: #FFF8E1; -fx-padding: 6; -fx-background-radius: 4;");
                stack.getChildren().add(blockRow(own, body, context, depth, references(own, context)));
                renderChildren(children, context, depth + 1);
            }
            case LISTING, LITERAL, DIAGRAM, OPAQUE -> {
                List<GitDocumentMaterializer.StoredLine> content = own.stream().filter(l -> !DELIMITER_OR_META.matcher(l.text()).matches()).toList();
                Label body = mono(content.isEmpty() ? joined(own, false) : content.stream().map(GitDocumentMaterializer.StoredLine::text).reduce((a, b) -> a + "\n" + b).orElse(""));
                if (block.kind() == Block.Kind.OPAQUE) {
                    body.setStyle("-fx-text-fill: #8a8782;");
                } else {
                    body.setStyle("-fx-background-color: #F4F3F1; -fx-padding: 6; -fx-background-radius: 4;");
                }
                stack.getChildren().add(blockRow(own, titled(block, body), context, depth));
            }
            case EXAMPLE, QUOTE, SIDEBAR, OPEN -> {
                Label head = new Label(block.title().isEmpty() ? block.parameters().isEmpty() ? block.kind().name().toLowerCase() : block.parameters() : block.title());
                head.setFont(SMALL);
                head.setStyle("-fx-text-fill: #8a8782;");
                stack.getChildren().add(blockRow(own, head, context, depth));
                renderChildren(children, context, depth + 1);
            }
            case LIST -> {
                if (!block.title().isEmpty()) {
                    stack.getChildren().add(blockRow(own, bold(block.title()), context, depth));
                }
                int n = 0;
                for (GitDocumentMaterializer.StoredBlock item : children) {
                    render(item, context, depth + 1, ++n);
                }
            }
            case LIST_ITEM, BIBLIOGRAPHY_ENTRY -> {
                String marker = block.title().isEmpty() ? (block.kind() == Block.Kind.BIBLIOGRAPHY_ENTRY ? "▪" : ordinalMarker(own, ordinal)) : "";
                String text = own.stream().map(l -> MARKER.matcher(l.text()).replaceFirst("")).map(String::strip)
                        .filter(t -> !t.equals("+")).reduce((a, b) -> a + " " + b).orElse("");
                Node body;
                if (!block.title().isEmpty()) {
                    String description = text.startsWith(block.title()) ? text.substring(block.title().length()).replaceFirst("^::+\\s*", "") : text;
                    VBox item = new VBox(2, bold(block.title()), prose(description));
                    body = item;
                } else {
                    body = prose(marker + " " + text);
                }
                stack.getChildren().add(blockRow(own, body, context, depth, references(own, context)));
                renderChildren(children, context, depth + 1);
            }
            case TABLE -> {
                GridPane grid = new GridPane();
                grid.setHgap(12);
                grid.setVgap(3);
                grid.setStyle("-fx-background-color: #F4F3F1; -fx-padding: 6; -fx-background-radius: 4;");
                boolean header = block.parameters().contains("header");
                int r = 0;
                for (GitDocumentMaterializer.StoredBlock row : children) {
                    List<GitDocumentMaterializer.StoredLine> cells = new ArrayList<>();
                    for (long nid : row.members()) {
                        GitDocumentMaterializer.StoredLine line = context.lines().get(nid);
                        if (line != null) {
                            cells.add(line);
                        }
                    }
                    String joinedRow = cells.stream().map(GitDocumentMaterializer.StoredLine::text).reduce((a, b) -> a + " " + b).orElse("");
                    String[] split = joinedRow.replaceFirst("^\\s*\\|", "").split("\\s\\|\\s|\\|");
                    for (int c = 0; c < split.length; c++) {
                        Label cell = new Label(split[c].strip());
                        cell.setWrapText(true);
                        cell.setFont(header && r == 0 ? Font.font("System", FontWeight.BOLD, 13) : Font.font(13));
                        cell.setMaxWidth(320);
                        grid.add(cell, c, r);
                    }
                    r++;
                }
                while (grid.getColumnConstraints().size() < 8) {
                    ColumnConstraints column = new ColumnConstraints();
                    column.setHgrow(Priority.SOMETIMES);
                    grid.getColumnConstraints().add(column);
                }
                List<GitDocumentMaterializer.StoredLine> all = new ArrayList<>(own);
                for (GitDocumentMaterializer.StoredBlock row : children) {
                    for (long nid : row.members()) {
                        GitDocumentMaterializer.StoredLine line = context.lines().get(nid);
                        if (line != null) {
                            all.add(line);
                        }
                    }
                }
                stack.getChildren().add(blockRow(all, titled(block, grid), context, depth));
            }
            case TABLE_ROW, TABLE_CELL -> renderChildren(children, context, depth);
            case IMAGE -> stack.getChildren().add(blockRow(own, prose("🖼 " + block.title() + " " + block.parameters()), context, depth));
        }
    }

    private void renderChildren(List<GitDocumentMaterializer.StoredBlock> children, Context context, int depth) {
        int n = 0;
        for (GitDocumentMaterializer.StoredBlock child : children) {
            render(child, context, depth, ++n);
        }
    }

    private static String ordinalMarker(List<GitDocumentMaterializer.StoredLine> own, int ordinal) {
        if (own.isEmpty()) {
            return "•";
        }
        String first = own.getFirst().text().stripLeading();
        return first.startsWith(".") || Character.isDigit(first.charAt(0)) ? ordinal + "." : "•";
    }

    private static String joined(List<GitDocumentMaterializer.StoredLine> lines, boolean prose) {
        StringBuilder sb = new StringBuilder();
        for (GitDocumentMaterializer.StoredLine line : lines) {
            String text = line.text();
            if (prose) {
                text = text.strip();
                if (text.endsWith("\\")) {
                    text = text.substring(0, text.length() - 1).stripTrailing();
                }
            }
            if (sb.length() > 0) {
                sb.append(prose ? ' ' : '\n');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private static Label prose(String text) {
        Label label = new Label(text.isEmpty() ? " " : text);
        label.setWrapText(true);
        label.setFont(PROSE);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Label mono(String text) {
        Label label = new Label(text.isEmpty() ? " " : text);
        label.setWrapText(true);
        label.setFont(MONO);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Label bold(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setFont(Font.font("System", FontWeight.BOLD, 14));
        return label;
    }

    private static Node titled(GitDocumentMaterializer.StoredBlock block, Node body) {
        if (block.title().isEmpty()) {
            return body;
        }
        Label title = new Label(block.title());
        title.setFont(Font.font("System", FontWeight.BOLD, 13));
        VBox box = new VBox(3, title, body);
        box.setFillWidth(true);
        return box;
    }

    /** The references on the block's lines, as one small line, or null when there are none. */
    private Node references(List<GitDocumentMaterializer.StoredLine> own, Context context) {
        List<String> parts = new ArrayList<>();
        for (GitDocumentMaterializer.StoredLine line : own) {
            for (GitDocumentMaterializer.StoredReference r : context.document().references().getOrDefault(line.nid(), List.of())) {
                parts.add(switch (r.kind()) {
                    case INDEX_TERM -> "⌕ " + r.target();
                    case CROSS_REFERENCE -> "↗ " + r.target() + (r.text().isEmpty() ? "" : " (" + r.text() + ")");
                    case CITATION -> "[" + r.target() + "]";
                    case FOOTNOTE -> "† " + r.target();
                    case LINK -> "🔗 " + r.target();
                    case ESTIMATE_MARK -> "≈ " + r.target();
                    case INCLUDE -> "include " + r.target();
                    case CONDITIONAL -> "if " + r.target();
                });
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        Label label = new Label(String.join("  ·  ", parts));
        label.setFont(SMALL);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #5f6b7a;");
        return label;
    }

    private Node blockRow(List<GitDocumentMaterializer.StoredLine> lines, Node body, Context context, int depth) {
        return blockRow(lines, body, context, depth, null);
    }

    /** One rendered block: provenance gutter (identicons, date and mark) beside the body, indented by depth. */
    private Node blockRow(List<GitDocumentMaterializer.StoredLine> lines, Node body, Context context, int depth, Node references) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(0, 0, 0, depth * 18));
        HBox gutter = new HBox(3);
        gutter.setAlignment(Pos.TOP_LEFT);
        gutter.setMinWidth(GUTTER_WIDTH);
        gutter.setPrefWidth(GUTTER_WIDTH);
        gutter.setMaxWidth(GUTTER_WIDTH);
        Map<Long, StampEntity<?>> distinct = new LinkedHashMap<>();
        for (GitDocumentMaterializer.StoredLine line : lines) {
            distinct.putIfAbsent(line.introduced().nid(), line.introduced());
        }
        List<StampEntity<?>> stamps = new ArrayList<>(distinct.values());
        stamps.sort(Comparator.comparingLong(StampEntity::time));
        int shown = 0;
        for (StampEntity<?> stamp : stamps) {
            if (shown++ == 3) {
                break;
            }
            gutter.getChildren().add(identicon(stamp, lines));
        }
        if (!stamps.isEmpty()) {
            Label when = new Label(DAY.format(Instant.ofEpochMilli(stamps.getFirst().time())) + (stamps.size() > 1 ? " +" + (stamps.size() - 1) : ""));
            when.setFont(SMALL);
            when.setStyle("-fx-text-fill: #5f6b7a;");
            when.setTooltip(new Tooltip(stamps.size() == 1 ? stampText(stamps.getFirst())
                    : stamps.size() + " STAMPs introduced these lines, from " + MOMENT.format(Instant.ofEpochMilli(stamps.getFirst().time()))
                    + " to " + MOMENT.format(Instant.ofEpochMilli(stamps.getLast().time()))));
            gutter.getChildren().add(when);
        }
        long affirmed = lines.stream().filter(l -> !l.affirmers().isEmpty()).count();
        if (affirmed > 0) {
            Label mark = new Label(affirmed == lines.size() ? "✓" : "✓ " + affirmed + "/" + lines.size());
            mark.setFont(SMALL);
            mark.setStyle("-fx-text-fill: #2e7d32; -fx-font-weight: bold;");
            mark.setTooltip(new Tooltip(affirmed == lines.size() ? "Every line of this block is affirmed" : affirmed + " of " + lines.size() + " lines affirmed"));
            gutter.getChildren().add(mark);
        }
        VBox content = new VBox(2, body);
        if (references != null) {
            content.getChildren().add(references);
        }
        content.setFillWidth(true);
        content.setMinWidth(0);
        HBox.setHgrow(content, Priority.ALWAYS);
        row.getChildren().addAll(gutter, content);
        return row;
    }

    /** The STAMP's identicon, the badge's own seed, at gutter size; the full STAMP text is its tooltip. */
    private Node identicon(StampEntity<?> stamp, List<GitDocumentMaterializer.StoredLine> lines) {
        Image image = badges.computeIfAbsent(stamp.nid(), nid -> renderIdenticon(stamp));
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setFitHeight(18);
        long introduced = lines.stream().filter(l -> l.introduced().nid() == stamp.nid()).count();
        Tooltip.install(view, new Tooltip(stampText(stamp) + "\nIntroduced " + introduced + " of these " + lines.size() + " line(s)"));
        return view;
    }

    private static Image renderIdenticon(StampEntity<?> stamp) {
        UUID seed = stamp.publicId().asUuidArray()[0];
        byte[] png = KonceptIdenticon.png(PublicIds.of(seed).idString(), KonceptIdenticon.DISPLAY_MODULE_SIZE);
        return new Image(new ByteArrayInputStream(png));
    }

    private String stampText(StampEntity<?> stamp) {
        ViewCalculator view = view();
        return "STAMP " + stamp.publicId().asUuidArray()[0].toString().substring(0, 8)
                + "\nStatus: " + stamp.state().name().toLowerCase()
                + "\nTime: " + MOMENT.format(Instant.ofEpochMilli(stamp.time()))
                + "\nAuthor: " + StampNames.name(view, stamp.authorNid())
                + "\nModule: " + StampNames.name(view, stamp.moduleNid())
                + "\nPath: " + StampNames.name(view, stamp.pathNid());
    }

    private void syncPreferences() {
        try {
            preferences().sync();
        } catch (Exception e) {
            LOG.warn("Could not sync the STAMPed Document card preferences", e);
        }
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
            KlPreferencesFactory cardPreferencesFactory = KlPreferencesFactory.create(windowPreferences, StampedDocumentCard.class);
            StampedDocumentCard card = new CardFactory().create(cardPreferencesFactory);
            card.setJournalTopic(journalTopic);
            return card;
        }

        @Override
        public AbstractHostCard restoreCard(KometPreferences windowPreferences) {
            KometPreferences cardNode = windowPreferences.node(StampedDocumentCard.class.getSimpleName());
            StampedDocumentCard card = new CardFactory().restore(cardNode);
            card.revert();
            return card;
        }
    }

    /** Blueprint factory building the card shell (the {@code KlArea.Factory} the base needs). */
    private static final class CardFactory implements CardBlueprint.Factory<StampedDocumentCard> {
        @Override
        public StampedDocumentCard restore(KometPreferences preferences) {
            return new StampedDocumentCard(preferences);
        }

        @Override
        public StampedDocumentCard create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
            StampedDocumentCard card = new StampedDocumentCard(preferencesFactory, this);
            card.setAreaLayout(areaGridSettings.with(this.getClass()));
            return card;
        }
    }
}
