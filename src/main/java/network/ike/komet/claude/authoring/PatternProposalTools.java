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
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Synonym;
import network.ike.komet.claude.authoring.PatternProposalWriter.Draft;
import network.ike.komet.claude.authoring.PatternProposalWriter.Field;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static network.ike.komet.claude.authoring.ConceptProposalTools.obj;
import static network.ike.komet.claude.authoring.ConceptProposalTools.onFxThread;
import static network.ike.komet.claude.authoring.ConceptProposalTools.str;
import static network.ike.komet.claude.authoring.ConceptProposalTools.strProp;
import static network.ike.komet.claude.authoring.ConceptProposalTools.synonyms;

/**
 * The assistant's pattern hand-off {@code propose_pattern}
 * writes a proposed pattern uncommitted into the store, where the journal's Unpublished Changes
 * panel lists it, and {@code discard_pattern_proposal} withdraws one the person does not want.
 *
 * <p>Every concept the pattern names, its meaning, its purpose, and each field's meaning, purpose
 * and data type, is resolved here against the open knowledge base; an identifier it does not hold
 * refuses the whole proposal, and nothing is written. A data type must be one of the choices
 * Komet's own pattern window offers: a child of "Display Fields". A concept that is itself still
 * unpublished refuses the proposal too, unless the person has said to go ahead regardless: a
 * pattern published before the concepts it names would point at nothing.
 *
 * <p>The assistant is a proposer, never a committer: the person reviews the pattern from the
 * Unpublished Changes panel, whose change review window shows its meaning, purpose and fields,
 * and commits it with Publish.
 */
public final class PatternProposalTools {

    private final Supplier<ViewProperties> view;

    /** The proposals this assistant wrote and has not seen discarded, by proposal id. */
    private final Map<String, Draft> proposals = new ConcurrentHashMap<>();

    /** The proposals this assistant discarded, so a later call is told so rather than not found. */
    private final Set<String> discarded = ConcurrentHashMap.newKeySet();

    /**
     * @param view the assistant's view, whose edit coordinate proposals are authored on; may
     *             supply null before the card is bound
     */
    public PatternProposalTools(Supplier<ViewProperties> view) {
        this.view = view;
    }

    /**
     * The tools to register with the Anthropic client.
     *
     * @return the two tools
     */
    public List<AnthropicTool> tools() {
        return List.of(proposePattern(), discardProposal());
    }

    // ── propose_pattern ─────────────────────────────────────────────────

    private AnthropicTool proposePattern() {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "propose_pattern";
            }

            @Override
            public String description() {
                return """
                        Hand a proposed pattern to the person for authoring: write it UNCOMMITTED into the
                        knowledge base, where the journal's Unpublished Changes panel lists it. THE PANEL IS
                        WHERE THEY REVIEW IT: its change review window shows the meaning, the purpose and each
                        field, and publishes or discards the pattern. Nothing is committed by this tool, and
                        you must never claim the pattern exists: it exists only once the person publishes it.
                        A pattern is its meaning concept, its purpose concept, and an ordered list of fields,
                        each a meaning concept, a purpose concept and a data type (one of the concepts
                        field_data_types lists). Every identifier you pass must be one a tool returned in
                        this conversation; one the knowledge base does not hold refuses the whole proposal.
                        A concept that is itself still unpublished refuses it too: tell the person to publish
                        those concepts first, and pass allow_unpublished=true only when they say to go ahead
                        anyway. Use it only when the person asks to create or author the pattern you showed
                        them; to withdraw it afterwards use discard_pattern_proposal.
                        """;
            }

