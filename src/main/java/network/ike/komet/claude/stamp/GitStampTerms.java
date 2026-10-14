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

import design.knowledge.stamp.adoc.identity.GitIdentityAdapter;
import design.knowledge.stamp.adoc.identity.Identity;
import design.knowledge.stamp.adoc.identity.StampCoordinates;
import design.knowledge.stamp.adoc.model.Status;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy.Concept;
import dev.ikm.tinkar.terms.State;

/**
 * Git-derived STAMP coordinates as Tinkar terms ({@code IKE-Network/ike-issues#468}).
 *
 * <p>The identity adapter in {@code stamp-adoc-core} derives every concept-valued coordinate
 * as a version-5 UUID under {@link GitIdentityAdapter#NAMESPACE} — the computation Tinkar's
 * {@code UuidT5Generator} performs, which {@code GitStampTermsTest} holds to parity — so the
 * author concept for a git identity is the same concept on every machine and in every store,
 * minted here as an {@link Concept EntityProxy} with the identity's display name as its
 * description. Seeding those concepts into an open datastore is {@link GitStampVocabulary}'s job.
 */
public final class GitStampTerms {

    private GitStampTerms() { }

    /**
     * @param identity a coordinate identity from the adapter
     * @return the concept proxy carrying its UUID and display name
     */
    public static Concept concept(Identity identity) {
        return Concept.make(identity.name(), identity.uuid());
    }

    /**
     * Every git-side status is an active version in Tinkar terms: an annotation is a role on
     * lines, recorded by its own STAMP, never a state of the version it annotates.
     *
     * @param status the git-side status
     * @return the Tinkar state
     */
    public static State state(Status status) {
        return switch (status) {
            case ACTIVE -> State.ACTIVE;
        };
    }

    /**
     * One git STAMP ready for the datastore: the five coordinates as the writer wants them.
     *
     * @param stamp  concept identifying the STAMP itself (derived from the commit)
     * @param state  the Tinkar state
     * @param time   epoch milliseconds of the commit's author time
     * @param author the author concept
     * @param module the module concept (repository or Kompendium component)
     * @param path   the path concept (branch)
     */
    public record GitStamp(ConceptFacade stamp, State state, long time, ConceptFacade author, ConceptFacade module, ConceptFacade path) {

        /**
         * @param coordinates the adapter's coordinates for a STAMP record
         * @return the same STAMP as Tinkar terms
         */
        public static GitStamp of(StampCoordinates coordinates) {
            // Qualified: inside the record, the bare names resolve to the accessors.
            return new GitStamp(GitStampTerms.concept(coordinates.stamp()), GitStampTerms.state(coordinates.status()),
                    coordinates.time().toEpochMilli(), GitStampTerms.concept(coordinates.author()),
                    GitStampTerms.concept(coordinates.module()), GitStampTerms.concept(coordinates.path()));
        }

        /**
         * Opens a writer whose every write carries this STAMP — the commit's author, time,
         * module and path rather than the Komet user's — so content persisted from a git
         * history keeps the provenance the history recorded. The caller seeds the concepts
         * first ({@link GitStampVocabulary#seedIfAbsent}) and commits or cancels the writer.
         *
         * @param transactionName a name for the transaction
         * @return an open writer stamped with this STAMP
         */
        public StampedWriter openWriter(String transactionName) {
            return StampedWriter.open(transactionName, state, time, author, module, path);
        }
    }
}
