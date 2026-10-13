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
package network.ike.komet.claude.authoring;

import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.kview.events.MakeKLWindowEvent;
import dev.ikm.komet.layout.editor.StandardEditorWindows;
import dev.ikm.tinkar.events.EvtBusFactory;
import javafx.application.Platform;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Synonym;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Written;
import network.ike.komet.claude.authoring.ProposedDefinition.Built;
import network.ike.komet.claude.authoring.ProposedDefinition.Proposal;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The assistant's authoring hand-off ({@code IKE-Network/ike-issues#1292}): {@code propose_concept}
 * writes a proposed concept uncommitted and opens a KL window on it in the assistant's journal, and
 * {@code discard_concept_proposal} withdraws one the person does not want.
 *
 * <p>The assistant is a proposer, never a committer. Nothing these tools write is committed: the
 * person reviews the proposal in the window, adjusts it, and commits it with the window's Publish
 * button — the same signed act as for a concept they authored by hand. The definition is a
 * {@code koncept-definition} block, the grammar the assistant already previews proposals in, so
 * what the person saw previewed is what the window opens with.
 *
 * <p>Modelling gaps cannot be axioms — a stored definition holds only concepts that exist — so
 * they travel in the editorial note, and are reported back to the assistant.
 */
public final class ConceptProposalTools {

    /** How long a tool call waits for the JavaFX thread to write the proposal. */
    private static final long FX_TIMEOUT_SECONDS = 30;

    private final Supplier<ViewProperties> view;
    private final Supplier<UUID> journalTopic;
    /** The proposals this assistant opened and has not seen discarded, by concept UUID. */
    private final Map<String, Written> proposals = new ConcurrentHashMap<>();

    /**
     * @param view         the assistant's view, whose edit coordinate proposals are authored on;
     *                     may supply null before the card is bound
     * @param journalTopic the assistant's journal, where the KL window opens; may supply null
     */
    public ConceptProposalTools(Supplier<ViewProperties> view, Supplier<UUID> journalTopic) {
        this.view = view;
        this.journalTopic = journalTopic;
    }

    /**
     * The tools to register with the Anthropic client.
     *
     * @return the two tools
     */
    public List<AnthropicTool> tools() {
        return List.of(proposeConcept(), discardProposal());
    }

    // ── propose_concept ─────────────────────────────────────────────────

    private AnthropicTool proposeConcept() {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "propose_concept";
            }

