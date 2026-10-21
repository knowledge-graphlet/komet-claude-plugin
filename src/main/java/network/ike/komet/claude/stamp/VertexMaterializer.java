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
import dev.ikm.tinkar.entity.FieldDefinitionForEntity;
import dev.ikm.tinkar.entity.PatternEntity;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.EntityProxy.Semantic;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.PatternFacade;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.impl.factory.primitive.LongObjectMaps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Git to graph as semantic vertices (IKE-Network/ike-issues#1313; design
 * {@code design-2026-10-20-instance-data}): writes one file's history into the open store as a
 * pattern of its own, derived from the starter set's Document vertex template, whose elements
 * are the document's vertices. Every line and every block is a semantic whose one field is a
 * vertex carrying its meaning concept and its properties keyed by concept; the inline references
 * of a line are vertices too. Parentage and order are each parent's versioned Members property,
 * since a semantic's referenced component is fixed on its chronology: every vertex references the
 * document's root vertex, and the root references the document's pattern.
 *
 * <p>The walk is the one {@link GitDocumentMaterializer} makes: one STAMP per commit that changed
 * the file, from the commit's own coordinates, under the working tree's module, which is a kind of
 * Ephemeral module since the repository is the record. A commit writes a version of each vertex
 * whose meaning or properties changed, an inactive version of each vertex it removed, the git
 * source beside the root, and an Annotation semantic per trailer. Identities are UUID5s of frozen
 * keys, so a later run converges and skips the commits the git source records.
 *
 * <p>The default model: {@link GitDocumentMaterializer#materialize(Path, Path, boolean)} hands
 * every file to this writer unless the system property {@value #MODEL_PROPERTY}{@code =prose}
 * selects the prose-element model. A document already stored as prose elements is materialized
 * again as vertices when it is next opened; the lists of stored documents then show the vertex
 * document in its place.
 */
public final class VertexMaterializer {

    /**
     * The system property that selects the document model: semantic vertices by default, the
     * prose-element model when it is {@code prose} (IKE-Network/ike-issues#1313).
     */
    public static final String MODEL_PROPERTY = "ike.document.model";

    private static final Logger LOG = LoggerFactory.getLogger(VertexMaterializer.class);

    private VertexMaterializer() {
    }

    /** @return whether documents are materialized as semantic vertices: unless the model property says {@code prose} */
    public static boolean enabled() {
        return !"prose".equalsIgnoreCase(System.getProperty(MODEL_PROPERTY, "vertex").strip());
    }

    /** The terms the vertex model writes with, which a store built from an older starter set lacks. */
    private static final List<EntityFacade> TERMS = List.of(IkeConcept.DOCUMENT_VERTEX, IkeConcept.DOCUMENT_VERTEX_VALUE,
            IkeConcept.LINE_SOURCE, IkeConcept.DOCUMENT_STRUCTURE, KernelConcept.VERTEX_FIELD, KernelConcept.EPHEMERAL_MODULE);

    /**
     * The vertex model's terms the open store lacks: none in a store built from the current IKE
     * starter set, some in one built before 2026-10-21.
     *
     * @return their names, empty when the store holds them all
     */
    public static List<String> missingTerms() {
        List<String> absent = new ArrayList<>();
        for (EntityFacade term : TERMS) {
            if (GitStampVocabulary.entityAbsent(term)) {
                absent.add(term.description());
            }
        }
        return absent;
    }

    /**
     * Whether new documents and journals are written as vertices in the open store: the model is
     * vertex and the store holds its terms. A store built from an older starter set keeps working,
     * in the prose-element model, until the current starter set is loaded into it.
     *
     * @return whether to write vertices
     */
    public static boolean available() {
        return enabled() && missingTerms().isEmpty();
    }

    /** What a person is told when the store's lack of the terms put a document in the prose-element model; empty otherwise. */
    public static String fallbackNote() {
        if (!enabled()) {
            return "";
        }
        List<String> absent = missingTerms();
        return absent.isEmpty() ? "" : " Stored as prose elements: this knowledge base lacks the document vertex terminology ("
                + String.join(", ", absent) + "); load the current IKE starter set into it (File › Import Dataset, its reasoned pb zip) "
                + "to store documents as vertices, then Rebuild from Git or Remove Superseded Documents.";
    }

    /** One vertex ready to write: its identity, what it references, its value and a comparable signature. */
    record Written(Semantic proxy, PublicId referenced, EntityVertex value, String signature, Kind kind) { }

    /** What a vertex stands for, for the counts a run reports. */
    enum Kind { ROOT, BLOCK, LINE, REFERENCE }

    /**
     * Materialize a file's first-parent history as semantic vertices.
     *
     * @param repoRoot working-tree root
     * @param file     path within it, absolute or root-relative
     * @param onlyNew  skip commits the store already records
     * @return what was written; its anchor is the document's root vertex
     * @throws IOException if the repository or the file cannot be read
     */
    public static GitDocumentMaterializer.Result materialize(Path repoRoot, Path file, boolean onlyNew) throws IOException {
        requirePresent(TERMS);
        GitIdentityAdapter adapter = GitIdentityAdapter.fromMailmap(repoRoot);
        String component = repoRoot.toAbsolutePath().normalize().getFileName().toString();
        try (GitRepo repo = GitRepo.open(repoRoot)) {
            String branch = repo.branch();
            StampHistory history = repo.stampHistoryOf(file);
            Path relative = history.file();
            String path = relative.toString().replace('\\', '/');
            Identity documentId = adapter.document(component, path);
            String key = documentId.key();
            String name = GitDocumentMaterializer.topicId(repo, relative).orElse(path);
            PublicId patternId = PublicIds.of(Uuid5.of(GitIdentityAdapter.NAMESPACE, "vertex-pattern:" + key));
            Semantic root = semantic("vertex:" + key + ":document");
            Semantic source = semantic("vertex-source:" + key);
            Map<LineId, String> lastText = new HashMap<>();
            Map<String, Written> last = new HashMap<>();
            boolean patternWritten = !GitStampVocabulary.entityAbsent(EntityProxy.Pattern.make(patternId));
            Set<String> recorded = patternWritten && onlyNew ? recordedCommits(root) : Set.of();
            int lines = 0;
            int blocks = 0;
            int references = 0;
            int annotations = 0;
            int skipped = 0;
            for (CommitEvent event : history.events()) {
                boolean contentRecorded = !event.touchesFile() || recorded.contains(event.commit().sha());
                List<Semantic> pendingAnnotations = new ArrayList<>();
                for (CommitEvent.AnnotationEvent a : event.annotations()) {
                    Semantic proxy = Semantic.make(PublicIds.of(a.annotation().id() != null ? a.annotation().id()
                            : Uuid5.of(GitIdentityAdapter.NAMESPACE, "vertex-annotation:" + key + ":" + event.commit().sha() + ":" + a.index())));
                    pendingAnnotations.add(!onlyNew || GitStampVocabulary.entityAbsent(proxy) ? proxy : null);
                }
                for (CommitEvent.Line line : event.introduced()) {
                    lastText.put(line.id(), line.text());
                }
                for (CommitEvent.Line line : event.retexted()) {
                    lastText.put(line.id(), line.text());
                }
                if (contentRecorded && pendingAnnotations.stream().allMatch(java.util.Objects::isNull)) {
                    if (event.touchesFile() && patternWritten) {
                        adopt(last, snapshot(key, patternId, root, event.manifest(), lastText));
                    }
                    skipped++;
                    continue;                                    // already in the store
                }
                GitStampTerms.GitStamp stamp = GitStampTerms.GitStamp.of(adapter.coordinatesOf(event, component, branch));
                GitStampVocabulary.seedIfAbsent(stamp);
                Map<String, Written> now = null;
                try (StampedWriter writer = stamp.openWriter("git-vertices " + event.commit().sha().substring(0, 7))) {
                    if (!patternWritten) {
                        writer.pattern(patternId, IkeConcept.DOCUMENT_VERTEX, IkeConcept.DOCUMENT_STRUCTURE,
                                StampedWriter.field(IkeConcept.DOCUMENT_VERTEX_VALUE, IkeConcept.DOCUMENT_STRUCTURE, KernelConcept.VERTEX_FIELD));
                        writer.fullyQualifiedName(patternId, name);
                        patternWritten = true;
                    }
                    if (event.touchesFile() && !contentRecorded) {
                        now = snapshot(key, patternId, root, event.manifest(), lastText);
                        for (Map.Entry<String, Written> e : now.entrySet()) {
                            Written before = last.get(e.getKey());
                            Written vertex = e.getValue();
                            if (before == null || !before.signature().equals(vertex.signature())) {
                                writer.semantic(vertex.proxy().publicId(), patternId, vertex.referenced(), vertex.value());
                                switch (vertex.kind()) {
                                    case LINE -> lines += before == null ? 1 : 0;
                                    case BLOCK, ROOT -> blocks++;
                                    case REFERENCE -> references++;
                                }
                            }
                        }
                        writer.semantic(source.publicId(), IkePattern.GIT_SOURCE_PATTERN.publicId(), root.publicId(),
                                event.pathAtCommit(), event.commit().sha());
                    }
                    for (int i = 0; i < event.annotations().size(); i++) {
                        Semantic proxy = pendingAnnotations.get(i);
                        if (proxy == null) {
                            continue;                            // already in the store
                        }
                        CommitEvent.AnnotationEvent a = event.annotations().get(i);
                        long[] nids = new long[a.lines().size()];
                        for (int j = 0; j < nids.length; j++) {
                            nids[j] = EntityService.get().nidForSemantic(patternId, lineProxy(a.lines().get(j)).publicId());
                        }
                        writer.semantic(proxy.publicId(), IkePattern.ANNOTATION_PATTERN.publicId(), root.publicId(),
                                StampedDocumentTerms.kind(a.kind()), EntityProxy.make(GitDocumentMaterializer.reviewedStampNid(source, a.reviewedSha())),
                                LongIds.list.of(nids), a.text());
                        annotations++;
                    }
                    writer.commit();
                }
                if (now != null) {
                    List<Written> retired = new ArrayList<>();
                    for (Map.Entry<String, Written> e : last.entrySet()) {
                        if (!now.containsKey(e.getKey())) {
                            retired.add(e.getValue());
                        }
                    }
                    if (!retired.isEmpty()) {
                        // What a commit removed gets an inactive version under that commit's coordinates,
                        // so the graph records the withdrawal where git recorded it.
                        try (StampedWriter writer = StampedWriter.open("git-vertices-retire " + event.commit().sha().substring(0, 7),
                                State.INACTIVE, stamp.time(), stamp.author(), stamp.module(), stamp.path())) {
                            for (Written vertex : retired) {
                                writer.semantic(vertex.proxy().publicId(), patternId, vertex.referenced(), vertex.value());
                            }
                            writer.commit();
                        }
                    }
                    adopt(last, now);
                }
            }
            LOG.info("Materialized {} as vertices of '{}': {} commit(s) written, {} already recorded, {} line(s), {} block version(s), "
                    + "{} reference version(s), {} annotation(s)", path, name, history.events().size() - skipped, skipped, lines, blocks,
                    references, annotations);
            return new GitDocumentMaterializer.Result(root.publicId(), name, history.events().size() - skipped, lines, annotations,
                    blocks, references, repo.headRevision());
        }
    }

    private static void adopt(Map<String, Written> last, Map<String, Written> now) {
        last.clear();
        last.putAll(now);
    }

    // ── the vertices of one commit ────────────────────────────────────────────

    /**
     * The vertices one commit's text implies, keyed by identity key: the root, a vertex per block,
     * per line and per inline reference, with their parentage as Members in document order.
     */
    static Map<String, Written> snapshot(String documentKey, PublicId patternId, Semantic root, List<LineId> manifest,
                                         Map<LineId, String> text) {
        List<String> lines = new ArrayList<>(manifest.size());
        for (LineId id : manifest) {
            lines.add(text.getOrDefault(id, ""));
        }
        Document doc = IkeDialectParser.parse(lines);
        List<LineRoles.Role> roles = LineRoles.of(doc);
        Map<Block, String> blockKeys = new HashMap<>();
        for (Block block : doc.blocks()) {
            blockKeys.put(block, block.kind() == Block.Kind.DOCUMENT
                    ? "vertex:" + documentKey + ":document"
                    : "vertex:block:" + documentKey + ":" + block.kind().name().toLowerCase() + ":"
                            + manifest.get((block.headingLine() > 0 ? block.headingLine() : block.firstLine()) - 1).key());
        }
        Map<String, Long> nids = new HashMap<>();
        java.util.function.Function<String, Long> nid = k -> nids.computeIfAbsent(k,
                kk -> EntityService.get().nidForSemantic(patternId, semantic(kk).publicId()));

        // Parentage: a line's parent is the block that owns it; a line no block owns, a blank
        // line, belongs to the deepest block whose span covers it, else to the document.
        Map<Integer, Block> owner = new HashMap<>();
        Map<Block, int[]> span = new HashMap<>();
        Block document = null;
        for (Block block : doc.blocks()) {
            if (block.kind() == Block.Kind.DOCUMENT) {
                document = block;
            }
            for (int line : block.lines()) {
                owner.put(line, block);
            }
        }
        if (document == null) {
            throw new IllegalStateException("the parse has no document block");
        }
        spanOf(document, span);
        Map<Block, List<long[]>> members = new HashMap<>();
        for (Block block : doc.blocks()) {
            List<long[]> own = members.computeIfAbsent(block, b -> new ArrayList<>());
            for (Block child : block.children()) {
                own.add(new long[] {child.firstLine(), nid.apply(blockKeys.get(child))});
            }
        }
        for (int line = 1; line <= lines.size(); line++) {
            Block parent = owner.get(line);
            if (parent == null) {
                parent = deepestCovering(document, line, span);
            }
            members.computeIfAbsent(parent, b -> new ArrayList<>()).add(new long[] {line, nid.apply(lineKey(manifest, line))});
        }

        // Inline references hang from their line, in their order on it.
        Map<String, Long> anchors = new HashMap<>();
        for (Block block : doc.blocks()) {
            if (!block.anchor().isEmpty()) {
                anchors.put(block.anchor(), nid.apply(blockKeys.get(block)));
            }
        }
        Map<Integer, List<Reference>> referencesByLine = new TreeMap<>();
        for (Reference reference : doc.references()) {
            referencesByLine.computeIfAbsent(reference.line(), l -> new ArrayList<>()).add(reference);
        }

        Map<String, Written> vertices = new LinkedHashMap<>();
        // The root, then the blocks, then the lines and their references: parents before children.
        for (Block block : doc.blocks()) {
            String key = blockKeys.get(block);
            Map<ConceptFacade, Object> properties = new LinkedHashMap<>();
            if (block.kind() == Block.Kind.DOCUMENT) {
                if (doc.title() != null && !doc.title().isBlank()) {
                    properties.put(IkeConcept.DOCUMENT_ELEMENT_TITLE, doc.title());
                }
                long[] meanings = fieldMeanings(IkePattern.ASCIIDOC_DOCUMENT_ATTRIBUTES_PATTERN);
                for (int i = 0; i < AsciiDocModelTerms.TOPIC_ATTRIBUTES.size() && i + 1 < meanings.length; i++) {
                    String value = doc.attribute(AsciiDocModelTerms.TOPIC_ATTRIBUTES.get(i)).orElse("");
                    if (!value.isEmpty()) {
                        properties.put(EntityProxy.Concept.make(meanings[i + 1]), value);
                    }
                }
            } else {
                if (block.kind() == Block.Kind.SECTION || block.kind() == Block.Kind.LIST || block.level() != 0) {
                    properties.put(IkeConcept.DOCUMENT_ELEMENT_LEVEL, block.level());
                }
                if (!block.title().isEmpty()) {
                    properties.put(IkeConcept.DOCUMENT_ELEMENT_TITLE, block.title());
                }
                if (!block.parameters().isEmpty()) {
                    properties.put(IkeConcept.DOCUMENT_ELEMENT_PARAMETERS, block.parameters());
                }
            }
            properties.put(IkeConcept.DOCUMENT_ELEMENT_MEMBERS, ordered(members.get(block)));
            boolean isRoot = block.kind() == Block.Kind.DOCUMENT;
            vertices.put(key, vertex(key, isRoot ? patternId : root.publicId(), AsciiDocModelTerms.kind(block.kind()), properties,
                    isRoot ? Kind.ROOT : Kind.BLOCK));
        }
        for (int line = 1; line <= lines.size(); line++) {
            LineRoles.Role role = roles.get(line - 1);
            Map<ConceptFacade, Object> properties = new LinkedHashMap<>(role.properties());
            List<Reference> onLine = referencesByLine.getOrDefault(line, List.of());
            if (!onLine.isEmpty()) {
                List<long[]> refs = new ArrayList<>();
                for (Reference reference : onLine) {
                    refs.add(new long[] {reference.index(), nid.apply(referenceKey(manifest, reference))});
                }
                properties.put(IkeConcept.DOCUMENT_ELEMENT_MEMBERS, ordered(refs));
            }
            String key = lineKey(manifest, line);
            vertices.put(key, vertex(key, root.publicId(), role.meaning(), properties, Kind.LINE));
            for (Reference reference : onLine) {
                PatternFacade pattern = AsciiDocModelTerms.referencePattern(reference.kind());
                Long resolved = reference.kind() == Reference.Kind.CITATION ? anchors.get(reference.target()) : null;
                Object[] fields = AsciiDocModelTerms.referenceFields(reference, resolved == null ? null : Semantic.make(resolved));
                long[] meanings = fieldMeanings(pattern);
                Map<ConceptFacade, Object> refProperties = new LinkedHashMap<>();
                for (int i = 0; i < fields.length && i < meanings.length; i++) {
                    refProperties.put(EntityProxy.Concept.make(meanings[i]), fields[i]);
                }
                String refKey = referenceKey(manifest, reference);
                vertices.put(refKey, vertex(refKey, root.publicId(), EntityProxy.Concept.make(patternMeaning(pattern)), refProperties,
                        Kind.REFERENCE));
            }
        }
        return vertices;
    }

    private static String lineKey(List<LineId> manifest, int line) {
        return "vertex:" + manifest.get(line - 1).key();
    }

    private static String referenceKey(List<LineId> manifest, Reference reference) {
        return "vertex:ref:" + manifest.get(reference.line() - 1).key() + ":" + reference.index();
    }

    /** A block's span: the first and last line it or any block beneath it owns. */
    private static int[] spanOf(Block block, Map<Block, int[]> spans) {
        int first = Integer.MAX_VALUE;
        int last = 0;
        for (int line : block.lines()) {
            first = Math.min(first, line);
            last = Math.max(last, line);
        }
        for (Block child : block.children()) {
            int[] s = spanOf(child, spans);
            if (s[1] > 0) {
                first = Math.min(first, s[0]);
                last = Math.max(last, s[1]);
            }
        }
        int[] span = new int[] {first == Integer.MAX_VALUE ? 0 : first, last};
        spans.put(block, span);
        return span;
    }

    /** The deepest block whose span covers a line, the document when none beneath it does. */
    private static Block deepestCovering(Block block, int line, Map<Block, int[]> spans) {
        for (Block child : block.children()) {
            int[] s = spans.get(child);
            if (s != null && s[1] > 0 && line > s[0] && line < s[1]) {
                return deepestCovering(child, line, spans);
            }
        }
        return block;
    }

    private static LongIdList ordered(List<long[]> members) {
        if (members == null || members.isEmpty()) {
            return LongIds.list.empty();
        }
        List<long[]> sorted = new ArrayList<>(members);
        sorted.sort((a, b) -> Long.compare(a[0], b[0]));
        long[] nids = new long[sorted.size()];
        for (int i = 0; i < nids.length; i++) {
            nids[i] = sorted.get(i)[1];
        }
        return LongIds.list.of(nids);
    }

    /** A vertex's value: its meaning and properties, with the semantic's own identity as the vertex's. */
    private static Written vertex(String key, PublicId referenced, ConceptFacade meaning, Map<ConceptFacade, Object> properties, Kind kind) {
        Semantic proxy = semantic(key);
        EntityVertex value = value(proxy.publicId().asUuidArray()[0], meaning, properties);
        StringBuilder signature = new StringBuilder().append(meaning.nid());
        Map<Long, String> sorted = new TreeMap<>();
        for (Map.Entry<ConceptFacade, Object> e : properties.entrySet()) {
            sorted.put(e.getKey().nid(), text(e.getValue()));
        }
        sorted.forEach((k, v) -> signature.append('|').append(k).append('=').append(v));
        return new Written(proxy, referenced, value, signature.toString(), kind);
    }

    /**
     * A vertex value: the semantic's own UUID as the vertex's, its meaning, and its properties
     * keyed by concept. The materializer and the editor both write vertices through this.
     *
     * @param id         the semantic's UUID
     * @param meaning    the vertex's meaning
     * @param properties the properties
     * @return the vertex, at index 0 of its one-vertex value
     */
    public static EntityVertex value(java.util.UUID id, ConceptFacade meaning, Map<ConceptFacade, Object> properties) {
        EntityVertex value = EntityVertex.make(id, meaning.nid());
        value.setVertexIndex(0);
        MutableLongObjectMap<Object> byNid = LongObjectMaps.mutable.empty();
        properties.forEach((key, v) -> byNid.put(key.nid(), v));
        value.setProperties(byNid);
        return value;
    }

    private static String text(Object value) {
        return switch (value) {
            case LongIdList ids -> java.util.Arrays.toString(ids.toArray());
            case EntityFacade facade -> "#" + facade.nid();
            case null -> "";
            default -> String.valueOf(value);
        };
    }

    private static Semantic semantic(String key) {
        return Semantic.make(PublicIds.of(Uuid5.of(GitIdentityAdapter.NAMESPACE, key)));
    }

    private static Semantic lineProxy(LineId id) {
        return semantic("vertex:" + id.key());
    }

    // ── the store ─────────────────────────────────────────────────────────────

    private static final Map<Long, long[]> FIELD_MEANINGS = new java.util.concurrent.ConcurrentHashMap<>();

    /** A pattern's field meanings, in field order, from its latest version. */
    private static long[] fieldMeanings(PatternFacade pattern) {
        return FIELD_MEANINGS.computeIfAbsent(pattern.nid(), nid -> {
            PatternEntityVersion version = latestPattern(nid);
            if (version == null) {
                return new long[0];
            }
            long[] meanings = new long[version.fieldDefinitions().size()];
            int i = 0;
            for (FieldDefinitionForEntity definition : version.fieldDefinitions()) {
                meanings[i++] = definition.meaningNid();
            }
            return meanings;
        });
    }

    private static long patternMeaning(PatternFacade pattern) {
        PatternEntityVersion version = latestPattern(pattern.nid());
        return version == null ? IkeConcept.DOCUMENT_ELEMENT.nid() : version.semanticMeaningNid();
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

    /** The commit SHAs the document's git source already records. */
    private static Set<String> recordedCommits(Semantic root) {
        Set<String> shas = new HashSet<>();
        if (GitStampVocabulary.entityAbsent(root)) {
            return shas;
        }
        for (SemanticEntity<SemanticEntityVersion> source : EntityService.get()
                .semanticsForComponentOfPattern(root.nid(), IkePattern.GIT_SOURCE_PATTERN.nid()).toList()) {
            for (SemanticEntityVersion v : source.versions()) {
                if (v.time() != Long.MAX_VALUE && v.fieldValues().size() > 1) {
                    shas.add(String.valueOf(v.fieldValues().get(1)));
                }
            }
        }
        return shas;
    }

    private static void requirePresent(List<? extends EntityFacade> terms) {
        List<String> absent = new ArrayList<>();
        for (EntityFacade term : terms) {
            if (GitStampVocabulary.entityAbsent(term)) {
                absent.add(term.description());
            }
        }
        if (!absent.isEmpty()) {
            throw new IllegalStateException("The open store lacks the document vertex terminology the vertex model needs: "
                    + String.join(", ", absent) + ". Load a knowledge base built from the current IKE starter set.");
        }
    }
}
