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
package network.ike.komet.claude.semantic;

import dev.ikm.komet.framework.dnd.KometClipboard;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.PatternEntity;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The semantic lift's pattern-graph arrangement surface (IKE-Network/ike-issues#759): the author
 * drags pattern Koncepts in and arranges how their semantics attach. The substrate is a
 * <em>graph</em>, not a tree — a pattern may attach under several parents, and an
 * <em>ambient</em> pattern (e.g. a comment) attaches to any node. The subject (the concept the
 * whole set is about) is the implicit root; a node with no explicit parent attaches to it.
 *
 * <p>First iteration: drop-to-add, the arrangement model (per-node parents + ambient flag),
 * per-node display of the pattern's referenced-component meaning and purpose, and durable
 * {@linkplain #serialize() serialize}/{@linkplain #restoreFrom(String) restore} by PublicId so the
 * arrangement survives a restart. Candidate-edge derivation, meaning/purpose refinement, and the
 * semantic forest are later children of the epic.
 */
public final class PatternGraphPanel extends VBox {

    /** Sentinel "parent" denoting the subject concept (the graph root). */
    static final int SUBJECT = Integer.MIN_VALUE;
    private static final String SUBJECT_TOKEN = "S";

    private final Supplier<ViewCalculator> viewSupplier;
    private final ObservableList<PatternNode> nodes = FXCollections.observableArrayList();
    private final VBox rows = new VBox(6);
    private Runnable onChange = () -> {
    };

    /**
     * Creates the panel bound to a view supplier (for resolving pattern labels and meaning/purpose).
     *
     * @param viewSupplier supplies the current {@link ViewCalculator}; must not be null
     */
    public PatternGraphPanel(Supplier<ViewCalculator> viewSupplier) {
        this.viewSupplier = viewSupplier;
        setSpacing(8);
        getChildren().addAll(dropZone(), rows);
        refreshRows();
    }

    /** The bound pattern nodes, in insertion order (the arrangement model). */
    ObservableList<PatternNode> nodes() {
        return nodes;
    }

    /**
     * Sets the callback fired whenever the arrangement changes (add/remove/parent/ambient), so the
     * host can persist the graph.
     *
     * @param onChange the change callback; null is treated as a no-op
     */
    void setOnChange(Runnable onChange) {
        this.onChange = (onChange == null) ? () -> {
        } : onChange;
    }

    // ── Drop target ─────────────────────────────────────────────────────────

    private Region dropZone() {
        Label prompt = new Label("Drag pattern Koncepts here to build the substrate graph…");
        prompt.setStyle("-fx-text-fill: #999999; -fx-font-size: 11;");
        VBox zone = new VBox(prompt);
        zone.setAlignment(Pos.CENTER);
        zone.setMinHeight(48);
        zone.setPadding(new Insets(10));
        zone.setStyle("-fx-border-color: #cccccc; -fx-border-style: dashed; -fx-border-width: 1; "
                + "-fx-border-radius: 6; -fx-background-color: #fafafa;");
        zone.setOnDragOver(event -> {
            if (event.getGestureSource() != zone && carriesPattern(event.getDragboard())) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        zone.setOnDragDropped(event -> {
            OptionalInt nid = KometClipboard.entityNidFrom(event.getDragboard());
            boolean added = nid.isPresent() && addPattern(nid.getAsInt());
            event.setDropCompleted(added);
            event.consume();
        });
        return zone;
    }

    private static boolean carriesPattern(Dragboard dragboard) {
        return dragboard != null
                && KometClipboard.PATTERN_TYPES.stream().anyMatch(dragboard::hasContent);
    }

    /**
     * Adds a dropped pattern by nid: rejects non-patterns and duplicates, builds its node attached to
     * the subject by default, and notifies the change callback.
     *
     * @param nid the dropped component nid
     * @return true if a pattern node was added
     */
    boolean addPattern(int nid) {
        if (containsPattern(nid)) {
            return false;
        }
        PatternNode node = createNode(nid);
        if (node == null) {
            return false;
        }
        nodes.add(node);
        changed();
        return true;
    }

    private boolean containsPattern(int nid) {
        for (PatternNode existing : nodes) {
            if (existing.patternNid == nid) {
                return true;
            }
        }
        return false;
    }

    /** Builds a node for a pattern nid (resolving label + referenced-component meaning/purpose), or null. */
    private PatternNode createNode(int nid) {
        ViewCalculator view = view();
        if (view == null) {
            return null;
        }
        try {
            Entity<?> entity = EntityHandle.getEntityOrThrow(nid);
            if (!(entity instanceof PatternEntity)) {
                return null;
            }
        } catch (RuntimeException notResolvable) {
            return null;
        }
        String label = label(view, nid);
        String meaning = "—";
        String purpose = "—";
        Latest<PatternEntityVersion> latest = view.stampCalculator().latest(nid);
        if (latest.isPresent()) {
            meaning = label(view, latest.get().semanticMeaningNid());
            purpose = label(view, latest.get().semanticPurposeNid());
        }
        return new PatternNode(nid, label, meaning, purpose);
    }

    private void changed() {
        refreshRows();
        try {
            onChange.run();
        } catch (RuntimeException ignored) {
            // a host persistence callback must not break the panel
        }
    }

    // ── Persistence (durable by PublicId) ────────────────────────────────────

    /**
     * Serializes the arrangement to a restorable string: one line per node,
     * {@code <patternUuid>;<ambient 0|1>;<parentToken,parentToken,…>} where a parent token is the
     * subject sentinel {@code S} or a parent pattern's UUID. UUIDs (durable) are used so the graph
     * round-trips independent of machine-local nids.
     *
     * @return the serialized arrangement (never null; empty when no nodes)
     */
    String serialize() {
        StringBuilder out = new StringBuilder();
        for (PatternNode node : nodes) {
            String uuid = uuidOf(node.patternNid);
            if (uuid == null) {
                continue;
            }
            StringBuilder parents = new StringBuilder();
            for (Integer parent : node.parents) {
                if (parents.length() > 0) {
                    parents.append(',');
                }
                parents.append(parent == SUBJECT ? SUBJECT_TOKEN : uuidOf(parent));
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(uuid).append(';').append(node.ambient.get() ? '1' : '0').append(';').append(parents);
        }
        return out.toString();
    }

    /**
     * Rebuilds the arrangement from a {@link #serialize()} string, resolving UUIDs to nids against
     * the current view. Malformed lines or unresolvable patterns are skipped rather than failing the
     * whole restore. Does not fire the change callback (a restore is not a user edit).
     *
     * @param serialized the serialized arrangement; null or blank clears the panel
     */
    void restoreFrom(String serialized) {
        nodes.clear();
        if (serialized != null && !serialized.isBlank()) {
            for (String line : serialized.split("\n")) {
                restoreLine(line);
            }
        }
        refreshRows();
    }

    private void restoreLine(String line) {
        try {
            String[] parts = line.split(";", -1);
            if (parts.length < 2) {
                return;
            }
            int nid = resolveUuid(parts[0]);
            if (nid == SUBJECT || containsPattern(nid)) {
                return;
            }
            PatternNode node = createNode(nid);
            if (node == null) {
                return;
            }
            node.ambient.set("1".equals(parts[1]));
            node.parents.clear();
            if (parts.length >= 3 && !parts[2].isBlank()) {
                for (String token : parts[2].split(",")) {
                    if (SUBJECT_TOKEN.equals(token)) {
                        node.parents.add(SUBJECT);
                    } else {
                        int parentNid = resolveUuid(token);
                        if (parentNid != SUBJECT) {
                            node.parents.add(parentNid);
                        }
                    }
                }
            }
            if (node.parents.isEmpty()) {
                node.parents.add(SUBJECT);
            }
            nodes.add(node);
        } catch (RuntimeException malformed) {
            // skip a malformed line
        }
    }

    private static String uuidOf(int nid) {
        try {
            UUID[] uuids = PrimitiveData.publicId(nid).asUuidArray();
            return uuids.length == 0 ? null : uuids[0].toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Resolves a UUID string to a nid, or {@link #SUBJECT} (used here as a "no nid" marker) on failure. */
    private static int resolveUuid(String uuidStr) {
        try {
            return EntityService.get().nidForPublicId(PublicIds.of(UUID.fromString(uuidStr.trim())));
        } catch (RuntimeException notResolvable) {
            return SUBJECT;
        }
    }

    // ── Row rendering ────────────────────────────────────────────────────────

    private void refreshRows() {
        rows.getChildren().clear();
        if (nodes.isEmpty()) {
            Label empty = new Label("No patterns yet. The first dropped pattern becomes a child of the "
                    + "subject; others can reference it.");
            empty.setWrapText(true);
            empty.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10;");
            rows.getChildren().add(empty);
            return;
        }
        for (PatternNode node : nodes) {
            rows.getChildren().add(rowFor(node));
        }
    }

    private Region rowFor(PatternNode node) {
        Label title = new Label(node.label);
        title.setStyle("-fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button remove = new Button("✕");
        remove.setStyle("-fx-font-size: 9; -fx-padding: 1 5 1 5;");
        remove.setOnAction(e -> {
            nodes.remove(node);
            changed();
        });
        HBox header = new HBox(6, title, spacer, remove);
        header.setAlignment(Pos.CENTER_LEFT);

        Label definition = new Label("meaning: " + node.meaningLabel + "   ·   purpose: " + node.purposeLabel);
        definition.setStyle("-fx-text-fill: #888888; -fx-font-size: 10;");

        CheckBox ambient = new CheckBox("ambient (attaches to any node)");
        ambient.setStyle("-fx-font-size: 10;");
        ambient.setSelected(node.ambient.get());
        ambient.selectedProperty().addListener((o, was, is) -> {
            node.ambient.set(is);
            changed();
        });

        VBox row = new VBox(4, header, definition, attachmentLine(node, ambient));
        row.setPadding(new Insets(6));
        row.setStyle("-fx-border-color: #eeeeee; -fx-border-width: 1; -fx-border-radius: 6; "
                + "-fx-background-color: white;");
        return row;
    }

    /** The "references:" line — either "any node" (ambient) or the removable parent chips + add control. */
    private Region attachmentLine(PatternNode node, CheckBox ambient) {
        HBox line = new HBox(6);
        line.setAlignment(Pos.CENTER_LEFT);
        Label caption = new Label("references:");
        caption.setStyle("-fx-text-fill: #888888; -fx-font-size: 10;");
        line.getChildren().add(caption);

        if (node.ambient.get()) {
            Label any = new Label("any node");
            any.setStyle("-fx-font-size: 10; -fx-font-style: italic;");
            line.getChildren().addAll(any, ambient);
            return line;
        }

        FlowPane chips = new FlowPane(4, 4);
        for (Integer parent : new ArrayList<>(node.parents)) {
            chips.getChildren().add(parentChip(node, parent));
        }
        ComboBox<Integer> addParent = new ComboBox<>();
        addParent.setPromptText("add parent…");
        addParent.setStyle("-fx-font-size: 10;");
        addParent.getItems().addAll(candidateParents(node));
        addParent.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer id) {
                return id == null ? "" : parentLabel(id);
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        });
        addParent.valueProperty().addListener((o, was, picked) -> {
            if (picked != null && !node.parents.contains(picked)) {
                node.parents.add(picked);
                changed();
            }
        });

        HBox.setHgrow(chips, Priority.ALWAYS);
        line.getChildren().addAll(chips, addParent, ambient);
        return line;
    }

    private Region parentChip(PatternNode node, Integer parent) {
        Label text = new Label(parentLabel(parent));
        text.setStyle("-fx-font-size: 10;");
        Button drop = new Button("✕");
        drop.setStyle("-fx-font-size: 8; -fx-padding: 0 4 0 4;");
        drop.setOnAction(e -> {
            node.parents.remove(parent);
            changed();
        });
        HBox chip = new HBox(2, text, drop);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setStyle("-fx-background-color: #eef2f7; -fx-background-radius: 8; -fx-padding: 1 4 1 6;");
        return chip;
    }

    /** Candidate parents to offer: the subject (if not already a parent) plus the other patterns. */
    private List<Integer> candidateParents(PatternNode node) {
        List<Integer> candidates = new ArrayList<>();
        if (!node.parents.contains(SUBJECT)) {
            candidates.add(SUBJECT);
        }
        for (PatternNode other : nodes) {
            if (other != node && !node.parents.contains(other.patternNid)) {
                candidates.add(other.patternNid);
            }
        }
        return candidates;
    }

    private String parentLabel(Integer parent) {
        if (parent == null) {
            return "";
        }
        if (parent == SUBJECT) {
            return "subject";
        }
        for (PatternNode node : nodes) {
            if (node.patternNid == parent) {
                return node.label;
            }
        }
        return "pattern " + parent;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ViewCalculator view() {
        try {
            return viewSupplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String label(ViewCalculator view, int nid) {
        return view.getFullyQualifiedNameText(nid)
                .orElseGet(() -> view.getPreferredDescriptionTextWithFallbackOrNid(nid));
    }

    /**
     * One pattern node in the arrangement graph: the pattern, its referenced-component meaning and
     * purpose, the set of parents its semantics reference (the subject sentinel or other pattern
     * nids), and whether it is ambient (attaches to any node). A new node starts attached to the
     * subject.
     */
    static final class PatternNode {
        final int patternNid;
        final String label;
        final String meaningLabel;
        final String purposeLabel;
        final ObservableList<Integer> parents = FXCollections.observableArrayList(SUBJECT);
        final BooleanProperty ambient = new SimpleBooleanProperty(false);

        PatternNode(int patternNid, String label, String meaningLabel, String purposeLabel) {
            this.patternNid = patternNid;
            this.label = label;
            this.meaningLabel = meaningLabel;
            this.purposeLabel = purposeLabel;
        }
    }
}