            @Override
            public Map<String, Object> inputSchema() {
                Map<String, Object> field = obj("type", "object",
                        "properties", obj(
                                "meaning", strProp("The concept the field holds: an SCTID or UUID."),
                                "purpose", strProp("The concept the field is for: an SCTID or UUID."),
                                "data_type", strProp("""
                                        The field's data type: the UUID of one of the concepts field_data_types
                                        lists (a child of Display Fields).""")),
                        "required", List.of("meaning", "purpose", "data_type"));
                return obj("type", "object",
                        "properties", obj(
                                "name", strProp("""
                                        The fully qualified name of the pattern, written the way the knowledge
                                        base's other patterns are named."""),
                                "meaning", strProp("The pattern's meaning concept: an SCTID or UUID."),
                                "purpose", strProp("The pattern's purpose concept: an SCTID or UUID."),
                                "fields", obj("type", "array",
                                        "description", "The field definitions, in order; the order is the pattern's.",
                                        "items", field),
                                "synonyms", obj("type", "array",
                                        "description", """
                                                Synonyms beyond the fully qualified name. If none is preferred,
                                                the name without its semantic tag becomes the preferred synonym.""",
                                        "items", obj("type", "object",
                                                "properties", obj(
                                                        "term", strProp("The synonym text."),
                                                        "acceptability", obj("type", "string",
                                                                "enum", List.of("preferred", "acceptable"),
                                                                "description", "US English acceptability."),
                                                        "case_sensitive", obj("type", "boolean",
                                                                "description", "True when the term's case is significant.")),
                                                "required", List.of("term", "acceptability"))),
                                "editorial_note", strProp("""
                                        Optional rationale for the proposal: what the pattern is for and what
                                        it was modelled on."""),
                                "allow_unpublished", obj("type", "boolean",
                                        "description", """
                                                Optional, default false. True ONLY when the person said to write the
                                                pattern although concepts it names are still unpublished.""")),
                        "required", List.of("name", "meaning", "purpose", "fields"));
            }

