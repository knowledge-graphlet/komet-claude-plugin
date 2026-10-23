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
import dev.ikm.komet.actions.KometActions;
import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.help.KlHelpTopic;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.CardBlueprint;
import dev.ikm.komet.layout_engine.host.AbstractHostCard;
import dev.ikm.komet.layout_engine.host.KlCardProvider;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.StampEntity;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import network.ike.komet.claude.doc.DocumentSurfaceArea;
import network.ike.komet.claude.help.PluginHelp;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.print.DocumentFonts;
import network.ike.komet.claude.stamp.print.DocumentModel;
import network.ike.komet.claude.stamp.print.GreedyFxOracle;
import network.ike.komet.claude.stamp.print.LayoutOracle;
import network.ike.komet.claude.stamp.print.PageFidelity;
import network.ike.komet.claude.stamp.print.TypstOracle;
import network.ike.komet.claude.stamp.print.TypstWriter;
import network.ike.komet.claude.stamp.print.PrintBlocks;
import network.ike.komet.claude.stamp.print.StoredDocumentModel;
import network.ike.komet.claude.stamp.print.TypstPdf;
import network.ike.komet.claude.doc.print.PrintSource;
import network.ike.komet.claude.doc.print.PagedDocumentEngine;
import network.ike.komet.claude.doc.print.settings.MarginPreset;
import network.ike.komet.claude.doc.print.settings.PrintSettings;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * The Stamped Document card ({@code IKE-Network/ike-issues#1307}; ike-lab-documents topic
 * {@code dev-stamped-document-surface}): one card, a Reading view and a Source view of the same
 * {@link StoredDocument}, and a Provenance layer over either. The document is what the
 * {@link GitDocumentMaterializer} wrote into the knowledge base, never the file; the repository
 * is consulted only for commit subjects and for the annotation trailers a person signs.
 *
 * <p>The toolbar is contextual: the document chooser, the view switch, the layer menu and an
 * overflow menu are always there; Commit… and Discard edit appear with an "edited" badge while
 * an edit is pending, and Restore as pending while a cancelled edit can be restored. The acts
 * on a selection — Affirm, Refute, Comment, Edit block, Show history — are the context menu of
 * the selection, in {@link DocumentSurfaceView}. The assistant's {@link StampTools} default to
 * the selection and hand their proposals to {@link #proposeAnnotation}.
 *
 * <p>A document opened from a repository (Open file… in the overflow) and one chosen from the
 * store are one choice: the card materializes the file if the store lacks it, remembers the
 * working tree, and shows the store's copy.
 *
 * <p>An assembly opens as one document: the host's include lines that name documents the store
 * holds (by their recorded path under the same working tree) show those children in place, each
 * still its own document ({@link DocumentAssembly}). A selection is located to the one member it
 * lies in, so an annotation's trailers name the child's file; the footer follows the member
 * under the viewport; an edit in place inside a child edits that child's block, and Commit…
 * commits the member whose edit is pending.
 *
 * <p>The card builds no body of its own: it hosts the plugin's {@link DocumentSurfaceArea} as the
 * Claude card does, and the view stacks the document's blocks on that area's surface, with the
 * title and section in the surface's header, the status line, document id and commit in its
 * footer, dates in the left margin and the identicon marginalia in the right. The area's
 * expansion is the paged preview and its preferences are the print settings.
 */
public final class StampedDocumentCard extends AbstractHostCard implements DocumentSurfaceView.Host, RepositoryPanel.Host {

    private static final Logger LOG = LoggerFactory.getLogger(StampedDocumentCard.class);

    static final String CARD_NAME = "STAMPed Document";

    private static final String PREF_DOCUMENT = "network.ike.komet.claude.stamp.document";
    /** The working tree a document was last opened from, committed or exported into. */
    private static final String PREF_WORKING_TREE = "network.ike.komet.claude.stamp.workingTree";
    private static final String PREF_VIEW = "network.ike.komet.claude.stamp.view";
    private static final String PREF_GRAPH_MODE = "network.ike.komet.claude.stamp.graphMode";
    /** Whether the Graph view's DocBook mode shows stored sentences as nodes (IKE-Network/ike-issues#1313). */
    private static final String PREF_GRAPH_SENTENCES = "network.ike.komet.claude.stamp.graphSentences";
    /** In the Layer menu: sentences as nodes in DocBook mode, or flattened into mixed content. */
    private final CheckMenuItem sentencesItem = new CheckMenuItem("Sentences in DocBook mode");
    private static final String PREF_LAYER = "network.ike.komet.claude.stamp.layer.";

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

    /** Re-reads the shown document in every live card, after something changed the store; call on the FX thread. */
    public static void reloadAll() {
        for (WeakReference<StampedDocumentCard> ref : OPEN) {
            StampedDocumentCard card = ref.get();
            if (card == null) {
                OPEN.remove(ref);
            } else {
                card.refresh();
            }
        }
    }

    private final BorderPane host = new BorderPane();
    /**
     * The host's center: the document area, which never leaves it, under the Graph view or the
     * pages while either shows. A layout area forgets itself, erasing its preferences node, when
     * its node leaves the scene, so swapping it out lost the print settings and failed every
     * later save of the card.
     */
    private final StackPane center = new StackPane();
    private final Label statusLine = new Label("Choose a document the store holds, or open a committed .adoc file.");
    /** The documents the store holds; a combo box so an empty one can say so rather than show a blank. */
    private final ComboBox<String> documents = new ComboBox<>();
    /** True while the chooser is being brought in line with a document already shown, so choosing it does not reopen it. */
    private boolean listingShown;
    /** Below this card width the toolbar's controls show their icons alone; above it, icon and name. */
    private static final double LABELED_TOOLBAR_WIDTH = 860;
    /** The same, while the Graph view's modes are on the toolbar too. */
    private static final double LABELED_TOOLBAR_WIDTH_WITH_MODES = 1120;
    /** The three views as one segmented control: exactly one selected, arrow keys move it. */
    private final SegmentedControl<DocumentSurfaceView.View> views = new SegmentedControl<>(
            List.of(DocumentSurfaceView.View.SOURCE, DocumentSurfaceView.View.READING, DocumentSurfaceView.View.PAGE_LAYOUT, DocumentSurfaceView.View.GRAPH),
            v -> switch (v) {
                case SOURCE -> "Source";
                case READING -> "Reading";
                case PAGE_LAYOUT -> "Page layout";
                case GRAPH -> "Graph";
            },
            v -> switch (v) {
                case SOURCE -> "The document as lines: number, introducing STAMP, mark, text";
                case READING -> "The document as a document";
                case PAGE_LAYOUT -> "The document on pages, as Print to PDF makes them through Typst: shown at once from JavaFX's own line breaks, settled on Typst's";
                case GRAPH -> "The document as the store holds it: its sections and blocks, their lines, the lines' references, each with its STAMP; an included document under its include line";
            },
            v -> switch (v) {
                case SOURCE -> "stampedDocumentSourceView";
                case READING -> "stampedDocumentReadingView";
                case PAGE_LAYOUT -> "stampedDocumentPageLayoutView";
                case GRAPH -> "stampedDocumentGraphView";
            },
            v -> switch (v) {
                case SOURCE -> "mdi2c-code-tags";
                case READING -> "mdi2b-book-open-outline";
                case PAGE_LAYOUT -> "mdi2f-file-document-outline";
                case GRAPH -> "mdi2f-file-tree-outline";
            });
    private PageStackView pageStack;
    /** The Graph view: a tree over the shown assembly, in the surface's place while it is chosen. */
    private DocumentGraphView graph;
    /** The Graph view's three modes as one segmented control, on the toolbar while the graph is shown. */
    private final SegmentedControl<DocumentGraphView.Mode> modes = new SegmentedControl<>(
            List.of(DocumentGraphView.Mode.LATEST, DocumentGraphView.Mode.HISTORY, DocumentGraphView.Mode.DOCBOOK),
            m -> switch (m) {
                case LATEST -> "Latest";
                case HISTORY -> "History";
                case DOCBOOK -> "DocBook";
            },
            m -> switch (m) {
                case LATEST -> "The document as it stands";
                case HISTORY -> "With its history: a line's versions beneath it, the lines later commits retired where they stood, the commits that touched each block";
                case DOCBOOK -> "As DocBook elements: markup lines folded into the blocks they title and attribute, a paragraph's lines joined into text and inline elements";
            },
            m -> switch (m) {
                case LATEST -> "stampedDocumentGraphLatest";
                case HISTORY -> "stampedDocumentGraphHistory";
                case DOCBOOK -> "stampedDocumentGraphDocBook";
            },
            m -> switch (m) {
                case LATEST -> "mdi2c-clock-check-outline";
                case HISTORY -> "mdi2h-history";
                case DOCBOOK -> "mdi2f-format-section";
            });
    /** Each member's history, read once per open while the History mode asks for it. */
    private final Map<String, DocumentHistory> histories = new HashMap<>();
    /** What the store says each node of the graph is, read once per open as the Graph view asks. */
    private final Map<Long, Optional<StoredNodeFacts.NodeFacts>> facts = new HashMap<>();
    /** The page stack's units, and the two layouts of them: JavaFX's estimate, shown at once, and Typst's, settled on when it returns. */
    private List<LayoutOracle.Unit> pageUnits = List.of();
    private LayoutOracle.Geometry pageGeometry;
    private LayoutOracle.Layout greedyLayout;
    private LayoutOracle.Layout typstLayout;
    private Path oracleFolder;
    /** Advanced at every page build; a Typst answer for an older build is dropped. */
    private int pageGeneration;
    private final Label settling = new Label("settling…");
    private final Label pageIndicator = new Label();
    private final MenuButton layerMenu = new MenuButton("Layer");
    private final MenuButton overflow = new MenuButton("More");
    private final Label editedBadge = new Label("edited");
    private final Button commit = new Button("Commit…");
    private final Button discard = new Button("Discard edit");
    private final Button restore = new Button("Restore as pending");
    private final Map<DocumentSurfaceView.Layer, CheckMenuItem> layerItems = new HashMap<>();
    private final Label footerIdentity = new Label();
    private HBox footer;
    private RepositoryPanel repositoryPanel;
    private boolean pendingEdit;
    private DocumentSurfaceArea documentArea;
    private DocumentSurfaceView surface;
    private Map<String, PublicId> anchors = new LinkedHashMap<>();
    /** The host document: the one the person opened. */
    private StoredDocument shown;
    /** The host with its children shown in place. */
    private DocumentAssembly assembly;
    /** What was read for each member, by recorded path: committed numbers and text, pending edit, summaries. */
    private Map<String, Loaded> members = Map.of();
    /** Every member's summaries as one, for the surface's popovers and tooltips. */
    private CommitSummaries summaries = CommitSummaries.NONE;
    private String shownPath;
    private Path repoRoot;
    /** A report for the status line once the chooser's next open has drawn the document. */
    private String reportAfterOpen;
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

    @Override
    protected Optional<KlHelpTopic> helpTopic() {
        return Optional.of(PluginHelp.STAMPED_DOCUMENT);
    }

    /** @return the name of the document shown, if one is */
    public Optional<String> currentDocument() {
        return Optional.ofNullable(documents.getValue());
    }

    /** @return the document as loaded from the store, if one is shown */
    public Optional<StoredDocument> shown() {
        return Optional.ofNullable(shown);
    }

    /** @return the working tree the shown document's file is known in, or {@code null} */
    public Path repoRoot() {
        return repoRoot;
    }

    /** @return the shown document's file relative to its repository root, when a working tree is known */
    public Optional<Path> currentFile() {
        return repoRoot == null || shownPath == null ? Optional.empty() : Optional.of(Path.of(shownPath));
    }

    /** @return whether this card shows exactly that file of that repository */
    public boolean showsFile(Path root, Path file) {
        return repoRoot != null && shownPath != null && repoRoot.equals(root) && Path.of(shownPath).equals(file);
    }

    /** @return the assembly shown: the host with its children in place, if a document is shown */
    public Optional<DocumentAssembly> assembly() {
        return Optional.ofNullable(assembly);
    }

    /**
     * @return the selected lines as 1-based inclusive ranges of the committed file they lie in
     *         (the host's or a child's); empty without a selection, or when it spans two members
     */
    public List<int[]> selectedLineNumbers() {
        return located().map(l -> l.local().ranges(o -> committedNumber(l.member(), o))).orElse(List.of());
    }

    /** @return the file the selection lies in, relative to the repository root: a child's or the host's */
    public Optional<Path> selectedFile() {
        return located().map(l -> Path.of(l.member().path()));
    }

    /** The selection located to its one member, if there is one and it lies in one member. */
    private Optional<DocumentAssembly.Located> located() {
        if (surface == null || assembly == null) {
            return Optional.empty();
        }
        return surface.selection().flatMap(assembly::locate);
    }

    /** The committed line number of a member's line by its index in that member, or 0. */
    private int committedNumber(DocumentAssembly.Member member, int index) {
        Loaded loaded = members.get(member.path());
        if (loaded == null || index < 0 || index >= member.document().lines().size()) {
            return 0;
        }
        return committedNumber(member.document().lines().get(index), loaded.committedNumbers());
    }

    /**
     * The 1-based number of a line in the committed file, or 0 when the file does not hold it or
     * its text shown is an uncommitted edit: an annotation names committed text, so an edited
     * line is annotated once the edit is committed, as the ledger's banner always said.
     *
     * @param line      the line as shown
     * @param committed the committed numbers by nid
     * @return the number, or 0
     */
    static int committedNumber(StoredLine line, Map<Long, Integer> committed) {
        return DocumentSurfaceView.uncommitted(line) ? 0 : committed.getOrDefault(line.nid(), 0);
    }

    /** The surface ordinal of the host's line with that committed number, or -1. */
    private int ordinalOf(int number) {
        return assembly == null ? -1 : ordinalOf(assembly.host(), number);
    }

    /** The surface ordinal of a member's line with that committed number, or -1. */
    private int ordinalOf(DocumentAssembly.Member member, int number) {
        Loaded loaded = members.get(member.path());
        if (loaded == null) {
            return -1;
        }
        for (Map.Entry<Long, Integer> e : loaded.committedNumbers().entrySet()) {
            if (e.getValue() == number) {
                return surface.ordinal(e.getKey());
            }
        }
        return -1;
    }

    /**
     * Selects a range of the host file's committed lines, 1-based inclusive, as a person's drag
     * would. Public so the assistant's hand-off and UI automation can select without the pointer.
     *
     * @param from the first line
     * @param to   the last line
     * @return whether any of the lines is shown
     */
    public boolean selectLines(int from, int to) {
        LineSelection wanted = LineSelection.covering(List.of(new int[] {from, to}), this::ordinalOf);
        if (wanted == null) {
            return false;
        }
        surface.select(wanted);
        return true;
    }

    /**
     * Selects a range of a member file's committed lines — the host's or a child's shown in
     * place — 1-based inclusive.
     *
     * @param file the file, relative to the repository root
     * @param from the first line
     * @param to   the last line
     * @return whether any of the lines is shown
     */
    public boolean selectLines(Path file, int from, int to) {
        if (assembly == null) {
            return false;
        }
        String path = file.toString().replace('\\', '/');
        DocumentAssembly.Member member = assembly.byPath().get(path);
        if (member == null) {
            return false;
        }
        LineSelection wanted = LineSelection.covering(List.of(new int[] {from, to}), n -> ordinalOf(member, n));
        if (wanted == null) {
            return false;
        }
        surface.select(wanted);
        return true;
    }

    // ── toolbar ───────────────────────────────────────────────────────────────

    @Override
    protected void buildToolbarControls(HBox toolBar) {
        documents.setId("stampedDocumentChooser");
        documents.setTooltip(new Tooltip("The documents the knowledge base holds"));
        documents.setMaxWidth(300);
        documents.getStyleClass().add("stamped-toolbar-control");
        documents.setPromptText("No documents");
        documents.getItems().addListener((javafx.collections.ListChangeListener<String>) change ->
                documents.setPromptText(documents.getItems().isEmpty() ? "No documents" : "Choose a document"));
        documents.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
            if (listingShown) {
                return;                                    // the chooser catching up with a document already shown
            }
            if (is != null && anchors.containsKey(is)) {
                preferences().put(PREF_DOCUMENT, is);
                syncPreferences();
                String report = reportAfterOpen;
                reportAfterOpen = null;
                open(anchors.get(is), report, null);
            }
        });
        views.setId("stampedDocumentViews");
        views.valueProperty().addListener((obs, was, is) -> {
            preferences().put(PREF_VIEW, is.name());
            syncPreferences();
            if (surface != null) {
                surface.setView(is);
                showCenter(is);
            }
        });
        modes.setId("stampedDocumentGraphModes");
        modes.setVisible(false);
        modes.setManaged(false);
        modes.valueProperty().addListener((obs, was, is) -> {
            preferences().put(PREF_GRAPH_MODE, is.name());
            syncPreferences();
            if (graph != null) {
                graph.setMode(is);
            }
        });
        layerMenu.setId("stampedDocumentLayerMenu");
        Icons.iconOnly(layerMenu, "mdi2l-layers-outline", "What the Provenance layer draws over the document");
        layerMenu.getStyleClass().add("stamped-toolbar-control");
        for (DocumentSurfaceView.Layer layer : DocumentSurfaceView.Layer.values()) {
            CheckMenuItem item = new CheckMenuItem(switch (layer) {
                case MARGINALIA -> "Marginalia";
                case TINTS -> "Commit tints";
                case REVIEW_QUEUE -> "Review queue";
                case ANNOTATION_MARKS -> "Annotation marks";
            });
            item.setId("stampedDocumentLayer" + layer.name());
            item.setSelected(preferences().getBoolean(PREF_LAYER + layer.name(), layer != DocumentSurfaceView.Layer.TINTS && layer != DocumentSurfaceView.Layer.REVIEW_QUEUE));
            item.selectedProperty().addListener((obs, was, is) -> {
                preferences().putBoolean(PREF_LAYER + layer.name(), is);
                syncPreferences();
                if (surface != null) {
                    surface.setLayer(layer, is);
                }
                if (graph != null) {
                    graph.relayer();
                }
            });
            layerItems.put(layer, item);
            layerMenu.getItems().add(item);
        }
        sentencesItem.setId("stampedDocumentGraphSentences");
        sentencesItem.setSelected(preferences().getBoolean(PREF_GRAPH_SENTENCES, true));
        sentencesItem.selectedProperty().addListener((obs, was, is) -> {
            preferences().putBoolean(PREF_GRAPH_SENTENCES, is);
            syncPreferences();
            if (graph != null) {
                graph.setSentences(is);
            }
        });
        layerMenu.getItems().addAll(new SeparatorMenuItem(), sentencesItem);
        overflow.setId("stampedDocumentOverflow");
        Icons.iconOnly(overflow, "mdi2d-dots-horizontal", "Verbs that act on the whole document");
        overflow.getStyleClass().add("stamped-toolbar-control");
        overflow.getItems().addAll(
                item("Open file…", "stampedDocumentOpenFile", "Choose a committed .adoc file in a git repository; it is materialized if the store lacks it", this::chooseFile),
                item("Refresh", "stampedDocumentRefresh", "Re-read the documents the store holds, and the one shown", this::refresh),
                item("Export…", "stampedDocumentExport", "Write the document from the store back into a repository's working tree at its recorded path, and compare it with HEAD", this::chooseExportTarget),
                item("Print to PDF…", "stampedDocumentPrintPdf", "Write the document, with the documents it includes, as Typst from the store's blocks and compile it with your installed typst", () -> printToPdf()),
                new SeparatorMenuItem(),
                item("Materialize", "stampedDocumentMaterialize", "Write the commits of this file the knowledge base lacks", () -> materialize(false)),
                item("Materialize All", "stampedDocumentMaterializeAll", "Write this file and every file it includes, each as its own document", () -> materialize(true)),
                item("Rebuild from Git", "stampedDocumentRebuild", "Drop this document from the knowledge base and materialize it again from git, which holds all an ephemeral document is", this::rebuildFromGit),
                item("Remove Superseded Documents", "stampedDocumentRemoveSuperseded", "Delete the prose-element documents that vertex documents of the same working tree and path replace", this::removeSuperseded),
                new SeparatorMenuItem(),
                item("Show history", "stampedDocumentHistory", "The STAMPs of the selected lines, or of the whole document, in order", this::showHistory),
                new SeparatorMenuItem(),
                item("Go to page…", "stampedDocumentGoToPage", "Scroll the Page layout to a page by its number", this::goToPage));
        editedBadge.setId("stampedDocumentEditedBadge");
        editedBadge.setStyle("-fx-text-fill: #FFE082; -fx-font-weight: bold;");
        editedBadge.setTooltip(new Tooltip("An edit made in Komet is pending: Commit… records it in git, Discard edit cancels it"));
        commit.setId("stampedDocumentCommit");
        commit.setTooltip(new Tooltip("Write the document with your edit into its working tree and record it there as your own git commit"));
        commit.getStyleClass().add("claude-card-toolbar-button");
        commit.setOnAction(e -> commitPending());
        discard.setId("stampedDocumentDiscard");
        discard.setTooltip(new Tooltip("Cancel the edit made in Komet, so the document reads as committed again"));
        discard.getStyleClass().add("claude-card-toolbar-button");
        discard.setOnAction(e -> discardPending());
        restore.setId("stampedDocumentRestore");
        restore.setTooltip(new Tooltip("Bring back the lines of a cancelled edit that the commit did not carry, as a pending edit over the document as now committed"));
        restore.getStyleClass().add("claude-card-toolbar-button");
        restore.setOnAction(e -> restorePending());
        HBox pendingGroup = new HBox(4, editedBadge, commit, discard, restore);
        pendingGroup.setId("stampedDocumentPendingGroup");
        pendingGroup.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        showPending(false, false);
        toolBar.getChildren().addAll(documents, views, modes, layerMenu, overflow, pendingGroup);
        // Names beside the icons while the card is wide enough for them; icons alone when it is not.
        fxObject().widthProperty().addListener((obs, was, is) -> labelToolbar());
        modes.visibleProperty().addListener((obs, was, is) -> labelToolbar());
        labelToolbar();
    }

    /** Shows each toolbar control's name beside its icon when the card is wide enough, its icon alone otherwise. */
    private void labelToolbar() {
        double needed = modes.isVisible() ? LABELED_TOOLBAR_WIDTH_WITH_MODES : LABELED_TOOLBAR_WIDTH;
        boolean compact = fxObject().getWidth() < needed;
        views.setCompact(compact);
        modes.setCompact(compact);
        Icons.compact(layerMenu, compact);
        Icons.compact(overflow, compact);
    }

    private static MenuItem item(String text, String id, String tooltip, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setId(id);
        item.setOnAction(e -> action.run());
        return item;
    }

    /** Shows the contextual group: the badge, Commit… and Discard edit while an edit is pending; Restore while one is restorable. */
    private void showPending(boolean pending, boolean restorable) {
        for (Node n : List.of(editedBadge, commit, discard)) {
            n.setVisible(pending);
            n.setManaged(pending);
        }
        restore.setVisible(restorable);
        restore.setManaged(restorable);
    }

    @Override
    protected void renderContent() {
        if (built) {
            return;
        }
        built = true;
        OPEN.add(new WeakReference<>(this));
        // The Document surface (#808) as a KlArea (#839), on a stable child preferences node, as the
        // Claude card hosts it: the area owns the print settings and the expand-to-paged-preview
        // affordance; the view stacks the document's blocks on the area's surface.
        documentArea = DocumentSurfaceArea.restore(preferences().node(DocumentSurfaceArea.class));
        // The pages' running head: the document's title, as the Reading view's header and the PDF name it; the
        // store's name for the document before a title is known.
        documentArea.setRunningHeadSupplier(() -> shown != null && !shown.attributes().isEmpty() && !shown.attributes().getFirst().isBlank()
                ? shown.attributes().getFirst() : documents.getValue() == null ? "" : documents.getValue());
        documentArea.restoreSettings();
        surface = new DocumentSurfaceView(this, documentArea.surface());
        graph = new DocumentGraphView(graphHost());
        documentArea.surface().setPrintSource(this::printSource);
        for (Map.Entry<DocumentSurfaceView.Layer, CheckMenuItem> e : layerItems.entrySet()) {
            surface.setLayer(e.getKey(), e.getValue().isSelected());
        }
        String rememberedView = preferences().get(PREF_VIEW, "");
        for (DocumentSurfaceView.View candidate : DocumentSurfaceView.View.values()) {
            if (candidate.name().equals(rememberedView)) {
                views.select(candidate);
            }
        }
        if (views.getValue() != surface.view()) {
            surface.setView(views.getValue());
        }
        String rememberedMode = preferences().get(PREF_GRAPH_MODE, "");
        for (DocumentGraphView.Mode candidate : DocumentGraphView.Mode.values()) {
            if (candidate.name().equals(rememberedMode)) {
                modes.select(candidate);
            }
        }
        graph.setSentences(sentencesItem.isSelected());
        graph.setMode(modes.getValue());
        statusLine.setId("stampedDocumentStatus");
        statusLine.setWrapText(true);
        statusLine.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLine, javafx.scene.layout.Priority.ALWAYS);
        footerIdentity.setId("stampedDocumentFooterIdentity");
        footerIdentity.setStyle("-fx-text-fill: #5f6b7a;");
        footerIdentity.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        settling.setId("stampedDocumentSettling");
        settling.setStyle("-fx-text-fill: #8a8782; -fx-font-style: italic;");
        settling.setTooltip(new Tooltip("Typst is laying the pages out; the lines re-flow to its breaks when it answers"));
        settling.setVisible(false);
        settling.setManaged(false);
        pageIndicator.setId("stampedDocumentPageIndicator");
        pageIndicator.setStyle("-fx-text-fill: #5f6b7a;");
        pageIndicator.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        pageIndicator.setVisible(false);
        pageIndicator.setManaged(false);
        HBox footer = new HBox(12, statusLine, settling, pageIndicator, footerIdentity);
        footer.setId("stampedDocumentFooter");
        footer.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        footer.setPadding(new Insets(4, 12, 4, 12));
        this.footer = footer;
        documentArea.surface().setFooter(footer);
        repositoryPanel = new RepositoryPanel(this);
        host.setTop(repositoryPanel);
        center.getChildren().setAll(documentArea.fxObject());
        host.setCenter(center);
        host.setBottom(null);
        // On the card's root, not its body: the toolbar is part of the card's chrome, outside the
        // body, and was left in JavaFX's default look while the sheets sat on the body alone.
        for (String sheet : List.of("/network/ike/komet/claude/claude-card.css", "/network/ike/komet/claude/stamp/stamped-document.css")) {
            URL css = StampedDocumentCard.class.getResource(sheet);
            if (css != null && !fxObject().getStylesheets().contains(css.toExternalForm())) {
                fxObject().getStylesheets().add(css.toExternalForm());
            }
        }
        setCardContent(host);
        String remembered = preferences().get(PREF_WORKING_TREE, "");
        if (!remembered.isEmpty() && Files.isDirectory(Path.of(remembered))) {
            repoRoot = Path.of(remembered);
        }
        repositoryPanel.refresh();
        refresh();
    }

    // ── the Host ──────────────────────────────────────────────────────────────

    /** The card's view, or the default one before the card is hosted. */
    @Override
    public ViewCalculator view() {
        return getCardViewProperties() != null ? getCardViewProperties().calculator() : Calculators.View.Default();
    }

    @Override
    public CommitSummaries summaries() {
        return summaries;
    }

    @Override
    public void status(String text) {
        statusLine.setText(text);
    }

    @Override
    public void selectionChanged() {
        // The acts on a selection live in its context menu; nothing on the toolbar changes. The graph follows it.
        if (graph != null) {
            graph.showSelection();
        }
    }

    @Override
    public void showingMember(DocumentAssembly.Member member) {
        if (member == null) {
            return;
        }
        String commit = member.document().commit();
        footerIdentity.setText((member.path().isEmpty() ? documents.getValue() : member.path()) + " @ "
                + (commit.isEmpty() ? "?" : commit.substring(0, Math.min(7, commit.length()))));
    }

    // ── loading ───────────────────────────────────────────────────────────────

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
                shown = null;
                statusLine.setText("The knowledge base holds no materialized documents yet. Open file… materializes one.");
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
        open(anchor, null, null);
        listShown(anchor);
    }

    /**
     * Lists a document the card shows but its chooser does not, as one opened directly is: the
     * store's documents are read again and the shown one chosen, without opening it a second time,
     * so the chooser never says "No documents" over a document on the card.
     */
    private void listShown(PublicId anchor) {
        if (anchors.values().stream().anyMatch(known -> PublicId.equals(known, anchor))) {
            return;
        }
        CompletableFuture.supplyAsync(GitDocumentMaterializer::storedDocuments).whenComplete((found, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                LOG.warn("Listing stored documents failed", failure);
                return;
            }
            anchors = found;
            listingShown = true;
            try {
                documents.getItems().setAll(found.keySet());
                found.forEach((name, id) -> {
                    if (PublicId.equals(id, anchor)) {
                        documents.setValue(name);
                    }
                });
            } finally {
                listingShown = false;
            }
        }));
    }

    /**
     * What one open reads for one member off the FX thread.
     *
     * @param document         the document with any pending edit in it
     * @param committedNumbers the 1-based line number of each committed line, by nid
     * @param committedLines   the committed text, line by line: what the file at HEAD must equal for a trailer to anchor
     * @param path             the recorded path, or {@code null} when the store holds no git source
     * @param pending          whether an edit of it is pending in Komet
     * @param restorable       whether a cancelled edit of it can be restored
     * @param summaries        the commit subjects of its STAMPs
     */
    private record Loaded(StoredDocument document, Map<Long, Integer> committedNumbers, List<String> committedLines, String path,
                          boolean pending, boolean restorable, CommitSummaries summaries) {

        /** Reads one document; off the FX thread. The committed text is read again only when an edit is pending. */
        static Optional<Loaded> read(PublicId anchor, Path root, boolean host) {
            Optional<StoredDocument> loaded = GitDocumentMaterializer.load(anchor, true);
            if (loaded.isEmpty()) {
                return Optional.empty();
            }
            boolean pending = DocumentEditor.pending(anchor).isPresent();
            StoredDocument committed = pending ? GitDocumentMaterializer.load(anchor, false).orElse(loaded.get()) : loaded.get();
            Map<Long, Integer> numbers = new HashMap<>();
            for (int i = 0; i < committed.lines().size(); i++) {
                numbers.put(committed.lines().get(i).nid(), i + 1);
            }
            String path = GitDocumentMaterializer.recordedPath(anchor).orElse(null);
            Path relative = path == null ? null : Path.of(path);
            boolean rootHoldsFile = root != null && relative != null && Files.isRegularFile(root.resolve(relative));
            return Optional.of(new Loaded(loaded.get(), numbers, committed.lines().stream().map(StoredLine::text).toList(), path,
                    pending, host && !DocumentEditor.restorable(anchor).isEmpty(),
                    CommitSummaries.read(anchor, rootHoldsFile ? root : null, relative)));
        }
    }

    /** What one open reads off the FX thread: the host, its children by path, and the assembly over them. */
    private record Opened(Loaded host, Map<String, Loaded> members, DocumentAssembly assembly) { }

    /**
     * Reads the host and every document its includes resolve to, by recorded path: under a known
     * working tree the child's anchor is the identity its file materializes under; without one,
     * the store's documents of that path (the first when two repositories hold it).
     */
    private static Opened read(PublicId anchor, Path root) {
        Optional<Loaded> host = Loaded.read(anchor, root, true);
        if (host.isEmpty()) {
            return null;
        }
        Map<String, Loaded> loaded = new LinkedHashMap<>();
        Map<String, List<PublicId>> byPath = root == null ? GitDocumentMaterializer.storedDocumentsByPath() : Map.of();
        java.util.function.Function<String, Optional<StoredDocument>> store = path -> {
            Loaded known = loaded.get(path);
            if (known != null) {
                return Optional.of(known.document());
            }
            PublicId child = root != null ? GitDocumentMaterializer.documentAnchor(root, path)
                    : byPath.getOrDefault(path, List.of()).stream().findFirst().orElse(null);
            if (child == null || child.equals(anchor)) {
                return Optional.empty();
            }
            Optional<Loaded> read = Loaded.read(child, root, false);
            read.ifPresent(l -> loaded.put(path, l));
            return read.map(Loaded::document);
        };
        DocumentAssembly assembly = DocumentAssembly.of(host.get().document(), host.get().path(), store);
        Map<String, Loaded> members = new LinkedHashMap<>();
        members.put(assembly.host().path(), host.get());
        for (DocumentAssembly.Member child : assembly.children()) {
            Loaded l = loaded.get(child.path());
            if (l != null) {
                members.put(child.path(), l);
            }
        }
        return new Opened(host.get(), members, assembly);
    }

    /** As {@link #open(PublicId)}, then puts a report on the status line and runs {@code afterShow} once drawn. */
    private void open(PublicId anchor, String report, Runnable afterShow) {
        statusLine.setText("Reading from the store …");
        Path root = repoRoot;
        CompletableFuture.supplyAsync(() -> read(anchor, root)).whenComplete((loaded, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                statusLine.setText("Could not read the document: " + failure.getMessage());
                LOG.warn("Loading {} failed", anchor, failure);
                return;
            }
            if (loaded == null) {
                statusLine.setText("The store holds no manifest for that document.");
                return;
            }
            show(loaded);
            if (report != null) {
                statusLine.setText(report);
            }
            if (afterShow != null) {
                afterShow.run();
            }
        }));
    }

    private void show(Opened opened) {
        Loaded loaded = opened.host();
        shown = loaded.document();
        assembly = opened.assembly();
        members = opened.members();
        histories.clear();
        facts.clear();
        shownPath = loaded.path();
        summaries = CommitSummaries.merge(members.values().stream().map(Loaded::summaries).toList());
        surface.show(assembly);
        pendingEdit = members.values().stream().anyMatch(Loaded::pending);
        repositoryPanel.refresh(state -> state.remotes().stream().findFirst()
                .ifPresent(remote -> GitRepositoryTerms.record(shown.anchor(), remote.url(), state.branch())));
        showingMember(assembly.host());
        documentArea.refreshExpandedView();
        if (surface.view() == DocumentSurfaceView.View.PAGE_LAYOUT) {
            buildPages();                                              // the pages follow the document
        } else if (surface.view() == DocumentSurfaceView.View.GRAPH) {
            // As does the graph, with its modes and the centre: a load that ends after the person
            // chose Graph found no document then, so showCenter left the surface in place; a store
            // slower to read, as Rocks is, made that the usual case (IKE-Network/ike-issues#1313).
            showCenter(DocumentSurfaceView.View.GRAPH);
        }
        showPending(pendingEdit, loaded.restorable());
        int affirmed = (int) assembly.lines().stream().filter(l -> !l.affirmers().isEmpty()).count();
        int references = assembly.references().values().stream().mapToInt(List::size).sum();
        long edited = assembly.lines().stream().filter(DocumentSurfaceView::uncommitted).count();
        int children = assembly.children().size();
        List<DocumentAssembly.Include> unresolved = assembly.unresolved();
        String tail = shown.root() == null ? "no structure; re-materialize to add it"
                : assembly.members().stream().mapToInt(m -> m.document().blocks().size()).sum() + " blocks, " + references + " references, "
                + affirmed + " affirmed lines";
        statusLine.setText(status(shown, tail
                + (children == 0 ? "" : "; " + children + " included document(s) shown in place")
                + (unresolved.isEmpty() ? "" : "; " + unresolved.size() + " include(s) left as lines: "
                + unresolved.stream().map(DocumentAssembly.Include::target).limit(3).collect(Collectors.joining(", ")) + (unresolved.size() > 3 ? ", …" : ""))
                + (edited == 0 ? "" : "; " + edited + " line(s) edited in Komet, uncommitted: Commit… to record them in git")
                + (repoRoot == null || shownPath == null || !Files.isRegularFile(repoRoot.resolve(shownPath))
                ? "; no working tree holds the file: Open file… to annotate" : "")));
    }

    private String status(StoredDocument document, String tail) {
        String name = StampNames.name(view(), PrimitiveData.nid(document.anchor()));
        String commit = document.commit().isEmpty() ? "?" : document.commit().substring(0, Math.min(7, document.commit().length()));
        int lines = assembly == null ? document.lines().size() : assembly.lines().size();
        return name + " @ " + commit + " — " + lines + " lines, " + document.manifestVersions() + " manifest versions, " + tail;
    }

    // ── print ─────────────────────────────────────────────────────────────────

    /**
     * The shown document as the print writers read it: the host with each resolved child flowed
     * in place of its include line, the children's titles at their includes' level offsets.
     *
     * @return the model, empty before a document is shown
     */
    public Optional<DocumentModel> model() {
        if (shown == null) {
            return Optional.empty();
        }
        if (assembly == null) {
            return Optional.of(StoredDocumentModel.of(shown));
        }
        Map<Long, StoredDocumentModel.Child> includes = new HashMap<>();
        Map<DocumentAssembly.Member, Integer> offsets = new HashMap<>();
        for (DocumentAssembly.Include include : assembly.includes()) {   // surface order: an including member precedes its children
            if (!include.resolved()) {
                continue;
            }
            int offset = offsets.getOrDefault(include.in(), 0) + StoredDocumentModel.Child.levelOffset(include.line().text());
            offsets.put(include.child(), offset);
            includes.put(include.line().nid(), new StoredDocumentModel.Child(include.child().document(), offset));
        }
        return Optional.of(StoredDocumentModel.of(shown, includes));
    }

    /**
     * Puts the view's body in the card's centre: the Document surface for the Reading and Source
     * views, the page stack for the Page layout — laid out at once from JavaFX's greedy line
     * breaks, then settled on Typst's exact lines when typst answers.
     *
     * @param view the view to show
     */
    private void showCenter(DocumentSurfaceView.View view) {
        if (view == DocumentSurfaceView.View.PAGE_LAYOUT && shown != null) {
            buildPages();
            return;
        }
        pageStack = null;
        pageGeneration++;
        showSettling(false);
        pageIndicator.setVisible(false);
        pageIndicator.setManaged(false);
        boolean graphShown = view == DocumentSurfaceView.View.GRAPH && shown != null;
        modes.setVisible(graphShown);
        modes.setManaged(graphShown);
        if (graphShown) {
            graph.show(assembly);
            showOverArea(graph.node());
            host.setBottom(footer);                                    // the footer stays with the surface, which is not in the scene now
            return;
        }
        graph.show(null);
        showOverArea(null);
        host.setBottom(null);
    }

    /**
     * Shows a view in the document area's place, or the area again: the area stays in the scene,
     * hidden, so it is never detached.
     *
     * @param view the Graph view's or the pages' node, or {@code null} for the document area
     */
    private void showOverArea(Node view) {
        Node area = documentArea.fxObject();
        center.getChildren().removeIf(child -> child != area);
        if (view != null) {
            center.getChildren().add(view);
        }
        area.setVisible(view == null);
        area.setManaged(view == null);
    }

    /** What the Graph view asks of the card: the surface's selection and layer, the card's acts. */
    private DocumentGraphView.Host graphHost() {
        return new DocumentGraphView.Host() {
            @Override
            public ViewCalculator view() {
                return StampedDocumentCard.this.view();
            }

            @Override
            public CommitSummaries summaries() {
                return summaries;
            }

            @Override
            public Optional<StoredNodeFacts.NodeFacts> facts(DocumentGraph.Node node) {
                Loaded loaded = members.get(node.member().path());
                boolean pending = loaded != null && loaded.pending();
                return facts.computeIfAbsent(node.nid(), nid -> {
                    try {
                        return StoredNodeFacts.read(nid, view(), pending);
                    } catch (RuntimeException failure) {
                        LOG.warn("Reading node {} of {} failed", nid, node.member().path(), failure);
                        return Optional.empty();
                    }
                });
            }

            @Override
            public DocumentHistory history(DocumentAssembly.Member member) {
                Loaded loaded = members.get(member.path());
                boolean pending = loaded != null && loaded.pending();
                return histories.computeIfAbsent(member.path(), p -> {
                    try {
                        return DocumentHistory.load(member.document(), pending);
                    } catch (RuntimeException failure) {
                        LOG.warn("Reading the history of {} failed", member.path(), failure);
                        return DocumentHistory.NONE;
                    }
                });
            }

            @Override
            public void highlightCommit(long stampNid) {
                surface.highlightCommit(stampNid);
            }

            @Override
            public void status(String text) {
                StampedDocumentCard.this.status(text);
            }

            @Override
            public Image identicon(StampEntity<?> stamp) {
                return surface.badge(stamp);
            }

            @Override
            public boolean layer(DocumentSurfaceView.Layer layer) {
                return surface.layer(layer);
            }

            @Override
            public Optional<LineSelection> selection() {
                return surface.selection();
            }

            @Override
            public void select(LineSelection selection) {
                surface.select(selection);
            }

            @Override
            public void clearSelection() {
                surface.clearSelection();
            }

            @Override
            public void annotate(AnnotationKind kind) {
                StampedDocumentCard.this.annotate(kind);
            }

            @Override
            public void showHistory() {
                StampedDocumentCard.this.showHistory();
            }

            @Override
            public void editSelectedBlock() {
                surface.editSelectedBlock();
            }

            @Override
            public void editSentence(long sentence, String text) {
                StampedDocumentCard.this.askToEditSentence(sentence, text);
            }

            @Override
            public void showingMember(DocumentAssembly.Member member) {
                StampedDocumentCard.this.showingMember(member);
            }
        };
    }

    /** @return the Graph view, for automation and tests */
    public Optional<TreeView<DocumentGraphView.Row>> graphTree() {
        return surface != null && surface.view() == DocumentSurfaceView.View.GRAPH ? Optional.of(graph.tree()) : Optional.empty();
    }

    /** Lays the pages out: the greedy pass now, Typst's in the background. */
    private void buildPages() {
        int generation = ++pageGeneration;
        PrintSettings settings = documentArea.settings().get();
        double[] dims = PagedDocumentEngine.pageDimensionsPoints(settings);
        MarginPreset m = settings.margins();
        pageGeometry = new LayoutOracle.Geometry(dims[0], dims[1], m.left(), m.top(), dims[0] - m.left() - m.right(), dims[1] - m.top() - m.bottom());
        pageUnits = surface.pageUnits(settings.theme());
        GreedyFxOracle greedy = new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO);
        greedyLayout = greedy.layout(pageUnits, pageGeometry);
        typstLayout = null;
        surface.beginLineCheck(pageGeometry.contentLeft());
        surface.layoutLines(greedyLayout, pageUnits);
        pageStack = new PageStackView(settings, dims[0], dims[1], runningHead());
        pageStack.currentPageProperty().addListener((obs, was, is) -> showPageIndicator());
        pageStack.show(content());
        showOverArea(pageStack);
        // The footer stays with the document area's surface, which is not in the scene now: show it under the stack.
        host.setBottom(footer);
        pageIndicator.setVisible(true);
        pageIndicator.setManaged(true);
        showPageIndicator();
        surface.renderSelection();
        settle(generation, settings, greedy);
    }

    /** What the page stack shows: the surface's line areas and marginalia for the layout in force. */
    private PageStackView.Content content() {
        LayoutOracle.Layout layout = typstLayout != null ? typstLayout : greedyLayout;
        return new PageStackView.Content() {
            @Override
            public int pageCount() {
                return layout == null ? 1 : layout.pages();
            }

            @Override
            public List<PageStackView.Placed> lines(int page) {
                return surface.linesOn(page, pageGeometry.contentLeft(), pageGeometry.contentWidth(), 0);
            }

            @Override
            public List<PageStackView.Mark> marginalia(int page) {
                return surface.marginaliaOn(page);
            }

            @Override
            public String section(int page) {
                return surface.sectionOn(page);
            }

            @Override
            public int[] identity(PageStackView.Placed placed) {
                return surface.identity(placed);
            }
        };
    }

    /**
     * The second pass: writes the document as word-labelled Typst, asks typst where every word
     * fell, and re-flows the pages to its lines — keeping the line the reader is on where it is.
     */
    private void settle(int generation, PrintSettings settings, GreedyFxOracle greedy) {
        Optional<DocumentModel> model = model();
        if (model.isEmpty()) {
            return;
        }
        showSettling(true);
        List<LayoutOracle.Unit> units = pageUnits;
        LayoutOracle.Geometry geometry = pageGeometry;
        CompletableFuture.supplyAsync(() -> {
            try {
                Path folder = Files.createTempDirectory("stamped-document-layout-");
                Path source = folder.resolve("layout.typ");
                Files.writeString(source, TypstWriter.write(model.get(), TypstPdf.setup(settings), TypstWriter.Labels.WORDS), java.nio.charset.StandardCharsets.UTF_8);
                Path fonts = DocumentFonts.extractTo(folder.resolve("fonts"));
                LayoutOracle.Layout layout = new TypstOracle(folder, source, fonts, greedy::width).layout(units, geometry);
                dump(folder, units, layout);
                return Map.entry(folder, layout);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((answer, failure) -> Platform.runLater(() -> {
            if (generation != pageGeneration || pageStack == null) {
                return;                                              // the pages were rebuilt, or left, meanwhile
            }
            showSettling(false);
            if (failure != null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("The pages keep JavaFX's layout: Typst could not lay them out: " + cause.getMessage());
                LOG.warn("Typst layout failed", cause);
                return;
            }
            LayoutOracle.Layout layout = answer.getValue();
            if (layout.lines().isEmpty()) {
                statusLine.setText("The pages keep JavaFX's layout: " + String.join("; ", layout.notes()));
                return;
            }
            oracleFolder = answer.getKey();
            typstLayout = layout;
            Optional<PageStackView.Anchor> anchor = pageStack.anchor();
            surface.beginLineCheck(geometry.contentLeft());
            surface.layoutLines(layout, units);
            pageStack.show(content());
            anchor.ifPresent(pageStack::restore);
            surface.renderSelection();
            showPageIndicator();
            statusLine.setText("Pages settled on Typst's lines in " + layout.millis() + " ms"
                    + (layout.notes().isEmpty() ? "" : " (" + String.join("; ", layout.notes()) + ")"));
        }));
    }

    /** Writes the units and the lines the oracle made of them beside the source, for the fidelity report and for reading. */
    private static void dump(Path folder, List<LayoutOracle.Unit> units, LayoutOracle.Layout layout) throws IOException {
        StringBuilder u = new StringBuilder();
        for (LayoutOracle.Unit unit : units) {
            u.append(unit.index()).append('\t').append(unit.block()).append('\t').append(unit.kind()).append('\t')
                    .append(unit.text().replace("\n", "\\n")).append('\n');
        }
        Files.writeString(folder.resolve("units.txt"), u.toString(), java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder l = new StringBuilder();
        for (LayoutOracle.Line line : layout.lines()) {
            l.append(String.format(java.util.Locale.ROOT, "p%d u%d x%.1f y%.1f [%d,%d)%s\t%s%n", line.page() + 1, line.unit(), line.x(), line.baseline(),
                    line.from(), line.to(), line.hyphenated() ? " -" : "", line.text(units.get(line.unit())).replace("\n", "\\n")));
        }
        Files.writeString(folder.resolve("lines.txt"), l.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void showSettling(boolean on) {
        settling.setVisible(on);
        settling.setManaged(on);
    }

    private void showPageIndicator() {
        if (pageStack != null) {
            pageIndicator.setText("Page " + (pageStack.currentPageProperty().get() + 1) + " of " + pageStack.pageCount()
                    + (typstLayout == null ? "  ·  estimated" : ""));
        }
    }

    /** Go to page…: asks for a page number and scrolls the Page layout to it. */
    private void goToPage() {
        if (views.getValue() != DocumentSurfaceView.View.PAGE_LAYOUT) {
            views.select(DocumentSurfaceView.View.PAGE_LAYOUT);
        }
        if (pageStack == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        TextInputDialog dialog = new TextInputDialog(Integer.toString(pageStack.currentPageProperty().get() + 1));
        dialog.setTitle("Go to page");
        dialog.setHeaderText("Page 1 to " + pageStack.pageCount());
        dialog.setContentText("Page:");
        dialog.initOwner(window());
        dialog.showAndWait().ifPresent(text -> {
            try {
                goToPage(Integer.parseInt(text.strip()));
            } catch (NumberFormatException e) {
                statusLine.setText("Not a page number: " + text);
            }
        });
    }

    /**
     * Scrolls the Page layout to a page, public for automation; must be called on the JavaFX
     * application thread.
     *
     * @param page the 1-based page
     */
    public void goToPage(int page) {
        if (pageStack != null) {
            pageStack.scrollToPage(page - 1);
        }
    }

    private String runningHead() {
        return shown != null && !shown.attributes().isEmpty() && !shown.attributes().getFirst().isBlank() ? shown.attributes().getFirst()
                : documents.getValue() == null ? "" : documents.getValue();
    }

    @Override
    public void viewRequested(DocumentSurfaceView.View view) {
        views.select(view);
    }

    @Override
    public void revealPage(int page, double y) {
        if (pageStack != null) {
            pageStack.scrollTo(page, y, 40);
        }
    }

    /** @return the page stack, when the Page layout is shown */
    public Optional<PageStackView> pageStack() {
        return Optional.ofNullable(pageStack);
    }

    /** @return the 1-based page under the eye in the Page layout, 0 when it is not shown */
    public int currentPage() {
        return pageStack == null ? 0 : pageStack.currentPageProperty().get() + 1;
    }

    /** @return the Page layout's page count, 0 when it is not shown */
    public int pageCount() {
        return pageStack == null ? 0 : pageStack.pageCount();
    }

    /** @return whether the Page layout shows Typst's lines (the second pass has settled) */
    public boolean pagesSettled() {
        return pageStack != null && typstLayout != null;
    }

    /**
     * Measures the two passes against each other and against Typst's unlabelled output for the
     * shown document: the greedy JavaFX estimate against Typst's lines (page counts, breaks,
     * lines that agree), whether the word labels moved any block, and the oracle's latency.
     * Must be called on the JavaFX application thread once the pages have settled; public for
     * UI automation.
     *
     * @return the report text, or the reason it could not be made
     */
    public String measurePageFidelity() {
        Optional<DocumentModel> model = model();
        if (model.isEmpty()) {
            return "No document is shown.";
        }
        if (pageStack == null || greedyLayout == null) {
            return "Show the Page layout view first.";
        }
        if (typstLayout == null) {
            return "The pages have not settled on Typst's layout yet.";
        }
        PrintSettings settings = documentArea.settings().get();
        try {
            Path folder = oracleFolder != null ? oracleFolder : Files.createTempDirectory("stamped-document-fidelity-");
            PageFidelity.Report report = PageFidelity.compare(greedyLayout, typstLayout, pageUnits);
            String perturbation = PageFidelity.perturbation(model.get(), settings, folder);
            String text = "document: " + documents.getValue() + "\nsettings: " + settings + "\nfolder: " + folder + "\n"
                    + "typst oracle: " + typstLayout.millis() + " ms; greedy pass: " + greedyLayout.millis() + " ms\n"
                    + report.text() + perturbation + surface.lineCheck() + "\n";
            Files.writeString(folder.resolve("fidelity-report.txt"), text, java.nio.charset.StandardCharsets.UTF_8);
            return text;
        } catch (IOException e) {
            return "The measurement could not be made: " + e.getMessage();
        }
    }

    /** What the surface's paged preview paginates: the stored blocks as the Reading view draws them, with running head and folios. */
    private PrintSource printSource() {
        return model().map(PrintBlocks::source).orElse(PrintSource.EMPTY);
    }

    /**
     * Promotes the document area to its paged preview — what the area's expand affordance does —
     * so UI automation can reach the pages without the pointer. Must be called on the JavaFX
     * application thread.
     */
    public void showPages() {
        if (documentArea != null) {
            documentArea.expand();
        }
    }

    /**
     * Print to PDF: writes the shown document, with its children, as Typst from the store's
     * blocks under the surface's print settings, and compiles it with the installed typst. The
     * PDF's path, or typst's own error, goes on the status line. Public so automation can print
     * without the menu; must be called on the JavaFX application thread.
     *
     * @return the print once it has run; completes with {@code null} when no document is shown
     */
    public CompletableFuture<TypstPdf.Printed> printToPdf() {
        Optional<DocumentModel> model = model();
        if (model.isEmpty()) {
            statusLine.setText("Choose a document first.");
            return CompletableFuture.completedFuture(null);
        }
        String name = shownPath != null ? shownPath : documents.getValue();
        PrintSettings settings = documentArea.settings().get();
        statusLine.setText("Writing " + TypstPdf.stem(name) + ".typ and compiling it with typst …");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return TypstPdf.print(model.get(), settings, name);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((printed, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("Print to PDF failed: " + cause.getMessage());
                LOG.warn("Print to PDF failed", cause);
                return;
            }
            statusLine.setText(printed.report());
            if (!printed.ok()) {
                LOG.warn("typst compile failed for {}: {}", printed.source(), printed.result().output());
            }
        }));
    }

    // ── opening a file ────────────────────────────────────────────────────────

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open a committed AsciiDoc file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("AsciiDoc", "*.adoc", "*.asciidoc"));
        if (repoRoot != null && Files.isDirectory(repoRoot)) {
            chooser.setInitialDirectory(repoRoot.toFile());
        }
        File chosen = chooser.showOpenDialog(window());
        if (chosen != null) {
            open(chosen.toPath());
        }
    }

    /**
     * Opens a committed AsciiDoc file: resolves its repository, materializes the commits the
     * store lacks (asking whether to take the included files too when the file is an
     * assembly), remembers the working tree and shows the store's document. Public so the
     * assistant's tools and UI automation can open a file without the chooser; must be called
     * on the JavaFX application thread.
     *
     * @param file the file to open, inside a git working tree
     */
    public void open(Path file) {
        Optional<Path> root = GitRepo.findRoot(file);
        if (root.isEmpty()) {
            statusLine.setText(file + " is not inside a git working tree.");
            return;
        }
        Path relative = root.get().relativize(file.toAbsolutePath().normalize());
        int includes = 0;
        try (GitRepo repo = GitRepo.open(root.get())) {
            includes = GitDocumentMaterializer.includeCount(repo.headLines(relative));
        } catch (IOException | RuntimeException e) {
            statusLine.setText("Cannot read " + relative + " at HEAD: " + e.getMessage());
            return;
        }
        boolean assembly = false;
        if (includes > 0) {
            Alert choice = new Alert(Alert.AlertType.CONFIRMATION);
            choice.setTitle("Materialize an assembly");
            choice.setHeaderText(relative.getFileName() + " includes " + includes + " file" + (includes == 1 ? "" : "s") + ".");
            choice.setContentText("Materialize this file and every file it includes, each as its own document, in include order? "
                    + "An include that cannot be resolved is reported by name; the rest proceed.");
            ButtonType all = new ButtonType("Materialize All");
            ButtonType only = new ButtonType("This File Only");
            choice.getButtonTypes().setAll(all, only, ButtonType.CANCEL);
            choice.initOwner(window());
            Optional<ButtonType> chosen = choice.showAndWait();
            if (chosen.isEmpty() || chosen.get() == ButtonType.CANCEL) {
                return;
            }
            assembly = chosen.get() == all;
        }
        repoRoot = root.get();
        preferences().put(PREF_WORKING_TREE, repoRoot.toString());
        syncPreferences();
        repositoryPanel.refresh();
        materialize(repoRoot, relative, assembly);
    }

    /** Materializes the shown document's file, or asks for a file when no working tree holds it. */
    private void materialize(boolean assembly) {
        Optional<Path> file = currentFile();
        if (file.isEmpty() || !Files.isRegularFile(repoRoot.resolve(file.get()))) {
            statusLine.setText("No working tree holds the shown document's file; Open file… names one.");
            chooseFile();
            return;
        }
        materialize(repoRoot, file.get(), assembly);
    }

    /** Writes the file's history into the store off the FX thread and shows the resulting document. */
    private void materialize(Path root, Path relative, boolean assembly) {
        statusLine.setText("Materializing " + relative + (assembly ? " and its includes" : "") + " into the knowledge base …");
        record Materialized(String name, PublicId anchor, String report) { }
        CompletableFuture.supplyAsync(() -> {
            try {
                if (assembly) {
                    GitDocumentMaterializer.AssemblyResult result = GitDocumentMaterializer.materializeAssembly(root, root.resolve(relative));
                    int settled = 0;
                    for (GitDocumentMaterializer.Result document : result.documents()) {
                        DocumentEditor.Settlement settlement = DocumentEditor.settle(document.anchor());
                        if (settlement.settled() || settlement.rebased()) {
                            settled++;
                        }
                    }
                    StringBuilder report = new StringBuilder("Materialized assembly '" + result.assembly().name() + "': " + result.summary() + ".");
                    if (settled > 0) {
                        report.append(" Edits pending in Komet were settled in ").append(settled).append(" document(s).");
                    }
                    for (GitDocumentMaterializer.Unresolved u : result.unresolved()) {
                        report.append("\nNot resolved: ").append(u);
                    }
                    report.append(VertexMaterializer.fallbackNote());
                    return new Materialized(result.assembly().name(), result.assembly().anchor(), report.toString());
                }
                GitDocumentMaterializer.Result result = GitDocumentMaterializer.materialize(root, root.resolve(relative), true);
                DocumentEditor.Settlement settlement = DocumentEditor.settle(result.anchor());
                return new Materialized(result.name(), result.anchor(), "Materialized '" + result.name() + "': " + result.commits() + " commit(s) written, " + result.lines()
                        + " line(s), " + result.blocks() + " block version(s), " + result.references() + " reference(s), "
                        + result.annotations() + " annotation(s)."
                        + (settlement.settled() || settlement.rebased() ? " Edit in Komet: " + describe(settlement) : "")
                        + VertexMaterializer.fallbackNote());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).whenComplete((materialized, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("Materialization failed: " + cause.getMessage());
                LOG.warn("Materializing {} failed", relative, cause);
                return;
            }
            preferences().put(PREF_DOCUMENT, materialized.name());
            syncPreferences();
            String report = materialized.report();
            CompletableFuture.supplyAsync(GitDocumentMaterializer::storedDocuments).whenComplete((found, listFailure) -> Platform.runLater(() -> {
                if (listFailure != null) {
                    statusLine.setText(report);
                    return;
                }
                anchors = new LinkedHashMap<>(found);
                // Two repositories can hold a document of one name (a clone beside its origin): the
                // one just materialized is the one this name opens from now on.
                anchors.put(materialized.name(), materialized.anchor());
                documents.getItems().setAll(anchors.keySet());
                if (materialized.name().equals(documents.getValue())) {
                    open(materialized.anchor(), report, null);
                } else {
                    reportAfterOpen = report;
                    documents.setValue(materialized.name());         // the chooser's listener opens it with the report
                }
            }));
        }));
    }

    // ── ephemeral documents: rebuilt from git (IKE-Network/ike-issues#1313) ─────

    /**
     * Drops the shown document from the knowledge base and materializes its file again. Only an
     * ephemeral document is rebuilt: the repository holds its lines, their authors and the
     * annotations signed into commits, so nothing is lost; an edit pending in Komet refuses it.
     */
    private void rebuildFromGit() {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        Optional<Path> file = currentFile();
        if (file.isEmpty() || !Files.isRegularFile(repoRoot.resolve(file.get()))) {
            statusLine.setText("No working tree holds the shown document's file; Open file… names one.");
            return;
        }
        PublicId anchor = shown.anchor();
        Path root = repoRoot;
        Path relative = file.get();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Rebuild from Git");
        confirm.setHeaderText("Rebuild " + relative + " from git?");
        confirm.setContentText("The document is dropped from the knowledge base and its file's history materialized again. "
                + "Git holds everything an ephemeral document is: its lines, who wrote them, and the annotations signed into commits.");
        ButtonType rebuild = new ButtonType("Rebuild");
        confirm.getButtonTypes().setAll(rebuild, ButtonType.CANCEL);
        confirm.initOwner(window());
        if (confirm.showAndWait().filter(b -> b == rebuild).isEmpty()) {
            return;
        }
        statusLine.setText("Dropping " + relative + " from the knowledge base …");
        CompletableFuture.supplyAsync(() -> DocumentScopes.dropForRebuild(anchor)).whenComplete((dropped, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("Not rebuilt: " + cause.getMessage());
                return;
            }
            materialize(root, relative, false);
        }));
    }

    /** Deletes the prose-element documents that vertex documents of the same working tree and path replace. */
    private void removeSuperseded() {
        CompletableFuture.supplyAsync(GitDocumentMaterializer::superseded).whenComplete((superseded, failure) -> Platform.runLater(() -> {
            if (failure != null || superseded.isEmpty()) {
                statusLine.setText(failure != null ? "Could not list superseded documents: " + failure.getMessage()
                        : "No document is superseded: every prose-element document is the only one of its working tree and path.");
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Remove Superseded Documents");
            confirm.setHeaderText("Delete " + superseded.size() + " superseded document" + (superseded.size() == 1 ? "" : "s") + "?");
            confirm.setContentText("Each was materialized as prose elements and has since been materialized as vertices from the same "
                    + "working tree and path. The lists already show the vertex document; git holds what both were.");
            ButtonType delete = new ButtonType("Delete");
            confirm.getButtonTypes().setAll(delete, ButtonType.CANCEL);
            confirm.initOwner(window());
            if (confirm.showAndWait().filter(b -> b == delete).isEmpty()) {
                return;
            }
            statusLine.setText("Deleting " + superseded.size() + " superseded document(s) …");
            CompletableFuture.supplyAsync(DocumentScopes::deleteSuperseded).whenComplete((cleared, deleteFailure) -> Platform.runLater(() -> {
                if (deleteFailure != null) {
                    Throwable cause = deleteFailure.getCause() != null ? deleteFailure.getCause() : deleteFailure;
                    statusLine.setText("Not deleted: " + cause.getMessage());
                    return;
                }
                statusLine.setText("Deleted " + cleared.deleted() + " superseded document(s): " + cleared.components() + " entities, "
                        + cleared.stamps() + " STAMPs." + (cleared.skipped().isEmpty() ? "" : " Left " + cleared.skipped().size() + ": "
                        + cleared.skipped().getFirst()));
                refresh();
            }));
        }));
    }

    // ── export ────────────────────────────────────────────────────────────────

    private void chooseExportTarget() {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Working tree to export " + documents.getValue() + " into");
        File chosen = chooser.showDialog(window());
        if (chosen != null) {
            exportTo(chosen.toPath());
        }
    }

    /**
     * Writes the shown document from the store into a working tree at its recorded path and
     * reports how it relates to the file at HEAD there; off the FX thread.
     *
     * @param repoRoot the working tree root
     */
    public void exportTo(Path repoRoot) {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        PublicId anchor = shown.anchor();
        statusLine.setText("Exporting to " + repoRoot + " …");
        CompletableFuture.supplyAsync(() -> {
            try {
                GitDocumentExporter.Exported exported = GitDocumentExporter.export(anchor).orElseThrow();
                Path file = GitDocumentExporter.writeTo(exported, repoRoot);
                return file + " — " + describe(GitDocumentExporter.compare(exported, repoRoot));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).whenComplete((report, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                statusLine.setText("Export failed: " + failure.getMessage());
                LOG.warn("Exporting {} failed", anchor, failure);
                return;
            }
            statusLine.setText("Exported " + report);
        }));
    }

    private static String describe(GitDocumentExporter.Comparison comparison) {
        return switch (comparison.relation()) {
            case IDENTICAL_TO_HEAD -> "identical to HEAD, " + comparison.exportedLines() + " lines";
            case DIFFERS_FROM_HEAD -> "differs from HEAD at line " + comparison.firstDifference() + " (" + comparison.exportedLines()
                    + " exported lines, " + comparison.headLines() + " at HEAD)";
            case NOT_IN_HEAD -> "not in HEAD there, " + comparison.exportedLines() + " lines written";
        };
    }

    // ── annotating ────────────────────────────────────────────────────────────

    /** The annotation kinds' display names: "Comment" for the kind whose birth name only tells it apart in the graph. */
    static String displayName(AnnotationKind kind) {
        return kind == AnnotationKind.COMMENT ? "Comment" : kind.label();
    }

    @Override
    public void annotate(AnnotationKind kind) {
        annotateSelection(kind, null, null);
    }

    /**
     * The assistant's hand-off ({@link StampTools}): select the lines of the given ranges and
     * open the same confirmation the context menu opens, with the text prefilled for the person
     * to edit. The person's click is the annotation; nothing is written here.
     *
     * @param kind   the annotation kind
     * @param ranges 1-based inclusive line ranges of the committed file
     * @param text   the annotation text the person gave, or {@code null}
     */
    public void proposeAnnotation(AnnotationKind kind, List<int[]> ranges, String text) {
        Platform.runLater(() -> {
            LineSelection wanted = LineSelection.covering(ranges, this::ordinalOf);
            if (wanted == null) {
                statusLine.setText("None of the proposed lines is in the shown document.");
                return;
            }
            surface.select(wanted);
            annotateSelection(kind, "The assistant prepared this " + displayName(kind).toLowerCase() + " at your request.", text);
        });
    }

    /** What an annotation of the selection would record: the trailers over the committed file at HEAD. */
    record Proposal(List<Annotation> annotations, int lines, Path root, String head) {

        /** @return the trailers, one per range */
        String trailers() {
            return String.join("\n", annotations.stream().map(Annotation::trailer).toList());
        }
    }

    /**
     * Prepares the annotation of the given committed line ranges over the repository at HEAD:
     * refuses when the file is not in a known working tree or the file at HEAD is not the text
     * the store holds as committed, since the trailers must name the lines as the store numbers
     * them.
     *
     * @param kind      the kind
     * @param ranges    1-based inclusive ranges
     * @param root      the working tree
     * @param file      the file within it
     * @param committed the document's committed lines as the store holds them
     * @return the proposal
     * @throws IllegalStateException when the trailers cannot be anchored
     */
    static Proposal prepare(AnnotationKind kind, List<int[]> ranges, Path root, Path file, List<String> committed) {
        if (root == null || file == null || !Files.isRegularFile(root.resolve(file))) {
            throw new IllegalStateException("No working tree holds the document's file; Open file… names one.");
        }
        if (ranges.isEmpty()) {
            throw new IllegalStateException("Select committed lines to annotate.");
        }
        try (GitRepo repo = GitRepo.open(root)) {
            String head = repo.headRevision();
            List<String> content = repo.headLines(file);
            if (!content.equals(committed)) {
                throw new IllegalStateException(file + " at HEAD (" + head.substring(0, Math.min(7, head.length()))
                        + ") is not the text the store holds as committed; Materialize first.");
            }
            List<Annotation> annotations = new ArrayList<>();
            int lines = 0;
            for (int[] r : ranges) {
                if (r[1] > content.size()) {
                    throw new IllegalStateException("Lines " + r[0] + "-" + r[1] + " are beyond the " + content.size() + " lines of " + file + " at HEAD.");
                }
                annotations.add(Annotation.of(kind, LineRange.of(file, r[0], r[1], head, content)));
                lines += r[1] - r[0] + 1;
            }
            return new Proposal(annotations, lines, root, head);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file + " at HEAD: " + e.getMessage(), e);
        }
    }

    /**
     * Prepares the annotation of one sentence (IKE-Network/ike-issues#1313): a column-qualified
     * range naming exactly the sentence's characters in the committed file at HEAD, which the walk
     * lands on the sentence itself and on the lines it holds whole.
     *
     * @param kind      the kind
     * @param sentence  the sentence's text as stored, whitespace normalized
     * @param ranges    the committed lines the sentence spans, 1-based inclusive
     * @param root      the working tree
     * @param file      the file within it
     * @param committed the document's committed lines as the store holds them
     * @return the proposal
     * @throws IllegalStateException when the trailer cannot be anchored
     */
    static Proposal prepareSentence(AnnotationKind kind, String sentence, List<int[]> ranges, Path root, Path file, List<String> committed) {
        Proposal lines = prepare(kind, ranges, root, file, committed);
        int first = ranges.stream().mapToInt(r -> r[0]).min().orElse(1);
        int last = ranges.stream().mapToInt(r -> r[1]).max().orElse(1);
        for (design.knowledge.stamp.adoc.dialect.Segmenter.Sentence found : design.knowledge.stamp.adoc.dialect.Segmenter
                .of(design.knowledge.stamp.adoc.dialect.IkeDialectParser.parse(committed)).sentences()) {
            if (found.text().equals(sentence) && found.lines().getFirst() >= first && found.lines().getLast() <= last) {
                LineRange range = LineRange.of(file, found.lines().getFirst(), found.flow().columnAt(found.start()) + 1,
                        found.lines().getLast(), found.flow().columnAt(found.end() - 1) + 1, lines.head(), committed);
                return new Proposal(List.of(Annotation.of(kind, range)), found.lines().size(), lines.root(), lines.head());
            }
        }
        throw new IllegalStateException("The sentence is not in the committed text of " + file + "; Materialize first.");
    }

    /**
     * Annotates the selected lines with {@code kind}: folds them into committed ranges, hashes
     * them over the committed text, asks for the annotation text and the signing choice, writes
     * the empty commit through the person's own git, and re-reads the commit into the store.
     */
    private void annotateSelection(AnnotationKind kind, String headerNote, String initialText) {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        Optional<LineSelection> selection = surface.selection();
        if (selection.isEmpty()) {
            statusLine.setText("Select the lines you have reviewed, then choose Affirm, Refute or Comment from their menu.");
            return;
        }
        Optional<DocumentAssembly.Located> located = located();
        if (located.isEmpty()) {
            statusLine.setText("The selection spans " + assembly.membersTouched(selection.get())
                    + " files; an annotation names one file's lines. Select within the host or within one included document.");
            return;
        }
        DocumentAssembly.Member member = located.get().member();
        Loaded loaded = members.get(member.path());
        int uncommitted = located.get().local().uncommitted(o -> committedNumber(member, o));
        List<int[]> ranges = selectedLineNumbers();
        if (ranges.isEmpty()) {
            statusLine.setText("The selected lines are edited in Komet and not committed; commit the edit before annotating them.");
            return;
        }
        if (repoRoot == null || loaded == null || loaded.path() == null || !Files.isRegularFile(repoRoot.resolve(loaded.path()))) {
            Optional<Path> chosen = workingTree(loaded == null ? shownPath : loaded.path());
            if (chosen.isEmpty()) {
                return;
            }
        }
        Path memberFile = loaded == null || loaded.path() == null ? null : Path.of(loaded.path());
        // a sentence chosen in the Graph view's DocBook mode is annotated as that sentence (#1313)
        Optional<DocumentGraph.Node> sentence = graph != null && surface.view() == DocumentSurfaceView.View.GRAPH
                ? graph.selectedSentence() : Optional.empty();
        Proposal proposal;
        try {
            proposal = sentence.isPresent()
                    ? prepareSentence(kind, sentence.get().markup().text(), ranges, repoRoot, memberFile,
                            loaded == null ? List.of() : loaded.committedLines())
                    : prepare(kind, ranges, repoRoot, memberFile, loaded == null ? List.of() : loaded.committedLines());
        } catch (IllegalStateException refused) {
            statusLine.setText(refused.getMessage());
            return;
        }
        String noun = sentence.isPresent() ? "the sentence on " + proposal.lines() + " line" + (proposal.lines() == 1 ? "" : "s") + " of " + memberFile
                : proposal.lines() + " line" + (proposal.lines() == 1 ? "" : "s") + " of " + memberFile;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(kind.verb() + " Lines");
        confirm.getDialogPane().setId("stampedDocumentAnnotationDialog");
        confirm.setHeaderText((headerNote == null ? "" : headerNote + "\n")
                + "Record your " + displayName(kind).toLowerCase() + (kind.judgment() ? " of " : " on ") + noun + " as an empty commit?"
                + (member.host() ? "" : "\nThe lines are in a document included by " + assembly.host().fileName() + "; the trailer names their own file.")
                + (uncommitted == 0 ? "" : "\n" + uncommitted + " selected line(s) are uncommitted edits and are left out."));
        TextArea text = new TextArea(initialText == null ? "" : initialText);
        text.setId("stampedDocumentAnnotationText");
        text.setPromptText(kind == AnnotationKind.REFUTATION ? "Why do you dispute these lines? (required)" : "Annotation text (optional)");
        text.setWrapText(true);
        text.setPrefRowCount(4);
        Label details = new Label(AnnotationCommit.subject(proposal.annotations()) + "\n\n" + proposal.trailers()
                + "\n\nThe text becomes the commit body. Signing (-S) gives the annotation non-repudiation.");
        details.setId("stampedDocumentAnnotationTrailers");
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
        confirm.initOwner(window());
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return;
        }
        boolean sign = choice.get() == signed;
        String body = text.getText();
        Path root = proposal.root();
        Path file = memberFile;
        PublicId anchor = shown.anchor();
        statusLine.setText("Writing annotation commit …");
        CompletableFuture.supplyAsync(() -> {
            try {
                AnnotationCommit.Result result = AnnotationCommit.write(root, proposal.annotations(), body, sign);
                if (result.ok()) {
                    GitDocumentMaterializer.materialize(root, root.resolve(file), true);   // the annotation reaches the store, on the member's file
                }
                return result;
            } catch (IOException e) {
                return new AnnotationCommit.Result(false, "", e.getMessage());
            }
        }).whenComplete((result, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                statusLine.setText(displayName(kind) + " not written: " + failure.getMessage());
                LOG.warn("Annotation commit failed in {}", root, failure);
            } else if (result.ok()) {
                open(anchor, displayName(kind) + " recorded as commit " + result.sha().substring(0, Math.min(7, result.sha().length()))
                        + " and read into the store.", null);
            } else {
                statusLine.setText(displayName(kind) + " not written: " + result.output());
                LOG.warn("Annotation commit failed in {}: {}", root, result.output());
            }
        }));
    }

    /** The History view: the commits of the file and the repository, and the selected lines' STAMPs, pinned until closed. */
    @Override
    public void showHistory() {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        HistoryDialog.show(window(), repoRoot, selectedFile().or(this::currentFile).orElse(null), summaries, surface::highlightCommit, surface.selectedLines(),
                view(), this::surfaceIdenticon, documents.getValue() == null ? "" : documents.getValue());
    }

    private final Map<Long, javafx.scene.image.Image> historyBadges = new HashMap<>();

    private javafx.scene.image.Image surfaceIdenticon(dev.ikm.tinkar.entity.StampEntity<?> stamp) {
        return historyBadges.computeIfAbsent(stamp.nid(), nid -> {
            UUID seed = stamp.publicId().asUuidArray()[0];
            byte[] png = network.ike.komet.claude.koncept.KonceptIdenticon.png(dev.ikm.tinkar.common.id.PublicIds.of(seed).idString(),
                    network.ike.komet.claude.koncept.KonceptIdenticon.DISPLAY_MODULE_SIZE);
            return new javafx.scene.image.Image(new java.io.ByteArrayInputStream(png));
        });
    }

    // ── the repository (phase 4) ──────────────────────────────────────────────

    @Override
    public boolean pendingEdit() {
        return pendingEdit;
    }

    @Override
    public void history() {
        showHistory();
    }

    @Override
    public void commit() {
        commitPending();
    }

    /** Runs one git operation off the FX thread, reports what git said, and re-reads the panel. */
    private void operate(String verb, java.util.function.Supplier<GitOperations.Run> operation, java.util.function.Consumer<GitOperations.Run> then) {
        if (repoRoot == null) {
            statusLine.setText("No working tree; Open file… names one, or Clone… makes one.");
            return;
        }
        statusLine.setText("git " + verb + " …");
        CompletableFuture.supplyAsync(operation).whenComplete((run, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("git " + verb + " could not run: " + cause.getMessage());
                LOG.warn("git {} in {} failed", verb, repoRoot, cause);
                return;
            }
            statusLine.setText((run.ok() ? "git " + verb + ": " : "git " + verb + " refused: ") + run.report(verb).replace('\n', ' '));
            repositoryPanel.refresh();
            if (then != null) {
                then.accept(run);
            }
        }));
    }

    @Override
    public void fetch() {
        Path root = repoRoot;
        operate("fetch", () -> uncheck(() -> GitOperations.fetch(root)), null);
    }

    @Override
    public void pull() {
        Path root = repoRoot;
        Optional<String> before = uncheck(() -> GitOperations.head(root));
        operate("pull --ff-only", () -> uncheck(() -> GitOperations.pullFastForwardOnly(root)), run -> {
            Optional<Path> file = currentFile();
            Optional<String> after = uncheck(() -> GitOperations.head(root));
            if (run.ok() && !before.equals(after) && file.isPresent() && Files.isRegularFile(root.resolve(file.get()))) {
                // The pull moved HEAD: the commits it brought reach the store the way Open file… does.
                materialize(root, file.get(), false);
            }
        });
    }

    @Override
    public void push() {
        Optional<GitOperations.RepositoryState> state = repositoryPanel.state();
        if (state.isEmpty()) {
            statusLine.setText("Nothing known about the repository yet.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Push");
        confirm.getDialogPane().setId("stampedDocumentPushDialog");
        confirm.setHeaderText(state.get().upstream().isEmpty()
                ? "Push " + state.get().branch() + " of " + state.get().root().getFileName() + "? It has no upstream yet; git decides where it goes."
                : "Push " + state.get().ahead() + " commit(s) of " + state.get().branch() + " to " + state.get().upstream().get() + "?");
        confirm.setContentText("Through your own git, with its credentials. What git says is reported on the status line.");
        ButtonType push = new ButtonType("Push");
        confirm.getButtonTypes().setAll(push, ButtonType.CANCEL);
        confirm.initOwner(window());
        if (confirm.showAndWait().filter(b -> b == push).isEmpty()) {
            return;
        }
        Path root = repoRoot;
        operate("push", () -> uncheck(() -> GitOperations.push(root)), null);
    }

    @Override
    public void cloneRepository() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Clone a Repository");
        dialog.getDialogPane().setId("stampedDocumentCloneDialog");
        dialog.setHeaderText("Clone a repository into a new or empty folder, through your own git.");
        javafx.scene.control.TextField url = new javafx.scene.control.TextField();
        url.setId("stampedDocumentCloneUrl");
        url.setPromptText("https://github.com/owner/repository.git or git@github.com:owner/repository.git");
        javafx.scene.control.TextField folder = new javafx.scene.control.TextField();
        folder.setId("stampedDocumentCloneFolder");
        folder.setPromptText("The folder to create; it must not exist, or be empty");
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Parent folder of the clone");
            File chosen = chooser.showDialog(window());
            if (chosen != null) {
                String name = url.getText().replaceFirst("/+$", "").replaceFirst("\\.git$", "");
                name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf(':')) + 1);
                folder.setText(chosen.toPath().resolve(name.isEmpty() ? "repository" : name).toString());
            }
        });
        HBox folderRow = new HBox(6, folder, browse);
        HBox.setHgrow(folder, javafx.scene.layout.Priority.ALWAYS);
        VBox content = new VBox(8, new Label("Repository URL"), url, new Label("Clone into"), folderRow);
        content.setPrefWidth(560);
        dialog.getDialogPane().setContent(content);
        ButtonType clone = new ButtonType("Clone");
        dialog.getDialogPane().getButtonTypes().setAll(clone, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(clone).disableProperty().bind(url.textProperty().isEmpty().or(folder.textProperty().isEmpty()));
        dialog.initOwner(window());
        if (dialog.showAndWait().filter(b -> b == clone).isEmpty()) {
            return;
        }
        cloneInto(url.getText().strip(), Path.of(folder.getText().strip()));
    }

    /**
     * Clones through the person's git into a folder that does not exist or is empty, and makes
     * the clone the working tree; public so UI automation can clone without the dialog.
     *
     * @param url    the repository URL
     * @param target the folder to create the clone in
     */
    public void cloneInto(String url, Path target) {
        statusLine.setText("git clone " + url + " …");
        CompletableFuture.supplyAsync(() -> uncheck(() -> GitOperations.clone(url, target))).whenComplete((run, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("git clone could not run: " + cause.getMessage());
                return;
            }
            if (!run.ok()) {
                statusLine.setText("git clone refused: " + run.report("clone").replace('\n', ' '));
                return;
            }
            repoRoot = target.toAbsolutePath().normalize();
            preferences().put(PREF_WORKING_TREE, repoRoot.toString());
            syncPreferences();
            statusLine.setText("Cloned into " + repoRoot + "; Open file… chooses a document in it. " + run.output().replace('\n', ' '));
            repositoryPanel.refresh();
        }));
    }

    private interface IoCall<T> {
        T call() throws IOException;
    }

    private static <T> T uncheck(IoCall<T> call) {
        try {
            return call.call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── editing ───────────────────────────────────────────────────────────────

    /** Opens a block for editing in place, for UI automation; the context menu's Edit block does the same for the selection. */
    public boolean editBlock(long blockNid) {
        return surface.editBlock(blockNid);
    }

    /**
     * Saves an edit of one block as uncommitted versions by performing the starter set's
     * {@code Edit document block} action, off the FX thread, and re-reads the document. A refused
     * or failed run leaves the editor open with the reason.
     */
    @Override
    public void saveEdit(StoredBlock block, String text) {
        PublicId anchor = shown.anchor();
        EditCoordinateRecord edit = editCoordinate();
        ViewCalculator view = view();
        statusLine.setText("Saving the edit …");
        CompletableFuture.supplyAsync(() -> KometActions.expect(KometConcept.EDIT_DOCUMENT_BLOCK)
                        .perform(EditDocumentBlock.input(block.nid(), text), view, edit))
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        statusLine.setText("Edit not saved: " + cause.getMessage());
                        LOG.warn("Editing block {} of {} failed", block.nid(), anchor, cause);
                        return;
                    }
                    if (!result.completed()) {
                        statusLine.setText("Edit not saved: " + result.message());
                        return;
                    }
                    surface.cancelEdit();
                    open(anchor);
                }));
    }

    /** Asks for a sentence's new text, then saves it as {@link #saveSentence} does. */
    private void askToEditSentence(long sentence, String text) {
        TextInputDialog dialog = new TextInputDialog(text);
        dialog.setTitle("Edit sentence");
        dialog.setHeaderText("The sentence's new text. Only the lines it changes are rewritten; it stays uncommitted until you commit in git.");
        dialog.getEditor().setPrefColumnCount(72);
        dialog.showAndWait().filter(t -> !t.isBlank() && !t.equals(text)).ifPresent(t -> saveSentence(sentence, t));
    }

    /**
     * Saves an edit of one sentence as uncommitted versions by performing the starter set's
     * {@code Edit document sentence} action, off the FX thread, and re-reads the document
     * (IKE-Network/ike-issues#1313).
     *
     * @param sentence the sentence's vertex nid
     * @param text     its new text
     */
    public void saveSentence(long sentence, String text) {
        PublicId anchor = shown.anchor();
        EditCoordinateRecord edit = editCoordinate();
        ViewCalculator view = view();
        statusLine.setText("Saving the edit …");
        CompletableFuture.supplyAsync(() -> KometActions.expect(KometConcept.EDIT_DOCUMENT_SENTENCE)
                        .perform(EditDocumentSentence.input(sentence, text), view, edit))
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        statusLine.setText("Edit not saved: " + cause.getMessage());
                        LOG.warn("Editing sentence {} of {} failed", sentence, anchor, cause);
                        return;
                    }
                    statusLine.setText(result.completed() ? result.message() : "Edit not saved: " + result.message());
                    if (result.completed()) {
                        open(anchor);
                    }
                }));
    }

    /** The card's edit coordinate, for the assistant's tools, which write under their own author on its module and path. */
    EditCoordinateRecord editCoordinateForTools() {
        return editCoordinate();
    }

    /** The card's edit coordinate, or the default one before the card is hosted. */
    private EditCoordinateRecord editCoordinate() {
        return getCardViewProperties() != null ? getCardViewProperties().nodeView().editCoordinate().toEditCoordinateRecord()
                : Coordinates.Edit.Default();
    }

    /** The working tree to commit into: the one known, or one the person chooses. */
    private Optional<Path> workingTree(String path) {
        if (repoRoot != null && path != null && Files.isRegularFile(repoRoot.resolve(path))) {
            return Optional.of(repoRoot);
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Working tree that holds " + path);
        File chosen = chooser.showDialog(window());
        if (chosen == null) {
            return Optional.empty();
        }
        repoRoot = chosen.toPath();
        preferences().put(PREF_WORKING_TREE, repoRoot.toString());
        syncPreferences();
        return Optional.of(repoRoot);
    }

    /**
     * Records the pending edit in git as the person's own commit: writes the document with the
     * edit into the working tree, asks for the message (a draft is offered) and the signing
     * choice, commits that one file, re-reads the commit into the store and settles the edit.
     */
    public void commitPending() {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        // The member whose edit is pending: the host, or a child edited in place; one commit per member.
        PublicId hostAnchor = shown.anchor();
        PublicId anchor = members.values().stream().filter(Loaded::pending).map(l -> l.document().anchor()).findFirst().orElse(hostAnchor);
        if (DocumentEditor.pending(anchor).isEmpty()) {
            statusLine.setText("Nothing to commit: no edit of this document is pending in Komet.");
            return;
        }
        GitDocumentExporter.Exported pending = GitDocumentExporter.export(anchor, true).orElseThrow();
        GitDocumentExporter.Exported committed = GitDocumentExporter.export(anchor, false).orElseThrow();
        Optional<Path> chosenRoot = workingTree(pending.path());
        if (chosenRoot.isEmpty()) {
            return;
        }
        Path root = chosenRoot.get();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Commit Document Edit");
        confirm.setHeaderText("Write " + pending.path() + " with your edit into " + root + " and commit it?");
        TextArea message = new TextArea(DocumentCommit.draftMessage(pending.path(), committed.lines(), pending.lines()));
        message.setId("stampedDocumentCommitMessage");
        message.setWrapText(true);
        message.setPrefRowCount(6);
        Label details = new Label("The message is a draft: rewrite it as you like. The commit holds this one file and is yours,"
                + " made through your own git. Signing (-S) gives it non-repudiation. Komet then re-reads the commit"
                + " and settles your edit, reporting anything the commit did not carry.");
        details.setWrapText(true);
        confirm.getDialogPane().setContent(new VBox(8, message, details));
        ButtonType signed = new ButtonType("Signed Commit");
        ButtonType unsigned = new ButtonType("Unsigned Commit");
        confirm.getButtonTypes().setAll(signed, unsigned, ButtonType.CANCEL);
        for (ButtonType type : List.of(signed, unsigned)) {
            confirm.getDialogPane().lookupButton(type).disableProperty().bind(message.textProperty().isEmpty());
        }
        confirm.initOwner(window());
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return;
        }
        boolean sign = choice.get() == signed;
        String text = message.getText();
        statusLine.setText("Writing and committing " + pending.path() + " …");
        CompletableFuture.supplyAsync(() -> {
            try {
                Path file = GitDocumentExporter.writeTo(pending, root);
                DocumentCommit.Result result = DocumentCommit.write(root, pending.path(), text, sign);
                if (!result.ok()) {
                    return "Not committed: " + result.output()
                            + " The file in the working tree holds your edit; the edit is still pending in Komet.";
                }
                GitDocumentMaterializer.materialize(root, file, true);
                return "Committed " + result.sha().substring(0, 7) + " (" + (sign ? "signed" : "unsigned") + "); "
                        + describe(DocumentEditor.settle(anchor));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).whenComplete((report, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("Commit failed: " + cause.getMessage());
                LOG.warn("Committing {} failed", anchor, cause);
                return;
            }
            open(hostAnchor, report, null);
        }));
    }

    /** What settling an edit found, for a status line. */
    static String describe(DocumentEditor.Settlement settlement) {
        if (settlement.rebased()) {
            return "the commit does not touch your edit, which stays pending on top of it.";
        }
        if (!settlement.settled()) {
            return "the commit is not yet in the store, so the edit is still pending.";
        }
        if (settlement.carried()) {
            return "the edit is settled: the commit carries all of it.";
        }
        StringBuilder text = new StringBuilder("the edit is settled against the commit, which differs from it.");
        if (!settlement.notCarried().isEmpty()) {
            text.append(" Not carried: ").append(quoted(settlement.notCarried()))
                    .append(". Restore as pending brings them back over the commit.");
        }
        if (!settlement.committedOnly().isEmpty()) {
            text.append(" Only in the commit: ").append(quoted(settlement.committedOnly())).append('.');
        }
        return text.toString();
    }

    private static String quoted(List<String> lines) {
        String shown = lines.stream().limit(3).map(l -> "“" + (l.length() > 60 ? l.substring(0, 57) + "..." : l) + "”")
                .collect(Collectors.joining(", "));
        return lines.size() + " line(s), " + shown + (lines.size() > 3 ? ", …" : "");
    }

    /**
     * Brings cancelled edits back as pending, over the document as now committed. An edit whose
     * block still exists is restored straight away; an edit whose block a commit gave a new
     * identity waits for the person: the one block it could be is highlighted for confirmation, or,
     * when several or none could be, the person picks one. Cancelling leaves the edit restorable.
     */
    private void restorePending() {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        PublicId anchor = shown.anchor();
        statusLine.setText("Restoring …");
        record Found(int restored, List<DocumentEditor.Restorable> waiting) { }
        CompletableFuture.supplyAsync(() -> new Found(DocumentEditor.restore(anchor), DocumentEditor.restorable(anchor)))
                .whenComplete((found, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        statusLine.setText("Not restored: " + cause.getMessage());
                        LOG.warn("Restoring the edit of {} failed", anchor, cause);
                        return;
                    }
                    if (found.restored() == 0 && found.waiting().isEmpty()) {
                        open(anchor, "Nothing to restore: no cancelled edit of this document differs from what is committed.", null);
                        return;
                    }
                    open(anchor, restoredReport(found.restored()), () -> resolve(anchor, found.waiting(), 0, found.restored()));
                }));
    }

    private static String restoredReport(int restored) {
        return restored == 0 ? "" : "Restored the edit of " + restored + " block(s) as pending over the latest commit; check the"
                + " edited lines against what the commit changed, then Commit… or Discard edit.";
    }

    /** Asks the person where each waiting edit goes, one at a time, and restores it there. */
    private void resolve(PublicId anchor, List<DocumentEditor.Restorable> waiting, int at, int restoredSoFar) {
        if (at >= waiting.size()) {
            if (restoredSoFar > 0) {
                statusLine.setText(restoredReport(restoredSoFar));
            }
            return;
        }
        DocumentEditor.Restorable edit = waiting.get(at);
        String editText = edit.lines().stream().filter(DocumentEditor.Kept::edited).map(DocumentEditor.Kept::text)
                .collect(Collectors.joining("\n"));
        Optional<Long> chosen;
        if (edit.match() == DocumentEditor.Match.CONFIRM) {
            surface.highlight(edit.target(), true);
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Restore Edit");
            confirm.setHeaderText("A commit rewrote the block this edit was made in. Restore the edit over the highlighted block?");
            TextArea lines = new TextArea(editText);
            lines.setEditable(false);
            lines.setPrefRowCount(Math.min(6, Math.max(2, edit.lines().size())));
            confirm.getDialogPane().setContent(new VBox(6, new Label("The lines you wrote:"), lines));
            ButtonType here = new ButtonType("Restore Here");
            confirm.getButtonTypes().setAll(here, ButtonType.CANCEL);
            confirm.initOwner(window());
            chosen = confirm.showAndWait().filter(b -> b == here).map(b -> edit.target());
            surface.highlight(edit.target(), false);
        } else {
            chosen = choose(edit, editText);
        }
        if (chosen.isEmpty()) {
            statusLine.setText("Kept the edit restorable; Restore as pending offers it again.");
            resolve(anchor, waiting, at + 1, restoredSoFar);
            return;
        }
        long target = chosen.get();
        CompletableFuture.supplyAsync(() -> DocumentEditor.restore(anchor, Map.of(edit.index(), target)))
                .whenComplete((restored, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        statusLine.setText("Not restored: " + cause.getMessage());
                        LOG.warn("Restoring an edit of {} over block {} failed", anchor, target, cause);
                        return;
                    }
                    open(anchor, restoredReport(restoredSoFar + restored),
                            () -> resolve(anchor, waiting, at + 1, restoredSoFar + restored));
                }));
    }

    /** Lets the person pick the block an edit goes over, highlighting each as it is chosen; empty when they cancel. */
    private Optional<Long> choose(DocumentEditor.Restorable edit, String editText) {
        Map<String, Long> byLabel = new LinkedHashMap<>();
        for (long candidate : edit.candidates()) {
            StoredBlock block = shown.blocks().get(candidate);
            String first = block == null || block.members().isEmpty() ? "block " + candidate
                    : shown.line(block.members().getFirst()).map(StoredLine::text).orElse("block " + candidate);
            String label = first.length() > 70 ? first.substring(0, 67) + "..." : first;
            while (byLabel.containsKey(label)) {
                label = label + " ";
            }
            byLabel.put(label, candidate);
        }
        ChoiceBox<String> picker = new ChoiceBox<>();
        picker.setId("stampedDocumentRestoreTarget");
        picker.getItems().setAll(byLabel.keySet());
        long[] lit = {0};
        picker.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
            if (lit[0] != 0) {
                surface.highlight(lit[0], false);
            }
            lit[0] = is == null ? 0 : byLabel.get(is);
            if (lit[0] != 0) {
                surface.highlight(lit[0], true);
            }
        });
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.setTitle("Restore Edit");
        ask.setHeaderText(edit.candidates().isEmpty() ? "No block of this kind is left to restore the edit over."
                : "A commit rewrote the block this edit was made in, and more than one block could be it, or none. Pick the block"
                + " to restore the edit over, or cancel to keep it restorable.");
        TextArea lines = new TextArea(editText);
        lines.setEditable(false);
        lines.setPrefRowCount(Math.min(6, Math.max(2, edit.lines().size())));
        ask.getDialogPane().setContent(new VBox(6, new Label("The lines you wrote:"), lines, new Label("Restore over:"), picker));
        ButtonType here = new ButtonType("Restore Here");
        ask.getButtonTypes().setAll(here, ButtonType.CANCEL);
        ask.getDialogPane().lookupButton(here).disableProperty().bind(picker.getSelectionModel().selectedItemProperty().isNull());
        ask.initOwner(window());
        Optional<Long> chosen = ask.showAndWait().filter(b -> b == here).map(b -> byLabel.get(picker.getValue()));
        if (lit[0] != 0) {
            surface.highlight(lit[0], false);
        }
        return chosen;
    }

    /** Cancels the pending edit, after the person confirms. */
    private void discardPending() {
        if (shown == null) {
            return;
        }
        PublicId anchor = shown.anchor();
        List<PublicId> pending = members.values().stream().filter(Loaded::pending).map(l -> l.document().anchor()).toList();
        if (pending.isEmpty()) {
            statusLine.setText("No edit of this document is pending in Komet.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Cancel the edit made in Komet? The document will read as committed again.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Discard Edit");
        confirm.initOwner(window());
        if (confirm.showAndWait().filter(b -> b == ButtonType.YES).isEmpty()) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            for (PublicId edited : pending) {
                DocumentEditor.cancel(edited);
            }
            return true;
        }).whenComplete((done, failure) -> Platform.runLater(() -> open(anchor)));
    }

    private Window window() {
        return host.getScene() == null ? null : host.getScene().getWindow();
    }

    @Override
    protected void subCardSave() {
        if (documentArea != null) {
            documentArea.saveSettings();             // the print settings live on the area's own node
        }
    }

    @Override
    public void knowledgeLayoutUnbind() {
        super.knowledgeLayoutUnbind();
        if (documentArea != null) {
            documentArea.knowledgeLayoutUnbind();    // collapses a promoted paged preview, tears the area down
        }
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
