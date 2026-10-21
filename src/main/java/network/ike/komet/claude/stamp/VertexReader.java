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
import design.knowledge.stamp.adoc.dialect.Reference;
import dev.ikm.tinkar.common.id.LongIdList;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.FieldDefinitionForEntity;
import dev.ikm.tinkar.entity.PatternEntity;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.terms.PatternFacade;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredAnnotation;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredReference;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredInline;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredSentence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a document materialized as semantic vertices (IKE-Network/ike-issues#1313;
 * {@link VertexMaterializer}) into the {@link StoredDocument} every view, the print model, the
 * exporter and the assistant's tools already read, so they work over either model. The document
 * is its root vertex's tree: Members in order give the blocks and lines, a line's Members its
 * inline references; a line's text is its Line source.
 *
 * <p>What the prose-element model kept in patterns of their own is read from the vertices: the
 * attributes from the root's properties, the order from the tree instead of a manifest, a block's
 * kind from its meaning. The git source and the annotations still reference the document's anchor,
 * which is the root vertex, so the existing lookups by anchor answer for both.
 */
public final class VertexReader {

    private VertexReader() {
    }

    /**
     * Whether an anchor is the root vertex of a document materialized as vertices.
     *
     * @param anchor the anchor id
     * @return whether it is a semantic of a document's own vertex pattern
     */
    public static boolean isVertexDocument(PublicId anchor) {
        if (anchor == null || !dev.ikm.tinkar.common.service.PrimitiveData.get().hasPublicId(anchor)) {
            return false;
        }
        Entity<? extends EntityVersion> entity = EntityHandle.get(EntityService.get().nidForPublicId(anchor)).entity().orElse(null);
        return entity instanceof SemanticEntity<?> semantic && isDocumentPattern(semantic.patternNid())
                && semantic.referencedComponentNid() == semantic.patternNid();
    }

    /** Whether a pattern is a document's own vertex pattern: its meaning is Document vertex, and it is not the template. */
    static boolean isDocumentPattern(long patternNid) {
        if (patternNid == IkePattern.DOCUMENT_VERTEX_PATTERN.nid()) {
            return false;
        }
        PatternEntityVersion version = latestPattern(patternNid);
        return version != null && version.semanticMeaningNid() == IkeConcept.DOCUMENT_VERTEX.nid();
    }

