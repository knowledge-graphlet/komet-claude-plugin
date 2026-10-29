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
package network.ike.komet.claude.ui;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionTreeEdits;
import network.ike.komet.claude.decision.Measure;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One measure of a decision tree as a chip: its word, then its bounds. Stated, and offered for a
 * what-if, it opens a field on a click and hands the typed bounds back; derived, it shows what
 * the derivation computed, in grey italics, with the derivation in its tooltip; not
 * determinable, it shows a question mark in the finding colour.
 */
final class MeasureChip extends StackPane {

    static final Color INK = Color.web("#333");
    static final Color GAP = Color.web("#b5541a");
    private static final Color DERIVED = Color.web("#5a5a5a");

    private final Label text;
    private final HBox row;

    /**
     * @param word    what the measure is: p, money, health, or the parameter's quantity word
     * @param bounds  the value, stated or computed; empty when not determinable
     * @param measure the measure as the tree carries it
     * @param ids     what names the semantics and derivation kinds in the tooltip
     * @param base    the surface's base font size
     * @param onEdit  where typed bounds go; null when the chip is not offered for a what-if
     */
    MeasureChip(String word, Optional<Bounds> bounds, Measure measure, Identities ids, double base,
                Consumer<Bounds> onEdit) {
        String shown = bounds.map(Bounds::toString).orElse("?");
        text = new Label(word.isEmpty() ? shown : word + " " + shown);
        text.setFont(Font.font(base * 0.9));
        text.setTextFill(bounds.isPresent() ? INK : GAP);
        setAlignment(Pos.CENTER_LEFT);
        row = new HBox(3, text);
        row.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(row);
        List<String> tip = new ArrayList<>();
        if (measure.semanticNid() != 0 && ids != null) {
            String unit = ids.label(measure.semanticNid());
            if (unit != null && !unit.isBlank()) {
                tip.add("in " + unit);
            }
        }
        if (measure.derived()) {
            text.setTextFill(bounds.isPresent() ? DERIVED : GAP);
            text.setStyle("-fx-font-style: italic;");
            StringBuilder from = new StringBuilder("derived from ");
            from.append(ids == null ? "a derivation" : ids.label(measure.derivedFrom().getAsLong()));
            if (!measure.operands().isEmpty()) {
                List<String> names = new ArrayList<>();
                measure.operands().forEach(nid -> names.add(ids == null ? Long.toString(nid) : ids.label(nid)));
                from.append(" of ").append(String.join(", ", names));
            }
            tip.add(from.toString());
        } else if (onEdit != null) {
            text.setStyle("-fx-border-color: transparent transparent #8a8a8a transparent; -fx-border-style: dotted;"
                    + " -fx-border-width: 0 0 1 0;");
            setCursor(Cursor.TEXT);
            tip.add("Click to try another value");
            Bounds current = bounds.orElse(Bounds.point(0));
            setOnMouseClicked(e -> open(current, base, onEdit));
        }
        if (!tip.isEmpty()) {
            Tooltip.install(this, new Tooltip(String.join("\n", tip)));
        }
    }

    /** Opens the field, as a click does; for a test. */
    void open(Bounds current, double base, Consumer<Bounds> onEdit) {
        TextField field = new TextField(current.isPoint() ? Tokens.number(current.lower()) : current.toString());
        field.setId("what-if-field");
        field.setFont(Font.font(base * 0.9));
        field.setPrefColumnCount(Math.max(5, field.getText().length()));
        Runnable restore = () -> getChildren().setAll(row);
        field.setOnAction(e -> {
            Optional<Bounds> typed = DecisionTreeEdits.typed(field.getText());
            if (typed.isEmpty()) {
                field.setStyle("-fx-border-color: #c0392b;");
                return;
            }
            restore.run();
            onEdit.accept(typed.get());
        });
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                restore.run();
            }
        });
        field.focusedProperty().addListener((obs, was, is) -> {
            if (!is && getChildren().contains(field)) {
                restore.run();
            }
        });
        getChildren().setAll(field);
        field.requestFocus();
        field.selectAll();
    }

    /** The field while one is open; for a test. */
    Optional<TextField> field() {
        return getChildren().stream().filter(TextField.class::isInstance).map(TextField.class::cast).findFirst();
    }

    String shown() {
        return text.getText();
    }

    /** Lights the chip, as the pointer resting on its bar in the tornado asks. */
    void setHot(boolean on) {
        setStyle(on ? "-fx-background-color: #fff3d6; -fx-background-radius: 4;" : "");
    }

    /** What happens as the pointer enters or leaves the chip. */
    void onHover(Consumer<Boolean> hover) {
        setOnMouseEntered(e -> hover.accept(true));
        setOnMouseExited(e -> hover.accept(false));
    }

    /** Marks the chip as one whose range holds a flip of the choice, with the threshold in a tooltip. */
    void mark(String tip) {
        if (marked().isPresent()) {
            return;
        }
        Label flag = new Label("▲");
        flag.setId("flip-mark");
        flag.setFont(Font.font(text.getFont().getSize() * 0.75));
        flag.setTextFill(Color.web("#d98b1a"));
        Tooltip.install(flag, new Tooltip(tip));
        row.getChildren().add(flag);
    }

    /** The flip mark, when the chip carries one; for a test. */
    Optional<Label> marked() {
        return row.getChildren().stream().filter(n -> "flip-mark".equals(n.getId())).map(Label.class::cast).findFirst();
    }
}
