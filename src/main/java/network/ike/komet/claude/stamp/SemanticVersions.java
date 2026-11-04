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

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.SemanticVersionRecord;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.State;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.Optional;

/**
 * New versions of a semantic a structured block states, uncommitted (ike-lab-documents topic
 * {@code dev-decision-document}, "Versions"): a decision tree's tree semantic and a complex
 * concept's specification. One author's edits before a commit are one change, so a version
 * pending by the same author is rewritten in place; after a commit, the next edit is a new
 * version. A version pending by someone else refuses the write, since a change is never made on
 * top of a change nobody has settled, unless the block is on a paired document, whose two authors
 * share one pending edit.
 */
public final class SemanticVersions {

    private SemanticVersions() {
    }

    /**
     * What writing a version did: why nothing was written, or whether the version replaced the
     * author's own uncommitted one.
     */
    public record Versioned(Optional<String> refusal, boolean replacedPending) {
        static Versioned refused(String why) {
            return new Versioned(Optional.of(why), false);
        }
    }

    /**
     * Writes a version of an existing semantic: a new one, or the author's pending one rewritten.
     *
     * @param nid     the semantic
     * @param pattern the pattern it must be of
     * @param fields  the new version's field values
     * @param view    the view the semantic's latest version is read under
     * @param edit    whose change it is, and on which path
     * @param what    the semantic's name in a refusal, as in "The tree was not written"
     * @param shared  whether the block stating it is on a paired document, whose two authors share
     *                one pending edit: a version pending by the other author is rewritten in place
     * @return what was written, or why nothing was
     * @throws RuntimeException if the store refuses the write; a transaction made for it is cancelled
     */
    public static Versioned version(long nid, long pattern, ImmutableList<Object> fields, ViewCalculator view,
                                    EditCoordinate edit, String what, boolean shared) {
        String notWritten = "The " + what + " was not written: ";
        if (!(EntityHandle.get(nid).orNull() instanceof SemanticRecord record) || record.patternNid() != pattern) {
            return Versioned.refused(notWritten + "its identity names something of another pattern.");
        }
        Latest<SemanticEntityVersion> latest = view.latest(nid);
        if (latest.isAbsent()) {
            return Versioned.refused(notWritten + "no version of it is visible in this view.");
        }
        SemanticEntityVersion head = latest.get();
        Optional<Transaction> pending = Optional.empty();
        if (head.uncommitted()) {
            if (head.stamp().authorNid() != edit.getAuthorNidForChanges() && !shared) {
                return Versioned.refused(notWritten + "a version by " + StampNames.name(view, head.stamp().authorNid())
                        + " is pending; it is committed or cancelled before anyone else writes the " + what + ".");
            }
            pending = Transaction.forVersion(head);
        }
        Transaction transaction = pending.orElseGet(() -> Transaction.make("Version of " + what + " " + record.publicId().idString()));
        try {
            long stamp = head.uncommitted() ? head.stampNid() : transaction.getStamp(State.ACTIVE, edit.getAuthorNidForChanges(),
                    head.moduleNid(), edit.getDefaultPathNid()).nid();
            SemanticRecord updated = record.with(new SemanticVersionRecord(record, stamp, fields)).build();
            transaction.addComponent(updated);
            EntityService.get().putEntity(updated);
            return new Versioned(Optional.empty(), pending.isPresent());
        } catch (RuntimeException e) {
            if (pending.isEmpty()) {
                transaction.cancel();
            }
            throw e;
        }
    }

    /**
     * Whether a component has a version this view sees, uncommitted ones included: a record whose
     * versions were all cancelled, by a write that failed after it began, is written again.
     *
     * @param publicId the component's public id
     * @param view     the view
     * @return whether it has a visible version
     */
    public static boolean exists(PublicId publicId, ViewCalculator view) {
        return PrimitiveData.get().hasPublicId(publicId) && visible(PrimitiveData.nid(publicId), view);
    }

    /**
     * Whether a component has a version this view sees.
     *
     * @param nid  the component
     * @param view the view
     * @return whether it has a visible version
     */
    public static boolean visible(long nid, ViewCalculator view) {
        try {
            return view.latest(nid).isPresent();
        } catch (IllegalStateException noVersions) {
            // a record left with no version at all, its one version cancelled
            return false;
        }
    }
}