    /**
     * Reads the document whose root vertex is the anchor.
     *
     * <p>With {@code pending}, the document is the pending edit's: its base, the commit it was made
     * against, with the edit over it. Each vertex the edit wrote reads its pending version, and every
     * other vertex its latest version no later than the base. In the prose-element model the pending
     * manifest pins the order the person saw; here the order is spread over every block's Members, so
     * the whole tree is read at the base, and a commit materialized since, not yet settled, cannot
     * take the edited block out of the tree it was edited in.
     *
     * @param anchor  the root vertex's id
     * @param pending whether to include uncommitted versions
     * @return the document, or empty when the anchor is no vertex document
     */
    public static Optional<StoredDocument> load(PublicId anchor, boolean pending) {
        if (!isVertexDocument(anchor)) {
            return Optional.empty();
        }
        long rootNid = EntityService.get().nidForPublicId(anchor);
        long base = pending ? DocumentEditor.pendingBase(anchor) : Long.MAX_VALUE;
        SemanticEntityVersion rootVersion = latestOf(versions(rootNid, pending, base));
        if (rootVersion == null || !(rootVersion.fieldValues().get(0) instanceof EntityVertex root)) {
            return Optional.empty();
        }
        Map<Long, List<StoredAnnotation>> annotated = GitDocumentMaterializer.annotationsByElement(rootNid);
        List<StoredLine> lines = new ArrayList<>();
        Map<Long, StoredBlock> blocks = new LinkedHashMap<>();
        Map<Long, List<StoredReference>> references = new HashMap<>();
        Map<Long, Reference.Kind> referenceKinds = referenceKinds();
        StoredBlock rootBlock = new StoredBlock(rootNid, Block.Kind.DOCUMENT, 0, text(root, IkeConcept.DOCUMENT_ELEMENT_TITLE), "",
                List.of(), rootVersion.stamp());
        List<Long> rootTree = new ArrayList<>();
        rootBlock = withMembers(rootBlock, walk(members(root), pending, base, annotated, lines, blocks, references, referenceKinds, rootTree), rootTree);
        blocks.put(rootNid, rootBlock);

        List<String> attributes = new ArrayList<>();
        attributes.add(text(root, IkeConcept.DOCUMENT_ELEMENT_TITLE));
        long[] meanings = fieldMeanings(IkePattern.ASCIIDOC_DOCUMENT_ATTRIBUTES_PATTERN);
        for (int i = 1; i < meanings.length; i++) {
            Object value = root.properties().get(meanings[i]);
            attributes.add(value == null ? "" : String.valueOf(value));
        }
        String commit = "";
        int commits = 0;
        for (SemanticEntity<SemanticEntityVersion> source : EntityService.get()
                .semanticsForComponentOfPattern(rootNid, IkePattern.GIT_SOURCE_PATTERN.nid()).toList()) {
            SemanticEntityVersion latest = null;
            for (SemanticEntityVersion v : source.versions()) {
                if (v.canceled() || v.time() == Long.MAX_VALUE) {
                    continue;
                }
                commits++;
                if (latest == null || v.time() > latest.time()) {
                    latest = v;
                }
            }
            if (latest != null && latest.fieldValues().size() > 1) {
                commit = String.valueOf(latest.fieldValues().get(1));
            }
        }
        Map<Long, List<StoredSentence>> sentences = new LinkedHashMap<>();
        for (long blockNid : blocks.keySet()) {
            SemanticEntityVersion version = latestOf(versions(blockNid, pending, base));
            if (version != null && version.fieldValues().get(0) instanceof EntityVertex vertex) {
                List<StoredSentence> content = sentences(vertex, pending, base, annotated);
                if (!content.isEmpty()) {
                    sentences.put(blockNid, content);
                }
            }
        }
        return Optional.of(new StoredDocument(anchor, commit, List.copyOf(lines), commits, List.copyOf(attributes), rootBlock,
                blocks, references, sentences));
    }

