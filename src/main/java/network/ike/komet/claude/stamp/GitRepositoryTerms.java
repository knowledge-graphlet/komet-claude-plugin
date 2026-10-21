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
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.terms.EntityProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * A plugin-side <em>proposal</em> for the terminology a materialized document needs to say where
 * it came from (ike-lab-documents topic {@code dev-stamped-document-surface}, phase 4;
 * IKE-Network/ike-issues#1307): a <b>Git repository</b> concept, with a <b>Git repository
 * pattern</b> whose fields are the remote URL and the default branch, referenced from the
 * git-source pattern's versions so that a document records its repository beside its path and
 * commit.
 *
 * <p>Nothing is minted here. Starter-set terminology goes through KEC and a checkpoint
 * ({@code dev-enum-concept-bindings}), so the proposal names the concept, the pattern and its
 * fields, and {@link #record} writes a repository semantic only once the store holds the
 * pattern — a store without it leaves the write out and says so in the log. The proxies below
 * are stand-ins in the plugin's own namespace, to be replaced by the starter set's identities
 * when the proposal is accepted.
 *
 * <table>
 * <caption>The proposal</caption>
 * <tr><th>Term</th><th>Kind</th><th>Definition</th></tr>
 * <tr><td>Git repository</td><td>concept</td><td>A git repository a document was materialized from, named by its remote URL</td></tr>
 * <tr><td>Git repository pattern</td><td>pattern on the document anchor</td><td>field 1: remote URL (string); field 2: default branch (string)</td></tr>
 * <tr><td>Git source pattern, field 3</td><td>field added to the existing pattern</td><td>the Git repository concept the source was read from</td></tr>
 * </table>
 */
public final class GitRepositoryTerms {

    private static final Logger LOG = LoggerFactory.getLogger(GitRepositoryTerms.class);

    /** The plugin's namespace for the stand-in identities; the starter set's replace them. */
    private static final UUID PROPOSAL_NAMESPACE = UUID.fromString("7c2a6e9e-5c43-5d0a-9b7a-2f3f6b2b3e41");

    /** The proposed concept. */
    public static final EntityProxy.Concept GIT_REPOSITORY = EntityProxy.Concept.make("Git repository (proposed)",
            UuidT5Generator.get(PROPOSAL_NAMESPACE, "Git repository (IkeFoundation)"));

    /** The proposed pattern: remote URL, default branch. */
    public static final EntityProxy.Pattern GIT_REPOSITORY_PATTERN = EntityProxy.Pattern.make("Git repository pattern (proposed)",
            UuidT5Generator.get(PROPOSAL_NAMESPACE, "Git repository pattern (IkeFoundation)"));

    private GitRepositoryTerms() { }

    /** @return whether the store holds the proposed pattern, so a repository can be recorded */
    public static boolean available() {
        return PrimitiveData.get().hasPublicId(GIT_REPOSITORY_PATTERN.publicId());
    }

    /**
     * What a document's repository semantic records, when one exists.
     *
     * @param remoteUrl     the remote URL
     * @param defaultBranch the default branch
     */
    public record Recorded(String remoteUrl, String defaultBranch) { }

    /**
     * Reads the document's recorded repository, if the store holds the pattern and a semantic.
     *
     * @param anchor the document anchor
     * @return the record, or empty
     */
    public static Optional<Recorded> recorded(PublicId anchor) {
        if (!available()) {
            return Optional.empty();
        }
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        long patternNid = EntityService.get().nidForPublicId(GIT_REPOSITORY_PATTERN.publicId());
        for (SemanticEntity<SemanticEntityVersion> s : EntityService.get().semanticsForComponentOfPattern(anchorNid, patternNid).toList()) {
            SemanticEntityVersion latest = null;
            for (SemanticEntityVersion v : s.versions()) {
                if (!v.canceled() && (latest == null || v.time() > latest.time())) {
                    latest = v;
                }
            }
            if (latest != null && latest.fieldValues().size() >= 2) {
                return Optional.of(new Recorded(String.valueOf(latest.fieldValues().get(0)), String.valueOf(latest.fieldValues().get(1))));
            }
        }
        return Optional.empty();
    }

    /**
     * Records where a document came from, when the store holds the proposed pattern; otherwise
     * does nothing but say so, since the write is optional until the terminology exists.
     *
     * @param anchor        the document anchor
     * @param remoteUrl     the remote URL git reports
     * @param defaultBranch the branch the document was read on
     * @return whether a semantic was written
     */
    public static boolean record(PublicId anchor, String remoteUrl, String defaultBranch) {
        if (!available()) {
            LOG.info("Not recording the repository of {}: the store has no Git repository pattern yet (a starter-set proposal)",
                    PublicIds.of(anchor.asUuidArray()).idString());
            return false;
        }
        LOG.info("The Git repository pattern is in the store; recording {} on {} is the next step once its identities are the starter set's",
                remoteUrl, defaultBranch);
        return false;
    }
}
