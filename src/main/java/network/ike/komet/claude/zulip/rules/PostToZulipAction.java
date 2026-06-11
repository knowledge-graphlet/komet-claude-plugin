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
import dev.ikm.tinkar.entity.EntityVersion;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Alert;
import network.ike.komet.claude.zulip.ZulipConfig;
import network.ike.komet.claude.zulip.ZulipNotifier;
import network.ike.komet.claude.zulip.ZulipSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A rule-generated context-menu action that posts a component's current state
 * and version history to Zulip. Emitted by {@link ZulipRules} when a component
 * is focused, and rendered into the context menu by the existing rule pipeline
 * (a {@code ConsequenceAction} → {@code ActionUtils.createMenuItem}).
 *
 * <p>If no Zulip connection is stored it first opens the {@link ZulipSettings}
 * dialog; the post itself runs off the FX thread (the view calculator is read
 * off-thread exactly as the assistant's graph tools do), with a completion alert.
 */
public final class PostToZulipAction extends AbstractActionSuggested {

    private static final Logger LOG = LoggerFactory.getLogger(PostToZulipAction.class);

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "zulip-notify");
        thread.setDaemon(true);
        return thread;
    });

    private final int nid;

    /**
     * Creates the action for a focused component version.
     *
     * @param version        the focused entity version (its component is posted)
     * @param viewCalculator the active view (name, identifier, STAMP, history)
     * @param editCoordinate the edit coordinate (unused by the post; required by the base)
     */
    public PostToZulipAction(EntityVersion version, ViewCalculator viewCalculator, EditCoordinate editCoordinate) {
        super("Post state + history to Zulip", viewCalculator, editCoordinate);
        this.nid = version.nid();
    }

    @Override
    public void doAction(ActionEvent actionEvent, EditCoordinateRecord editCoordinate) {
        ZulipConfig config = ZulipSettings.fromPreferences();
        if (config == null) {
            config = ZulipSettings.configure(null);
            if (config == null) {
                return; // user cancelled or left it incomplete
            }
        }
        ZulipNotifier notifier = new ZulipNotifier(config);
        ViewCalculator view = viewCalculator();
        WORKER.submit(() -> {
            try {
                long id = notifier.notifyConcept(nid, view);
                Platform.runLater(() -> alert(Alert.AlertType.INFORMATION,
                        "Posted to Zulip (message " + id + ")."));
            } catch (RuntimeException e) {
                LOG.error("Zulip post failed for nid {}", nid, e);
                Platform.runLater(() -> alert(Alert.AlertType.ERROR,
                        "Zulip post failed: " + e.getMessage()));
            }
        });
    }

    private static void alert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message);
        alert.setHeaderText(null);
        alert.show();
    }
}
