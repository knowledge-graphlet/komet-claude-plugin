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
package network.ike.komet.claude.doc;

import dev.ikm.tinkar.common.id.LongIdList;
import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.stamp.calculator.StampCalculator;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy.Concept;
import dev.ikm.tinkar.terms.EntityProxy.Semantic;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import network.ike.komet.claude.stamp.VertexMaterializer;
import network.ike.komet.claude.stamp.VertexReader;
import network.ike.komet.claude.doc.MarkdownStructure.Child;
import network.ike.komet.claude.doc.MarkdownStructure.Element;
import network.ike.komet.claude.doc.MarkdownStructure.Line;
import network.ike.komet.claude.doc.MarkdownStructure.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The conversation journal's chronology store ({@code IKE-Network/ike-issues#807}): writes each
 * completed conversation exchange into the open datastore as STAMP-versioned chronologies, and
 * rebuilds a conversation from them.
 *
 * <p><b>Model.</b> A journal is a structured document in the block model it shares with documents
 * materialized from git ({@code IKE-Network/ike-issues#844}). One anchor
 * concept per conversation. Each line of each turn is a prose element; the journal manifest on the
 * anchor orders every line, a new version per append. Each turn is a {@code Conversation turn}
 * block whose members are its blocks and its blank lines, split by {@link MarkdownStructure}; the
 * journal's root block, of kind {@code Conversation journal}, lists the turns in order. Every
 * element is a semantic of the document block pattern on the anchor, so an action can take any
 * block of a conversation as its subject, the same way it takes a block of a document.
 *
 * <p>Turn attribution is the STAMP author dimension: a user turn's lines and blocks stamp with the
 * view's edit-coordinate author, an assistant turn's with {@link IkeConcept#KOMET_ASSISTANT}.
 * All instance data stamps under {@link IkeConcept#CONVERSATION_JOURNAL_MODULE}, so journals
 * are filterable by the module dimension. A journal block's identity is given when its turn is
 * written: random for lines and blocks, and derived from the anchor for the root block.
 *
 * <p><b>Write-after-success.</b> {@link #appendExchange} is called only after an exchange
 * completes, off the FX thread; a failed send never touches the journal. Store failures throw —
 * the caller degrades to its JSON-only persistence.
 *
 * <p><b>Writes.</b> An append is one {@link StampedWriter}: the user turn at one stamp, the
 * assistant turn, the manifest and the root block at a second, committed or cancelled together.
 * Each stamp takes an explicit time from the monotonic clock, so manifest and root versions are
 * strictly ordered. A semantic re-written at the same public id merges a new version through
 * {@code EntityProvider.putEntity → PrimitiveData.merge} — the append mechanic
 * {@code JournalStoreIT} locks as this store's regression gate.
 */
public final class JournalStore {

    private static final Logger LOG = LoggerFactory.getLogger(JournalStore.class);

    /** Name key of the root block's identity, derived from the anchor's UUID. */
    private static final String ROOT_KEY = "conversation-journal-root";

    /**
     * Monotonic STAMP clock: strictly increasing per tick, seeded from wall time. Two exchanges in
     * the same millisecond would otherwise mint manifest versions with identical STAMP times,
     * making "latest" ambiguous — the loader could resolve the older list.
     */
    private static final AtomicLong LAST_STAMP_TIME = new AtomicLong();

    /** The next STAMP time: wall time, bumped past any time already handed out. */
    private static long nextStampTime() {
        return LAST_STAMP_TIME.updateAndGet(last -> Math.max(last + 1, System.currentTimeMillis()));
    }

    /** Supplies the calculator whose coordinate stamps user turns and resolves latest versions. */
    private final Supplier<ViewCalculator> viewCalculator;

    /**
     * Creates a store that derives STAMP authorship and version resolution from the supplied view.
     *
     * @param viewCalculator supplier of the hosting card's view calculator; may supply {@code null}
     *                       (user turns then stamp as {@link KernelConcept#USER} and {@link #load}
     *                       resolves nothing)
     */
    public JournalStore(Supplier<ViewCalculator> viewCalculator) {
        this.viewCalculator = viewCalculator;
    }

    /**
     * The root block's identity: derived from the anchor, so a journal has exactly one.
     *
     * @param anchor the journal anchor
     * @return the root block's public id
     */
    static PublicId rootBlockId(PublicId anchor) {
        return PublicIds.of(UuidT5Generator.get(anchor.asUuidArray()[0], ROOT_KEY));
    }

    // ── Append ───────────────────────────────────────────────────────────

    /**
     * Persists one completed exchange: mints the journal anchor if needed (idempotently, at
     * {@code anchor} when non-null, else at a new random id), writes the user turn (author = the
     * edit coordinate's author) and the assistant turn (author =
     * {@link IkeConcept#KOMET_ASSISTANT}) as lines and blocks, appends their lines to a new
     * version of the journal manifest and their turn blocks to a new version of the root block. All
     * instance data stamps under {@link IkeConcept#CONVERSATION_JOURNAL_MODULE}. Call off the
     * FX thread, only after the exchange succeeded.
     *
     * @param anchor            the conversation's journal anchor, or {@code null} on first append
     * @param conversationName  the conversation's display name (the anchor's fully qualified name)
     * @param userMarkdown      the user turn's Markdown source
     * @param assistantMarkdown the assistant turn's Markdown source
     * @return the journal anchor id (minted or confirmed) — persist it with the conversation
     * @throws RuntimeException on any store failure (the caller degrades to JSON-only persistence)
     */
    public PublicId appendExchange(PublicId anchor, String conversationName,
                                   String userMarkdown, String assistantMarkdown) {
        DocumentVocabulary.requirePresent();
        // A journal keeps the model it was begun in; a new one is begun as vertices, the default
        // document model (IKE-Network/ike-issues#1313).
        boolean vertexJournal = anchor != null && VertexReader.isVertexDocument(anchor);
        boolean newJournal = anchor == null || DocumentVocabulary.entityAbsent(Concept.make(anchor)) && !vertexJournal;
        if (vertexJournal || (newJournal && VertexMaterializer.enabled())) {
            return appendVertices(anchor != null ? anchor : PublicIds.newRandom(), conversationName, userMarkdown, assistantMarkdown);
        }
        PublicId anchorId = anchor != null ? anchor : PublicIds.newRandom();
        // The anchor is "new" only when no chronology exists — a nid mapping alone (minted by an
        // earlier failed append) must not skip the anchor concept's creation. An unknown anchor id
        // is never resolved to a nid here: a strict store (Rocks) throws on unknown UUIDs.
        ConceptFacade anchorConcept = Concept.make(anchorId);
        boolean anchorNew = DocumentVocabulary.entityAbsent(anchorConcept);
        String name = (conversationName == null || conversationName.isBlank())
                ? "Conversation journal" : conversationName;

        // Resolve the append base BEFORE any store write, from the raw versions of the manifest and
        // the root block — never through the card's display coordinate: a view positioned in the
        // past, filtered, or torn down mid-append must never truncate the journal. A head that
        // cannot be read fails CLOSED (throw → the caller degrades to JSON-only), never open (an
        // empty prior list would orphan every earlier turn).
        LongIdList priorLines = LongIds.list.empty();
        Semantic manifest;
        List<SemanticEntity<SemanticEntityVersion>> manifests = anchorNew ? List.of()
                : EntityService.get().semanticsForComponentOfPattern(
                        EntityService.get().nidForPublicId(anchorId),
                        IkePattern.JOURNAL_MANIFEST_PATTERN.nid()).toList();
        if (!manifests.isEmpty()) {
            if (manifests.size() > 1) {
                LOG.warn("Journal anchor {} carries {} manifests; appending to the first",
                        anchorId.idString(), manifests.size());
            }
            SemanticEntity<SemanticEntityVersion> manifestEntity = manifests.getFirst();
            manifest = Semantic.make(manifestEntity.publicId());
            SemanticEntityVersion head = rawHead(manifestEntity);
            if (head == null) {
                throw new IllegalStateException("Journal manifest for " + anchorId.idString()
                        + " has no committed version; refusing an append that would orphan prior turns");
            }
            priorLines = (LongIdList) head.fieldValues().get(0);
            seedClockPast(head);
        } else {
            manifest = Semantic.make(PublicIds.newRandom());
        }

        Semantic root = Semantic.make(rootBlockId(anchorId));
        LongIdList priorTurns = LongIds.list.empty();
        if (!manifests.isEmpty()) {
            SemanticEntityVersion rootHead = DocumentVocabulary.entityAbsent(root) ? null
                    : rawHead(EntityHandle.get(EntityService.get().nidForPublicId(root)).expectSemantic());
            if (rootHead == null) {
                throw new IllegalStateException("Journal " + anchorId.idString()
                        + " has a manifest but no root block; refusing an append that would orphan prior turns");
            }
            priorTurns = (LongIdList) rootHead.fieldValues().get(4);
            seedClockPast(rootHead);
        }

        long userTime = nextStampTime();
        long assistantTime = nextStampTime();
        // Distinct explicit times from the monotonic clock keep manifest versions strictly ordered,
        // even across same-millisecond exchanges. Closing the writer unfinished cancels both stamps,
        // so a partial append leaves no durable orphans.
        try (StampedWriter writer = StampedWriter.open("conversation-journal-append", State.ACTIVE, userTime,
                userAuthor(), IkeConcept.CONVERSATION_JOURNAL_MODULE, KernelConcept.DEVELOPMENT_PATH)) {
            if (anchorNew) {
                writer.concept(anchorConcept.publicId());
                writer.fullyQualifiedName(anchorConcept.publicId(), name);
            }
            List<Long> lines = new ArrayList<>(priorLines.size() + 16);
            for (long nid : priorLines.toArray()) {
                lines.add(nid);
            }
            long userTurn = writeTurn(writer, anchorConcept, userMarkdown, lines);

            writer.restamp(State.ACTIVE, assistantTime, IkeConcept.KOMET_ASSISTANT,
                    IkeConcept.CONVERSATION_JOURNAL_MODULE, KernelConcept.DEVELOPMENT_PATH);
            long assistantTurn = writeTurn(writer, anchorConcept, assistantMarkdown, lines);

            // Manifest and root: a new version of each, at the assistant's stamp, in the same
            // writer, so a crash cannot leave a second manifest or a turn outside the root.
            LongIdList newLines = LongIds.list.of(lines.stream().mapToLong(Long::longValue).toArray());
            writer.semantic(manifest, IkePattern.JOURNAL_MANIFEST_PATTERN.publicId(), anchorConcept.publicId(), newLines);
            long[] turns = new long[priorTurns.size() + 2];
            System.arraycopy(priorTurns.toArray(), 0, turns, 0, priorTurns.size());
            turns[turns.length - 2] = userTurn;
            turns[turns.length - 1] = assistantTurn;
            writer.semantic(root, IkePattern.DOCUMENT_BLOCK_PATTERN.publicId(), anchorConcept.publicId(),
                    IkeConcept.CONVERSATION_JOURNAL, 0, name, "", LongIds.list.of(turns));

            writer.commit();
            LOG.info("Appended exchange to journal {} ({} turns, {} lines)",
                    anchorId.idString(), turns.length, newLines.size());
            return anchorId;
        }
    }

    // ── Append, as vertices ──────────────────────────────────────────────

    /** Name key of a vertex journal's own pattern, derived from its root's UUID. */
    private static final String PATTERN_KEY = "conversation-journal-pattern";

    /**
     * A vertex journal's own pattern: derived from its root, so a journal has exactly one.
     *
     * @param anchor the journal's root vertex
     * @return the pattern's public id
     */
    static PublicId journalPatternId(PublicId anchor) {
        return PublicIds.of(UuidT5Generator.get(anchor.asUuidArray()[0], PATTERN_KEY));
    }

    /**
     * Appends an exchange to a journal stored as semantic vertices (IKE-Network/ike-issues#1313).
     * The journal is instance data: its own pattern, derived from the Document vertex template and
     * named for the conversation, is written under {@link IkeConcept#CONVERSATION_JOURNAL_MODULE},
     * a kind of Instance module, and so is every vertex. The anchor is the root vertex, of meaning
     * {@link IkeConcept#CONVERSATION_JOURNAL}, whose Members are the turns; a turn's Members are its
     * blocks and its blank lines, a block's its lines and child blocks, as {@link MarkdownStructure}
     * splits them; every line is a line vertex whose Line source is the line as written. Every vertex
     * references the root, the root its pattern. An append writes the new turns and a new version of
     * the root; there is no manifest, the order being the tree's.
     */
    private PublicId appendVertices(PublicId anchorId, String conversationName, String userMarkdown, String assistantMarkdown) {
        String name = (conversationName == null || conversationName.isBlank()) ? "Conversation journal" : conversationName;
        PublicId patternId = journalPatternId(anchorId);
        boolean journalNew = !VertexReader.isVertexDocument(anchorId);
        long[] priorTurns = new long[0];
        if (!journalNew) {
            // The append base is the root's raw head, never the card's view: see appendExchange.
            SemanticEntityVersion rootHead = rawHead(EntityHandle.get(EntityService.get().nidForPublicId(anchorId)).expectSemantic());
            if (rootHead == null || !(rootHead.fieldValues().get(0) instanceof EntityVertex rootVertex)) {
                throw new IllegalStateException("Journal " + anchorId.idString()
                        + " has no committed root; refusing an append that would orphan prior turns");
            }
            priorTurns = VertexReader.members(rootVertex);
            seedClockPast(rootHead);
        }
        long userTime = nextStampTime();
        long assistantTime = nextStampTime();
        try (StampedWriter writer = StampedWriter.open("conversation-journal-append", State.ACTIVE, userTime,
                userAuthor(), IkeConcept.CONVERSATION_JOURNAL_MODULE, KernelConcept.DEVELOPMENT_PATH)) {
            if (journalNew) {
                writer.pattern(patternId, IkeConcept.DOCUMENT_VERTEX, IkeConcept.DOCUMENT_STRUCTURE,
                        StampedWriter.field(IkeConcept.DOCUMENT_VERTEX_VALUE, IkeConcept.DOCUMENT_STRUCTURE, KernelConcept.VERTEX_FIELD));
                writer.fullyQualifiedName(patternId, name);
                EntityService.get().nidForSemantic(patternId, anchorId);   // the root's nid, so its members can reference it
            }
            long userTurn = writeTurnVertices(writer, patternId, anchorId, userMarkdown);
            writer.restamp(State.ACTIVE, assistantTime, IkeConcept.KOMET_ASSISTANT,
                    IkeConcept.CONVERSATION_JOURNAL_MODULE, KernelConcept.DEVELOPMENT_PATH);
            long assistantTurn = writeTurnVertices(writer, patternId, anchorId, assistantMarkdown);

            long[] turns = java.util.Arrays.copyOf(priorTurns, priorTurns.length + 2);
            turns[turns.length - 2] = userTurn;
            turns[turns.length - 1] = assistantTurn;
            Map<ConceptFacade, Object> properties = new LinkedHashMap<>();
            properties.put(IkeConcept.DOCUMENT_ELEMENT_TITLE, name);
            properties.put(IkeConcept.DOCUMENT_ELEMENT_MEMBERS, LongIds.list.of(turns));
            writer.semantic(anchorId, patternId, patternId,
                    VertexMaterializer.value(anchorId.asUuidArray()[0], IkeConcept.CONVERSATION_JOURNAL, properties));
            writer.commit();
            LOG.info("Appended exchange to vertex journal {} ({} turns)", anchorId.idString(), turns.length);
            return anchorId;
        }
    }

    /** Writes one turn as vertices at the writer's current stamp: its lines, then its blocks from the innermost out. */
    private static long writeTurnVertices(StampedWriter writer, PublicId patternId, PublicId root, String markdown) {
        MarkdownStructure.Turn turn = MarkdownStructure.parse(markdown);
        long[] lineNids = new long[turn.lines().size()];
        writeLineVertices(writer, patternId, root, turn.turn(), turn.lines(), lineNids);
        return writeElementVertex(writer, patternId, root, turn.turn(), lineNids);
    }

    /** Writes the line vertices an element owns directly, and those of its children, each classified by its owner. */
    private static void writeLineVertices(StampedWriter writer, PublicId patternId, PublicId root, Element element,
                                          List<String> lines, long[] lineNids) {
        int position = 0;
        for (Member member : element.members()) {
            switch (member) {
                case Line line -> {
                    String text = lines.get(line.index());
                    Map<ConceptFacade, Object> properties = new LinkedHashMap<>();
                    properties.put(IkeConcept.LINE_SOURCE, text);
                    ConceptFacade meaning = lineMeaning(element, position, text, properties);
                    PublicId id = PublicIds.newRandom();
                    lineNids[line.index()] = writer.semantic(id, patternId, root,
                            VertexMaterializer.value(id.asUuidArray()[0], meaning, properties));
                }
                case Child child -> writeLineVertices(writer, patternId, root, child.element(), lines, lineNids);
            }
            position++;
        }
    }

    private static long writeElementVertex(StampedWriter writer, PublicId patternId, PublicId root, Element element, long[] lineNids) {
        long[] members = new long[element.members().size()];
        for (int i = 0; i < members.length; i++) {
            members[i] = switch (element.members().get(i)) {
                case Line line -> lineNids[line.index()];
                case Child child -> writeElementVertex(writer, patternId, root, child.element(), lineNids);
            };
        }
        Map<ConceptFacade, Object> properties = new LinkedHashMap<>();
        if (element.level() != 0) {
            properties.put(IkeConcept.DOCUMENT_ELEMENT_LEVEL, element.level());
        }
        if (!element.title().isEmpty()) {
            properties.put(IkeConcept.DOCUMENT_ELEMENT_TITLE, element.title());
        }
        if (!element.parameters().isEmpty()) {
            properties.put(IkeConcept.DOCUMENT_ELEMENT_PARAMETERS, element.parameters());
        }
        properties.put(IkeConcept.DOCUMENT_ELEMENT_MEMBERS, LongIds.list.of(members));
        PublicId id = PublicIds.newRandom();
        return writer.semantic(id, patternId, root, VertexMaterializer.value(id.asUuidArray()[0], element.kind(), properties));
    }

    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,}).*$");
    private static final Pattern ITEM = Pattern.compile("^\\s*([-*+]|\\d+[.)])\\s+.*$");

    /**
     * A Markdown line's meaning, the line concepts the AsciiDoc lines take: blank, a section's
     * heading (its title a property), a listing's fences and verbatim body, a list item's marker
     * line, and prose otherwise.
     */
    private static ConceptFacade lineMeaning(Element owner, int position, String text, Map<ConceptFacade, Object> properties) {
        if (text.isBlank()) {
            return IkeConcept.BLANK_LINE;
        }
        long kind = owner.kind().nid();
        if (kind == IkeConcept.DOCUMENT_SECTION.nid() && position == 0) {
            properties.put(IkeConcept.DOCUMENT_ELEMENT_TITLE, owner.title());
            return IkeConcept.HEADING_LINE;
        }
        if (kind == IkeConcept.DOCUMENT_LISTING.nid()) {
            return FENCE.matcher(text).matches() ? IkeConcept.DELIMITER_LINE : IkeConcept.VERBATIM_LINE;
        }
        if (kind == IkeConcept.DOCUMENT_LIST_ITEM.nid() && ITEM.matcher(text).matches()) {
            return IkeConcept.ITEM_LINE;
        }
        return IkeConcept.PROSE_LINE;
    }

    /**
     * Writes one turn at the writer's current stamp: a prose element per line, then its blocks from
     * the innermost out, so every member exists before the block that lists it.
     *
     * @param writer the open writer, stamped with the turn's author
     * @param anchor the journal anchor every element refers to
     * @param markdown the turn's Markdown source
     * @param manifest the manifest's lines so far; this turn's lines are appended in order
     * @return the turn block's nid
     */
    private static long writeTurn(StampedWriter writer, ConceptFacade anchor, String markdown, List<Long> manifest) {
        MarkdownStructure.Turn turn = MarkdownStructure.parse(markdown);
        long[] lineNids = new long[turn.lines().size()];
        for (int i = 0; i < lineNids.length; i++) {
            lineNids[i] = writer.semantic(Semantic.make(PublicIds.newRandom()),
                    IkePattern.PROSE_ELEMENT_PATTERN.publicId(), anchor.publicId(), turn.lines().get(i));
            manifest.add(lineNids[i]);
        }
        return writeElement(writer, anchor, turn.turn(), lineNids);
    }

    private static long writeElement(StampedWriter writer, ConceptFacade anchor, Element element, long[] lineNids) {
        long[] members = new long[element.members().size()];
        for (int i = 0; i < members.length; i++) {
            Member member = element.members().get(i);
            members[i] = switch (member) {
                case Line line -> lineNids[line.index()];
                case Child child -> writeElement(writer, anchor, child.element(), lineNids);
            };
        }
        return writer.semantic(Semantic.make(PublicIds.newRandom()), IkePattern.DOCUMENT_BLOCK_PATTERN.publicId(), anchor.publicId(),
                element.kind(), element.level(), element.title(), element.parameters(), LongIds.list.of(members));
    }

    /** Seeds the monotonic clock past a head, so a wall-clock regression cannot hide a new version behind it. */
    private static void seedClockPast(SemanticEntityVersion head) {
        LAST_STAMP_TIME.updateAndGet(last -> Math.max(last, head.time()));
    }

    /**
     * A semantic's raw head: its committed version with the greatest STAMP time, read straight from
     * the chronology (no coordinate filtering — the append base is the newest write, period).
     * Versions carrying the uncommitted sentinel time are ignored.
     *
     * @param entity the semantic
     * @return the head version, or {@code null} when none is committed
     */
    private static SemanticEntityVersion rawHead(SemanticEntity<SemanticEntityVersion> entity) {
        if (entity.canceled()) {
            return null;
        }
        SemanticEntityVersion head = null;
        for (SemanticEntityVersion version : entity.versions()) {
            if (version.time() == Long.MAX_VALUE) {
                continue;   // uncommitted sentinel — not an append base
            }
            if (head == null || version.time() > head.time()) {
                head = version;
            }
        }
        return head;
    }

    // ── Load ─────────────────────────────────────────────────────────────

    /**
     * One rebuilt turn: its Markdown source and whether the assistant authored it (from the turn
     * block version's STAMP author).
     *
     * @param markdown          the turn's Markdown source
     * @param assistantAuthored {@code true} when the turn's author is {@link IkeConcept#KOMET_ASSISTANT}
     */
    public record TurnRecord(String markdown, boolean assistantAuthored) {
    }

    /**
     * Rebuilds a conversation from its journal: the root block's latest turns, each turn's latest
     * members flattened back to its lines in order, joined with {@code \n}.
     *
     * @param anchor the conversation's journal anchor; may be {@code null}
     * @return the turns in document order, or empty when the anchor, root block, or view is absent
     *         (the caller falls back to its JSON persistence)
     */
    public Optional<List<TurnRecord>> load(PublicId anchor) {
        if (anchor == null || !PrimitiveData.get().hasPublicId(anchor)
                || DocumentVocabulary.entityAbsent(IkePattern.DOCUMENT_BLOCK_PATTERN)) {
            // Unknown anchor, or a store built from a starter set without the block model.
            return Optional.empty();
        }
        ViewCalculator calculator = viewCalculator.get();
        if (calculator == null) {
            return Optional.empty();
        }
        if (VertexReader.isVertexDocument(anchor)) {
            return Optional.of(loadVertices(anchor, calculator.stampCalculator()));
        }
        Semantic root = Semantic.make(rootBlockId(anchor));
        if (DocumentVocabulary.entityAbsent(root)) {
            // A journal written before the shared block model, or none: the JSON copy is the record.
            return Optional.empty();
        }
        StampCalculator stamps = calculator.stampCalculator();
        Latest<SemanticEntityVersion> rootVersion =
                stamps.latestSemanticVersion(EntityService.get().nidForPublicId(root));
        if (!rootVersion.isPresent()) {
            return Optional.empty();
        }
        LongIdList turnNids = (LongIdList) rootVersion.get().fieldValues().get(4);
        long assistantNid = IkeConcept.KOMET_ASSISTANT.nid();
        long proseNid = IkePattern.PROSE_ELEMENT_PATTERN.nid();
        List<TurnRecord> turns = new ArrayList<>(turnNids.size());
        for (long turnNid : turnNids.toArray()) {
            Latest<SemanticEntityVersion> turn = stamps.latestSemanticVersion(turnNid);
            if (!turn.isPresent()) {
                LOG.warn("Journal turn {} has no version on the current coordinate; skipping", turnNid);
                continue;
            }
            List<String> lines = new ArrayList<>();
            appendLines(stamps, proseNid, turn.get(), lines);
            turns.add(new TurnRecord(String.join("\n", lines), turn.get().authorNid() == assistantNid));
        }
        return Optional.of(turns);
    }

    /** A vertex journal's turns: the root's Members, each turn's lines in order, its author the turn version's. */
    private static List<TurnRecord> loadVertices(PublicId anchor, StampCalculator stamps) {
        List<TurnRecord> turns = new ArrayList<>();
        Latest<SemanticEntityVersion> root = stamps.latestSemanticVersion(EntityService.get().nidForPublicId(anchor));
        if (!root.isPresent() || !(root.get().fieldValues().get(0) instanceof EntityVertex rootVertex)) {
            return turns;
        }
        for (long turnNid : VertexReader.members(rootVertex)) {
            Latest<SemanticEntityVersion> turn = stamps.latestSemanticVersion(turnNid);
            if (!turn.isPresent() || !(turn.get().fieldValues().get(0) instanceof EntityVertex turnVertex)) {
                LOG.warn("Journal turn {} has no version on the current coordinate; skipping", turnNid);
                continue;
            }
            List<String> lines = new ArrayList<>();
            appendVertexLines(stamps, turnVertex, lines);
            turns.add(new TurnRecord(String.join("\n", lines), turn.get().authorNid() == IkeConcept.KOMET_ASSISTANT.nid()));
        }
        return turns;
    }

    private static void appendVertexLines(StampCalculator stamps, EntityVertex element, List<String> lines) {
        for (long memberNid : VertexReader.members(element)) {
            Latest<SemanticEntityVersion> member = stamps.latestSemanticVersion(memberNid);
            if (!member.isPresent() || !(member.get().fieldValues().get(0) instanceof EntityVertex vertex)) {
                LOG.warn("Journal member {} has no version on the current coordinate; skipping", memberNid);
                continue;
            }
            Object source = vertex.properties().get(IkeConcept.LINE_SOURCE.nid());
            if (source != null) {
                lines.add(String.valueOf(source));
            } else {
                appendVertexLines(stamps, vertex, lines);
            }
        }
    }

    /** Appends an element's lines in order, descending into its child elements. */
    private static void appendLines(StampCalculator stamps, long proseNid, SemanticEntityVersion element,
                                    List<String> lines) {
        LongIdList members = (LongIdList) element.fieldValues().get(4);
        for (long memberNid : members.toArray()) {
            Latest<SemanticEntityVersion> member = stamps.latestSemanticVersion(memberNid);
            if (!member.isPresent()) {
                LOG.warn("Journal member {} has no version on the current coordinate; skipping", memberNid);
                continue;
            }
            if (member.get().patternNid() == proseNid) {
                lines.add(String.valueOf(member.get().fieldValues().get(0)));
            } else {
                appendLines(stamps, proseNid, member.get(), lines);
            }
        }
    }

    /** The user-turn author: the view's edit-coordinate author, else {@link KernelConcept#USER}. */
    private ConceptFacade userAuthor() {
        ViewCalculator calculator = viewCalculator.get();
        if (calculator == null) {
            return KernelConcept.USER;
        }
        ConceptFacade author = calculator.viewCoordinateRecord().editCoordinate().getAuthorForChanges();
        return author == null ? KernelConcept.USER : Concept.make(author);
    }
}