            @Override
            public String description() {
                return "Hand a proposed concept to the person for authoring: write it UNCOMMITTED and open a "
                        + "Komet KL window on it, where they review, adjust, and commit it with Publish. Nothing "
                        + "is committed by this tool, and you must never claim the concept exists — it exists "
                        + "only once the person publishes it. Pass the same koncept-definition block you showed "
                        + "them (its `proposed:` line is the fully specified name, with its semantic tag); every "
                        + "identifier is resolved again here, and any that is not in the knowledge base becomes "
                        + "a gap instead of an axiom. Gaps travel in the editorial note. Use it only when the "
                        + "person asks to create, author, or open the proposed concept.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return obj("type", "object",
                        "properties", obj(
                                "definition", strProp("The koncept-definition block body, exactly as shown to the "
                                        + "person: a `proposed: <fully specified name (semantic tag)>` line, then "
                                        + "necessary/sufficient sets, supertypes, roles, groups, and gap: lines."),
                                "synonyms", obj("type", "array",
                                        "description", "Synonyms beyond the fully specified name. If none is "
                                                + "preferred, the name without its semantic tag becomes the preferred synonym.",
                                        "items", obj("type", "object",
                                                "properties", obj(
                                                        "term", strProp("The synonym text."),
                                                        "acceptability", obj("type", "string",
                                                                "enum", List.of("preferred", "acceptable"),
                                                                "description", "US English acceptability."),
                                                        "case_sensitive", obj("type", "boolean",
                                                                "description", "True when the term's case is significant.")),
                                                "required", List.of("term", "acceptability"))),
                                "editorial_note", strProp("Optional rationale for the proposal: what it was "
                                        + "modelled on and why. The gaps are appended to it.")),
                        "required", List.of("definition"));
            }

            @Override
            public String execute(Map<String, Object> input) {
                try {
                    return propose(input);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    return "Error: " + e.getMessage();
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }

    private String propose(Map<String, Object> input) {
        ViewProperties viewProperties = view.get();
        UUID topic = journalTopic.get();
        if (viewProperties == null || topic == null) {
            throw new IllegalStateException("the assistant is not attached to a journal yet, so there is no window to open");
        }
        String block = str(input, "definition");
        Proposal proposal = block == null ? null : ProposedDefinition.parse(block);
        if (proposal == null) {
            throw new IllegalArgumentException("the definition is not a well-formed koncept-definition block");
        }
        String fsn = proposal.title();
        if (fsn == null || fsn.isBlank()) {
            throw new IllegalArgumentException("the block has no `proposed:` line naming the concept");
        }
        Built built = ProposedDefinition.build(proposal);
        if (built.tree() == null) {
            throw new IllegalArgumentException("none of the block's identifiers is in this knowledge base, so "
                    + "there is no definition to propose: " + String.join("; ", built.gaps()));
        }
        List<Synonym> synonyms = synonyms(input);
        String note = note(str(input, "editorial_note"), built.gaps());

        Written written = onFxThread(() -> {
            var edit = viewProperties.nodeView().editCoordinate();
            Written w = ConceptProposalWriter.write(viewProperties.calculator(), edit.getAuthorForChanges(),
                    edit.getDefaultModule(), edit.getDefaultPath(), fsn, built.tree(), synonyms, note);
            try {
                // The event's title names the window LAYOUT to open, not a caption: the standard
                // concept window, which lays out the names and the stated definition.
                EvtBusFactory.getDefaultEvtBus().publish(topic, new MakeKLWindowEvent(this,
                        MakeKLWindowEvent.OPEN_STANDARD_WINDOW, w.concept(), StandardEditorWindows.CONCEPT_WINDOW_2));
            } catch (RuntimeException windowFailed) {
                // No window to review it in means no proposal: withdraw what was written.
                ConceptProposalWriter.discard(w);
                throw new IllegalStateException("the proposal was withdrawn because its window could not open: "
                        + windowFailed.getMessage(), windowFailed);
            }
            return w;
        });
        String id = ComponentText.identifier(written.concept().nid());
        proposals.put(id, written);

        StringBuilder result = new StringBuilder("Opened an uncommitted proposal for \"" + fsn
                + "\" in a KL window in the person's journal (proposal " + id + "): the concept, its fully "
                + "specified name, " + synonyms.size() + " synonym(s), the stated definition");
        result.append(note == null ? "" : ", and an editorial note").append(". NOTHING IS COMMITTED: it becomes "
                + "a concept only when the person presses Publish in that window. Tell them it is waiting there "
                + "for their review.");
        if (!built.gaps().isEmpty() && proposal.sets().stream().anyMatch(ProposedDefinition.SetSpec::sufficient)) {
            result.append("\nWARNING: a SUFFICIENT set is proposed while gaps remain. Until the gaps are "
                    + "resolved, the definition lacks what distinguishes the concept, so as a sufficient set it "
                    + "would classify as equivalent to every concept meeting the rest. Tell the person; a "
                    + "necessary (primitive) set is the safe choice until the gaps are filled.");
        }
        if (!built.gaps().isEmpty()) {
            result.append("\nGaps (in the editorial note, not in the definition — the person must resolve them "
                    + "before publishing):");
            built.gaps().forEach(gap -> result.append("\n- ").append(gap));
        }
        return result.toString();
    }

    // ── discard_concept_proposal ────────────────────────────────────────

    private AnthropicTool discardProposal() {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "discard_concept_proposal";
            }

            @Override
            public String description() {
                return "Withdraw an uncommitted proposal propose_concept opened, rolling back everything it "
                        + "wrote. Use it only when the person asks to discard or withdraw that proposal. A "
                        + "proposal they already published is a committed concept and is left alone.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return obj("type", "object",
                        "properties", obj("proposal", strProp("The proposal id propose_concept returned.")),
                        "required", List.of("proposal"));
            }

            @Override
            public String execute(Map<String, Object> input) {
                String id = str(input, "proposal");
                Written written = id == null ? null : proposals.get(id.strip());
                if (written == null) {
                    return "Error: no open proposal " + id + " from this assistant";
                }
                try {
                    boolean discarded = onFxThread(() -> ConceptProposalWriter.discard(written));
                    proposals.remove(id.strip());
                    return discarded
                            ? "Discarded proposal " + id + ": nothing of it remains. Ask the person to close its window."
                            : "Proposal " + id + " was already published; it is a committed concept and was left alone.";
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The synonyms of the input, in order. */
    static List<Synonym> synonyms(Map<String, Object> input) {
        List<Synonym> synonyms = new ArrayList<>();
        if (input.get("synonyms") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object term = map.get("term");
                    if (term == null || term.toString().isBlank()) {
                        throw new IllegalArgumentException("a synonym has no term");
                    }
                    boolean preferred = "preferred".equalsIgnoreCase(String.valueOf(map.get("acceptability")));
                    boolean caseSensitive = Boolean.TRUE.equals(map.get("case_sensitive"))
                            || "true".equalsIgnoreCase(String.valueOf(map.get("case_sensitive")));
                    synonyms.add(new Synonym(term.toString().strip(), preferred, caseSensitive));
                }
            }
        }
        return synonyms;
    }

    /** The editorial note: the assistant's rationale, then the gaps; null when both are empty. */
    static String note(String rationale, List<String> gaps) {
        StringBuilder note = new StringBuilder(rationale == null ? "" : rationale.strip());
        if (!gaps.isEmpty()) {
            if (!note.isEmpty()) {
                note.append("\n\n");
            }
            note.append("Modelling gaps (unresolved; not in the definition):");
            gaps.forEach(gap -> note.append("\n- ").append(gap));
        }
        return note.isEmpty() ? null : note.toString();
    }

    /** Runs the work on the JavaFX application thread and waits for it. */
    private static <T> T onFxThread(Supplier<T> work) {
        if (Platform.isFxApplicationThread()) {
            return work.get();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Komet did not write the proposal within " + FX_TIMEOUT_SECONDS + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while writing the proposal");
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static Map<String, Object> obj(Object... keyValues) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> strProp(String description) {
        return obj("type", "string", "description", description);
    }
}
