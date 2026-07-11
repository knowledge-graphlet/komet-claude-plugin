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

import dev.ikm.tinkar.common.id.IntIdList;
import dev.ikm.tinkar.common.id.IntIds;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.composer.Composer;
import dev.ikm.tinkar.composer.Session;
import dev.ikm.tinkar.composer.assembler.ConceptAssembler;
import dev.ikm.tinkar.composer.assembler.PatternAssembler;
import dev.ikm.tinkar.composer.assembler.SemanticAssembler;
import dev.ikm.tinkar.composer.template.FullyQualifiedName;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityBinding;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.EntityProxy.Concept;
import dev.ikm.tinkar.terms.EntityProxy.Semantic;
import dev.ikm.tinkar.terms.State;
import dev.ikm.tinkar.terms.TinkarTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The conversation journal's chronology store ({@code IKE-Network/ike-issues#807}): writes each
 * completed conversation exchange into the open datastore as STAMP-versioned chronologies, and
 * rebuilds a conversation from them.
 *
 * <p><b>Model.</b> One <em>journal-anchor</em> concept per conversation; one
 * <em>journal-manifest</em> semantic on the anchor whose single field is the ordered element
 * id-list — every append is a new manifest version, so replay walks manifest versions in time;
 * one <em>prose-element</em> semantic per turn (the turn's raw Markdown, fenced blocks included —
 * the surface splits blocks at render time). Turn attribution is the STAMP author dimension:
 * user turns stamp with the view's edit-coordinate author, assistant turns with
 * {@link RichSurfaceTerms#KOMET_ASSISTANT_AUTHOR}. All instance data stamps under
 * {@link RichSurfaceTerms#CONVERSATION_JOURNAL_MODULE}, so journals are filterable by the module
 * dimension.
 *
 * <p><b>Write-after-success.</b> {@link #appendExchange} is called only after an exchange
 * completes, off the FX thread; a failed send never touches the journal. Store failures throw —
 * the caller degrades to its JSON-only persistence.
 *
 * <p><b>Sessions.</b> Sessions use the explicit-time {@code Composer.open} overload with distinct
 * times: the commit-time overload keys its session cache at {@code Long.MAX_VALUE}, so two
 * same-dimension sessions would silently coalesce. A semantic re-compose at the same public id
 * merges a new version through {@code EntityProvider.putEntity → PrimitiveData.merge} — the
 * manifest-append mechanic {@code JournalStoreIT} locks as this store's regression gate.
 */
public final class JournalStore {

    private static final Logger LOG = LoggerFactory.getLogger(JournalStore.class);

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

    /**
     * Runs composer work with the given pattern's PublicId bound as the nid-allocation scope.
     * The Rocks engine encodes an entity's pattern into its nid, so allocating a nid for a
     * <em>new</em> semantic (or pattern) requires {@code SCOPED_PATTERN_PUBLICID_FOR_NID} to name
     * the pattern being instantiated — the composer's transaction path does not bind it itself.
     * Stores that mint nids unscoped (the ephemeral test store) ignore the binding.
     *
     * @param patternId the pattern whose instances the work creates
     * @param work      the composer call
     */
    private static void composeInPatternScope(PublicId patternId, Runnable work) {
        ScopedValue.where(PrimitiveData.SCOPED_PATTERN_PUBLICID_FOR_NID, patternId).run(work);
    }

    /**
     * Whether the proxy has no chronology in the open store. Deliberately stricter than
     * {@code hasPublicId}: a failed earlier write can leave a minted nid <em>mapping</em> with no
     * entity behind it, and such a residue must not gate out the (re-)seed.
     *
     * @param proxy the term to check
     * @return {@code true} when no entity exists for the proxy
     */
    private static boolean entityAbsent(EntityProxy proxy) {
        if (!PrimitiveData.get().hasPublicId(proxy.publicId())) {
            return true;
        }
        try {
            return EntityService.get()
                    .getEntity(EntityService.get().nidForPublicId(proxy.publicId()))
                    .isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Supplies the calculator whose coordinate stamps user turns and resolves latest versions. */
    private final Supplier<ViewCalculator> viewCalculator;

    /**
     * Creates a store that derives STAMP authorship and version resolution from the supplied view.
     *
     * @param viewCalculator supplier of the hosting card's view calculator; may supply {@code null}
     *                       (user turns then stamp as {@link TinkarTerm#USER} and {@link #load}
     *                       resolves nothing)
     */
    public JournalStore(Supplier<ViewCalculator> viewCalculator) {
        this.viewCalculator = viewCalculator;
    }

    // ── Vocabulary bootstrap ─────────────────────────────────────────────

    /**
     * Composes the wave-1 {@link RichSurfaceTerms} vocabulary into the open datastore if absent —
     * concepts (existence-gated one by one) and the two patterns. Idempotent and thread-safe; safe
     * to call before every write. The vocabulary is shared infrastructure, so it stamps under
     * {@link TinkarTerm#DEVELOPMENT_MODULE} (the {@code NarratorIdentity} precedent), not the
     * conversation module.
     */
    public static synchronized void bootstrapIfAbsent() {
        seedConceptIfAbsent(RichSurfaceTerms.CONVERSATION_JOURNAL);
        seedConceptIfAbsent(RichSurfaceTerms.JOURNAL_ELEMENT);
        seedConceptIfAbsent(RichSurfaceTerms.PROSE_ELEMENT);
        seedConceptIfAbsent(RichSurfaceTerms.COMPONENT_LIST_ELEMENT);
        seedConceptIfAbsent(RichSurfaceTerms.REFERENCE_ELEMENT);
        seedConceptIfAbsent(RichSurfaceTerms.TOMBSTONE_ELEMENT);
        seedConceptIfAbsent(RichSurfaceTerms.JOURNAL_ELEMENTS);
        seedConceptIfAbsent(RichSurfaceTerms.PROSE_CONTENT);
        seedConceptIfAbsent(RichSurfaceTerms.KOMET_ASSISTANT_AUTHOR);
        seedConceptIfAbsent(RichSurfaceTerms.CONVERSATION_JOURNAL_MODULE);
        seedManifestPatternIfAbsent();
        seedProsePatternIfAbsent();
    }

    private static void seedConceptIfAbsent(Concept concept) {
        if (!entityAbsent(concept)) {
            return;
        }
        Composer composer = new Composer("rich-surface-vocabulary-seed");
        try {
            Session session = composer.open(State.ACTIVE, System.currentTimeMillis(), TinkarTerm.USER,
                    TinkarTerm.DEVELOPMENT_MODULE, TinkarTerm.DEVELOPMENT_PATH);
            // The FQN attach creates a description-pattern semantic; its nid allocation needs the
            // description pattern bound as scope (the concept's own nid self-binds).
            composeInPatternScope(TinkarTerm.DESCRIPTION_PATTERN.publicId(),
                    () -> session.compose((ConceptAssembler assembler) -> assembler
                            .concept(concept)
                            .attach(FullyQualifiedName.class, name -> name
                                    .language(TinkarTerm.ENGLISH_LANGUAGE)
                                    .text(concept.description())
                                    .caseSignificance(TinkarTerm.DESCRIPTION_NOT_CASE_SENSITIVE))));
            composer.commitSession(session);
            LOG.info("Seeded rich-surface concept '{}'", concept.description());
        } catch (RuntimeException e) {
            composer.cancelAllSessions();
            throw e;
        }
    }

    private static void seedManifestPatternIfAbsent() {
        if (!entityAbsent(RichSurfaceTerms.JOURNAL_MANIFEST_PATTERN)) {
            return;
        }
        Composer composer = new Composer("rich-surface-vocabulary-seed");
        try {
            Session session = composer.open(State.ACTIVE, System.currentTimeMillis(), TinkarTerm.USER,
                    TinkarTerm.DEVELOPMENT_MODULE, TinkarTerm.DEVELOPMENT_PATH);
            composeInPatternScope(EntityBinding.Pattern.pattern().publicId(),
                    () -> session.compose((PatternAssembler assembler) -> assembler
                            .pattern(RichSurfaceTerms.JOURNAL_MANIFEST_PATTERN)
                            .meaning(RichSurfaceTerms.CONVERSATION_JOURNAL)
                            .purpose(RichSurfaceTerms.JOURNAL_ELEMENTS)
                            .fieldDefinition(RichSurfaceTerms.JOURNAL_ELEMENTS,
                                    RichSurfaceTerms.JOURNAL_ELEMENTS,
                                    TinkarTerm.COMPONENT_ID_LIST_FIELD)));
            composer.commitSession(session);
            LOG.info("Seeded journal manifest pattern");
        } catch (RuntimeException e) {
            composer.cancelAllSessions();
            throw e;
        }
    }

    private static void seedProsePatternIfAbsent() {
        if (!entityAbsent(RichSurfaceTerms.PROSE_ELEMENT_PATTERN)) {
            return;
        }
        Composer composer = new Composer("rich-surface-vocabulary-seed");
        try {
            Session session = composer.open(State.ACTIVE, System.currentTimeMillis(), TinkarTerm.USER,
                    TinkarTerm.DEVELOPMENT_MODULE, TinkarTerm.DEVELOPMENT_PATH);
            composeInPatternScope(EntityBinding.Pattern.pattern().publicId(),
                    () -> session.compose((PatternAssembler assembler) -> assembler
                            .pattern(RichSurfaceTerms.PROSE_ELEMENT_PATTERN)
                            .meaning(RichSurfaceTerms.PROSE_ELEMENT)
                            .purpose(RichSurfaceTerms.PROSE_CONTENT)
                            .fieldDefinition(RichSurfaceTerms.PROSE_CONTENT,
                                    RichSurfaceTerms.PROSE_CONTENT,
                                    TinkarTerm.STRING)));
            composer.commitSession(session);
            LOG.info("Seeded prose element pattern");
        } catch (RuntimeException e) {
            composer.cancelAllSessions();
            throw e;
        }
    }

    // ── Append ───────────────────────────────────────────────────────────

    /**
     * Persists one completed exchange: mints the journal anchor if needed (idempotently, at
     * {@code anchor} when non-null, else at a new random id), writes the user prose element
     * (author = the edit coordinate's author) and the assistant prose element (author =
     * {@link RichSurfaceTerms#KOMET_ASSISTANT_AUTHOR}), and appends both — in order — to a new
     * version of the journal manifest. All instance data stamps under
     * {@link RichSurfaceTerms#CONVERSATION_JOURNAL_MODULE}. Call off the FX thread, only after the
     * exchange succeeded.
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
        bootstrapIfAbsent();
        PublicId anchorId = anchor != null ? anchor : PublicIds.newRandom();
        // The anchor is "new" only when no chronology exists — a nid mapping alone (minted by an
        // earlier failed append) must not skip the anchor concept's creation. An unknown anchor id
        // is never resolved to a nid here: a strict store (Rocks) throws on unknown UUIDs.
        Concept anchorConcept = Concept.make(anchorId);
        boolean anchorNew = entityAbsent(anchorConcept);
        Semantic userElement = Semantic.make(PublicIds.newRandom());
        Semantic assistantElement = Semantic.make(PublicIds.newRandom());

        // Resolve the append base BEFORE any store write, from the manifest chronology's raw
        // versions — never through the card's display coordinate: a view positioned in the past,
        // filtered, or torn down mid-append must never truncate the journal. A resolvable manifest
        // whose head cannot be read fails CLOSED (throw → the caller degrades to JSON-only), never
        // open (an empty prior list would orphan every earlier turn).
        IntIdList priorElements = IntIds.list.empty();
        Semantic manifest;
        int[] manifestNids = anchorNew ? new int[0]
                : EntityService.get().semanticNidsForComponentOfPattern(
                        EntityService.get().nidForPublicId(anchorId),
                        RichSurfaceTerms.JOURNAL_MANIFEST_PATTERN.nid());
        if (manifestNids.length > 0) {
            if (manifestNids.length > 1) {
                LOG.warn("Journal anchor {} carries {} manifests; appending to the first",
                        anchorId.idString(), manifestNids.length);
            }
            manifest = Semantic.make(PrimitiveData.publicId(manifestNids[0]));
            SemanticEntityVersion head = manifestHead(manifestNids[0]);
            if (head == null) {
                throw new IllegalStateException("Journal manifest for " + anchorId.idString()
                        + " has no committed version; refusing an append that would orphan prior turns");
            }
            priorElements = (IntIdList) head.fieldValues().get(0);
            // Seed the monotonic clock past the head, so a wall-clock regression (a restart or a
            // second process) cannot mint a version that hides behind the existing head.
            LAST_STAMP_TIME.updateAndGet(last -> Math.max(last, head.time()));
        } else {
            manifest = Semantic.make(PublicIds.newRandom());
        }

        long userTime = nextStampTime();
        long assistantTime = nextStampTime();
        Composer composer = new Composer("conversation-journal-append");
        try {
            // Distinct explicit times: the commit-time open() overload would coalesce same-dimension
            // sessions (its session key carries Long.MAX_VALUE), and the monotonic clock keeps
            // manifest versions strictly ordered even across same-millisecond exchanges.
            Session userSession = composer.open(State.ACTIVE, userTime, userAuthor(),
                    RichSurfaceTerms.CONVERSATION_JOURNAL_MODULE, TinkarTerm.DEVELOPMENT_PATH);
            if (anchorNew) {
                String name = (conversationName == null || conversationName.isBlank())
                        ? "Conversation journal" : conversationName;
                composeInPatternScope(TinkarTerm.DESCRIPTION_PATTERN.publicId(),
                        () -> userSession.compose((ConceptAssembler assembler) -> assembler
                                .concept(anchorConcept)
                                .attach(FullyQualifiedName.class, fqn -> fqn
                                        .language(TinkarTerm.ENGLISH_LANGUAGE)
                                        .text(name)
                                        .caseSignificance(TinkarTerm.DESCRIPTION_NOT_CASE_SENSITIVE))));
            }
            composeInPatternScope(RichSurfaceTerms.PROSE_ELEMENT_PATTERN.publicId(),
                    () -> userSession.compose((SemanticAssembler assembler) -> assembler
                            .semantic(userElement)
                            .pattern(RichSurfaceTerms.PROSE_ELEMENT_PATTERN)
                            .reference(anchorConcept)
                            .fieldValues(values ->
                                    values.with(userMarkdown == null ? "" : userMarkdown))));

            Session assistantSession = composer.open(State.ACTIVE, assistantTime,
                    RichSurfaceTerms.KOMET_ASSISTANT_AUTHOR,
                    RichSurfaceTerms.CONVERSATION_JOURNAL_MODULE, TinkarTerm.DEVELOPMENT_PATH);
            composeInPatternScope(RichSurfaceTerms.PROSE_ELEMENT_PATTERN.publicId(),
                    () -> assistantSession.compose((SemanticAssembler assembler) -> assembler
                            .semantic(assistantElement)
                            .pattern(RichSurfaceTerms.PROSE_ELEMENT_PATTERN)
                            .reference(anchorConcept)
                            .fieldValues(values ->
                                    values.with(assistantMarkdown == null ? "" : assistantMarkdown))));

            // Manifest: append both element nids (write order = document order) as a new version of
            // the existing manifest semantic, or mint the manifest on first append. Composed in the
            // same session as the assistant element so a crash cannot leave a second manifest behind.
            int[] appended = new int[priorElements.size() + 2];
            System.arraycopy(priorElements.toArray(), 0, appended, 0, priorElements.size());
            appended[appended.length - 2] = EntityService.get().nidForPublicId(userElement.publicId());
            appended[appended.length - 1] = EntityService.get().nidForPublicId(assistantElement.publicId());
            IntIdList newElements = IntIds.list.of(appended);
            composeInPatternScope(RichSurfaceTerms.JOURNAL_MANIFEST_PATTERN.publicId(),
                    () -> assistantSession.compose((SemanticAssembler assembler) -> assembler
                            .semantic(manifest)
                            .pattern(RichSurfaceTerms.JOURNAL_MANIFEST_PATTERN)
                            .reference(anchorConcept)
                            .fieldValues(values -> values.with(newElements))));

            composer.commitAllSessions();
            LOG.info("Appended exchange to journal {} ({} elements)",
                    anchorId.idString(), newElements.size());
            return anchorId;
        } catch (RuntimeException e) {
            // A partial append must not leave durable orphans (compose writes immediately).
            composer.cancelAllSessions();
            throw e;
        }
    }

    /**
     * The manifest's raw head: its committed version with the greatest STAMP time, read straight
     * from the chronology (no coordinate filtering — the append base is the newest write, period).
     * Versions carrying the uncommitted sentinel time are ignored.
     *
     * @param manifestNid the manifest semantic's nid
     * @return the head version, or {@code null} when none is committed
     */
    private static SemanticEntityVersion manifestHead(int manifestNid) {
        Optional<SemanticEntity<SemanticEntityVersion>> entity =
                EntityService.get().getEntity(manifestNid);
        if (entity.isEmpty()) {
            return null;
        }
        SemanticEntityVersion head = null;
        for (SemanticEntityVersion version : entity.get().versions()) {
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
     * One rebuilt turn: its Markdown source and whether the assistant authored it (from the
     * element version's STAMP author).
     *
     * @param markdown          the turn's Markdown source
     * @param assistantAuthored {@code true} when the element's author is
     *                          {@link RichSurfaceTerms#KOMET_ASSISTANT_AUTHOR}
     */
    public record TurnRecord(String markdown, boolean assistantAuthored) {
    }

    /**
     * Rebuilds a conversation from its journal: the latest manifest version's ordered element
     * id-list, each element's latest version yielding its Markdown and author-derived role.
     *
     * @param anchor the conversation's journal anchor; may be {@code null}
     * @return the turns in document order, or empty when the anchor, manifest, or view is absent
     *         (the caller falls back to its JSON persistence)
     */
    public Optional<List<TurnRecord>> load(PublicId anchor) {
        if (anchor == null || !PrimitiveData.get().hasPublicId(anchor)
                || entityAbsent(RichSurfaceTerms.JOURNAL_MANIFEST_PATTERN)) {
            // Unknown anchor, or a store this vocabulary was never bootstrapped into.
            return Optional.empty();
        }
        ViewCalculator calculator = viewCalculator.get();
        if (calculator == null) {
            return Optional.empty();
        }
        int[] manifestNids = EntityService.get().semanticNidsForComponentOfPattern(
                EntityService.get().nidForPublicId(anchor),
                RichSurfaceTerms.JOURNAL_MANIFEST_PATTERN.nid());
        if (manifestNids.length == 0) {
            return Optional.empty();
        }
        if (manifestNids.length > 1) {
            LOG.warn("Journal anchor {} carries {} manifests; loading the first",
                    anchor.idString(), manifestNids.length);
        }
        Latest<SemanticEntityVersion> manifest =
                calculator.stampCalculator().latestSemanticVersion(manifestNids[0]);
        if (!manifest.isPresent()) {
            return Optional.empty();
        }
        IntIdList elements = (IntIdList) manifest.get().fieldValues().get(0);
        int assistantAuthorNid = RichSurfaceTerms.KOMET_ASSISTANT_AUTHOR.nid();
        List<TurnRecord> turns = new ArrayList<>(elements.size());
        for (int elementNid : elements.toArray()) {
            Latest<SemanticEntityVersion> element =
                    calculator.stampCalculator().latestSemanticVersion(elementNid);
            if (!element.isPresent()) {
                LOG.warn("Journal element {} has no version on the current coordinate; skipping",
                        elementNid);
                continue;
            }
            String markdown = String.valueOf(element.get().fieldValues().get(0));
            turns.add(new TurnRecord(markdown, element.get().authorNid() == assistantAuthorNid));
        }
        return Optional.of(turns);
    }

    /** The user-turn author: the view's edit-coordinate author, else {@link TinkarTerm#USER}. */
    private Concept userAuthor() {
        ViewCalculator calculator = viewCalculator.get();
        if (calculator == null) {
            return TinkarTerm.USER;
        }
        ConceptFacade author = calculator.viewCoordinateRecord().editCoordinate().getAuthorForChanges();
        return author == null ? TinkarTerm.USER : Concept.make(author);
    }
}
