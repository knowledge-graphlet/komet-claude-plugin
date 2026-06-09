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
package network.ike.komet.claude.narrator;

import dev.ikm.tinkar.composer.Composer;
import dev.ikm.tinkar.composer.Session;
import dev.ikm.tinkar.composer.template.Comment;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.State;

/**
 * Writes a commit comment into the graph.
 * <p>
 * A commit comment is a {@link dev.ikm.tinkar.terms.TinkarTerm#COMMENT_PATTERN} semantic whose
 * referenced component is the commit's STAMP nid, with the comment text in field 0. The comment
 * semantic carries its own stamp, authored by the {@link NarratorIdentity narrator identity}, so
 * the comment's authorship and time are intrinsic and auditable against the graph.
 * <p>
 * <b>Note:</b> writing a comment is itself a commit, which the entity layer broadcasts as a commit
 * event. Callers (the commit narrator) must therefore recognize and skip the narrator's own
 * comment commits to avoid narrating them recursively.
 */
public final class CommentWriter {

    /**
     * Writes one comment referencing {@code targetStampNid}, authored by the narrator identity.
     * No-op if the narrative is blank.
     *
     * @param targetStampNid the STAMP nid the comment is about (the commit being narrated)
     * @param narrative      the comment text
     */
    public void writeComment(int targetStampNid, String narrative) {
        if (narrative == null || narrative.isBlank()) {
            return;
        }
        Composer composer = new Composer("komet-narrator");
        Session session = composer.open(State.ACTIVE,
                NarratorIdentity.NARRATOR_AUTHOR,
                NarratorIdentity.NARRATION_MODULE,
                NarratorIdentity.NARRATION_PATH);
        session.compose(new Comment().text(narrative), EntityProxy.make(targetStampNid));
        composer.commitSession(session);
    }
}