            @Override
            public String execute(Map<String, Object> input) {
                try {
                    return propose(input);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    return "Error: " + e.getMessage() + ". Nothing was written.";
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }

    private String propose(Map<String, Object> input) {
        ViewProperties viewProperties = view.get();
        if (viewProperties == null) {
            throw new IllegalStateException("the assistant is not attached to a journal yet, so there is nowhere to write the proposal");
        }
        ViewCalculator calc = viewProperties.calculator();
        String fsn = str(input, "name");
        if (fsn == null || fsn.isBlank()) {
            throw new IllegalArgumentException("the proposal has no name");
        }
        fsn = fsn.strip();
        EntityFacade meaning = concept(calc, str(input, "meaning"), "meaning");
        EntityFacade purpose = concept(calc, str(input, "purpose"), "purpose");
        List<Field> fields = fields(calc, input);
        List<Synonym> synonyms = synonyms(input, "synonyms");
        String note = str(input, "editorial_note");
        List<EntityFacade> unpublished = unpublished(meaning, purpose, fields);
        if (!unpublished.isEmpty() && !bool(input, "allow_unpublished")) {
            List<String> names = new ArrayList<>();
            unpublished.forEach(concept -> names.add(ComponentText.identified(calc, concept.nid())));
            throw new IllegalStateException("""
                    the pattern names concepts that are still unpublished: %s. Ask the person to publish them from the \
                    Unpublished Changes panel first, then propose the pattern; pass allow_unpublished=true only if \
                    they say to write it regardless""".formatted(String.join("; ", names)));
        }
        String name = fsn;
        Draft written = onFxThread(() -> {
            var edit = viewProperties.nodeView().editCoordinate();
            return PatternProposalWriter.write(calc, edit.getAuthorForChanges(), edit.getDefaultModule(),
                    edit.getDefaultPath(), name, meaning, purpose, fields, synonyms, note);
        });
        String id = ComponentText.identifier(written.pattern().nid());
        proposals.put(id, written);
        StringBuilder result = new StringBuilder("""
                Wrote an uncommitted proposal for the pattern "%s" (proposal %s): the pattern with its meaning, \
                its purpose and %d field(s), its fully qualified name, %d synonym(s)"""
                .formatted(fsn, id, fields.size(), synonyms.size()));
        result.append(note == null || note.isBlank() ? "" : ", and an editorial note")
                .append(". NOTHING IS COMMITTED: it becomes a pattern only when the person presses Publish. ")
                .append("""
                        It is listed in the journal's Unpublished Changes panel: tell them it is waiting there, and \
                        that they review it in the panel's change review window (double-click the component to see \
                        its meaning, purpose and fields, then Publish or Discard it). To withdraw it, use \
                        discard_pattern_proposal with this proposal id.""");
        if (!unpublished.isEmpty()) {
            result.append("""
                     WARNING: as the person asked, the pattern names concepts that are still unpublished; tell them \
                    those concepts must be published before or with the pattern.""");
        }
        return result.toString();
    }

    // ── discard_pattern_proposal ────────────────────────────────────────

    private AnthropicTool discardProposal() {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "discard_pattern_proposal";
            }

            @Override
            public String description() {
                return """
                        Withdraw an uncommitted proposal propose_pattern wrote, rolling back everything it wrote.
                        Use it only when the person asks to discard or withdraw that proposal, or to start it
                        over. A proposal they already published is a committed pattern and is left alone.
                        """;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return obj("type", "object",
                        "properties", obj("proposal", strProp("The proposal id propose_pattern returned.")),
                        "required", List.of("proposal"));
            }

            @Override
            public String execute(Map<String, Object> input) {
                String id = str(input, "proposal") == null ? null : str(input, "proposal").strip();
                if (id != null && discarded.contains(id)) {
                    return "Error: proposal " + id + " was already discarded; nothing of it remains";
                }
                Draft draft = id == null ? null : proposals.get(id);
                if (draft == null) {
                    return "Error: no open pattern proposal " + id + " from this assistant";
                }
                try {
                    boolean withdrawn = onFxThread(() -> PatternProposalWriter.discard(draft));
                    proposals.remove(id);
                    if (withdrawn || draft.status() == PatternProposalWriter.Status.DISCARDED) {
                        discarded.add(id);
                    }
                    return withdrawn
                            ? "Discarded proposal %s: nothing of it remains.".formatted(id)
                            : "Proposal %s was already published; it is a committed pattern and was left alone.".formatted(id);
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The field definitions under {@code fields}, each concept resolved. */
    private static List<Field> fields(ViewCalculator calc, Map<String, Object> input) {
        if (!(input.get("fields") instanceof List<?> list)) {
            throw new IllegalArgumentException("the proposal has no fields list");
        }
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("field " + (i + 1) + " is not an object");
            }
            String at = "field " + (i + 1) + " ";
            EntityFacade meaning = concept(calc, text(map.get("meaning")), at + "meaning");
            EntityFacade purpose = concept(calc, text(map.get("purpose")), at + "purpose");
            EntityFacade dataType = concept(calc, text(map.get("data_type")), at + "data_type");
            if (!calc.descendentsOf(KometConcept.DISPLAY_FIELDS).contains(dataType.nid())) {
                throw new IllegalArgumentException(at + "data_type " + ComponentText.identified(calc, dataType.nid())
                        + " is not a field data type; use one of the concepts field_data_types lists");
            }
            fields.add(new Field(meaning, purpose, dataType));
        }
        return fields;
    }

    /**
     * The concept an identifier names, refused when the knowledge base does not hold it or it is
     * not a concept.
     */
    private static EntityFacade concept(ViewCalculator calc, String id, String what) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("the proposal has no " + what);
        }
        String value = id.strip();
        String kind;
        try {
            UUID.fromString(value);
            kind = "uuid";
        } catch (IllegalArgumentException notUuid) {
            kind = value.indexOf(',') >= 0 ? "uuid" : "sctid";
        }
        PublicId publicId = ProposedDefinition.resolve(kind, value);
        if (publicId == null) {
            throw new IllegalArgumentException(what + " " + value + " is not in this knowledge base; every identifier "
                    + "must be one a tool returned in this conversation");
        }
        long nid = PrimitiveData.nid(publicId);
        Entity<?> entity = EntityHandle.get(nid).expectEntity();
        if (!(entity instanceof ConceptEntity<?>)) {
            throw new IllegalArgumentException(what + " " + ComponentText.identified(calc, nid) + " is not a concept");
        }
        return EntityProxy.Concept.make(nid);
    }

    /** The concepts the pattern names that are still uncommitted, each once. */
    private static List<EntityFacade> unpublished(EntityFacade meaning, EntityFacade purpose, List<Field> fields) {
        List<EntityFacade> all = new ArrayList<>(List.of(meaning, purpose));
        fields.forEach(field -> all.addAll(List.of(field.meaning(), field.purpose(), field.dataType())));
        List<EntityFacade> unpublished = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>();
        for (EntityFacade concept : all) {
            if (seen.add(concept.nid()) && EntityHandle.get(concept.nid()).expectEntity().uncommitted()) {
                unpublished.add(concept);
            }
        }
        return unpublished;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return Boolean.TRUE.equals(v) || (v != null && "true".equalsIgnoreCase(v.toString()));
    }
}
