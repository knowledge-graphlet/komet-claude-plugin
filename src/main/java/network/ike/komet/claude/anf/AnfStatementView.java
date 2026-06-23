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

import dev.ikm.komet.framework.controls.KonceptBadge;
import dev.ikm.komet.framework.dnd.DropHelper;
import dev.ikm.komet.framework.dnd.KometClipboard;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.layout.controls.KlConceptField;
import dev.ikm.tinkar.common.service.PrimitiveData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.function.Function;

/**
 * Renders a validated {@link AnfStatement} as a read-only JavaFX node — the rich
 * projection of the lift's formal pane. Grounded concepts render with the shared
 * Komet {@link KonceptBadge} atom (identicon + name + taxonomic status glyph, and a
 * Komet-concept <em>drag source</em>), so an ANF concept drags into Komet's concept
 * viewers exactly as a concept dragged from anywhere else in Komet; candidate and
 * clarify slots render as distinct badges so the three-way honesty of the lift stays
 * visible.
 *
 * <p>When an {@link Editor} is supplied, each concept slot is also a <em>drop
 * target</em>: dropping a Komet concept onto it substitutes that concept for the one
 * the lift proposed (re-resolving the dropped concept through the editor and
 * rebuilding the statement for that slot's position).
 */
public final class AnfStatementView {

    /** Concept-chip identicon size (px) and font — the central knob for "make the concepts bigger". */
    private static final double CHIP_ICON_PX = 22;
    private static final String CHIP_FONT_STYLE = "-fx-font-size: 14px;";

    private static final String CANDIDATE_STYLE =
            "-fx-background-color: #fdf3e7; -fx-background-radius: 6; -fx-padding: 1 6 1 4; "
            + "-fx-border-color: #d9a441; -fx-border-radius: 6; -fx-border-style: segments(4, 3);";
    private static final String CLARIFY_STYLE =
            "-fx-background-color: #f3eef7; -fx-background-radius: 6; -fx-padding: 1 6 1 4;";

    private AnfStatementView() {
    }

    /**
     * Renders a statement read-only, with no view (presentation-only badges, no drag).
     *
     * @param s the validated statement; must not be null
     * @return a node for the formal pane
     */
    public static Node render(AnfStatement s) {
        return render(s, null, null);
    }

