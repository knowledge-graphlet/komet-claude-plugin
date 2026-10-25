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
import network.ike.komet.claude.authoring.ConceptProposalWriter.Draft;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Edit;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Edited;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Synonym;
import network.ike.komet.claude.authoring.ConceptProposalWriter.SynonymChange;
import network.ike.komet.claude.authoring.ProposedDefinition.Built;
import network.ike.komet.claude.authoring.ProposedDefinition.Proposal;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The assistant's authoring hand-off ({@code IKE-Network/ike-issues#1292}): {@code propose_concept}
 * writes a proposed concept uncommitted and opens a KL window on it in the assistant's journal,
 * {@code edit_proposal} corrects that open proposal in place, and {@code discard_concept_proposal}
 * withdraws one the person does not want.
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
    /** The proposals this assistant opened and has not seen discarded, by proposal id. */
    private final Map<String, Open> proposals = new ConcurrentHashMap<>();
    /** The proposals this assistant discarded, so an edit of one is told so rather than not found. */
    private final Set<String> discarded = ConcurrentHashMap.newKeySet();

    /**
     * An open proposal and what its editorial note is made of, so an edit can make the note again.
     *
     * @param draft     the proposal
     * @param rationale the assistant's rationale, or null
     * @param gaps      the modelling gaps of its current definition
     */
    private record Open(Draft draft, String rationale, List<String> gaps) {
    }

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
     * @return the three tools
     */
    public List<AnthropicTool> tools() {
        return List.of(proposeConcept(), editProposal(), discardProposal());
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
                        + "a gap instead of an axiom. Gaps travel in the editorial note. Pass "
                        + "'textual_definition' to pre-populate the prose textual definition, editable in the "
                        + "window. Use it only when the person asks to create, author, or open the proposed "
                        + "concept; to correct the open proposal afterwards use edit_proposal.";
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
                                        + "modelled on and why. The gaps are appended to it."),
                                "textual_definition", strProp("Optional. The plain-text prose textual definition: "
                                        + "the human-readable clinical description, written as an uncommitted "
                                        + "textual definition the person can edit in the window before publishing. "
                                        + "It should not restate the logical axioms.")),
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
        Proposal proposal = proposal(str(input, "definition"));
        String fsn = proposal.title();
        if (fsn == null || fsn.isBlank()) {
            throw new IllegalArgumentException("the block has no `proposed:` line naming the concept");
        }
        Built built = built(proposal);
        List<Synonym> synonyms = synonyms(input, "synonyms");
        String rationale = str(input, "editorial_note");
        String note = note(rationale, built.gaps());
        String textualDefinition = str(input, "textual_definition");

        Draft written = onFxThread(() -> {
            var edit = viewProperties.nodeView().editCoordinate();
            Draft w = ConceptProposalWriter.write(viewProperties.calculator(), edit.getAuthorForChanges(),
                    edit.getDefaultModule(), edit.getDefaultPath(), fsn, built.tree(), synonyms, note,
                    textualDefinition);
            try {
                // The event's title names the window LAYOUT to open, not a caption: the standard
                // concept window, which lays out the names and the stated definition.
                EvtBusFactory.getDefaultEvtBus().publish(topic, new MakeKLWindowEvent(this,
                        MakeKLWindowEvent.OPEN_STANDARD_WINDOW, w.concept(), StandardEditorWindows.CONCEPT_WINDOW));
            } catch (RuntimeException windowFailed) {
                // No window to review it in means no proposal: withdraw what was written.
                ConceptProposalWriter.discard(w);
                throw new IllegalStateException("the proposal was withdrawn because its window could not open: "
                        + windowFailed.getMessage(), windowFailed);
            }
            return w;
        });
        String id = ComponentText.identifier(written.concept().nid());
        proposals.put(id, new Open(written, rationale, built.gaps()));

        boolean hasTextualDefinition = textualDefinition != null && !textualDefinition.isBlank();
        StringBuilder result = new StringBuilder("Opened an uncommitted proposal for \"" + fsn
                + "\" in a KL window in the person's journal (proposal " + id + "): the concept, its fully "
                + "specified name, " + synonyms.size() + " synonym(s), the stated definition");
        result.append(hasTextualDefinition ? ", a textual definition" : "")
                .append(note == null ? "" : ", and an editorial note").append(". NOTHING IS COMMITTED: it becomes "
                + "a concept only when the person presses Publish in that window. Tell them it is waiting there "
                + "for their review. To correct it, use edit_proposal with this proposal id.");
        appendGaps(result, proposal, built);
        return result.toString();
    }

    /** Warns of a sufficient set proposed with gaps, and lists the gaps. */
    private static void appendGaps(StringBuilder result, Proposal proposal, Built built) {
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
    }

    // ── edit_proposal ───────────────────────────────────────────────────

    private AnthropicTool editProposal() {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "edit_proposal";
            }

            @Override
            public String description() {
                return "Correct an open, uncommitted proposal in place: update one or more of its fields without "
                        + "discarding it, so the person's own edits in its KL window are kept and the window stays "
                        + "open. Only the fields you pass change; everything else is left as the person left it. "
                        + "Nothing is committed: it stays a proposal until the person presses Publish. Use it for "
                        + "incremental corrections the person asks for (a synonym, the textual definition, a role "
                        + "group) on a proposal id propose_concept returned; to start over entirely use "
                        + "discard_concept_proposal and propose_concept. A published or discarded proposal is "
                        + "refused. A field the person has already edited in the window is left as they left it "
                        + "and reported as a warning — tell them. The proposal keeps exactly one preferred "
                        + "synonym: to change which one, demote the old and promote the new in the same call.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                Map<String, Object> synonymItem = obj("type", "object",
                        "properties", obj(
                                "term", strProp("The synonym text."),
                                "acceptability", obj("type", "string",
                                        "enum", List.of("preferred", "acceptable"),
                                        "description", "US English acceptability."),
                                "case_sensitive", obj("type", "boolean",
                                        "description", "True when the term's case is significant.")),
                        "required", List.of("term", "acceptability"));
                return obj("type", "object",
                        "properties", obj(
                                "proposal", strProp("The proposal id propose_concept returned."),
                                "textual_definition", strProp("Optional. Replaces the textual definition, or "
                                        + "adds one when the proposal has none."),
                                "editorial_note", strProp("Optional. Replaces your rationale in the editorial "
                                        + "note; the definition's gaps are still appended to it."),
                                "synonyms_add", obj("type", "array",
                                        "description", "Optional. Synonyms to add; none may repeat a synonym "
                                                + "the proposal already has.",
                                        "items", synonymItem),
                                "synonyms_remove", obj("type", "array",
                                        "description", "Optional. The exact terms of proposed synonyms to withdraw.",
                                        "items", obj("type", "string")),
                                "synonyms_update", obj("type", "array",
                                        "description", "Optional. Change the acceptability or case significance "
                                                + "of a proposed synonym, found by its exact term.",
                                        "items", obj("type", "object",
                                                "properties", obj(
                                                        "term", strProp("The synonym's exact text."),
                                                        "acceptability", obj("type", "string",
                                                                "enum", List.of("preferred", "acceptable"),
                                                                "description", "New US English acceptability."),
                                                        "case_sensitive", obj("type", "boolean",
                                                                "description", "New case significance.")),
                                                "required", List.of("term"))),
                                "definition", strProp("Optional. A whole replacement koncept-definition block "
                                        + "(sets, axioms, gap: lines); its `proposed:` line is ignored — rename with "
                                        + "proposed_name. Replaces the entire stated definition; if the person has "
                                        + "already edited the axioms in the window, theirs stand and you are warned."),
                                "proposed_name", strProp("Optional. A replacement fully specified name, with its "
                                        + "semantic tag. A preferred synonym derived from the old name follows it.")),
                        "required", List.of("proposal"));
            }

            @Override
            public String execute(Map<String, Object> input) {
                try {
                    return edit(input);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    return "Error: " + e.getMessage() + ". Nothing was written.";
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }

    private String edit(Map<String, Object> input) {
        String id = str(input, "proposal") == null ? null : str(input, "proposal").strip();
        if (id != null && discarded.contains(id)) {
            throw new IllegalStateException("proposal " + id + " was discarded, so there is nothing to edit; use "
                    + "propose_concept to start over");
        }
        Open open = id == null ? null : proposals.get(id);
        if (open == null) {
            throw new IllegalArgumentException("no open proposal " + id + " from this assistant");
        }
        String block = str(input, "definition");
        Proposal proposal = block == null ? null : proposal(block);
        Built built = proposal == null ? null : built(proposal);
        String rationale = input.containsKey("editorial_note") ? str(input, "editorial_note") : open.rationale();
        List<String> gaps = built == null ? open.gaps() : built.gaps();
        boolean noteChanges = input.containsKey("editorial_note") || built != null;
        Edit edit = new Edit(str(input, "proposed_name"), built == null ? null : built.tree(),
                str(input, "textual_definition"), noteChanges ? note(rationale, gaps) : null,
                synonyms(input, "synonyms_add"), terms(input, "synonyms_remove"), changes(input, "synonyms_update"));

        Edited edited;
        try {
            edited = onFxThread(() -> open.draft().edit(edit));
        } catch (IllegalStateException refused) {
            if (open.draft().status() == ConceptProposalWriter.Status.DISCARDED) {
                proposals.remove(id);
                discarded.add(id);
            }
            throw refused;
        }
        proposals.put(id, new Open(open.draft(),
                edited.fields().contains("editorial_note") && input.containsKey("editorial_note") ? rationale : open.rationale(),
                edited.fields().contains("definition") ? gaps : open.gaps()));

        StringBuilder result = new StringBuilder();
        if (edited.fields().isEmpty()) {
            result.append("No change to proposal ").append(id).append(": it already held what was passed. ");
        } else {
            result.append("Updated proposal ").append(id).append(" in place; its window stays open and refreshes. ");
        }
        result.append("fields_updated: ").append(edited.fields()).append(". NOTHING IS COMMITTED: it becomes a "
                + "concept only when the person presses Publish.");
        if (!edited.warnings().isEmpty()) {
            result.append("\nWarnings (tell the person):");
            edited.warnings().forEach(warning -> result.append("\n- ").append(warning));
        }
        if (proposal != null && edited.fields().contains("definition")) {
            appendGaps(result, proposal, built);
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
                Open open = id == null ? null : proposals.get(id.strip());
                if (open == null) {
                    return "Error: no open proposal " + id + " from this assistant";
                }
                try {
                    boolean wasOpen = open.draft().status() == ConceptProposalWriter.Status.OPEN;
                    boolean withdrawn = onFxThread(() -> ConceptProposalWriter.discard(open.draft()));
                    proposals.remove(id.strip());
                    if (withdrawn || !wasOpen && open.draft().status() == ConceptProposalWriter.Status.DISCARDED) {
                        discarded.add(id.strip());
                    }
                    return withdrawn
                            ? "Discarded proposal " + id + ": nothing of it remains. Ask the person to close its window."
                            : "Proposal " + id + " was already published; it is a committed concept and was left alone.";
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The koncept-definition block, parsed. */
    private static Proposal proposal(String block) {
        Proposal proposal = block == null ? null : ProposedDefinition.parse(block);
        if (proposal == null) {
            throw new IllegalArgumentException("the definition is not a well-formed koncept-definition block");
        }
        return proposal;
    }

    /** The block's stated definition; refused when none of its identifiers resolves. */
    private static Built built(Proposal proposal) {
        Built built = ProposedDefinition.build(proposal);
        if (built.tree() == null) {
            throw new IllegalArgumentException("none of the block's identifiers is in this knowledge base, so "
                    + "there is no definition to propose: " + String.join("; ", built.gaps()));
        }
        return built;
    }

    /** The synonyms under the input's key, in order. */
    static List<Synonym> synonyms(Map<String, Object> input, String key) {
        List<Synonym> synonyms = new ArrayList<>();
        if (input.get(key) instanceof List<?> list) {
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

    /** The strings under the input's key, in order. */
    static List<String> terms(Map<String, Object> input, String key) {
        List<String> terms = new ArrayList<>();
        if (input.get(key) instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    terms.add(item.toString().strip());
                }
            }
        }
        return terms;
    }

    /** The synonym changes under the input's key, in order; an absent property is left unchanged. */
    static List<SynonymChange> changes(Map<String, Object> input, String key) {
        List<SynonymChange> changes = new ArrayList<>();
        if (input.get(key) instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object term = map.get("term");
                    if (term == null || term.toString().isBlank()) {
                        throw new IllegalArgumentException("a synonym update has no term");
                    }
                    Object acceptability = map.get("acceptability");
                    Object caseSensitive = map.get("case_sensitive");
                    changes.add(new SynonymChange(term.toString().strip(),
                            acceptability == null ? null : "preferred".equalsIgnoreCase(acceptability.toString()),
                            caseSensitive == null ? null : Boolean.TRUE.equals(caseSensitive)
                                    || "true".equalsIgnoreCase(caseSensitive.toString())));
                }
            }
        }
        return changes;
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
    static <T> T onFxThread(Supplier<T> work) {
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
            throw new IllegalStateException("Komet did not finish writing within " + FX_TIMEOUT_SECONDS + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while writing");
        }
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    static Map<String, Object> obj(Object... keyValues) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    static Map<String, Object> strProp(String description) {
        return obj("type", "string", "description", description);
    }
}
