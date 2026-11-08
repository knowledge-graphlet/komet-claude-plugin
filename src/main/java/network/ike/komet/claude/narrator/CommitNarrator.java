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
package network.ike.komet.claude.narrator;

import dev.ikm.tinkar.common.id.PublicId;
import design.knowledge.stamp.adoc.git.AnnotationCommit;
import design.knowledge.stamp.adoc.identity.GitIdentityAdapter;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.AnnotationTarget;
import design.knowledge.stamp.adoc.model.KonceptRef;
import network.ike.komet.document.DocumentCommits;
import network.ike.komet.document.GitDocumentMaterializer;
import dev.ikm.komet.preferences.PreferencesService;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.StampEntityVersion;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.events.CommitEvent;
import dev.ikm.tinkar.events.EvtBus;
import dev.ikm.tinkar.events.EvtBusFactory;
import dev.ikm.tinkar.events.FrameworkTopics;
import dev.ikm.tinkar.events.Subscriber;
import network.ike.komet.claude.ClaudeCard;
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.document.ComponentText;
import network.ike.komet.claude.tools.GraphTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Listens for graph commits and, for each worthy commit, narrates the change with Claude and
 * writes the narrative back as a commit comment (a {@code COMMENT_PATTERN}-on-stamp semantic).
 * <p>
 * Runs headless — it reads the diff over the default {@link Calculators.View#Default() view} and
 * never touches any assistant window. Because the event bus delivers synchronously on the
 * committing thread, {@link #onCommit} does only the worthiness check inline and offloads all
 * narration (the blocking Claude call + the comment write) to a single daemon worker thread.
 * <p>
 * The narrator's own comment write is itself a commit; the {@link #isWorthy worthiness guard}
 * recognizes and skips it (by author/module) <em>before</em> any work is scheduled, so the
 * synchronous bus cannot recurse.
 * <p>
 * Store commits are narrated only when knowledge. A commit under an ephemeral module, such as a
 * stamped document rebuilt from git, is not narrated from the store at all: a rebuild is transparent
 * (IKE-Network/ike-issues#1355). A stamped document a person commits to git through Komet is narrated
 * from that commit instead ({@link DocumentCommits}): the narration is written to the document's git
 * repository as a comment on the commit's STAMP, authored by the narrator, and comes into the store,
 * under the document's module, with every rebuild.
 * <p>
 * Every narration's identity is derived from the STAMP it narrates ({@link CommentWriter#narrationId}),
 * so a change is narrated once: one already in the store is not asked of Claude again.
 */
public final class CommitNarrator {

    private static final Logger LOG = LoggerFactory.getLogger(CommitNarrator.class);

    /** Above this many changed components (or finalized stamps), coalesce to one roll-up comment. */
    private static final int BULK_THRESHOLD = 10;

    /** Narrations are short; cap output tokens modestly. */
    private static final int MAX_TOKENS = 2048;

    private static final String SYSTEM_PROMPT = """
            You are the Komet commit narrator. When a curator commits a change to the clinical
            knowledge graph, you write a brief, SME-readable note explaining what changed.

            Rules:
            - Use the provided read-only tools to ground every concept, identifier, and
              relationship you mention. Never state a code, name, or hierarchy position from
              memory — confirm it with a tool first.
            - Be concise: one or two sentences. Lead with what changed.
            - Write for a clinical terminologist; prefer the knowledge base's fully specified names.
            - Do not speculate beyond the diff and what the tools confirm. Output only the note.""";

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "komet-narrator");
        thread.setDaemon(true);
        return thread;
    });
    private final EvtBus bus = EvtBusFactory.getDefaultEvtBus();
    private final Subscriber<CommitEvent> subscriber = this::onCommit;
    private final Consumer<DocumentCommits.Committed> documentCommits = this::onDocumentCommit;
    private final GraphTools graphTools = new GraphTools(Calculators.View::Default);
    private final CommentWriter commentWriter = new CommentWriter();

    /** @return whether an Anthropic API key is configured (the narrator is otherwise inert). */
    public static boolean isConfigured() {
        return !apiKey().isBlank();
    }

    private static String apiKey() {
        return PreferencesService.userPreferences().get(ClaudeCard.PREF_API_KEY, "");
    }

    private static String model() {
        return PreferencesService.userPreferences()
                .get(ClaudeCard.PREF_MODEL, AnthropicClient.DEFAULT_MODEL);
    }

    /** Subscribes the narrator to commit events. */
    public void subscribe() {
        bus.subscribe(FrameworkTopics.COMMIT_TOPIC, CommitEvent.class, subscriber);
        DocumentCommits.listen(documentCommits);
    }

    /** Unsubscribes and stops the worker thread. */
    public void shutdown() {
        bus.unsubscribe(FrameworkTopics.COMMIT_TOPIC, CommitEvent.class, subscriber);
        DocumentCommits.stopListening(documentCommits);
        worker.shutdownNow();
    }

    // Runs on the COMMITTING thread (the bus is synchronous): stay cheap, then offload.
    private void onCommit(CommitEvent evt) {
        if (!isWorthy(evt)) {
            return;
        }
        String key = apiKey();
        if (key.isBlank()) {
            return; // headless: no key, no narration, no UI prompt
        }
        String model = model();
        worker.submit(() -> {
            try {
                narrateAndWrite(evt, key, model);
            } catch (Throwable t) {
                LOG.error("Narration failed for transaction {}", evt.transactionUuid(), t);
            }
        });
    }

    /**
     * A commit is worthy of narration unless it is the narrator's own comment write (every
     * finalized stamp authored by the narrator identity) or it changed nothing. The guard runs
     * before any work is scheduled, so the synchronous bus cannot recurse into narration.
     */
    private boolean isWorthy(CommitEvent evt) {
        long[] stampNids = evt.stampNids();
        if (stampNids == null || stampNids.length == 0) {
            return false;
        }
        boolean allNarratorAuthored = true;
        for (long stampNid : stampNids) {
            StampEntityVersion stamp = Entity.getStamp(stampNid).lastVersion();
            if (stamp.authorNid() != NarratorIdentity.NARRATOR_AUTHOR.nid()
                    || stamp.moduleNid() != NarratorIdentity.NARRATION_MODULE.nid()) {
                allNarratorAuthored = false;
                break;
            }
        }
        if (allNarratorAuthored) {
            return false; // our own comment commit
        }
        boolean anyKnowledge = false;
        for (long stampNid : stampNids) {
            if (narratable(stampNid)) {
                anyKnowledge = true;
                break;
            }
        }
        if (!anyKnowledge) {
            return false; // ephemeral or instance only: its narration is not a knowledge comment (#1355)
        }
        long[] componentNids = evt.componentNids();
        return componentNids != null && componentNids.length > 0;
    }

    private void narrateAndWrite(CommitEvent evt, String key, String model) {
        ViewCalculator view = Calculators.View.Default();
        AnthropicClient client = new AnthropicClient(key, model, MAX_TOKENS).effort(ClaudeCard.configuredEffort());
        long[] components = evt.componentNids();

        if (evt.stampCount() > BULK_THRESHOLD || components.length > BULK_THRESHOLD) {
            narrateBulk(evt, client, view, components);
            return;
        }
        for (long componentNid : components) {
            try {
                narrateComponent(componentNid, client, view);
            } catch (Throwable t) {
                LOG.warn("Failed to narrate component {}", componentNid, t);
            }
        }
    }

    private void narrateComponent(long componentNid, AnthropicClient client, ViewCalculator view) {
        var optEntity = EntityHandle.get(componentNid).entity();
        if (optEntity.isEmpty()) {
            return;
        }
        List<EntityVersion> versions = new ArrayList<>();
        for (EntityVersion version : optEntity.get().versions()) {
            versions.add(version);
        }
        if (versions.isEmpty()) {
            return;
        }
        versions.sort((a, b) -> Long.compare(b.stamp().time(), a.stamp().time()));
        long stampNid = versions.get(0).stampNid();
        if (!narratable(stampNid)) {
            return;
        }
        PublicId id = CommentWriter.narrationId(Entity.getStamp(stampNid).publicId(), optEntity.get().publicId());
        if (CommentWriter.narrated(id)) {
            return;                                      // narrated once already: not asked again
        }
        String context = componentContext(componentNid, versions, view);
        String narrative = client.ask(SYSTEM_PROMPT, graphTools.tools(),
                "A curator just committed a change.\n" + context
                        + "\n\nWrite a short, SME-readable note about what changed.");
        commentWriter.writeComment(id, stampNid, narrative);
    }

    private void narrateBulk(CommitEvent evt, AnthropicClient client, ViewCalculator view, long[] components) {
        long rollUpStamp = 0;
        for (long stampNid : evt.stampNids()) {
            if (narratable(stampNid)) {
                rollUpStamp = stampNid;
                break;
            }
        }
        if (rollUpStamp == 0) {
            return;
        }
        PublicId id = CommentWriter.narrationId(Entity.getStamp(rollUpStamp).publicId(), null);
        if (CommentWriter.narrated(id)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(components.length).append(" components changed in transaction '")
                .append(evt.transactionName()).append("'. Examples: ");
        int sample = Math.min(components.length, 8);
        for (int i = 0; i < sample; i++) {
            sb.append(name(components[i], view)).append("; ");
        }
        String narrative = client.ask(SYSTEM_PROMPT, graphTools.tools(),
                "A bulk change was just committed.\n" + sb
                        + "\n\nWrite a single short roll-up note summarizing the change.");
        commentWriter.writeComment(id, rollUpStamp, narrative);
    }

    // Runs on the thread that committed the document: stay cheap, then offload.
    private void onDocumentCommit(DocumentCommits.Committed committed) {
        String key = apiKey();
        if (key.isBlank()) {
            return;
        }
        String model = model();
        worker.submit(() -> {
            try {
                narrateDocumentCommit(committed, key, model);
            } catch (Throwable t) {
                LOG.error("Narration failed for the commit of {} at {}", committed.file(), committed.sha(), t);
            }
        });
    }

    /**
     * Narrates a stamped document a person committed to git (IKE-Network/ike-issues#1355): the narration is a
     * comment on the commit's STAMP, written to the document's repository as an annotation commit authored by
     * the narrator, then brought into the store by rebuilding the document, under the document's module. Its
     * identity is derived from the commit's STAMP, so it is written once, and every rebuild recreates it.
     */
    private void narrateDocumentCommit(DocumentCommits.Committed committed, String key, String model) throws Exception {
        PublicId anchor = GitDocumentMaterializer.documentAnchor(committed.root(), committed.file());
        Optional<StampEntity<?>> stamp = GitDocumentMaterializer.commitStamp(anchor, committed.sha());
        if (stamp.isEmpty()) {
            LOG.warn("Not narrated: the store does not contain the STAMP of {} at {}", committed.file(), committed.sha());
            return;
        }
        PublicId id = CommentWriter.narrationId(stamp.get().publicId(), null);
        if (CommentWriter.narrated(id)) {
            return;
        }
        String change = DocumentChange.of(committed.root(), committed.file(), committed.sha());
        AnthropicClient client = new AnthropicClient(key, model, MAX_TOKENS).effort(ClaudeCard.configuredEffort());
        String narrative = client.ask(SYSTEM_PROMPT, graphTools.tools(),
                "A curator just committed a change to the stamped document " + committed.file() + ".\n" + change
                        + "\n\nWrite a short, SME-readable note about what changed.");
        if (narrative == null || narrative.isBlank()) {
            return;
        }
        GitIdentityAdapter adapter = GitIdentityAdapter.fromMailmap(committed.root());
        Annotation comment = new Annotation(AnnotationKind.COMMENT.ref(),
                new AnnotationTarget.ChangeSet(new KonceptRef(adapter.stamp(committed.sha()).uuid(), committed.sha().substring(0, 7))),
                new KonceptRef(NarratorIdentity.NARRATOR_AUTHOR.publicId().asUuidArray()[0], NarratorIdentity.NARRATOR_AUTHOR_NAME),
                Instant.now(), id.asUuidArray()[0]);
        AnnotationCommit.Result written = AnnotationCommit.write(committed.root(), List.of(comment), narrative.strip(), false);
        if (!written.ok()) {
            LOG.warn("Not narrated: the narration of {} at {} could not be committed: {}", committed.file(),
                    committed.sha(), written.output());
            return;
        }
        GitDocumentMaterializer.materialize(committed.root(), committed.root().resolve(committed.file()), true);
    }

    /**
     * Whether a commit's STAMP may carry a knowledge comment: its module is knowledge
     * (IKE-Network/ike-issues#1355).
     *
     * @param stampNid the STAMP
     * @return {@code true} for a knowledge STAMP
     */
    static boolean narratable(long stampNid) {
        return EntityBoundaries.stampKind(stampNid).kind() == DataKind.KNOWLEDGE;
    }

    private static String componentContext(long nid, List<EntityVersion> versionsNewestFirst, ViewCalculator view) {
        StringBuilder sb = new StringBuilder();
        sb.append("Component: ").append(name(nid, view)).append('.');
        StampEntity<?> after = versionsNewestFirst.get(0).stamp();
        sb.append(" Current state: ").append(after.state())
                .append(", by ").append(name(after.authorNid(), view))
                .append(" on module ").append(name(after.moduleNid(), view)).append('.');
        if (versionsNewestFirst.size() > 1) {
            sb.append(" Previous state: ").append(versionsNewestFirst.get(1).stamp().state()).append('.');
        } else {
            sb.append(" This is a newly created component.");
        }
        return sb.toString();
    }

    /**
     * The view's coordinate-preferred description — the badge's own resolution (#942) — else the
     * component's UUID. The narrative is stored as a comment semantic, so it never holds a nid
     * ({@code IKE-Network/ike-issues#1170}).
     */
    private static String name(long nid, ViewCalculator view) {
        return ComponentText.name(view, nid);
    }
}