    /**
     * Renders a statement. Grounded concept chips are Komet concept drag sources
     * whenever {@code view} is non-null. When {@code editor} is non-null, each concept
     * slot also accepts a dropped Komet concept and substitutes it for the proposed one.
     *
     * @param s      the validated statement; must not be null
     * @param view   the view for live, draggable badges; null renders presentation-only
     * @param editor the substitution editor, or null for a non-editable view
     * @return a node for the formal pane
     */
    public static Node render(AnfStatement s, ViewProperties view, Editor editor) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(8));
        box.getChildren().add(label(s.statementType().name().toLowerCase(Locale.ROOT) + " statement",
                "-fx-font-weight: bold;"));

        if (s.topic() != null) {
            box.getChildren().add(row("Topic",
                    slotNode(s.topic(), view, editor, g -> withTopic(s, g),
                            s.statementType() == AnfStatement.Type.NARRATIVE)));
        }
        if (s.subjectOfInformation() != null) {
            box.getChildren().add(row("Subject",
                    slotNode(s.subjectOfInformation(), view, editor, g -> withSubject(s, g), true)));
        }
        renderCircumstance(box, s, view, editor);
        return box;
    }

    /**
     * Renders the statement's circumstance — exhaustively over the sealed {@link Circumstance}
     * kind, so a new kind is a compile error here. Single concept-slot fields are drop targets
     * that substitute the slot; list and timing fields render their chips (draggable) without
     * substitution.
     */
    private static void renderCircumstance(VBox box, AnfStatement s, ViewProperties view, Editor editor) {
        switch (s.circumstance()) {
            case Circumstance.Performance p -> {
                if (p.result() != null) {
                    box.getChildren().add(measureRow("Result", p.result(), view, editor,
                            m -> perfResult(s, p, m)));
                }
                addSlotRow(box, "Status", p.status(), view, editor, g -> perfStatus(s, p, g));
                addSlotRow(box, "Body site", p.bodySite(), view, editor, g -> perfBodySite(s, p, g));
                addSlotRow(box, "Method", p.method(), view, editor, g -> perfMethod(s, p, g));
                addSlotRow(box, "Laterality", p.laterality(), view, editor, g -> perfLaterality(s, p, g));
                addSlotRow(box, "Health risk", p.healthRisk(), view, editor, g -> perfHealthRisk(s, p, g));
                if (p.normalRange() != null) {
                    box.getChildren().add(measureRow("Normal range", p.normalRange(), view, editor,
                            m -> perfNormalRange(s, p, m)));
                }
                addListRow(box, "Purpose", p.purpose(), view);
                addReadonlyMeasure(box, "Timing", p.timing(), view);
            }
            case Circumstance.Request r -> {
                if (r.requestedResult() != null) {
                    box.getChildren().add(measureRow("Requested", r.requestedResult(), view, editor,
                            m -> reqRequestedResult(s, r, m)));
                }
                addSlotRow(box, "Priority", r.priority(), view, editor, g -> reqPriority(s, r, g));
                addSlotRow(box, "Method", r.method(), view, editor, g -> reqMethod(s, r, g));
                addListRow(box, "Triggers", r.conditionalTrigger(), view);
                renderRepetition(box, r.repetition(), view);
                addListRow(box, "Purpose", r.purpose(), view);
                addReadonlyMeasure(box, "Timing", r.timing(), view);
            }
            case Circumstance.Narrative n -> {
                box.getChildren().add(row("Narrative", label(n.text(), "")));
                addListRow(box, "Purpose", n.purpose(), view);
                addReadonlyMeasure(box, "Timing", n.timing(), view);
            }
        }
    }

    /** Adds a labelled row for a single concept slot, droppable to substitute it, when present. */
    private static void addSlotRow(VBox box, String labelText, AnfSlot slot, ViewProperties view,
                                   Editor editor, Function<AnfSlot.Grounded, AnfStatement> rebuild) {
        if (slot != null) {
            box.getChildren().add(row(labelText, slotNode(slot, view, editor, rebuild, true)));
        }
    }

    /** A measure row: the interval plus a drop-to-substitute measure-semantic chip. */
    private static HBox measureRow(String labelText, AnfStatement.Result result, ViewProperties view,
                                   Editor editor, Function<AnfStatement.Result, AnfStatement> rebuild) {
        return row(labelText, label(interval(result), ""),
                slotNode(result.measureSemantic(), view, editor,
                        g -> rebuild.apply(withMeasureSemantic(result, g)), false));
    }

    /** Adds a read-only measure row (no substitution), when the measure is present. */
    private static void addReadonlyMeasure(VBox box, String labelText, AnfStatement.Result result,
                                           ViewProperties view) {
        if (result != null) {
            box.getChildren().add(row(labelText, label(interval(result), ""),
                    chipFor(result.measureSemantic(), view)));
        }
    }

    /** Adds a labelled row of chips for a list of slots (draggable, not substitutable), when non-empty. */
    private static void addListRow(VBox box, String labelText, java.util.List<AnfSlot> slots,
                                   ViewProperties view) {
        if (slots != null && !slots.isEmpty()) {
            Node[] chips = new Node[slots.size()];
            for (int i = 0; i < slots.size(); i++) {
                chips[i] = chipFor(slots.get(i), view);
            }
            box.getChildren().add(row(labelText, chips));
        }
    }

    /** Renders a request's repetition schedule as read-only measure rows. */
    private static void renderRepetition(VBox box, Circumstance.Repetition repetition, ViewProperties view) {
        if (repetition == null) {
            return;
        }
        addReadonlyMeasure(box, "Period start", repetition.periodStart(), view);
        addReadonlyMeasure(box, "Period duration", repetition.periodDuration(), view);
        addReadonlyMeasure(box, "Event separation", repetition.eventSeparation(), view);
        addReadonlyMeasure(box, "Event duration", repetition.eventDuration(), view);
        addReadonlyMeasure(box, "Event frequency", repetition.eventFrequency(), view);
    }

    // ── Statement rebuilders for drop-to-substitute (records are immutable) ──────

    private static AnfStatement withTopic(AnfStatement s, AnfSlot.Grounded topic) {
        return new AnfStatement(s.statementType(), topic, s.subjectOfInformation(), s.circumstance());
    }

    private static AnfStatement withSubject(AnfStatement s, AnfSlot.Grounded subject) {
        return new AnfStatement(s.statementType(), s.topic(), subject, s.circumstance());
    }

    private static AnfStatement withCircumstance(AnfStatement s, Circumstance circumstance) {
        return new AnfStatement(s.statementType(), s.topic(), s.subjectOfInformation(), circumstance);
    }

    private static AnfStatement.Result withMeasureSemantic(AnfStatement.Result r, AnfSlot.Grounded m) {
        return new AnfStatement.Result(r.lowerBound(), r.upperBound(),
                r.includeLowerBound(), r.includeUpperBound(), m);
    }

    private static AnfStatement perfStatus(AnfStatement s, Circumstance.Performance p, AnfSlot.Grounded v) {
        return withCircumstance(s, new Circumstance.Performance(p.timing(), p.purpose(), v, p.result(),
                p.healthRisk(), p.normalRange(), p.bodySite(), p.method(), p.laterality()));
    }

    private static AnfStatement perfResult(AnfStatement s, Circumstance.Performance p, AnfStatement.Result v) {
        return withCircumstance(s, new Circumstance.Performance(p.timing(), p.purpose(), p.status(), v,
                p.healthRisk(), p.normalRange(), p.bodySite(), p.method(), p.laterality()));
    }

    private static AnfStatement perfHealthRisk(AnfStatement s, Circumstance.Performance p, AnfSlot.Grounded v) {
        return withCircumstance(s, new Circumstance.Performance(p.timing(), p.purpose(), p.status(), p.result(),
                v, p.normalRange(), p.bodySite(), p.method(), p.laterality()));
    }

    private static AnfStatement perfNormalRange(AnfStatement s, Circumstance.Performance p,
                                                AnfStatement.Result v) {
        return withCircumstance(s, new Circumstance.Performance(p.timing(), p.purpose(), p.status(), p.result(),
                p.healthRisk(), v, p.bodySite(), p.method(), p.laterality()));
    }

    private static AnfStatement perfBodySite(AnfStatement s, Circumstance.Performance p, AnfSlot.Grounded v) {
        return withCircumstance(s, new Circumstance.Performance(p.timing(), p.purpose(), p.status(), p.result(),
                p.healthRisk(), p.normalRange(), v, p.method(), p.laterality()));
    }

    private static AnfStatement perfMethod(AnfStatement s, Circumstance.Performance p, AnfSlot.Grounded v) {
        return withCircumstance(s, new Circumstance.Performance(p.timing(), p.purpose(), p.status(), p.result(),
                p.healthRisk(), p.normalRange(), p.bodySite(), v, p.laterality()));
    }

    private static AnfStatement perfLaterality(AnfStatement s, Circumstance.Performance p, AnfSlot.Grounded v) {
        return withCircumstance(s, new Circumstance.Performance(p.timing(), p.purpose(), p.status(), p.result(),
                p.healthRisk(), p.normalRange(), p.bodySite(), p.method(), v));
    }

    private static AnfStatement reqPriority(AnfStatement s, Circumstance.Request r, AnfSlot.Grounded v) {
        return withCircumstance(s, new Circumstance.Request(r.timing(), r.purpose(), v, r.requestedResult(),
                r.repetition(), r.conditionalTrigger(), r.method()));
    }

    private static AnfStatement reqRequestedResult(AnfStatement s, Circumstance.Request r,
                                                   AnfStatement.Result v) {
        return withCircumstance(s, new Circumstance.Request(r.timing(), r.purpose(), r.priority(), v,
                r.repetition(), r.conditionalTrigger(), r.method()));
    }

    private static AnfStatement reqMethod(AnfStatement s, Circumstance.Request r, AnfSlot.Grounded v) {
        return withCircumstance(s, new Circumstance.Request(r.timing(), r.purpose(), r.priority(),
                r.requestedResult(), r.repetition(), r.conditionalTrigger(), v));
    }

    /**
     * Editing hooks for a rendered statement: resolve a dropped concept and receive the
     * substituted statement. Lives here so the renderer can build the drop targets while
     * the host (which owns the view and the current statement) does the resolution and
     * re-render.
     */
    public interface Editor {
        /**
         * Resolves a dropped concept nid to a grounded slot in the host's view.
         *
         * @param conceptNid the dropped concept's nid
         * @return the grounded slot, or null if it does not resolve
         */
        AnfSlot.Grounded resolve(int conceptNid);

        /**
         * Receives the statement after a STRUCTURAL change (drop substitution, load), to store and
         * re-render the card.
         *
         * @param updated the new statement
         */
        void onEdited(AnfStatement updated);

        /**
         * Receives the statement after a single-field edit (an editable concept field's substitute
         * or clear). The host stores it but does <strong>not</strong> re-render the card — the field
         * already shows its own new state, so re-rendering would destroy sibling fields mid-edit.
         *
         * @param updated the new statement
         */
        void onFieldEdited(AnfStatement updated);

        /**
         * The type-ahead concept search backend for the editable fields.
         *
         * @return a completer, or null when editing is unavailable
         */
        dev.ikm.komet.layout.controls.KlConceptField.Completer completer();

        /**
         * Opens a concept's detail (descriptions + axioms) for a clicked field.
         *
         * @param slot the grounded slot to inspect
         */
        void showDetail(AnfSlot slot);
    }

    /**
     * The node for a concept slot: an editable {@link KlConceptField} when an {@link Editor} is
     * present and the slot is grounded (clear to empty, type to re-search, drop to substitute, click
     * for detail); otherwise the existing drop-substitutable badge (read-only contexts, or
     * candidate/clarify slots which have no concept to edit).
     *
     * @param slot      the slot to render
     * @param view      the view for live badges/search
     * @param editor    the editor, or null for read-only
     * @param rebuild   rebuilds the statement for this slot position; receives a grounded concept,
     *                  or {@code null} when the field is cleared
     * @param clearable whether the field offers the clear (X) — false for a typed topic / a unit
     * @return the slot node
     */
    private static Node slotNode(AnfSlot slot, ViewProperties view, Editor editor,
                                 Function<AnfSlot.Grounded, AnfStatement> rebuild, boolean clearable) {
        if (editor != null && editor.completer() != null && slot instanceof AnfSlot.Grounded grounded) {
            return conceptField(grounded, view, editor, rebuild, clearable);
        }
        return droppable(chipFor(slot, view), editor, rebuild);
    }

    /** Builds an editable {@link KlConceptField} bound to a slot position. */
    private static KlConceptField conceptField(AnfSlot.Grounded slot, ViewProperties view, Editor editor,
                                               Function<AnfSlot.Grounded, AnfStatement> rebuild, boolean clearable) {
        KlConceptField field = new KlConceptField();
        field.viewPropertiesProperty().set(view);
        field.completerProperty().set(editor.completer());
        field.iconSizeProperty().set(CHIP_ICON_PX);
        field.setClearable(clearable);
        field.setValue(new KlConceptField.Value.Concept(slot.nid(), slot.label()));
        field.onSelectedProperty().set(concept -> {
            AnfSlot.Grounded grounded = editor.resolve(concept.nid());
            if (grounded != null) {
                editor.onFieldEdited(rebuild.apply(grounded));
                // Normalize the displayed value to the resolved label (quiet — fires no callback).
                field.setValue(new KlConceptField.Value.Concept(grounded.nid(), grounded.label()));
            }
        });
        field.onClearedProperty().set(() -> editor.onFieldEdited(rebuild.apply(null)));
        field.onDetailProperty().set(value -> {
            if (value instanceof KlConceptField.Value.Concept concept) {
                AnfSlot.Grounded grounded = editor.resolve(concept.nid());
                if (grounded != null) {
                    editor.showDetail(grounded);
                }
            }
        });
        return field;
    }

    /**
     * Renders a slot as a chip node, reused by the statement card and the discovered-
     * concept inventory list. A grounded concept becomes the shared {@link KonceptBadge}
     * atom (a Komet concept drag source; presentation-only when {@code view} is null);
     * candidate and clarify slots become distinct labelled badges.
     *
     * @param s    the slot to render
     * @param view the view for a live, draggable badge; null renders presentation-only
     * @return the chip node
     */
    public static Node chipFor(AnfSlot s, ViewProperties view) {
        return switch (s) {
            case AnfSlot.Grounded g -> {
                KonceptBadge badge = (view != null)
                        ? new KonceptBadge(g.nid(), view)
                        : new KonceptBadge(PrimitiveData.publicId(g.nid()), g.label());
                badge.setIconSize(CHIP_ICON_PX);
                badge.setStyle(CHIP_FONT_STYLE);
                yield badge;
            }
            case AnfSlot.Candidate c -> badge(c.provisionalLabel(),
                    CANDIDATE_STYLE + CHIP_FONT_STYLE, "candidate · " + c.disposition());
            case AnfSlot.Clarify c -> badge("? " + c.field(), CLARIFY_STYLE + CHIP_FONT_STYLE, c.question());
        };
    }

    private static HBox row(String labelText, Node... nodes) {
        Label l = label(labelText + ":", "-fx-text-fill: #666666;");
        l.setMinWidth(70);
        HBox h = new HBox(6, l);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getChildren().addAll(nodes);
        return h;
    }

    private static Node badge(String text, String style, String tooltip) {
        Label l = new Label(text);
        l.setStyle(style);
        if (tooltip != null && !tooltip.isBlank()) {
            l.setTooltip(new Tooltip(tooltip));
        }
        return l;
    }

    private static String interval(AnfStatement.Result r) {
        String lower = r.lowerBound() == null
                ? "(-∞"
                : (r.includeLowerBound() ? "[" : "(") + trim(r.lowerBound());
        String upper = r.upperBound() == null
                ? "∞)"
                : trim(r.upperBound()) + (r.includeUpperBound() ? "]" : ")");
        return lower + ", " + upper;
    }

    private static String trim(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static Label label(String text, String style) {
        Label l = new Label(text);
        if (style != null && !style.isBlank()) {
            l.setStyle(style);
        }
        return l;
    }

    /**
     * Wraps a slot node as a concept drop target when {@code editor} is non-null, using
     * Komet's {@link DropHelper}: a dropped Komet concept is resolved and {@code rebuild}
     * produces the substituted statement, which the editor stores and re-renders.
     */
    private static Node droppable(Node node, Editor editor, Function<AnfSlot.Grounded, AnfStatement> rebuild) {
        if (editor != null && node instanceof Region region) {
            new DropHelper(region,
                    dragboard -> KometClipboard.conceptNid(dragboard).ifPresent(nid -> {
                        AnfSlot.Grounded grounded = editor.resolve(nid);
                        if (grounded != null) {
                            editor.onEdited(rebuild.apply(grounded));
                        }
                    }),
                    event -> true,
                    () -> false);
        }
        return node;
    }
}
