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

import dev.ikm.komet.framework.Identicon;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * Renders a validated {@link AnfStatement} as a read-only JavaFX node — the rich
 * projection of the lift's formal pane. Grounded concepts appear as identicon
 * Koncept chips (byte-identical to Komet's own); candidate and clarify slots render
 * distinctly so the three-way honesty of the lift is visible. The view is built from
 * the statement alone (the slots already carry resolved identity), so it needs no
 * view coordinate.
 */
public final class AnfStatementView {

    private static final String CHIP_STYLE =
            "-fx-background-color: #e9eff6; -fx-background-radius: 6; -fx-padding: 1 6 1 4;";
    private static final String CANDIDATE_STYLE =
            "-fx-background-color: #fdf3e7; -fx-background-radius: 6; -fx-padding: 1 6 1 4; "
            + "-fx-border-color: #d9a441; -fx-border-radius: 6; -fx-border-style: segments(4, 3);";
    private static final String CLARIFY_STYLE =
            "-fx-background-color: #f3eef7; -fx-background-radius: 6; -fx-padding: 1 6 1 4;";

    private AnfStatementView() {
    }

    /**
     * Renders a statement as a labelled card.
     *
     * @param s the validated statement; must not be null
     * @return a node for the formal pane
     */
    public static Node render(AnfStatement s) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(8));
        box.getChildren().add(label(s.statementType().name().toLowerCase(Locale.ROOT) + " statement",
                "-fx-font-weight: bold;"));

        if (s.topic() != null) {
            HBox topic = row("Topic", slot(s.topic().focus()));
            for (AnfStatement.RoleFiller rf : s.topic().roleFillers()) {
                topic.getChildren().addAll(label("·", ""), slot(rf.role()), label("→", ""), slot(rf.filler()));
            }
            box.getChildren().add(topic);
        }
        if (s.result() != null) {
            box.getChildren().add(row("Result", label(interval(s.result()), ""), slot(s.result().measureSemantic())));
        }
        if (s.status() != null) {
            box.getChildren().add(row("Status", slot(s.status())));
        }
        if (s.narrative() != null && !s.narrative().isBlank()) {
            box.getChildren().add(row("Narrative", label(s.narrative(), "")));
        }
        return box;
    }

    private static HBox row(String labelText, Node... nodes) {
        Label l = label(labelText + ":", "-fx-text-fill: #666666;");
        l.setMinWidth(70);
        HBox h = new HBox(6, l);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getChildren().addAll(nodes);
        return h;
    }

    private static Node slot(AnfSlot s) {
        return switch (s) {
            case AnfSlot.Grounded g -> chip(g);
            case AnfSlot.Candidate c -> badge(c.provisionalLabel(), CANDIDATE_STYLE, "candidate · " + c.disposition());
            case AnfSlot.Clarify c -> badge("? " + c.field(), CLARIFY_STYLE, c.question());
        };
    }

    private static Node chip(AnfSlot.Grounded g) {
        HBox chip = new HBox(4);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setStyle(CHIP_STYLE);
        try {
            PublicId pid = PrimitiveData.publicId(g.nid());
            ImageView icon = Identicon.generateIdenticon(pid, 14, 14);
            chip.getChildren().add(icon);
        } catch (RuntimeException ignored) {
            // No identicon available; render a label-only chip.
        }
        Label name = new Label(g.label());
        name.setStyle("-fx-text-fill: #2a5a8a;");
        Label id = new Label(g.identifier());
        id.setStyle("-fx-text-fill: #8888aa; -fx-font-size: 0.8em;");
        chip.getChildren().addAll(name, id);
        return chip;
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
}