    /**
     * Reads a parent's members in order: a line goes to {@code lines} with its references, a block
     * to {@code blocks} with its own members read in turn. A member whose latest version is inactive
     * was removed and is skipped.
     *
     * <p>A blank line is a member of the deepest block covering it in the vertex tree, so every line
     * has a parent; the prose-element model's blocks never listed blank lines, and the views read a
     * block's members as its content, so a blank line is read into the lines and left out of its
     * parent's members.
     *
     * @param tree receives every member still present, blank lines included, as the tree holds them
     * @return the members still present, as the parent's member nids
     */
    private static List<Long> walk(long[] members, boolean pending, long base, Map<Long, List<StoredAnnotation>> annotated, List<StoredLine> lines,
                                   Map<Long, StoredBlock> blocks, Map<Long, List<StoredReference>> references,
                                   Map<Long, Reference.Kind> referenceKinds, List<Long> tree) {
        List<Long> present = new ArrayList<>();
        for (long nid : members) {
            List<SemanticEntityVersion> versions = versions(nid, pending, base);
            SemanticEntityVersion latest = latestOf(versions);
            if (latest == null || latest.state() != State.ACTIVE || !(latest.fieldValues().get(0) instanceof EntityVertex vertex)) {
                continue;
            }
            tree.add(nid);
            if (vertex.getMeaningNid() != IkeConcept.BLANK_LINE.nid()) {
                present.add(nid);
            }
            if (vertex.properties().containsKey(IkeConcept.LINE_SOURCE.nid())) {
                String text = text(vertex, IkeConcept.LINE_SOURCE);
                SemanticEntityVersion first = versions.stream().min(Comparator.comparingLong(SemanticEntityVersion::time)).orElse(latest);
                lines.add(new StoredLine(nid, text, GitDocumentMaterializer.authored(versions, first).stamp(), latest.stamp(),
                        GitDocumentMaterializer.inForce(annotated.getOrDefault(nid, List.of()), versions, text)));
                for (long referenceNid : members(vertex)) {
                    SemanticEntityVersion ref = latestOf(versions(referenceNid, pending, base));
                    if (ref == null || ref.state() != State.ACTIVE || !(ref.fieldValues().get(0) instanceof EntityVertex rv)) {
                        continue;
                    }
                    Reference.Kind kind = referenceKinds.get(rv.getMeaningNid());
                    if (kind == null) {
                        continue;
                    }
                    long[] meanings = fieldMeanings(AsciiDocModelTerms.referencePattern(kind));
                    Object target = meanings.length > 0 ? rv.properties().get(meanings[0]) : null;
                    Object second = meanings.length > 1 ? rv.properties().get(meanings[1]) : null;
                    references.computeIfAbsent(nid, k -> new ArrayList<>()).add(new StoredReference(referenceNid, kind,
                            target == null ? "" : String.valueOf(target), second instanceof String s ? s : "", nid));
                }
            } else {
                Block.Kind kind = AsciiDocModelTerms.kindOf(EntityHandle.get(vertex.getMeaningNid()).expectEntity().publicId())
                        .orElse(Block.Kind.OPAQUE);
                Object level = vertex.properties().get(IkeConcept.DOCUMENT_ELEMENT_LEVEL.nid());
                StoredBlock block = new StoredBlock(nid, kind, level instanceof Integer l ? l : 0, text(vertex, IkeConcept.DOCUMENT_ELEMENT_TITLE),
                        text(vertex, IkeConcept.DOCUMENT_ELEMENT_PARAMETERS), List.of(), latest.stamp());
                blocks.put(nid, block);                          // placed before its members, so the order is the document's
                List<Long> blockTree = new ArrayList<>();
                blocks.put(nid, withMembers(block, walk(members(vertex), pending, base, annotated, lines, blocks, references, referenceKinds, blockTree),
                        blockTree));
            }
        }
        return present;
    }

    // ── sentences and inline elements ─────────────────────────────────────────

    /**
     * A block's or footnote's sentences, its Content in order, each with its inline elements; a
     * sentence whose latest version is inactive was removed and is skipped.
     */
    private static List<StoredSentence> sentences(EntityVertex parent, boolean pending, long base, Map<Long, List<StoredAnnotation>> annotated) {
        List<StoredSentence> out = new ArrayList<>();
        for (long nid : ids(parent, IkeConcept.BLOCK_CONTENT)) {
            List<SemanticEntityVersion> versions = versions(nid, pending, base);
            SemanticEntityVersion latest = latestOf(versions);
            if (latest == null || latest.state() != State.ACTIVE || !(latest.fieldValues().get(0) instanceof EntityVertex vertex)) {
                continue;
            }
            String text = text(vertex, IkeConcept.SEGMENT_TEXT);
            out.add(new StoredSentence(nid, text, wrote(versions, latest).stamp(), latest.stamp(), inlines(vertex, pending, base, annotated),
                    GitDocumentMaterializer.inForce(annotated.getOrDefault(nid, List.of()), versions, text)));
        }
        return out;
    }

    private static List<StoredInline> inlines(EntityVertex parent, boolean pending, long base, Map<Long, List<StoredAnnotation>> annotated) {
        List<StoredInline> out = new ArrayList<>();
        for (long nid : members(parent)) {
            List<SemanticEntityVersion> versions = versions(nid, pending, base);
            SemanticEntityVersion latest = latestOf(versions);
            if (latest == null || latest.state() != State.ACTIVE || !(latest.fieldValues().get(0) instanceof EntityVertex vertex)) {
                continue;
            }
            out.add(new StoredInline(nid, vertex.getMeaningNid(), text(vertex, IkeConcept.SEGMENT_TEXT), wrote(versions, latest).stamp(),
                    latest.stamp(), inlines(vertex, pending, base, annotated), sentences(vertex, pending, base, annotated)));
        }
        return out;
    }

