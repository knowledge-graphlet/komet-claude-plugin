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

import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.identity.GitIdentityAdapter;
import design.knowledge.stamp.adoc.identity.Identity;
import design.knowledge.stamp.adoc.model.CommitEvent;
import design.knowledge.stamp.adoc.model.LineId;
import design.knowledge.stamp.adoc.model.StampHistory;
import dev.ikm.tinkar.common.id.LongIdList;
import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.EntityProxy.Concept;
import dev.ikm.tinkar.terms.EntityProxy.Semantic;
import dev.ikm.tinkar.terms.State;
import network.ike.komet.claude.doc.JournalStore;
import network.ike.komet.claude.doc.RichSurfaceTerms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git to graph ({@code IKE-Network/ike-issues#468}): writes one file's history into the open
 * datastore as the journal-shaped document described in {@link StampedDocumentTerms} — one
 * anchor concept, one prose element per line, one manifest version per commit that changed the
 * file, a git-source version beside it carrying the commit SHA, and an affirmation semantic per
 * {@code Affirms:} trailer — every write STAMPed with the commit's own coordinates through the
 * identity adapter. Replaying the manifest versions in STAMP time is the file's history.
 *
 * <p>Identities are deterministic (document, line, manifest, source and affirmation ids are all
 * UUID5s of frozen keys) and every STAMP is a complete tuple, so the store itself converges: a
 * tuple is one stamp entity and identical versions under it merge to one. On top of that a run
 * skips the commits the store already records — the git-source versions name every materialized
 * commit, and each affirmation semantic exists or not — so a later run is incremental. Whether
 * the store is ephemeral or persisted is the datastore's choice, not this class's.
 */
public final class GitDocumentMaterializer {

    private static final Logger LOG = LoggerFactory.getLogger(GitDocumentMaterializer.class);
    private static final Pattern TOPIC_ID = Pattern.compile("^:topic-id:\\s*(\\S+)\\s*$");

    private GitDocumentMaterializer() { }

    /**
     * What one run wrote.
     *
     * @param anchor       the document's anchor concept id
     * @param name         the anchor's fully qualified name (topic id when the file declares one, else the path)
     * @param commits      events written by this run (events already in the store are skipped)
     * @param lines        line elements introduced across the history
     * @param affirmations affirmation semantics written
     * @param headSha      the HEAD the history was read from
     */
    public record Result(PublicId anchor, String name, int commits, int lines, int affirmations, String headSha) { }

    /**
     * Materialize a file's whole first-parent history into the open store.
     *
     * @param repoRoot working-tree root
     * @param file     path within it, absolute or root-relative
     * @return what was written
     * @throws IOException if the repository or the file cannot be read
     */
    public static Result materialize(Path repoRoot, Path file) throws IOException {
        return materialize(repoRoot, file, true);
    }

    /**
     * {@link #materialize(Path, Path)} with the incremental gate optional. Without it every
     * event is written again; the store still converges, because a stamp whose time is given is
     * one entity per tuple and identical versions under it merge to one. The gate only saves the
     * work.
     *
     * @param repoRoot working-tree root
     * @param file     path within it
     * @param onlyNew  skip commits the store already records
     * @return what was written
     * @throws IOException if the repository or the file cannot be read
     */
    public static Result materialize(Path repoRoot, Path file, boolean onlyNew) throws IOException {
        JournalStore.bootstrapIfAbsent();
        StampedDocumentTerms.bootstrapIfAbsent();
        GitIdentityAdapter adapter = GitIdentityAdapter.fromMailmap(repoRoot);
        String component = repoRoot.toAbsolutePath().normalize().getFileName().toString();
        try (GitRepo repo = GitRepo.open(repoRoot)) {
            String branch = repo.branch();
            StampHistory history = repo.stampHistoryOf(file);
            Path relative = history.file();
            String path = relative.toString().replace('\\', '/');
            Identity documentId = adapter.document(component, path);
            String name = topicId(repo, relative).orElse(path);
            Concept anchor = Concept.make(name, documentId.uuid());
            Semantic manifest = Semantic.make(PublicIds.of(design.knowledge.stamp.adoc.identity.Uuid5.of(GitIdentityAdapter.NAMESPACE, "manifest:" + documentId.key())));
            Semantic source = Semantic.make(PublicIds.of(design.knowledge.stamp.adoc.identity.Uuid5.of(GitIdentityAdapter.NAMESPACE, "source:" + documentId.key())));
            Map<LineId, String> lastText = new HashMap<>();
            int lines = 0;
            int affirmations = 0;
            boolean anchorWritten = !StampedDocumentTerms.entityAbsent(anchor);
            java.util.Set<String> recorded = anchorWritten && onlyNew ? recordedCommits(anchor) : java.util.Set.of();
            int skipped = 0;
            for (CommitEvent event : history.events()) {
                boolean contentRecorded = !event.touchesFile() || recorded.contains(event.commit().sha());
                List<Semantic> pendingAffirmations = new ArrayList<>();
                int index = 0;
                for (CommitEvent.AffirmationEvent ignoredEvent : event.affirmations()) {
                    Semantic proxy = Semantic.make(PublicIds.of(design.knowledge.stamp.adoc.identity.Uuid5.of(GitIdentityAdapter.NAMESPACE,
                            "affirmation:" + event.commit().sha() + ":" + index++)));
                    pendingAffirmations.add(!onlyNew || StampedDocumentTerms.entityAbsent(proxy) ? proxy : null);
                }
                if (contentRecorded && pendingAffirmations.stream().allMatch(java.util.Objects::isNull)) {
                    for (CommitEvent.Line line : event.introduced()) {
                        lastText.put(line.id(), line.text());    // keep the retire texts right for later events
                    }
                    for (CommitEvent.Line line : event.retexted()) {
                        lastText.put(line.id(), line.text());
                    }
                    skipped++;
                    continue;                                    // already in the store
                }
                GitStampTerms.GitStamp stamp = GitStampTerms.GitStamp.of(adapter.coordinatesOf(event, component, branch));
                GitStampVocabulary.seedIfAbsent(stamp);
                try (StampedWriter writer = stamp.openWriter("git-materialize " + event.commit().sha().substring(0, 7))) {
                    if (!anchorWritten) {
                        writer.concept(anchor);
                        writer.fullyQualifiedName(anchor, name);
                        anchorWritten = true;
                    }
                    for (CommitEvent.Line line : contentRecorded ? List.<CommitEvent.Line>of() : event.introduced()) {
                        writer.semantic(lineProxy(adapter, line.id()), RichSurfaceTerms.PROSE_ELEMENT_PATTERN, anchor, line.text());
                        lastText.put(line.id(), line.text());
                        lines++;
                    }
                    for (CommitEvent.Line line : contentRecorded ? List.<CommitEvent.Line>of() : event.retexted()) {
                        writer.semantic(lineProxy(adapter, line.id()), RichSurfaceTerms.PROSE_ELEMENT_PATTERN, anchor, line.text());
                        lastText.put(line.id(), line.text());
                    }
                    if (event.touchesFile() && !contentRecorded) {
                        long[] nids = new long[event.manifest().size()];
                        for (int i = 0; i < nids.length; i++) {
                            nids[i] = EntityService.get().nidForPublicId(lineProxy(adapter, event.manifest().get(i)).publicId());
                        }
                        writer.semantic(manifest, RichSurfaceTerms.JOURNAL_MANIFEST_PATTERN, anchor, LongIds.list.of(nids));
                        writer.semantic(source, StampedDocumentTerms.GIT_SOURCE_PATTERN, anchor, event.pathAtCommit(), event.commit().sha());
                    }
                    for (int i = 0; i < event.affirmations().size(); i++) {
                        Semantic proxy = pendingAffirmations.get(i);
                        if (proxy == null) {
                            continue;                            // already in the store
                        }
                        CommitEvent.AffirmationEvent a = event.affirmations().get(i);
                        long[] nids = new long[a.lines().size()];
                        for (int j = 0; j < nids.length; j++) {
                            nids[j] = EntityService.get().nidForPublicId(lineProxy(adapter, a.lines().get(j)).publicId());
                        }
                        writer.semantic(proxy, StampedDocumentTerms.AFFIRMATION_PATTERN, anchor, LongIds.list.of(nids), a.affirmation().trailer());
                        affirmations++;
                    }
                    writer.commit();
                }
                if (!event.retired().isEmpty() && !contentRecorded) {
                    // A retired line gets an inactive version under the retiring commit's STAMP, so the
                    // graph records the withdrawal where git recorded it.
                    try (StampedWriter writer = StampedWriter.open("git-retire " + event.commit().sha().substring(0, 7), State.INACTIVE,
                            stamp.time(), stamp.author(), stamp.module(), stamp.path())) {
                        for (LineId id : event.retired()) {
                            writer.semantic(lineProxy(adapter, id), RichSurfaceTerms.PROSE_ELEMENT_PATTERN, anchor, lastText.getOrDefault(id, ""));
                        }
                        writer.commit();
                    }
                }
            }
            LOG.info("Materialized {} as '{}': {} commit(s) written, {} already recorded, {} line(s), {} affirmation(s)",
                    path, name, history.events().size() - skipped, skipped, lines, affirmations);
            return new Result(anchor.publicId(), name, history.events().size() - skipped, lines, affirmations, repo.headRevision());
        }
    }

    /** The commit SHAs the document's git-source versions already record. */
    private static java.util.Set<String> recordedCommits(Concept anchor) {
        java.util.Set<String> shas = new java.util.HashSet<>();
        long anchorNid = EntityService.get().nidForPublicId(anchor.publicId());
        for (SemanticEntity<SemanticEntityVersion> source : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, StampedDocumentTerms.GIT_SOURCE_PATTERN.nid()).toList()) {
            for (SemanticEntityVersion v : source.versions()) {
                if (v.time() != Long.MAX_VALUE && v.fieldValues().size() > 1) {
                    shas.add(String.valueOf(v.fieldValues().get(1)));
                }
            }
        }
        return shas;
    }

    static Semantic lineProxy(GitIdentityAdapter adapter, LineId id) {
        Identity identity = adapter.line(id);
        return Semantic.make(identity.name(), identity.uuid());
    }

    private static Optional<String> topicId(GitRepo repo, Path relative) throws IOException {
        for (String line : repo.headLines(relative).subList(0, Math.min(40, repo.headLines(relative).size()))) {
            Matcher m = TOPIC_ID.matcher(line);
            if (m.matches()) {
                return Optional.of(m.group(1));
            }
        }
        return Optional.empty();
    }

    // ── reading back ──────────────────────────────────────────────────────────

    /**
     * One line as read from the store.
     *
     * @param nid        the line element's nid
     * @param text       the latest text
     * @param introduced the STAMP of the element's first version
     * @param latest     the STAMP of its latest version (differs after an ignored reflow)
     * @param affirmers  the STAMPs of the affirmation semantics referencing it
     */
    public record StoredLine(long nid, String text, StampEntity<?> introduced, StampEntity<?> latest, List<StampEntity<?>> affirmers) { }

    /**
     * The document as the store holds it at the latest manifest version.
     *
     * @param anchor  the anchor id
     * @param commit  the commit SHA of the latest git-source version
     * @param lines   the lines in manifest order
     * @param manifestVersions how many manifest versions the store holds (one per materialized commit that changed the file)
     */
    public record StoredDocument(PublicId anchor, String commit, List<StoredLine> lines, int manifestVersions) { }

    /**
     * Rebuild a document from the store: the latest manifest version's order, each line's first
     * and latest version STAMPs, and the affirmations referencing it.
     *
     * @param anchor the document anchor id
     * @return the document, or empty when the anchor has no manifest
     */
    public static Optional<StoredDocument> load(PublicId anchor) {
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        List<SemanticEntity<SemanticEntityVersion>> manifests = EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, RichSurfaceTerms.JOURNAL_MANIFEST_PATTERN.nid()).toList();
        if (manifests.isEmpty()) {
            return Optional.empty();
        }
        SemanticEntity<SemanticEntityVersion> manifest = manifests.getFirst();
        SemanticEntityVersion head = latest(manifest);
        if (head == null) {
            return Optional.empty();
        }
        Map<Long, List<StampEntity<?>>> affirmers = new HashMap<>();
        for (SemanticEntity<SemanticEntityVersion> a : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, StampedDocumentTerms.AFFIRMATION_PATTERN.nid()).toList()) {
            SemanticEntityVersion v = latest(a);
            if (v == null) {
                continue;
            }
            LongIdList ids = (LongIdList) v.fieldValues().get(0);
            for (long nid : ids.toArray()) {
                affirmers.computeIfAbsent(nid, k -> new ArrayList<>()).add(v.stamp());
            }
        }
        String commit = "";
        for (SemanticEntity<SemanticEntityVersion> s : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, StampedDocumentTerms.GIT_SOURCE_PATTERN.nid()).toList()) {
            SemanticEntityVersion v = latest(s);
            if (v != null) {
                commit = String.valueOf(v.fieldValues().get(1));
            }
        }
        List<StoredLine> lines = new ArrayList<>();
        LongIdList order = (LongIdList) head.fieldValues().get(0);
        for (long nid : order.toArray()) {
            Optional<? extends Entity<? extends EntityVersion>> held = EntityHandle.get(nid).entity();
            if (held.isEmpty()) {
                continue;
            }
            Entity<? extends EntityVersion> element = held.get();
            SemanticEntityVersion first = null;
            SemanticEntityVersion last = null;
            for (EntityVersion version : element.versions()) {
                SemanticEntityVersion v = (SemanticEntityVersion) version;
                if (v.time() == Long.MAX_VALUE) {
                    continue;
                }
                if (first == null || v.time() < first.time()) {
                    first = v;
                }
                if (last == null || v.time() > last.time()) {
                    last = v;
                }
            }
            if (first == null) {
                continue;
            }
            lines.add(new StoredLine(nid, String.valueOf(last.fieldValues().get(0)), first.stamp(), last.stamp(),
                    affirmers.getOrDefault(nid, List.of())));
        }
        int versions = 0;
        for (SemanticEntityVersion v : manifest.versions()) {
            if (v.time() != Long.MAX_VALUE) {
                versions++;
            }
        }
        return Optional.of(new StoredDocument(anchor, commit, lines, versions));
    }

    private static SemanticEntityVersion latest(SemanticEntity<SemanticEntityVersion> entity) {
        if (entity.canceled()) {
            return null;
        }
        SemanticEntityVersion head = null;
        for (SemanticEntityVersion version : entity.versions()) {
            if (version.time() == Long.MAX_VALUE) {
                continue;
            }
            if (head == null || version.time() > head.time()) {
                head = version;
            }
        }
        return head;
    }
}
