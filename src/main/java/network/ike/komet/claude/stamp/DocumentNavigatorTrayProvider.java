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

import dev.ikm.komet.layout.tray.KlSidebarTray;
import dev.ikm.komet.layout.tray.KlSidebarTrayProvider;

import java.util.UUID;

/** Puts the stamped documents' navigator on the journal's left sidebar. */
public final class DocumentNavigatorTrayProvider implements KlSidebarTrayProvider {

    /** Required by the service loader. */
    public DocumentNavigatorTrayProvider() { }

    @Override
    public String toggleId() {
        return DocumentNavigatorTray.TOGGLE_ID;
    }

    @Override
    public String tooltip() {
        return "Documents";
    }

    @Override
    public String iconShape() {
        return "M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6Zm4 18H6V4h7v5h5v11ZM8 12h8v2H8v-2Zm0 4h8v2H8v-2Z";
    }

    @Override
    public KlSidebarTray create(UUID journalTopic) {
        return new DocumentNavigatorTray(journalTopic);
    }
}