    /** The version that wrote a segment's current text: the earliest of the latest run of versions with that text. */
    private static SemanticEntityVersion wrote(List<SemanticEntityVersion> versions, SemanticEntityVersion latest) {
        List<SemanticEntityVersion> sorted = new ArrayList<>(versions);
        sorted.sort(Comparator.comparingLong(SemanticEntityVersion::time));
        String now = segmentText(latest);
        SemanticEntityVersion wrote = latest;
        for (int i = sorted.size() - 1; i >= 0; i--) {
            SemanticEntityVersion v = sorted.get(i);
            if (v.state() != State.ACTIVE || !segmentText(v).equals(now)) {
                break;
            }
            wrote = v;
        }
        return wrote;
    }

    private static String segmentText(SemanticEntityVersion version) {
        return version.fieldValues().get(0) instanceof EntityVertex vertex ? text(vertex, IkeConcept.SEGMENT_TEXT) : "";
    }

    private static long[] ids(EntityVertex vertex, dev.ikm.tinkar.terms.ConceptFacade key) {
        return vertex.properties().get(key.nid()) instanceof LongIdList ids ? ids.toArray() : new long[0];
    }

    private static StoredBlock withMembers(StoredBlock block, List<Long> members, List<Long> tree) {
        return new StoredBlock(block.nid(), block.kind(), block.level(), block.title(), block.parameters(), List.copyOf(members), block.stamp(),
                List.copyOf(tree));
    }

    // ── history ───────────────────────────────────────────────────────────────

    /**
     * A vertex document's history for the Graph view's History mode: the line order after every
     * commit that changed the file, read as the tree stood then, and every version of every line,
     * its text its Line source.
     *
     * @param anchor  the root vertex's id
     * @param pending whether to include uncommitted versions
     * @return the history
     */
    public static DocumentHistory history(PublicId anchor, boolean pending) {
        long rootNid = EntityService.get().nidForPublicId(anchor);
        List<Long> times = new ArrayList<>();
        for (SemanticEntity<SemanticEntityVersion> source : EntityService.get()
                .semanticsForComponentOfPattern(rootNid, IkePattern.GIT_SOURCE_PATTERN.nid()).toList()) {
            for (SemanticEntityVersion v : source.versions()) {
                if (!v.canceled() && v.time() != Long.MAX_VALUE) {
                    times.add(v.time());
                }
            }
        }
        if (pending) {
            times.add(Long.MAX_VALUE);
        }
        times.sort(Comparator.naturalOrder());
        List<List<Long>> orders = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (long time : times.stream().distinct().toList()) {
            List<Long> order = new ArrayList<>();
            preorderAt(rootNid, time, order);
            orders.add(order);
            seen.addAll(order);
        }
        Map<Long, List<DocumentHistory.Raw>> raw = new LinkedHashMap<>();
        for (long nid : seen) {
            List<SemanticEntityVersion> versions = versions(nid, pending);
            versions.sort(Comparator.comparingLong(SemanticEntityVersion::time));
            List<DocumentHistory.Raw> list = new ArrayList<>();
            for (SemanticEntityVersion v : versions) {
                list.add(new DocumentHistory.Raw(v.stamp(), GitDocumentMaterializer.lineText(v), v.state() == State.ACTIVE));
            }
            raw.put(nid, list);
        }
        return DocumentHistory.from(orders, raw);
    }

    /** The line vertices in document order as the tree stood at a time: each vertex read at its version then. */
    private static void preorderAt(long nid, long time, List<Long> into) {
        SemanticEntityVersion version = versionAt(nid, time);
        if (version == null || version.state() != State.ACTIVE || !(version.fieldValues().get(0) instanceof EntityVertex vertex)) {
            return;
        }
        if (vertex.properties().containsKey(IkeConcept.LINE_SOURCE.nid())) {
            into.add(nid);
            return;
        }
        for (long member : members(vertex)) {
            preorderAt(member, time, into);
        }
    }

