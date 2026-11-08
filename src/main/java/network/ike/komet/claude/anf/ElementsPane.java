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
package network.ike.komet.claude.anf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import network.ike.komet.claude.anf.element.ClinicalElement;
import network.ike.komet.claude.anf.element.ElementColours;
import network.ike.komet.claude.anf.element.ElementSet;
import network.ike.komet.claude.anf.element.ElementState;
import network.ike.komet.claude.anf.element.NoteSection;
import network.ike.komet.document.Span;

/**
 * Step one of the two-step lift on the lift region ({@code dev-anf-element-isolation}), as a tree
 * (KEC 2026-10-31): the note's sections, each element under the section its words are in, and
 * under each element, when it is open, its ANF statement. A row is the quoted words, the element
 * sentence to edit, the element's state, whether it is reviewed, and its gestures. The note itself,
 * with each element's words marked, is the narrative above the tree, so the pane draws no copy of
 * it. Every row is at its natural height: the region's one scroll bar scrolls the tree with the
 * rest. The pane holds no lift logic: every gesture computes a new {@link ElementSet} and hands it
 * to the {@link Host}, which persists it and redraws; a lift is asked of the host by element id.
 */
final class ElementsPane extends VBox {

    /** What the pane asks of the lift region. */
    interface Host {
        /** The element set changed by a gesture: persist it and redraw. */
        void changed(ElementSet set);

        /** An element was selected, or none. */
        void selected(Optional<ClinicalElement> element);

        /** Lift these elements, in order. */
        void lift(List<String> ids);

        /** The span the author has selected in the note, when there is one. */
        Optional<Span> selection();

        /** The leaf under an open element: its statement, editable, or why it has none. */
        Node statement(ClinicalElement element);

        /**
         * How many not-clinical marks could be recorded on the note as annotations and are not yet:
         * none unless the narrative is a stamped document's (the segments design's decision 9, revised).
         */
        default int unrecordedNotClinical(ElementSet set) {
            return 0;
        }

        /** Record the set's unrecorded not-clinical marks on the note, as annotations in its git history. */
        default void recordNotClinical() {
        }
    }

    /** A rendered statement leaf and the statement it shows, kept while the element's statement is unchanged. */
    private record Leaf(Optional<AnfStatement> statement, Node node) {
    }

    private final Host host;
    private final VBox tree = new VBox(4);
    private final Label coverage = new Label();
    private final Button addFromSelection = new Button("Add from selection");
    private final Button markNotClinical = new Button("Mark not clinical");
    private final Button recordNotClinical = new Button("Record on the note");
    private final Button expandAll = new Button("Expand all");
    private final Button collapseAll = new Button("Collapse all");
    private final Button liftAll = new Button("Lift elements");
    private final HBox tools = new HBox(6);
    /** Elements whose statement is open beneath them. */
    private final Set<String> open = new HashSet<>();
    /** Sections closed by the author, by their key. */
    private final Set<String> closedSections = new HashSet<>();
    private final Map<String, Leaf> leaves = new HashMap<>();
    private final Map<String, Node> rowNodes = new LinkedHashMap<>();
    private ElementSet set;
    private String selectedId;
    private boolean busy;
    private boolean noteChanged;

    ElementsPane(Host host) {
        super(6);
        this.host = host;
        setPadding(new Insets(4, 0, 4, 0));
        tree.setId("element-tree");
        coverage.setId("element-coverage");
        coverage.setWrapText(true);
        coverage.setStyle("-fx-text-fill: #8a5a00; -fx-font-size: 11;");
        addFromSelection.setId("add-element-button");
        addFromSelection.setOnAction(e -> addFromSelection());
        markNotClinical.setId("not-clinical-button");
        markNotClinical.setOnAction(e -> markNotClinical());
        recordNotClinical.setId("record-not-clinical-button");
        recordNotClinical.setOnAction(e -> host.recordNotClinical());
        recordNotClinical.managedProperty().bind(recordNotClinical.visibleProperty());
        recordNotClinical.setVisible(false);
        expandAll.setId("expand-elements-button");
        expandAll.setOnAction(e -> {
            if (set != null) {
                set.elements().forEach(element -> open.add(element.id()));
                closedSections.clear();
                rebuild();
            }
        });
        collapseAll.setId("collapse-elements-button");
        collapseAll.setOnAction(e -> {
            open.clear();
            rebuild();
        });
        liftAll.setId("lift-elements-button");
        liftAll.setOnAction(e -> host.lift(set == null ? List.of() : set.toLift().stream().map(ClinicalElement::id).toList()));
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        tools.getChildren().addAll(addFromSelection, markNotClinical, recordNotClinical, spring, expandAll, collapseAll, liftAll);
        tools.setAlignment(Pos.CENTER_LEFT);
        tools.managedProperty().bind(tools.visibleProperty());
        coverage.managedProperty().bind(coverage.visibleProperty());
        getChildren().addAll(coverage, tools, tree);
        setElements(null);
    }

