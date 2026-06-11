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
package network.ike.komet.claude.html;

import dev.ikm.komet.rules.actions.AbstractActionSuggested;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityVersion;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Alert;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A rule-generated context-menu action that renders a focused component as rich HTML
 * ({@link HtmlKonceptRenderer} — the adoc pill badge + styled definition/history tables)
 * and places it on the system clipboard as {@code text/html}, so it can be pasted into
 * Outlook / Apple Mail. Emitted by {@code ZulipRules} on component focus.
 *
 * <p>Rendering (which reads the view) runs off the FX thread on a daemon WORKER, exactly
 * like {@code PostToZulipAction}; the clipboard write and completion alert hop back to the
 * FX thread via {@link Platform#runLater}.
 */
public final class CopyKonceptHtmlAction extends AbstractActionSuggested {

    private static final Logger LOG = LoggerFactory.getLogger(CopyKonceptHtmlAction.class);

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "koncept-html");
        thread.setDaemon(true);
        return thread;
    });

    private final int nid;

    /**
     * Creates the action for a focused component version.
     *
     * @param version        the focused entity version (its component is rendered)
     * @param viewCalculator the active view (name, parents, definition, STAMP)
     * @param editCoordinate the edit coordinate (unused; required by the base)
     */
    public CopyKonceptHtmlAction(EntityVersion version, ViewCalculator viewCalculator, EditCoordinate editCoordinate) {
        super("Copy as HTML (for email)", viewCalculator, editCoordinate);
        this.nid = version.nid();
    }

    @Override
    public void doAction(ActionEvent actionEvent, EditCoordinateRecord editCoordinate) {
        ViewCalculator view = viewCalculator();
        WORKER.submit(() -> {
            try {
                String label = view.getPreferredDescriptionTextWithFallbackOrNid(nid);
                String html = new HtmlKonceptRenderer(view).render(nid);
                Platform.runLater(() -> {
                    ClipboardContent content = new ClipboardContent();
                    content.putHtml(html);
                    content.putString(label);
                    Clipboard.getSystemClipboard().setContent(content);
                    alert(Alert.AlertType.INFORMATION,
                            "Copied “" + label + "” as HTML — paste into Mail / Outlook.");
                });
            } catch (RuntimeException e) {
                LOG.error("HTML copy failed for nid {}", nid, e);
                Platform.runLater(() -> alert(Alert.AlertType.ERROR, "HTML copy failed: " + e.getMessage()));
            }
        });
    }

    private static void alert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message);
        alert.setHeaderText(null);
        alert.show();
    }
}