    // ── versions and properties ───────────────────────────────────────────────

    private static List<SemanticEntityVersion> versions(long nid, boolean pending) {
        return versions(nid, pending, Long.MAX_VALUE);
    }

    /** A vertex's versions: committed ones no later than {@code base}, and, with {@code pending}, an uncommitted one. */
    private static List<SemanticEntityVersion> versions(long nid, boolean pending, long base) {
        List<SemanticEntityVersion> out = new ArrayList<>();
        Entity<? extends EntityVersion> entity = EntityHandle.get(nid).entity().orElse(null);
        if (entity == null) {
            return out;
        }
        for (EntityVersion version : entity.versions()) {
            if (version instanceof SemanticEntityVersion v && !v.canceled() && (v.time() == Long.MAX_VALUE ? pending : v.time() <= base)) {
                out.add(v);
            }
        }
        return out;
    }

    private static SemanticEntityVersion latestOf(List<SemanticEntityVersion> versions) {
        SemanticEntityVersion latest = null;
        for (SemanticEntityVersion v : versions) {
            if (latest == null || v.time() >= latest.time()) {
                latest = v;
            }
        }
        return latest;
    }

    private static SemanticEntityVersion latest(long nid, boolean pending) {
        return latestOf(versions(nid, pending));
    }

    private static SemanticEntityVersion versionAt(long nid, long time) {
        SemanticEntityVersion at = null;
        for (SemanticEntityVersion v : versions(nid, true)) {
            if (v.time() <= time && (at == null || v.time() >= at.time())) {
                at = v;
            }
        }
        return at;
    }

    /**
     * A vertex's Members, in order.
     *
     * @param vertex the vertex
     * @return the member nids, empty when it has none
     */
    public static long[] members(EntityVertex vertex) {
        return vertex.properties().get(IkeConcept.DOCUMENT_ELEMENT_MEMBERS.nid()) instanceof LongIdList ids ? ids.toArray() : new long[0];
    }

    private static String text(EntityVertex vertex, dev.ikm.tinkar.terms.ConceptFacade key) {
        Object value = vertex.properties().get(key.nid());
        return value == null ? "" : String.valueOf(value);
    }

    /** Each reference kind by the meaning its vertices carry, the meaning of its pattern. */
    private static Map<Long, Reference.Kind> referenceKinds() {
        Map<Long, Reference.Kind> kinds = new HashMap<>();
        for (Reference.Kind kind : Reference.Kind.values()) {
            PatternEntityVersion version = latestPattern(AsciiDocModelTerms.referencePattern(kind).nid());
            if (version != null) {
                kinds.put(version.semanticMeaningNid(), kind);
            }
        }
        return kinds;
    }

    private static long[] fieldMeanings(PatternFacade pattern) {
        PatternEntityVersion version = latestPattern(pattern.nid());
        if (version == null) {
            return new long[0];
        }
        long[] meanings = new long[version.fieldDefinitions().size()];
        int i = 0;
        for (FieldDefinitionForEntity definition : version.fieldDefinitions()) {
            meanings[i++] = definition.meaningNid();
        }
        return meanings;
    }

    private static PatternEntityVersion latestPattern(long patternNid) {
        Entity<? extends EntityVersion> entity = EntityHandle.get(patternNid).entity().orElse(null);
        if (!(entity instanceof PatternEntity<?> pattern)) {
            return null;
        }
        PatternEntityVersion latest = null;
        for (EntityVersion v : pattern.versions()) {
            if (v instanceof PatternEntityVersion pv && !pv.canceled() && (latest == null || pv.time() >= latest.time())) {
                latest = pv;
            }
        }
        return latest;
    }
}
