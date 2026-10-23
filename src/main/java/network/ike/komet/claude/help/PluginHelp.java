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
package network.ike.komet.claude.help;

import dev.ikm.komet.layout.help.KlHelpTopic;
import dev.ikm.komet.layout.help.KlResourceHelpProvider;

/**
 * The plugin's help book: AsciiDoc topics kept in the plugin's jar under
 * {@code META-INF/komet-help/komet-claude-plugin/}, so they are released and downloaded with it.
 * Its cards open it at their own topics.
 */
public final class PluginHelp extends KlResourceHelpProvider {

    /** The book's identifier. */
    public static final String BOOK_ID = "komet-claude-plugin";

    /** The STAMPed Document card's topic. */
    public static final KlHelpTopic STAMPED_DOCUMENT = new KlHelpTopic(BOOK_ID, "stamped-document");

    /** Creates the book; called by the service loader. */
    public PluginHelp() {
        super(BOOK_ID, "IKE plugin for Komet");
    }
}
