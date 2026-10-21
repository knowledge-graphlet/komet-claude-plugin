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

import design.knowledge.stamp.adoc.model.StampRecord;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import network.ike.foundation.ike.bindings.IkePattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the repository says about the commits a stamped document's STAMPs stand for: the
 * short SHA and the subject of each, read from the repository when the card has one open
 * (ike-lab-documents topic {@code dev-stamped-document-surface}). The store records only the
 * SHA, in the git-source semantic's version under each commit's STAMP; the subject is git's.
 *
 * @param shaByStamp the commit SHA of each STAMP nid the document's git source was versioned under
 * @param records    the file's STAMP records by commit SHA, empty when no repository was read
 */
public record CommitSummaries(Map<Long, String> shaByStamp, Map<String, StampRecord> records) {

    private static final Logger LOG = LoggerFactory.getLogger(CommitSummaries.class);

    /** Nothing known. */
    public static final CommitSummaries NONE = new CommitSummaries(Map.of(), Map.of());

    /**
     * Reads the SHAs from the store and, when a working tree holding the file is given, the
     * commit records from the repository. Never throws: a repository that cannot be read leaves
     * the records empty.
     *
     * @param anchor   the document anchor
     * @param repoRoot the working tree, or {@code null}
     * @param relative the file's path within it, or {@code null}
     * @return the summaries
     */
    public static CommitSummaries read(PublicId anchor, Path repoRoot, Path relative) {
        Map<Long, String> shas = new HashMap<>();
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        for (SemanticEntity<SemanticEntityVersion> s : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, IkePattern.GIT_SOURCE_PATTERN.nid()).toList()) {
            for (SemanticEntityVersion v : s.versions()) {
                if (!v.canceled() && v.fieldValues().size() > 1) {
                    shas.put(v.stamp().nid(), String.valueOf(v.fieldValues().get(1)));
                }
            }
        }
        Map<String, StampRecord> records = new HashMap<>();
        if (repoRoot != null && relative != null && Files.isRegularFile(repoRoot.resolve(relative))) {
            try {
                GitStampBridge bridge = GitStampBridge.read(repoRoot, relative);
                for (StampRecord r : bridge.stamps().stamps().values()) {
                    records.put(r.commitSha(), r);
                }
            } catch (IOException | RuntimeException e) {
                LOG.info("Commit summaries of {} not read from {}: {}", relative, repoRoot, e.getMessage());
            }
        }
        return new CommitSummaries(Map.copyOf(shas), Map.copyOf(records));
    }

    /**
     * The summaries of several documents as one, for a surface that shows an assembly: every
     * member's STAMPs and records together.
     *
     * @param all the summaries to merge
     * @return one summaries over all of them
     */
    public static CommitSummaries merge(java.util.Collection<CommitSummaries> all) {
        Map<Long, String> shas = new HashMap<>();
        Map<String, StampRecord> records = new HashMap<>();
        for (CommitSummaries s : all) {
            shas.putAll(s.shaByStamp());
            records.putAll(s.records());
        }
        return new CommitSummaries(Map.copyOf(shas), Map.copyOf(records));
    }

    /** @return the commit SHA the STAMP stands for, if the store recorded one */
    public Optional<String> sha(long stampNid) {
        return Optional.ofNullable(shaByStamp.get(stampNid));
    }

    /** @return the commit's record, if the repository was read and holds it */
    public Optional<StampRecord> record(long stampNid) {
        return sha(stampNid).map(records::get);
    }

    /** @return "abc1234  subject" for the STAMP's commit, as much of it as is known */
    public Optional<String> summary(long stampNid) {
        Optional<String> sha = sha(stampNid);
        if (sha.isEmpty()) {
            return Optional.empty();
        }
        String shortSha = sha.get().substring(0, Math.min(7, sha.get().length()));
        StampRecord record = records.get(sha.get());
        return Optional.of(record == null ? shortSha : shortSha + "  " + record.summary());
    }
}