    ElementSet elements() {
        return set;
    }

    void setElements(ElementSet elements) {
        this.set = elements;
        if (set != null && selectedId != null && set.element(selectedId).isEmpty()) {
            selectedId = null;
        }
        if (set == null) {
            open.clear();
            leaves.clear();
        }
        noteChanged = false;
        rebuild();
    }

    void setBusy(boolean isBusy) {
        this.busy = isBusy;
        updateButtons();
        tree.setDisable(isBusy);
    }

    /** The note in the editor no longer matches the narrative the elements were isolated from. */
    void setNoteChanged(boolean changed) {
        if (changed != noteChanged) {
            noteChanged = changed;
            rebuild();
        }
    }

    Optional<ClinicalElement> selected() {
        return set == null || selectedId == null ? Optional.empty() : set.element(selectedId);
    }

    /** Selects an element and opens it, and its section, so its statement shows. */
    void select(String id) {
        selectedId = id;
        if (id != null) {
            open.add(id);
            set.element(id).ifPresent(e -> closedSections.remove(sectionKeyOf(e)));
        }
        rebuild();
        host.selected(selected());
    }

    /** Selects the element whose words hold a narrative offset, if any, and answers its row. */
    Optional<Node> selectAt(int offset) {
        if (set == null) {
            return Optional.empty();
        }
        List<ClinicalElement> over = set.at(offset);
        if (over.isEmpty()) {
            return Optional.empty();
        }
        select(over.getFirst().id());
        return Optional.ofNullable(rowNodes.get(over.getFirst().id()));
    }

