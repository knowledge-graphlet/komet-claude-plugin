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
package network.ike.komet.claude.zulip.rules;

import dev.ikm.komet.rules.actions.AbstractActionSuggested;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.event.ActionEvent;
import network.ike.komet.claude.zulip.ZulipSettings;

/**
 * A rule-generated action that opens the Zulip connection dialog to edit the
 * stored bot credentials — pre-filled with the current values and overwriting
 * them on OK (a single connection, replaced in place). Lets the user swap, e.g.,
 * an incoming-webhook bot (which cannot upload files) for a generic bot.
 */
public final class ConfigureZulipAction extends AbstractActionSuggested {

    /**
     * @param viewCalculator the active view (required by the base; unused here)
     * @param editCoordinate the edit coordinate (required by the base; unused here)
     */
    public ConfigureZulipAction(ViewCalculator viewCalculator, EditCoordinate editCoordinate) {
        super("Configure Zulip connection…", viewCalculator, editCoordinate);
    }

    @Override
    public void doAction(ActionEvent actionEvent, EditCoordinateRecord editCoordinate) {
        // Pre-fills current values, overwrites them on OK (replace-in-place).
        ZulipSettings.configure(null);
    }
}
