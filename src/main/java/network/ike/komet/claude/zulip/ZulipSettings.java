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
package network.ike.komet.claude.zulip;

import dev.ikm.komet.preferences.PreferencesService;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/**
 * Reads, writes, and prompts for the Zulip connection in the per-OS-user Komet
 * preferences — the same store the Anthropic API key uses
 * ({@code PreferencesService.userPreferences()}), under
 * {@code network.ike.komet.claude.zulip.*} keys. The bot API key lives only in
 * the user's preferences; it is never committed or logged.
 */
public final class ZulipSettings {

    private static final String PREF_URL = "network.ike.komet.claude.zulip.url";
    private static final String PREF_EMAIL = "network.ike.komet.claude.zulip.email";
    private static final String PREF_API_KEY = "network.ike.komet.claude.zulip.apiKey";
    private static final String PREF_STREAM = "network.ike.komet.claude.zulip.stream";

    private ZulipSettings() {
        throw new UnsupportedOperationException();
    }

    /**
     * Reads the stored Zulip connection.
     *
     * @return the configuration, or {@code null} if the URL, bot email, or API
     *         key has not been set
     */
    public static ZulipConfig fromPreferences() {
        var prefs = PreferencesService.userPreferences();
        String url = prefs.get(PREF_URL, "");
        String email = prefs.get(PREF_EMAIL, "");
        String key = prefs.get(PREF_API_KEY, "");
        String stream = prefs.get(PREF_STREAM, ZulipConfig.DEFAULT_STREAM);
        if (url.isBlank() || email.isBlank() || key.isBlank()) {
            return null;
        }
        return new ZulipConfig(url, email, key, stream.isBlank() ? ZulipConfig.DEFAULT_STREAM : stream);
    }

    /**
     * Whether a usable Zulip connection is stored.
     *
     * @return true if URL, bot email, and API key are all set
     */
    public static boolean isConfigured() {
        return fromPreferences() != null;
    }

    /**
     * Shows a modal dialog to enter or edit the Zulip connection and, on OK,
     * stores it in the user preferences.
     *
     * @param owner the owning window for modality, or null
     * @return the saved configuration, or {@code null} if cancelled or left incomplete
     */
    public static ZulipConfig configure(Window owner) {
        var prefs = PreferencesService.userPreferences();
        TextField url = new TextField(prefs.get(PREF_URL, ""));
        url.setPromptText("https://your-realm.zulipchat.com");
        TextField email = new TextField(prefs.get(PREF_EMAIL, ""));
        email.setPromptText("komet-bot@your-realm.zulipchat.com");
        PasswordField apiKey = new PasswordField();
        apiKey.setText(prefs.get(PREF_API_KEY, ""));
        apiKey.setPromptText("bot API key");
        TextField stream = new TextField(prefs.get(PREF_STREAM, ZulipConfig.DEFAULT_STREAM));

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Realm URL"), url);
        grid.addRow(1, new Label("Bot email"), email);
        grid.addRow(2, new Label("Bot API key"), apiKey);
        grid.addRow(3, new Label("Default stream"), stream);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Zulip connection");
        dialog.setHeaderText("Enter the Zulip bot credentials. Stored in your Komet user preferences.");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return null;
        }
        prefs.put(PREF_URL, url.getText().strip());
        prefs.put(PREF_EMAIL, email.getText().strip());
        prefs.put(PREF_API_KEY, apiKey.getText().strip());
        prefs.put(PREF_STREAM, stream.getText().strip());
        try {
            prefs.flush();
        } catch (Exception ignored) {
            // best-effort persistence; the values are usable for this session regardless
        }
        return fromPreferences();
    }
}
