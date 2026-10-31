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
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import network.ike.komet.claude.anf.element.ClinicalElement;
import network.ike.komet.claude.anf.element.ElementColours;
import network.ike.komet.claude.anf.element.ElementSet;
import network.ike.komet.claude.anf.element.ElementState;
import network.ike.komet.claude.anf.element.Span;

/**
 * Step one of the two-step lift on the lift region ({@code dev-anf-element-isolation}): the note
 * with each element's words underlined in its colour and the words no element covers shaded, and
 * the element list beneath it, each row the quoted words, the element sentence to edit, the
 * element's state, whether it is reviewed, and its gestures. The pane holds no lift logic: every
 * gesture computes a new {@link ElementSet} and hands it to the {@link Host}, which persists it and
 * redraws; a lift is asked of the host by element id.
 */
final class ElementsPane extends VBox {

    /** What the pane asks of the region it sits in. */
    interface Host {
        /** The element set changed by a gesture: persist it and redraw. */
        void changed(ElementSet set);

        /** An element was selected, or none: show its statement. */
        void selected(Optional<ClinicalElement> element);

        /** Lift these elements, in order. */
        void lift(List<String> ids);

        /** The span the author has selected in the note, when there is one. */
        Optional<Span> selection();
    }

    private final Host host;
    private final TextFlow note = new TextFlow();
    private final VBox rows = new VBox();
    private final Label coverage = new Label();
    private final Button addFromSelection = new Button("Add from selection");
    private final Button markNotClinical = new Button("Mark not clinical");
    private final Button liftAll = new Button("Lift elements");
    private ElementSet set;
    private String selectedId;
    private boolean busy;
    private boolean noteChanged;

    ElementsPane(Host host) {
        super(6);
        this.host = host;
        setPadding(new Insets(4));
        note.setId("element-note");
        note.setLineSpacing(6);
        note.setPadding(new Insets(6, 8, 6, 8));
        note.setStyle("-fx-background-color: white; -fx-border-color: #e6e3ee; -fx-border-radius: 6; -fx-background-radius: 6;");
        coverage.setId("element-coverage");
        coverage.setWrapText(true);
        coverage.setStyle("-fx-text-fill: #8a5a00; -fx-font-size: 11;");
        addFromSelection.setId("add-element-button");
        addFromSelection.setOnAction(e -> addFromSelection());
        markNotClinical.setId("not-clinical-button");
        markNotClinical.setOnAction(e -> markNotClinical());
        liftAll.setId("lift-elements-button");
        liftAll.setOnAction(e -> host.lift(set == null ? List.of() : set.toLift().stream().map(ClinicalElement::id).toList()));
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        HBox tools = new HBox(6, addFromSelection, markNotClinical, spring, liftAll);
        tools.setAlignment(Pos.CENTER_LEFT);
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(note, coverage, tools, scroll);
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
        noteChanged = false;
        rebuild();
    }

