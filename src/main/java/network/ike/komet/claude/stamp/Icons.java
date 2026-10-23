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

import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Toolbar icons for the STAMPed Document card: Ikonli glyphs from the packs Komet's runtime
 * already holds (Material Design 2 here), so a narrow card shows a control by its icon and
 * names it in the tooltip, where a label would truncate past reading.
 */
final class Icons {

    /** The pixel size of a toolbar glyph. */
    static final int SIZE = 16;

    private Icons() {
    }

    /**
     * An icon by its Ikonli code.
     *
     * @param code the icon code, e.g. {@code mdi2h-history}
     * @return the icon
     */
    static FontIcon of(String code) {
        FontIcon icon = new FontIcon(code);
        icon.setIconSize(SIZE);
        icon.getStyleClass().add("claude-card-toolbar-icon");
        return icon;
    }

    /**
     * Shows a control's icon alone, or its icon with its name beside it.
     *
     * @param control the control
     * @param compact true for the icon alone
     */
    static void compact(Labeled control, boolean compact) {
        control.setContentDisplay(compact ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
    }

    /**
     * Shows a control by its icon alone; its text stays, for assistive technology and lookups by
     * text, and the tooltip leads with it.
     *
     * @param control     the control
     * @param code        the icon code
     * @param description what the control does, or {@code null}
     */
    static void iconOnly(Labeled control, String code, String description) {
        control.setGraphic(of(code));
        control.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        control.setAccessibleText(control.getText());
        control.setTooltip(new Tooltip(description == null ? control.getText() : control.getText() + " — " + description));
    }
}
