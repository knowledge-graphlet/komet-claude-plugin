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
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.terms.ProxyFactory;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Dragboard;
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
            box.getChildren().add(row("Topic", droppable(chipFor(s.topic(), view), editor, g -> new AnfStatement(
                    s.statementType(), g, s.subjectOfInformation(), s.result(), s.status(),
                    s.modifiers(), s.associatedStatements(), s.narrative()))));
        }
        if (s.subjectOfInformation() != null) {
            box.getChildren().add(row("Subject", droppable(chipFor(s.subjectOfInformation(), view), editor, g -> new AnfStatement(
                    s.statementType(), s.topic(), g, s.result(), s.status(),
                    s.modifiers(), s.associatedStatements(), s.narrative()))));
        }
        if (s.result() != null) {
            box.getChildren().add(row("Result", label(interval(s.result()), ""),
                    droppable(chipFor(s.result().measureSemantic(), view), editor, g -> new AnfStatement(
                            s.statementType(), s.topic(), s.subjectOfInformation(),
                            new AnfStatement.Result(s.result().lowerBound(), s.result().upperBound(),
                                    s.result().includeLowerBound(), s.result().includeUpperBound(), g),
                            s.status(), s.modifiers(), s.associatedStatements(), s.narrative()))));
        }
        if (s.status() != null) {
            box.getChildren().add(row("Status", droppable(chipFor(s.status(), view), editor, g -> new AnfStatement(
                    s.statementType(), s.topic(), s.subjectOfInformation(), s.result(), g,
                    s.modifiers(), s.associatedStatements(), s.narrative()))));
        }
        if (s.narrative() != null && !s.narrative().isBlank()) {
            box.getChildren().add(row("Narrative", label(s.narrative(), "")));
        }
        return box;
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
         * Receives the statement after a slot substitution, to store and re-render.
         *
         * @param updated the new statement
         */
        void onEdited(AnfStatement updated);
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
            case AnfSlot.Grounded g -> (view != null)
                    ? new KonceptBadge(g.nid(), view)
                    : new KonceptBadge(PrimitiveData.publicId(g.nid()), g.label());
            case AnfSlot.Candidate c -> badge(c.provisionalLabel(), CANDIDATE_STYLE, "candidate · " + c.disposition());
            case AnfSlot.Clarify c -> badge("? " + c.field(), CLARIFY_STYLE, c.question());
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
                    dragboard -> {
                        Integer nid = conceptNid(dragboard);
                        if (nid != null) {
                            AnfSlot.Grounded grounded = editor.resolve(nid);
                            if (grounded != null) {
                                editor.onEdited(rebuild.apply(grounded));
                            }
                        }
                    },
                    event -> true,
                    () -> false);
        }
        return node;
    }

    /** The dropped concept's nid from a Komet concept dragboard, or null. */
    private static Integer conceptNid(Dragboard dragboard) {
        if (dragboard.hasContent(KometClipboard.KOMET_CONCEPT_PROXY)) {
            try {
                return ProxyFactory.fromXmlFragment(
                        (String) dragboard.getContent(KometClipboard.KOMET_CONCEPT_PROXY)).nid();
            } catch (RuntimeException ignored) {
                // malformed payload; treat as no concept
            }
        }
        return null;
    }
}