    /** The statement leaf on screen already shows {@code updated}: a field edited in place, kept as it is. */
    void statementEditedInPlace(String id, AnfStatement updated) {
        Leaf leaf = leaves.get(id);
        if (leaf != null) {
            leaves.put(id, new Leaf(Optional.of(updated), leaf.node()));
        }
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    private void rebuild() {
        tree.getChildren().clear();
        rowNodes.clear();
        if (set == null) {
            Label prompt = new Label("Isolate the note's clinical elements to see each fact the lift will make a statement of.");
            prompt.setWrapText(true);
            prompt.setStyle("-fx-text-fill: #888;");
            tree.getChildren().add(prompt);
            coverage.setVisible(false);
            tools.setVisible(false);
            updateButtons();
            return;
        }
        coverage.setVisible(true);
        tools.setVisible(true);
        List<Span> uncovered = set.uncovered();
        if (noteChanged) {
            coverage.setStyle("-fx-text-fill: #8a5a00; -fx-font-size: 11;");
            coverage.setText("The note has changed since its elements were isolated: isolate again, or undo the change.");
        } else if (uncovered.isEmpty()) {
            coverage.setText("Every part of the note is accounted for.");
            coverage.setStyle("-fx-text-fill: #0f6e56; -fx-font-size: 11;");
        } else {
            coverage.setStyle("-fx-text-fill: #8a5a00; -fx-font-size: 11;");
            coverage.setText(uncovered.size() + (uncovered.size() == 1 ? " stretch" : " stretches")
                    + " of the note, shaded, support no element and are not marked not clinical.");
        }
        List<NoteSection> sections = NoteSection.of(set.narrative());
        Map<Integer, List<ClinicalElement>> bySection = new TreeMap<>();
        for (ClinicalElement element : set.elements()) {
            bySection.computeIfAbsent(sectionOf(sections, element), k -> new ArrayList<>()).add(element);
        }
        bySection.forEach((index, elements) -> tree.getChildren().add(section(index, sections, elements)));
        Set<String> ids = new HashSet<>();
        set.elements().forEach(e -> ids.add(e.id()));
        leaves.keySet().retainAll(ids);
        open.retainAll(ids);
        updateButtons();
    }

    private static int sectionOf(List<NoteSection> sections, ClinicalElement element) {
        int at = element.spans().getFirst().start();
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).holds(at)) {
                return i;
            }
        }
        return Math.max(0, sections.size() - 1);
    }

    private String sectionKeyOf(ClinicalElement element) {
        List<NoteSection> sections = NoteSection.of(set.narrative());
        int index = sectionOf(sections, element);
        return sectionKey(index, sections.isEmpty() ? "" : sections.get(index).title());
    }

    private static String sectionKey(int index, String title) {
        return index + ":" + title;
    }

    private Node section(int index, List<NoteSection> sections, List<ClinicalElement> elements) {
        String title = sections.isEmpty() ? "Note" : sections.get(index).title();
        String key = sectionKey(index, title);
        boolean closed = closedSections.contains(key);
        Label toggle = disclosure(!closed);
        Label name = new Label(title);
        name.setStyle("-fx-font-weight: bold; -fx-text-fill: #3c3489;");
        name.setWrapText(true);
        Label count = new Label(elements.size() + (elements.size() == 1 ? " element" : " elements"));
        count.setStyle("-fx-text-fill: #888; -fx-font-size: 10.5;");
        count.setMinWidth(Region.USE_PREF_SIZE);
        HBox header = new HBox(6, toggle, name, count);
        header.setId("element-section-" + index);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setCursor(Cursor.HAND);
        header.setPadding(new Insets(4, 4, 2, 0));
        header.setOnMouseClicked(e -> {
            if (!closedSections.remove(key)) {
                closedSections.add(key);
            }
            rebuild();
        });
        VBox box = new VBox(2, header);
        if (!closed) {
            VBox children = new VBox();
            children.setPadding(new Insets(0, 0, 0, 14));
            for (ClinicalElement element : elements) {
                children.getChildren().add(elementNode(element, colourOf(element.id())));
            }
            box.getChildren().add(children);
        }
        return box;
    }

    private Node elementNode(ClinicalElement element, String colour) {
        String id = element.id();
        boolean isOpen = open.contains(id);
        HBox row = row(element, colour);
        Label toggle = disclosure(isOpen);
        toggle.setId("element-toggle-" + id);
        toggle.setOnMouseClicked(e -> {
            if (!open.remove(id)) {
                open.add(id);
            }
            rebuild();
            e.consume();
        });
        row.getChildren().addFirst(toggle);
        rowNodes.put(id, row);
        VBox box = new VBox(row);
        if (isOpen) {
            box.getChildren().add(leaf(element));
        }
        return box;
    }

    private Node leaf(ClinicalElement element) {
        Leaf cached = leaves.get(element.id());
        if (cached != null && cached.statement().equals(element.statement())) {
            return cached.node();
        }
        VBox node = new VBox(host.statement(element));
        node.setId("element-statement-" + element.id());
        node.setPadding(new Insets(2, 4, 8, 34));
        leaves.put(element.id(), new Leaf(element.statement(), node));
        return node;
    }

    private static Label disclosure(boolean isOpen) {
        Label toggle = new Label(isOpen ? "▾" : "▸");
        toggle.setStyle("-fx-text-fill: #5e4b7e; -fx-font-size: 12;");
        toggle.setMinWidth(12);
        toggle.setCursor(Cursor.HAND);
        return toggle;
    }

    private String colour(int index) {
        return ElementColours.of(index);
    }

    private String colourOf(String id) {
        for (int i = 0; i < set.elements().size(); i++) {
            if (set.elements().get(i).id().equals(id)) {
                return colour(i);
            }
        }
        return "#888888";
    }

    private HBox row(ClinicalElement element, String colour) {
        String id = element.id();
        boolean selected = id.equals(selectedId);
        Circle dot = new Circle(5, Color.web(colour));
        element.reason().ifPresent(r -> Tooltip.install(dot, new Tooltip("Why: " + r)));

        Label quotes = new Label(String.join(" · ", element.quotes(set.narrative()).stream().map(q -> "“" + q + "”").toList()));
        quotes.setStyle("-fx-font-style: italic; -fx-text-fill: #5e4b7e; -fx-font-size: 11.5;");
        quotes.setWrapText(true);
        TextField sentence = new TextField(element.sentence());
        sentence.setId("element-sentence-" + id);
        sentence.setOnAction(e -> commitSentence(id, sentence.getText()));
        sentence.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                commitSentence(id, sentence.getText());
            }
        });
        HBox.setHgrow(sentence, Priority.ALWAYS);
        VBox body = new VBox(2, quotes, sentence);
        if (!element.links().isEmpty()) {
            Label links = new Label("linked to " + String.join(", ", element.links().stream()
                    .map(l -> set.element(l).map(ClinicalElement::sentence).orElse(l)).toList()));
            links.setStyle("-fx-text-fill: #888; -fx-font-size: 10.5;");
            links.setWrapText(true);
            body.getChildren().add(links);
        }
        HBox.setHgrow(body, Priority.ALWAYS);

        Label state = stateTag(element.state());
        state.setId("element-state-" + id);
        CheckBox reviewed = new CheckBox("reviewed");
        reviewed.setId("element-reviewed-" + id);
        reviewed.setSelected(element.accepted());
        reviewed.selectedProperty().addListener((obs, was, is) -> host.changed(set.accept(id, is)));

        Button lift = small(element.state() == ElementState.NOT_LIFTED ? "Lift" : "Lift again", "element-lift-" + id,
                () -> {
                    if (!id.equals(selectedId)) {
                        select(id);
                    }
                    host.lift(List.of(id));
                });
        Button split = small("Split", "element-split-" + id,
                () -> host.changed(set.split(id, element.sentence(), element.sentence() + " (split)")));
        Optional<ClinicalElement> next = nextAfter(id);
        Button merge = small("Merge ↓", "element-merge-" + id, () -> next.ifPresent(n ->
                host.changed(set.merge(id, n.id(), joined(element.sentence(), n.sentence())))));
        merge.setDisable(next.isEmpty());
        merge.setTooltip(new Tooltip("Merge with the element below: only when the two could not be true or false independently"));
        Button remove = small("✕", "element-remove-" + id, () -> host.changed(set.remove(id)));
        remove.setTooltip(new Tooltip("Remove this element; its words are then uncovered"));
        MenuButton link = linkMenu(element);
        HBox actions = new HBox(4, lift, split, merge, link, remove);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (element.state() == ElementState.STALE) {
            Button keep = small("Keep lift", "element-keep-" + id, () -> host.changed(set.replace(element.acceptLift())));
            keep.setTooltip(new Tooltip("Keep the statement as it is for the edited sentence"));
            actions.getChildren().add(1, keep);
        }
        VBox side = new VBox(3, new HBox(6, state, reviewed), actions);

        HBox row = new HBox(8, dot, body, side);
        row.setId("element-" + id);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(6, 4, 6, 4));
        row.setStyle("-fx-border-color: #ece8f2; -fx-border-width: 0 0 1 0;"
                + (selected ? "-fx-background-color: #f4f1fb;" : ""));
        row.setOnMouseClicked(e -> {
            if (!id.equals(selectedId)) {
                select(id);
            }
        });
        return row;
    }

    private Optional<ClinicalElement> nextAfter(String id) {
        List<ClinicalElement> list = set.elements();
        for (int i = 0; i + 1 < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                return Optional.of(list.get(i + 1));
            }
        }
        return Optional.empty();
    }

    private static String joined(String a, String b) {
        String first = a.strip();
        if (first.endsWith(".")) {
            first = first.substring(0, first.length() - 1);
        }
        return first + "; " + b.strip();
    }

    private void commitSentence(String id, String text) {
        if (set == null || text == null || text.isBlank()) {
            return;
        }
        set.element(id).filter(e -> !e.sentence().equals(text.strip()))
                .ifPresent(e -> host.changed(set.edit(id, text)));
    }

    private static Label stateTag(ElementState state) {
        Label tag = new Label(state.word());
        String[] colours = switch (state) {
            case LIFTED -> new String[] {"#ddefe3", "#0f6e56"};
            case NEEDS_ATTENTION -> new String[] {"#faeeda", "#854f0b"};
            case STALE -> new String[] {"#fcebeb", "#a32d2d"};
            case NOT_LIFTED -> new String[] {"#ece8f2", "#5e4b7e"};
        };
        tag.setStyle("-fx-background-color: " + colours[0] + "; -fx-text-fill: " + colours[1]
                + "; -fx-background-radius: 4; -fx-padding: 1 6 1 6; -fx-font-size: 10.5;");
        return tag;
    }

    /**
     * The link gesture (dev-anf-element-isolation, step one): one check item per other element of
     * the note, checked when the two are linked, so "sitting" is linked to the blood pressure it
     * qualifies where the author sees both spans. A link is symmetric; unchecking forgets it on both.
     */
    private MenuButton linkMenu(ClinicalElement element) {
        String id = element.id();
        MenuButton menu = new MenuButton("Link");
        menu.setId("element-link-" + id);
        menu.setStyle("-fx-font-size: 10.5; -fx-padding: 0 2 0 2;");
        menu.setTooltip(new Tooltip("Link this element to one it qualifies or is qualified by"));
        int i = 0;
        for (ClinicalElement other : set.elements()) {
            String colour = ElementColours.of(i++);
            if (other.id().equals(id)) {
                continue;
            }
            CheckMenuItem item = new CheckMenuItem(other.sentence(), new Circle(4, Color.web(colour)));
            item.setId("element-link-" + id + "-" + other.id());
            item.setSelected(element.links().contains(other.id()));
            item.selectedProperty().addListener((obs, was, is) ->
                    host.changed(is ? set.link(id, other.id()) : set.unlink(id, other.id())));
            menu.getItems().add(item);
        }
        menu.setDisable(menu.getItems().isEmpty());
        return menu;
    }

    private static Button small(String text, String id, Runnable action) {
        Button button = new Button(text);
        button.setId(id);
        button.setStyle("-fx-font-size: 10.5; -fx-padding: 1 6 1 6;");
        button.setOnAction(e -> action.run());
        return button;
    }

    private void updateButtons() {
        boolean has = set != null && !noteChanged;
        addFromSelection.setDisable(busy || !has);
        expandAll.setDisable(set == null);
        collapseAll.setDisable(set == null);
        markNotClinical.setDisable(busy || !has);
        int unrecorded = set == null ? 0 : host.unrecordedNotClinical(set);
        recordNotClinical.setVisible(unrecorded > 0);
        recordNotClinical.setDisable(busy || !has);
        recordNotClinical.setTooltip(new Tooltip("Write " + unrecorded + (unrecorded == 1 ? " not-clinical mark" : " not-clinical marks")
                + " into the document's git history as Not clinical annotations, so every reader of the note sees them"));
        liftAll.setDisable(busy || !has || !set.allAccepted() || set.toLift().isEmpty());
        liftAll.setTooltip(new Tooltip(has && !set.allAccepted()
                ? "Mark every element reviewed first; a single element can be lifted at any time"
                : "Lift every element not yet lifted, and every stale one"));
    }

    // ── Gestures on a selection of the note ─────────────────────────────────

    private void addFromSelection() {
        host.selection().ifPresent(span -> {
            ElementSet before = set;
            ElementSet next = set.add(span.text(set.narrative()).strip(), List.of(span));
            host.changed(next);
            select(next.elements().stream().map(ClinicalElement::id)
                    .filter(i -> before.element(i).isEmpty()).findFirst().orElse(null));
        });
    }

    private void markNotClinical() {
        host.selection().ifPresent(span -> host.changed(set.markNotClinical(span)));
    }

    /** The rows' ids, in tree order, for a test. */
    List<String> rowIds() {
        List<String> ids = new ArrayList<>();
        rowNodes.values().forEach(n -> ids.add(n.getId()));
        return ids;
    }
}
