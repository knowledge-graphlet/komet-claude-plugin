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

import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.dialect.Document;
import design.knowledge.stamp.adoc.dialect.IkeDialectParser;
import design.knowledge.stamp.adoc.dialect.Reference;
import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.identity.GitIdentityAdapter;
import design.knowledge.stamp.adoc.identity.Identity;
import design.knowledge.stamp.adoc.identity.Uuid5;
import design.knowledge.stamp.adoc.model.AnnotationKind;
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
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.EntityProxy.Concept;
import dev.ikm.tinkar.terms.EntityProxy.Semantic;
import dev.ikm.tinkar.terms.PatternFacade;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.doc.DocumentVocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git to graph ({@code IKE-Network/ike-issues#468}): writes one file's history into the open
 * datastore as the journal-shaped document of the IKE starter set's git-provenance terms — one
 * anchor concept, one prose element per line, one manifest version per commit that changed the
 * file, a git-source version beside it carrying the commit SHA, and an Annotation pattern semantic
 * per {@code Annotation:} trailer (IKE-Network/ike-issues#1300): its kind, the STAMP of the version
 * the annotator saw, the annotated lines and the commit body as its text — every write STAMPed with the commit's own coordinates through the
 * identity adapter. Replaying the manifest versions in STAMP time is the file's history.
 *
 * <p>Over the lines it writes the AsciiDoc document model ({@link AsciiDocModelTerms}): each
 * commit's text is parsed with the {@link IkeDialectParser}, and the document's attributes, its
 * blocks (kind, level, title, parameters, ordered members) and the inline references on its
 * lines become semantics under the same STAMP. A block's identity is the line that identifies
 * it — a section's heading, any other block's first line — so a block survives the commits that
 * leave that line in place; a version is written only when a block's fields changed, a block or
 * reference that disappears gets an inactive version under the commit that removed it, and a
 * reference is a semantic on the line it occurs in, regenerated whenever the line changes.
 *
 * <p>Identities are deterministic (document, line, manifest, source, block, reference and
 * annotation ids are all UUID5s of frozen keys, or the trailer's own id when it carries one) and every STAMP is a complete tuple, so the
 * store itself converges: a tuple is one stamp entity and identical versions under it merge to
 * one. On top of that a run skips the commits the store already records — the git-source
 * versions name every materialized commit, and each annotation semantic exists or not — so a
 * later run is incremental. Whether the store is ephemeral or persisted is the datastore's
 * choice, not this class's.
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
     * @param annotations  annotation semantics written
     * @param blocks       block versions written (new blocks and changed ones)
     * @param references   reference versions written
     * @param headSha      the HEAD the history was read from
     */
    public record Result(PublicId anchor, String name, int commits, int lines, int annotations, int blocks, int references, String headSha) { }

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
        DocumentVocabulary.requirePresent();
        GitIdentityAdapter adapter = GitIdentityAdapter.fromMailmap(repoRoot);
        String component = repoRoot.toAbsolutePath().normalize().getFileName().toString();
        try (GitRepo repo = GitRepo.open(repoRoot)) {
            String branch = repo.branch();
            StampHistory history = repo.stampHistoryOf(file);
            Path relative = history.file();
            String path = relative.toString().replace('\\', '/');
            Identity documentId = adapter.document(component, path);
            String name = topicId(repo, relative).orElse(path);
            ConceptFacade anchor = Concept.make(name, documentId.uuid());
            Semantic manifest = Semantic.make(PublicIds.of(Uuid5.of(GitIdentityAdapter.NAMESPACE, "manifest:" + documentId.key())));
            Semantic source = Semantic.make(PublicIds.of(Uuid5.of(GitIdentityAdapter.NAMESPACE, "source:" + documentId.key())));
            Semantic attributes = Semantic.make(PublicIds.of(Uuid5.of(GitIdentityAdapter.NAMESPACE, "attributes:" + documentId.key())));
            Map<LineId, String> lastText = new HashMap<>();
            Structure structure = new Structure();
            int lines = 0;
            int annotations = 0;
            int blocks = 0;
            int references = 0;
            boolean anchorWritten = !DocumentVocabulary.entityAbsent(anchor);
            Set<String> recorded = anchorWritten && onlyNew ? recordedCommits(anchor) : Set.of();
            int skipped = 0;
            for (CommitEvent event : history.events()) {
                boolean contentRecorded = !event.touchesFile() || recorded.contains(event.commit().sha());
                List<Semantic> pendingAnnotations = new ArrayList<>();
                for (CommitEvent.AnnotationEvent a : event.annotations()) {
                    Semantic proxy = Semantic.make(PublicIds.of(a.annotation().id() != null ? a.annotation().id()
                            : Uuid5.of(GitIdentityAdapter.NAMESPACE, "annotation:" + documentId.key() + ":" + event.commit().sha() + ":" + a.index())));
                    pendingAnnotations.add(!onlyNew || DocumentVocabulary.entityAbsent(proxy) ? proxy : null);
                }
                for (CommitEvent.Line line : event.introduced()) {
                    lastText.put(line.id(), line.text());
                }
                for (CommitEvent.Line line : event.retexted()) {
                    lastText.put(line.id(), line.text());
                }
                Snapshot now = event.touchesFile() ? snapshot(adapter, documentId, event.manifest(), lastText) : null;
                if (contentRecorded && pendingAnnotations.stream().allMatch(java.util.Objects::isNull)) {
                    structure.adopt(now);
                    skipped++;
                    continue;                                    // already in the store
                }
                GitStampTerms.GitStamp stamp = GitStampTerms.GitStamp.of(adapter.coordinatesOf(event, component, branch));
                GitStampVocabulary.seedIfAbsent(stamp);
                List<Retirement> retirements = new ArrayList<>();
                try (StampedWriter writer = stamp.openWriter("git-materialize " + event.commit().sha().substring(0, 7))) {
                    if (!anchorWritten) {
                        writer.concept(anchor.publicId());
                        writer.fullyQualifiedName(anchor.publicId(), name);
                        anchorWritten = true;
                    }
                    for (CommitEvent.Line line : contentRecorded ? List.<CommitEvent.Line>of() : event.introduced()) {
                        writer.semantic(lineProxy(adapter, line.id()), IkePattern.PROSE_ELEMENT_PATTERN.publicId(), anchor.publicId(), line.text());
                        lines++;
                    }
                    for (CommitEvent.Line line : contentRecorded ? List.<CommitEvent.Line>of() : event.retexted()) {
                        writer.semantic(lineProxy(adapter, line.id()), IkePattern.PROSE_ELEMENT_PATTERN.publicId(), anchor.publicId(), line.text());
                    }
                    if (now != null && !contentRecorded) {
                        long[] nids = new long[event.manifest().size()];
                        for (int i = 0; i < nids.length; i++) {
                            nids[i] = EntityService.get().nidForPublicId(lineProxy(adapter, event.manifest().get(i)).publicId());
                        }
                        writer.semantic(manifest, IkePattern.JOURNAL_MANIFEST_PATTERN.publicId(), anchor.publicId(), LongIds.list.of(nids));
                        writer.semantic(source, IkePattern.GIT_SOURCE_PATTERN.publicId(), anchor.publicId(), event.pathAtCommit(), event.commit().sha());
                        if (!Arrays.equals(now.attributes, structure.attributes)) {
                            writer.semantic(attributes, IkePattern.ASCIIDOC_DOCUMENT_ATTRIBUTES_PATTERN.publicId(), anchor.publicId(), (Object[]) now.attributes);
                        }
                        for (Map.Entry<String, Written> e : now.blocks.entrySet()) {
                            Written last = structure.blocks.get(e.getKey());
                            if (last == null || !last.signature.equals(e.getValue().signature)) {
                                writer.semantic(e.getValue().proxy, IkePattern.DOCUMENT_BLOCK_PATTERN.publicId(), anchor.publicId(), e.getValue().fields);
                                blocks++;
                            }
                        }
                        for (Map.Entry<String, Written> e : now.references.entrySet()) {
                            Written last = structure.references.get(e.getKey());
                            if (last == null || !last.signature.equals(e.getValue().signature)) {
                                writer.semantic(e.getValue().proxy, e.getValue().pattern.publicId(), e.getValue().referenced, e.getValue().fields);
                                references++;
                            }
                        }
                        structure.retired(now, retirements);
                    }
                    for (int i = 0; i < event.annotations().size(); i++) {
                        Semantic proxy = pendingAnnotations.get(i);
                        if (proxy == null) {
                            continue;                            // already in the store
                        }
                        CommitEvent.AnnotationEvent a = event.annotations().get(i);
                        long[] nids = new long[a.lines().size()];
                        for (int j = 0; j < nids.length; j++) {
                            nids[j] = EntityService.get().nidForPublicId(lineProxy(adapter, a.lines().get(j)).publicId());
                        }
                        writer.semantic(proxy, IkePattern.ANNOTATION_PATTERN.publicId(), anchor.publicId(),
                                StampedDocumentTerms.kind(a.kind()), EntityProxy.make(reviewedStampNid(source, a.reviewedSha())),
                                LongIds.list.of(nids), a.text());
                        annotations++;
                    }
                    writer.commit();
                }
                if (!contentRecorded && (!event.retired().isEmpty() || !retirements.isEmpty())) {
                    // A retired line, block or reference gets an inactive version under the retiring
                    // commit's STAMP, so the graph records the withdrawal where git recorded it.
                    try (StampedWriter writer = StampedWriter.open("git-retire " + event.commit().sha().substring(0, 7), State.INACTIVE,
                            stamp.time(), stamp.author(), stamp.module(), stamp.path())) {
                        for (LineId id : event.retired()) {
                            writer.semantic(lineProxy(adapter, id), IkePattern.PROSE_ELEMENT_PATTERN.publicId(), anchor.publicId(), lastText.getOrDefault(id, ""));
                        }
                        for (Retirement r : retirements) {
                            writer.semantic(r.written.proxy, r.written.pattern.publicId(), r.written.referenced == null ? anchor.publicId() : r.written.referenced, r.written.fields);
                        }
                        writer.commit();
                    }
                }
                structure.adopt(now);
            }
            LOG.info("Materialized {} as '{}': {} commit(s) written, {} already recorded, {} line(s), {} block version(s), {} reference version(s), {} annotation(s)",
                    path, name, history.events().size() - skipped, skipped, lines, blocks, references, annotations);
            return new Result(anchor.publicId(), name, history.events().size() - skipped, lines, annotations, blocks, references, repo.headRevision());
        }
    }

    /**
     * The nid of the STAMP the git source was versioned under at {@code sha}: the version the
     * annotator saw. The walker names the last commit that changed the file at or before the
     * annotation's anchoring revision, and every such commit wrote a git-source version earlier
     * in the history; when none matches — an anchor the walk could not place — the latest
     * source version stands in.
     */
    private static long reviewedStampNid(Semantic source, String sha) {
        Entity<? extends EntityVersion> entity = EntityHandle.get(source.nid()).expectEntity();
        SemanticEntityVersion latest = null;
        for (EntityVersion version : entity.versions()) {
            SemanticEntityVersion v = (SemanticEntityVersion) version;
            if (v.time() == Long.MAX_VALUE) {
                continue;
            }
            if (sha.equals(String.valueOf(v.fieldValues().get(1)))) {
                return v.stampNid();
            }
            if (latest == null || v.time() > latest.time()) {
                latest = v;
            }
        }
        if (latest == null) {
            throw new IllegalStateException("no git-source version to anchor an annotation to");
        }
        return latest.stampNid();
    }

    // ── structure ─────────────────────────────────────────────────────────────

    /** One semantic version ready to write: its identity, pattern, referenced component, fields and a comparable signature. */
    private record Written(Semantic proxy, PatternFacade pattern, PublicId referenced, Object[] fields, String signature) { }

    private record Retirement(Written written) { }

    /** The parse of one commit's text, as the semantics it implies, keyed for comparison with the previous commit's. */
    private static final class Snapshot {
        String[] attributes;
        final Map<String, Written> blocks = new LinkedHashMap<>();
        final Map<String, Written> references = new LinkedHashMap<>();
    }

    /** What the store holds after the events so far, for writing only what changed. */
    private static final class Structure {
        String[] attributes;
        final Map<String, Written> blocks = new HashMap<>();
        final Map<String, Written> references = new HashMap<>();

        void adopt(Snapshot now) {
            if (now == null) {
                return;
            }
            attributes = now.attributes;
            blocks.keySet().retainAll(now.blocks.keySet());
            blocks.putAll(now.blocks);
            references.keySet().retainAll(now.references.keySet());
            references.putAll(now.references);
        }

        void retired(Snapshot now, List<Retirement> into) {
            for (Map.Entry<String, Written> e : blocks.entrySet()) {
                if (!now.blocks.containsKey(e.getKey())) {
                    into.add(new Retirement(e.getValue()));
                }
            }
            for (Map.Entry<String, Written> e : references.entrySet()) {
                if (!now.references.containsKey(e.getKey())) {
                    into.add(new Retirement(e.getValue()));
                }
            }
        }
    }

    private static Snapshot snapshot(GitIdentityAdapter adapter, Identity documentId, List<LineId> manifest, Map<LineId, String> text) {
        List<String> lines = new ArrayList<>(manifest.size());
        for (LineId id : manifest) {
            lines.add(text.getOrDefault(id, ""));
        }
        Document doc = IkeDialectParser.parse(lines);
        Snapshot snapshot = new Snapshot();
        snapshot.attributes = new String[1 + AsciiDocModelTerms.TOPIC_ATTRIBUTES.size()];
        snapshot.attributes[0] = doc.title();
        for (int i = 0; i < AsciiDocModelTerms.TOPIC_ATTRIBUTES.size(); i++) {
            snapshot.attributes[i + 1] = doc.attribute(AsciiDocModelTerms.TOPIC_ATTRIBUTES.get(i)).orElse("");
        }
        long[] lineNids = new long[manifest.size()];
        for (int i = 0; i < lineNids.length; i++) {
            lineNids[i] = EntityService.get().nidForPublicId(lineProxy(adapter, manifest.get(i)).publicId());
        }
        Map<Block, String> keys = new HashMap<>();
        Map<Block, Long> nids = new HashMap<>();
        Map<String, Long> anchors = new HashMap<>();
        for (Block block : doc.blocks()) {
            String key = block.kind() == Block.Kind.DOCUMENT
                    ? "block:" + documentId.key() + ":document"
                    : "block:" + documentId.key() + ":" + block.kind().name().toLowerCase() + ":"
                            + manifest.get((block.headingLine() > 0 ? block.headingLine() : block.firstLine()) - 1).key();
            keys.put(block, key);
            Semantic proxy = Semantic.make(PublicIds.of(Uuid5.of(GitIdentityAdapter.NAMESPACE, key)));
            long nid = EntityService.get().nidForSemantic(IkePattern.DOCUMENT_BLOCK_PATTERN.publicId(), proxy.publicId());
            nids.put(block, nid);
            if (!block.anchor().isEmpty()) {
                anchors.put(block.anchor(), nid);
            }
        }
        for (Block block : doc.blocks()) {
            // members in document order: own lines and child blocks sorted by their position
            List<long[]> members = new ArrayList<>();
            for (int line : block.lines()) {
                members.add(new long[] {line, lineNids[line - 1]});
            }
            for (Block child : block.children()) {
                members.add(new long[] {child.firstLine(), nids.get(child)});
            }
            members.sort((a, b) -> Long.compare(a[0], b[0]));
            long[] memberNids = new long[members.size()];
            for (int i = 0; i < memberNids.length; i++) {
                memberNids[i] = members.get(i)[1];
            }
            Object[] fields = {AsciiDocModelTerms.kind(block.kind()), block.level(), block.title(), block.parameters(), LongIds.list.of(memberNids)};
            String signature = block.kind() + "|" + block.level() + "|" + block.title() + "|" + block.parameters() + "|" + Arrays.toString(memberNids);
            String key = keys.get(block);
            snapshot.blocks.put(key, new Written(Semantic.make(PublicIds.of(Uuid5.of(GitIdentityAdapter.NAMESPACE, key))),
                    IkePattern.DOCUMENT_BLOCK_PATTERN, null, fields, signature));
        }
        for (Reference reference : doc.references()) {
            LineId lineId = manifest.get(reference.line() - 1);
            String key = "ref:" + lineId.key() + ":" + reference.index();
            Long resolved = reference.kind() == Reference.Kind.CITATION ? anchors.get(reference.target()) : null;
            Object[] fields = AsciiDocModelTerms.referenceFields(reference, resolved == null ? null : Semantic.make(resolved));
            String signature = reference.kind() + "|" + reference.target() + "|" + reference.text() + "|" + resolved;
            snapshot.references.put(key, new Written(Semantic.make(PublicIds.of(Uuid5.of(GitIdentityAdapter.NAMESPACE, key))),
                    AsciiDocModelTerms.referencePattern(reference.kind()), lineProxy(adapter, lineId).publicId(), fields, signature));
        }
        return snapshot;
    }

    /** The commit SHAs the document's git-source versions already record. */
    private static Set<String> recordedCommits(ConceptFacade anchor) {
        Set<String> shas = new HashSet<>();
        long anchorNid = EntityService.get().nidForPublicId(anchor.publicId());
        for (SemanticEntity<SemanticEntityVersion> source : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, IkePattern.GIT_SOURCE_PATTERN.nid()).toList()) {
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
     * @param annotations the annotations covering it in force: for each annotator, their latest
     *                    judgment (an affirmation or a refutation), and every comment
     */
    public record StoredLine(long nid, String text, StampEntity<?> introduced, StampEntity<?> latest, List<StoredAnnotation> annotations) {

        /** @return the STAMPs of the affirmations in force */
        public List<StampEntity<?>> affirmers() {
            return of(AnnotationKind.AFFIRMATION);
        }

        /** @return the STAMPs of the refutations in force */
        public List<StampEntity<?>> refuters() {
            return of(AnnotationKind.REFUTATION);
        }

        private List<StampEntity<?>> of(AnnotationKind kind) {
            return annotations.stream().filter(a -> a.kind() == kind).<StampEntity<?>>map(StoredAnnotation::stamp).toList();
        }
    }

    /**
     * One annotation as read from the store.
     *
     * @param kind          the kind
     * @param stamp         the annotation's own STAMP: who and when
     * @param reviewedStamp the nid of the STAMP of the version the annotator saw
     * @param text          the annotation text, possibly empty
     */
    public record StoredAnnotation(AnnotationKind kind, StampEntity<?> stamp, long reviewedStamp, String text) { }

    /**
     * One structural element as read from the store.
     *
     * @param nid        the block semantic's nid
     * @param kind       the element kind
     * @param level      a section's heading level or a list's depth
     * @param title      the heading, block title or description term
     * @param parameters the block attribute line as written
     * @param members    the nids of the lines and blocks it contains, in document order
     * @param stamp      the STAMP of the block's latest version
     */
    public record StoredBlock(long nid, Block.Kind kind, int level, String title, String parameters, List<Long> members, StampEntity<?> stamp) { }

    /**
     * One inline reference as read from the store.
     *
     * @param nid     the reference semantic's nid
     * @param kind    the reference kind
     * @param target  the anchor, key, path, URL, term or note
     * @param text    the display text, parameters, or empty
     * @param lineNid the line element the reference occurs on
     */
    public record StoredReference(long nid, Reference.Kind kind, String target, String text, long lineNid) { }

    /**
     * The document as the store holds it at the latest manifest version.
     *
     * @param anchor           the anchor id
     * @param commit           the commit SHA of the latest git-source version
     * @param lines            the lines in manifest order
     * @param manifestVersions how many manifest versions the store holds (one per materialized commit that changed the file)
     * @param attributes       the document attributes: title, then the topic attributes in {@link AsciiDocModelTerms#TOPIC_ATTRIBUTES} order
     * @param root             the document block, or {@code null} when the store holds no structure for it
     * @param blocks           every active block by nid
     * @param references       the active references by the nid of the line they occur on
     */
    public record StoredDocument(PublicId anchor, String commit, List<StoredLine> lines, int manifestVersions,
                                 List<String> attributes, StoredBlock root, Map<Long, StoredBlock> blocks,
                                 Map<Long, List<StoredReference>> references) {

        /** @return the line with that nid, if the manifest holds it */
        public Optional<StoredLine> line(long nid) {
            return lines.stream().filter(l -> l.nid() == nid).findFirst();
        }
    }

    /**
     * Rebuild a document from the store: the latest manifest version's order, each line's first
     * and latest version STAMPs, the annotations covering it, and the structure and
     * references over the lines.
     *
     * @param anchor the document anchor id
     * @return the document, or empty when the anchor has no manifest
     */
    public static Optional<StoredDocument> load(PublicId anchor) {
        return load(anchor, false);
    }

    /**
     * Rebuild a document from the store, either as committed or with the edit pending in the
     * store: with {@code pending}, the manifest, each line and each block read their uncommitted
     * version where they have one, so a line written in Komet and not yet committed in git is in
     * the document. The git source, the attributes and the references stay as committed.
     *
     * @param anchor  the document anchor id
     * @param pending whether to include uncommitted versions
     * @return the document, or empty when the anchor has no manifest
     */
    public static Optional<StoredDocument> load(PublicId anchor, boolean pending) {
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        List<SemanticEntity<SemanticEntityVersion>> manifests = EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, IkePattern.JOURNAL_MANIFEST_PATTERN.nid()).toList();
        if (manifests.isEmpty()) {
            return Optional.empty();
        }
        SemanticEntity<SemanticEntityVersion> manifest = manifests.getFirst();
        SemanticEntityVersion head = latest(manifest, pending);
        if (head == null) {
            return Optional.empty();
        }
        Map<Long, List<StoredAnnotation>> annotated = annotationsByElement(anchorNid);
        String commit = "";
        for (SemanticEntity<SemanticEntityVersion> s : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, IkePattern.GIT_SOURCE_PATTERN.nid()).toList()) {
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
                if (v.canceled() || (v.time() == Long.MAX_VALUE && !pending)) {
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
                    annotated.getOrDefault(nid, List.of())));
        }
        int versions = 0;
        for (SemanticEntityVersion v : manifest.versions()) {
            if (v.time() != Long.MAX_VALUE && !v.canceled()) {      // a settled edit leaves a cancelled pending version
                versions++;
            }
        }
        List<String> attributes = new ArrayList<>();
        for (SemanticEntity<SemanticEntityVersion> s : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, IkePattern.ASCIIDOC_DOCUMENT_ATTRIBUTES_PATTERN.nid()).toList()) {
            SemanticEntityVersion v = latest(s);
            if (v != null) {
                attributes.clear();
                for (Object value : v.fieldValues()) {
                    attributes.add(String.valueOf(value));
                }
            }
        }
        Map<Long, Block.Kind> kinds = new HashMap<>();
        for (Block.Kind kind : Block.Kind.values()) {
            kinds.put(AsciiDocModelTerms.kind(kind).nid(), kind);
        }
        Map<Long, StoredBlock> blocks = new LinkedHashMap<>();
        StoredBlock root = null;
        for (SemanticEntity<SemanticEntityVersion> s : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, IkePattern.DOCUMENT_BLOCK_PATTERN.nid()).toList()) {
            SemanticEntityVersion v = latest(s, pending);
            if (v == null || v.state() != State.ACTIVE) {
                continue;
            }
            Block.Kind kind = kinds.get(((EntityFacade) v.fieldValues().get(0)).nid());
            if (kind == null) {
                continue;
            }
            List<Long> members = new ArrayList<>();
            for (long nid : ((LongIdList) v.fieldValues().get(4)).toArray()) {
                members.add(nid);
            }
            StoredBlock block = new StoredBlock(s.nid(), kind, (Integer) v.fieldValues().get(1), String.valueOf(v.fieldValues().get(2)),
                    String.valueOf(v.fieldValues().get(3)), members, v.stamp());
            blocks.put(s.nid(), block);
            if (kind == Block.Kind.DOCUMENT) {
                root = block;
            }
        }
        Set<Long> lineNids = new HashSet<>();
        for (StoredLine line : lines) {
            lineNids.add(line.nid());
        }
        Map<Long, List<StoredReference>> references = new HashMap<>();
        for (Reference.Kind kind : Reference.Kind.values()) {
            EntityService.get().semanticsOfPattern(AsciiDocModelTerms.referencePattern(kind).nid())
                    .filter(s -> lineNids.contains(s.referencedComponentNid()))
                    .forEach(s -> {
                        SemanticEntityVersion v = latest(s);
                        if (v == null || v.state() != State.ACTIVE) {
                            return;
                        }
                        String text = v.fieldValues().size() > 1 && v.fieldValues().get(1) instanceof String t ? t : "";
                        references.computeIfAbsent(s.referencedComponentNid(), k -> new ArrayList<>())
                                .add(new StoredReference(s.nid(), kind, String.valueOf(v.fieldValues().get(0)), text, s.referencedComponentNid()));
                    });
        }
        return Optional.of(new StoredDocument(anchor, commit, lines, versions, attributes, root, blocks, references));
    }

    /** The anchors of every document the store holds, by name. */
    public static Map<String, PublicId> storedDocuments() {
        Map<String, PublicId> found = new java.util.TreeMap<>();
        if (DocumentVocabulary.entityAbsent(IkePattern.GIT_SOURCE_PATTERN)) {
            return found;
        }
        dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator view = dev.ikm.tinkar.coordinate.Calculators.View.Default();
        EntityService.get().semanticsOfPattern(IkePattern.GIT_SOURCE_PATTERN.nid()).forEach(s -> {
            long anchorNid = s.referencedComponentNid();
            EntityHandle.get(anchorNid).entity().ifPresent(anchor ->
                    found.put(StampNames.name(view, anchorNid), anchor.publicId()));
        });
        return found;
    }

    /**
     * The annotations on {@code anchorNid} by the element they cover, each list holding, per
     * annotator (the STAMP author), only their latest judgment, plus every comment, oldest
     * first. A kind the engine does not know is skipped.
     */
    private static Map<Long, List<StoredAnnotation>> annotationsByElement(long anchorNid) {
        record Seen(StoredAnnotation annotation, long element) { }
        List<Seen> seen = new ArrayList<>();
        for (SemanticEntity<SemanticEntityVersion> a : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, IkePattern.ANNOTATION_PATTERN.nid()).toList()) {
            SemanticEntityVersion v = latest(a);
            if (v == null || !v.active()) {
                continue;
            }
            Object kindValue = v.fieldValues().get(0);
            Optional<AnnotationKind> kind = kindValue instanceof dev.ikm.tinkar.component.Component c
                    ? AnnotationKind.of(c.publicId().asUuidArray()[0]) : Optional.empty();
            if (kind.isEmpty()) {
                continue;
            }
            long reviewed = v.fieldValues().get(1) instanceof dev.ikm.tinkar.component.Component c
                    ? EntityService.get().nidForPublicId(c.publicId()) : 0;
            StoredAnnotation stored = new StoredAnnotation(kind.get(), v.stamp(), reviewed, String.valueOf(v.fieldValues().get(3)));
            for (long nid : ((LongIdList) v.fieldValues().get(2)).toArray()) {
                seen.add(new Seen(stored, nid));
            }
        }
        seen.sort(java.util.Comparator.comparingLong(s -> s.annotation().stamp().time()));
        Map<Long, List<StoredAnnotation>> byElement = new HashMap<>();
        for (Seen s : seen) {
            List<StoredAnnotation> on = byElement.computeIfAbsent(s.element(), k -> new ArrayList<>());
            StoredAnnotation a = s.annotation();
            if (a.kind() != AnnotationKind.COMMENT) {            // a later stance by the same annotator replaces the earlier one
                on.removeIf(o -> o.kind() != AnnotationKind.COMMENT && o.stamp().authorNid() == a.stamp().authorNid());
            }
            on.add(a);
        }
        return byElement;
    }

    private static SemanticEntityVersion latest(SemanticEntity<SemanticEntityVersion> entity) {
        return latest(entity, false);
    }

    /** The latest version, committed only or with an uncommitted one (which is latest) when {@code pending}. */
    static SemanticEntityVersion latest(SemanticEntity<SemanticEntityVersion> entity, boolean pending) {
        if (entity.canceled()) {
            return null;
        }
        SemanticEntityVersion head = null;
        for (SemanticEntityVersion version : entity.versions()) {
            if (version.canceled() || (version.time() == Long.MAX_VALUE && !pending)) {
                continue;
            }
            if (head == null || version.time() > head.time()) {
                head = version;
            }
        }
        return head;
    }
}
