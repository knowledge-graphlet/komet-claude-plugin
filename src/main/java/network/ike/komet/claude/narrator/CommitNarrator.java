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

import dev.ikm.komet.preferences.PreferencesService;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.StampEntityVersion;
import dev.ikm.tinkar.events.CommitEvent;
import dev.ikm.tinkar.events.EvtBus;
import dev.ikm.tinkar.events.EvtBusFactory;
import dev.ikm.tinkar.events.FrameworkTopics;
import dev.ikm.tinkar.events.Subscriber;
import network.ike.komet.claude.ClaudeAssistantArea;
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.tools.GraphTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final GraphTools graphTools = new GraphTools(Calculators.View::Default);
    private final CommentWriter commentWriter = new CommentWriter();

    /** @return whether an Anthropic API key is configured (the narrator is otherwise inert). */
    public static boolean isConfigured() {
        return !apiKey().isBlank();
    }

    private static String apiKey() {
        return PreferencesService.userPreferences().get(ClaudeAssistantArea.PREF_API_KEY, "");
    }

    private static String model() {
        return PreferencesService.userPreferences()
                .get(ClaudeAssistantArea.PREF_MODEL, AnthropicClient.DEFAULT_MODEL);
    }

    /** Subscribes the narrator to commit events. */
    public void subscribe() {
        bus.subscribe(FrameworkTopics.COMMIT_TOPIC, CommitEvent.class, subscriber);
    }

    /** Unsubscribes and stops the worker thread. */
    public void shutdown() {
        bus.unsubscribe(FrameworkTopics.COMMIT_TOPIC, CommitEvent.class, subscriber);
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
        int[] stampNids = evt.stampNids();
        if (stampNids == null || stampNids.length == 0) {
            return false;
        }
        boolean allNarratorAuthored = true;
        for (int stampNid : stampNids) {
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
        int[] componentNids = evt.componentNids();
        return componentNids != null && componentNids.length > 0;
    }

    private void narrateAndWrite(CommitEvent evt, String key, String model) {
        ViewCalculator view = Calculators.View.Default();
        AnthropicClient client = new AnthropicClient(key, model, MAX_TOKENS);
        int[] components = evt.componentNids();

        if (evt.stampCount() > BULK_THRESHOLD || components.length > BULK_THRESHOLD) {
            narrateBulk(evt, client, view, components);
            return;
        }
        for (int componentNid : components) {
            try {
                narrateComponent(componentNid, client, view);
            } catch (Throwable t) {
                LOG.warn("Failed to narrate component {}", componentNid, t);
            }
        }
    }

    private void narrateComponent(int componentNid, AnthropicClient client, ViewCalculator view) {
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
        int stampNid = versions.get(0).stampNid();
        String context = componentContext(componentNid, versions, view);
        String narrative = client.ask(SYSTEM_PROMPT, graphTools.tools(),
                "A curator just committed a change.\n" + context
                        + "\n\nWrite a short, SME-readable note about what changed.");
        commentWriter.writeComment(stampNid, narrative);
    }

    private void narrateBulk(CommitEvent evt, AnthropicClient client, ViewCalculator view, int[] components) {
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
        commentWriter.writeComment(evt.stampNids()[0], narrative);
    }

    private static String componentContext(int nid, List<EntityVersion> versionsNewestFirst, ViewCalculator view) {
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

    private static String name(int nid, ViewCalculator view) {
        return view.getFullyQualifiedNameText(nid)
                .orElseGet(() -> view.getPreferredDescriptionTextWithFallbackOrNid(nid));
    }
}
