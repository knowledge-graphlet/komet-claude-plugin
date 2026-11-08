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

import network.ike.komet.claude.decision.DecisionTreeFeedback;
import network.ike.komet.document.surface.MarkdownRichText.Entry;
import network.ike.komet.document.surface.MarkdownRichText.Role;

/** The turns of the assistant's conversation, as the transcript draws them. */
public final class AssistantTurns {

    private AssistantTurns() {
    }

    /**
     * A turn sent as the person's: Komet's own when it carries Komet's findings on a block of a
     * reply, which read as Markdown, else the person's, as {@link Entry#user} draws it.
     *
     * @param content the turn's text
     * @return the entry
     */
    public static Entry user(String content) {
        if (DecisionTreeFeedback.isFeedback(content)) {
            return new Entry(Role.KOMET, content, true);
        }
        return Entry.user(content);
    }
}
