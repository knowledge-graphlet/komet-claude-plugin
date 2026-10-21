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

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A segmented control: one pill of joined segments for an exclusive choice, the Komet way to
 * toggle among a few states (KEC, 2026-10-20: three separate buttons are not elegant; a three-way
 * selection like a radio group is). Plain {@link ToggleButton}s in a {@link ToggleGroup}, styled
 * by {@code stamped-document.css} ({@code segmented}, {@code first}, {@code middle}, {@code last}),
 * so it reads as Komet's own toggle buttons. Exactly one segment is always selected: a click on
 * the selected segment does nothing, and the group never falls to no selection. Left and right
 * arrows move the selection when the control has focus. It is a radio group to assistive
 * technology, each segment a radio item.
 *
 * @param <T> the value a segment stands for
 */
final class SegmentedControl<T> extends HBox {

    private final ToggleGroup group = new ToggleGroup();
    private final List<ToggleButton> segments = new ArrayList<>();
    private final List<T> values = new ArrayList<>();
    private final ReadOnlyObjectWrapper<T> value = new ReadOnlyObjectWrapper<>();

    /**
     * @param options  the values in order
     * @param label    the text of a value's segment
     * @param tooltip  the tooltip of a value's segment, or {@code null} for none
     * @param id       the id of a value's segment
     */
    SegmentedControl(List<T> options, Function<T, String> label, Function<T, String> tooltip, Function<T, String> id) {
        setSpacing(0);
        getStyleClass().add("segmented");
        setAccessibleRole(AccessibleRole.RADIO_BUTTON);          // the group's role: a set of radio items (see each segment)
        setFocusTraversable(false);
        for (int i = 0; i < options.size(); i++) {
            T option = options.get(i);
            ToggleButton segment = new ToggleButton(label.apply(option));
            segment.setToggleGroup(group);
            segment.setId(id.apply(option));
            segment.getStyleClass().addAll("segment", i == 0 ? "first" : i == options.size() - 1 ? "last" : "middle");
            segment.setAccessibleRole(AccessibleRole.RADIO_BUTTON);
            segment.setAccessibleRoleDescription("radio item");
            if (tooltip != null && tooltip.apply(option) != null) {
                segment.setTooltip(new Tooltip(tooltip.apply(option)));
            }
            segment.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT) {
                    int at = values.indexOf(value.get());
                    int to = Math.clamp(at + (e.getCode() == KeyCode.LEFT ? -1 : 1), 0, values.size() - 1);
                    select(values.get(to));
                    segments.get(to).requestFocus();
                    e.consume();
                }
            });
            segments.add(segment);
            values.add(option);
            getChildren().add(segment);
        }
        group.selectedToggleProperty().addListener((obs, was, is) -> {
            if (is == null) {
                // A click on the selected segment would clear the group: keep it selected.
                if (was != null) {
                    was.setSelected(true);
                }
                return;
            }
            value.set(values.get(segments.indexOf((ToggleButton) is)));
        });
        if (!segments.isEmpty()) {
            segments.getFirst().setSelected(true);
            value.set(values.getFirst());
        }
    }

    /** @return the selected value, never null once built */
    ReadOnlyObjectProperty<T> valueProperty() {
        return value.getReadOnlyProperty();
    }

    /** @return the selected value */
    T getValue() {
        return value.get();
    }

    /**
     * Selects a value; a value not among the options is ignored.
     *
     * @param wanted the value to select
     */
    void select(T wanted) {
        int at = values.indexOf(wanted);
        if (at >= 0) {
            segments.get(at).setSelected(true);
        }
    }

    /** @return the selected segment's toggle, for a host that reads the group */
    Toggle selectedToggle() {
        return group.getSelectedToggle();
    }
}
