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

import dev.ikm.komet.layout.help.KlHelpProvider;
import dev.ikm.komet.layout.help.KlHelpViewer;

/**
 * Provides Komet's help viewer: help topics in AsciiDoc, rendered in the rich text control with
 * their cross references followed, the book's contents and a search over it
 * ({@link AsciiDocHelpViewer}).
 */
public final class AsciiDocHelpViewerFactory implements KlHelpViewer.Factory {

    /** Creates the factory; called by the service loader. */
    public AsciiDocHelpViewerFactory() {
    }

    @Override
    public KlHelpViewer create(KlHelpProvider book) {
        return new AsciiDocHelpViewer(book);
    }
}