    void setBusy(boolean isBusy) {
        this.busy = isBusy;
        updateButtons();
        rows.setDisable(isBusy);
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

    void select(String id) {
        selectedId = id;
        rebuild();
        host.selected(selected());
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    private void rebuild() {
        note.getChildren().clear();
        rows.getChildren().clear();
        if (set == null) {
            Text prompt = new Text("Isolate the note's clinical elements to see each fact the lift will make a statement of.");
            prompt.setFill(Color.web("#888"));
            note.getChildren().add(prompt);
            coverage.setText("");
            updateButtons();
            return;
        }
        drawNote();
        List<Span> uncovered = set.uncovered();
        if (noteChanged) {
            coverage.setText("The note has changed since its elements were isolated: isolate again, or undo the change.");
        } else if (uncovered.isEmpty()) {
            coverage.setText("Every part of the note is accounted for.");
            coverage.setStyle("-fx-text-fill: #0f6e56; -fx-font-size: 11;");
        } else {
            coverage.setStyle("-fx-text-fill: #8a5a00; -fx-font-size: 11;");
            coverage.setText(uncovered.size() + (uncovered.size() == 1 ? " stretch" : " stretches")
                    + " of the note, shaded, support no element and are not marked not clinical.");
        }
        int i = 0;
        for (ClinicalElement element : set.elements()) {
            rows.getChildren().add(row(element, colour(i++)));
        }
        updateButtons();
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

    /** The note, word by word: underlined in the colour of the element each word supports, shaded where none does. */
    private void drawNote() {
        String text = set.narrative();
        TreeSet<Integer> cuts = new TreeSet<>(List.of(0, text.length()));
        for (ClinicalElement e : set.elements()) {
            for (Span s : e.spans()) {
                cuts.add(s.start());
                cuts.add(Math.min(s.end(), text.length()));
            }
        }
        for (Span s : set.notClinical()) {
            cuts.add(s.start());
            cuts.add(Math.min(s.end(), text.length()));
        }
        List<Span> uncovered = set.uncovered();
        Integer previous = null;
        for (int cut : cuts) {
            if (previous != null && cut > previous) {
                piece(text, previous, cut, uncovered);
            }
            previous = cut;
        }
    }

    private void piece(String text, int from, int to, List<Span> uncovered) {
        List<ClinicalElement> over = set.at(from);
        boolean notClinical = set.notClinical().stream().anyMatch(s -> s.contains(from));
        boolean gap = uncovered.stream().anyMatch(s -> s.contains(from));
        // word by word, so a long stretch wraps
        int i = from;
        while (i < to) {
            int j = i;
            while (j < to && !Character.isWhitespace(text.charAt(j))) {
                j++;
            }
            if (j > i) {
                Label word = new Label(text.substring(i, j));
                word.setStyle(style(over, notClinical, gap));
                if (!over.isEmpty()) {
                    word.setCursor(Cursor.HAND);
                    String id = over.get(0).id();
                    word.setOnMouseClicked(e -> select(id));
                    word.setTooltip(new Tooltip(String.join("\n", over.stream().map(ClinicalElement::sentence).toList())));
                }
                note.getChildren().add(word);
            }
            int k = j;
            while (k < to && Character.isWhitespace(text.charAt(k))) {
                k++;
            }
            if (k > j) {
                note.getChildren().add(new Text(text.substring(j, k)));
            }
            i = k;
        }
    }

    private String style(List<ClinicalElement> over, boolean notClinical, boolean gap) {
        StringBuilder style = new StringBuilder("-fx-font-size: 13.5; -fx-padding: 0 0 1 0;");
        if (!over.isEmpty()) {
            String colour = colourOf(over.get(0).id());
            boolean selected = over.stream().anyMatch(e -> e.id().equals(selectedId));
            style.append("-fx-border-color: transparent transparent ").append(colour).append(" transparent;")
                    .append("-fx-border-width: 0 0 ").append(selected ? 3 : 2).append(" 0;");
            if (selected) {
                style.append("-fx-background-color: #f4f1fb;");
            }
            if (over.size() > 1) {
                style.append("-fx-border-style: solid solid segments(5, 2) solid;");
            }
        } else if (notClinical) {
            style.append("-fx-text-fill: #9a9a9a;");
        } else if (gap) {
            style.append("-fx-background-color: #f1efe8; -fx-text-fill: #7a776d;")
                    .append("-fx-border-color: transparent transparent #b4b2a9 transparent; -fx-border-width: 0 0 1 0;")
                    .append("-fx-border-style: solid solid segments(3, 2) solid;");
        }
        return style.toString();
    }

    private Node row(ClinicalElement element, String colour) {
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
        markNotClinical.setDisable(busy || !has);
        liftAll.setDisable(busy || !has || !set.allAccepted() || set.toLift().isEmpty());
        liftAll.setTooltip(new Tooltip(has && !set.allAccepted()
                ? "Mark every element reviewed first; a single element can be lifted at any time"
                : "Lift every element not yet lifted, and every stale one"));
    }

    // ── Gestures on a selection of the note ─────────────────────────────────

    private void addFromSelection() {
        host.selection().ifPresent(span -> {
            ElementSet next = set.add(span.text(set.narrative()).strip(), List.of(span));
            host.changed(next);
            selectedId = next.elements().stream().map(ClinicalElement::id)
                    .filter(i -> set.element(i).isEmpty()).findFirst().orElse(null);
            rebuild();
            host.selected(selected());
        });
    }

    private void markNotClinical() {
        host.selection().ifPresent(span -> host.changed(set.markNotClinical(span)));
    }

    /** The rows' ids, for a test. */
    List<String> rowIds() {
        List<String> ids = new ArrayList<>();
        rows.getChildren().forEach(n -> ids.add(n.getId()));
        return ids;
    }
}
