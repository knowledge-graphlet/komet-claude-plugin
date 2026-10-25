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
import dev.ikm.komet.kview.mvvm.view.changes.UnpublishedChange;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelPattern;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.authoring.ConceptChangeWriter.Change;
import network.ike.komet.claude.authoring.ConceptChangeWriter.DefinitionEdited;
import network.ike.komet.claude.authoring.ConceptChangeWriter.DescriptionsEdited;
import network.ike.komet.claude.authoring.ConceptChangeWriter.Status;
import network.ike.komet.claude.authoring.DefinitionOperation.SetSelector;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static network.ike.komet.claude.authoring.ConceptProposalTools.obj;
import static network.ike.komet.claude.authoring.ConceptProposalTools.onFxThread;
import static network.ike.komet.claude.authoring.ConceptProposalTools.str;
import static network.ike.komet.claude.authoring.ConceptProposalTools.strProp;

/**
 * The assistant's changes to existing concepts ({@code IKE-Network/ike-issues#1326}):
 * {@code propose_definition_change} and {@code propose_description_change} write a change
 * uncommitted, {@code discard_change} withdraws one, and {@code pending_changes} reads what is
 * waiting to be published, the person's included.
 *
 * <p>The assistant is a proposer, never a committer. A change waits in the Unpublished Changes
 * tray of the journal, under the concept it is on, where the person reviews each field before and
 * after, then publishes or discards it. Every change the assistant makes to one concept joins
 * the same {@link Change}, and so one transaction, until that is published or discarded.
 */
public final class ConceptChangeTools {

    /** A badge token an identifier may be given as: {@code k:sctid=…[label]}. */
    private static final Pattern TOKEN = Pattern.compile("^k:\\s*(sctid|uuid|id)\\s*=\\s*([^\\[\\]=\\s]+)\\s*(?:\\[[^\\]]*])?$");

    private final Supplier<ViewProperties> view;
    /** The change open on each concept, by the concept's nid. */
    private final Map<Long, Change> changes = new ConcurrentHashMap<>();

    /**
     * @param view the assistant's view, whose edit coordinate changes are written on; may supply
     *             null before the card is bound
     */
    public ConceptChangeTools(Supplier<ViewProperties> view) {
        this.view = view;
    }

    /**
     * The tools to register with the Anthropic client.
     *
     * @return the four tools
     */
    public List<AnthropicTool> tools() {
        return List.of(definitionChange(), descriptionChange(), discardChange(), pendingChanges());
    }

    // ── propose_definition_change ───────────────────────────────────────

    private AnthropicTool definitionChange() {
        Map<String, Object> role = obj("type", "object",
                "properties", obj("type", strProp("The role type: SCTID, UUID or badge token."),
                        "filler", strProp("The role filler: SCTID, UUID or badge token.")),
                "required", List.of("type", "filler"));
        Map<String, Object> operation = obj("type", "object",
                "properties", obj(
                        "op", obj("type", "string", "enum", List.of("add_supertype", "remove_supertype", "replace_supertype",
                                "add_role", "remove_role", "add_group", "remove_group", "add_to_group", "remove_from_group",
                                "set_kind")),
                        "concept", strProp("add_supertype, remove_supertype, replace_supertype: the supertype (for "
                                + "replace_supertype, the one replaced)."),
                        "to", strProp("replace_supertype: the supertype that replaces it."),
                        "role", role,
                        "roles", obj("type", "array", "items", role, "description", "add_group: the new group's roles."),
                        "group", obj("type", "array", "items", role, "description", "remove_group, add_to_group, "
                                + "remove_from_group: exactly the roles the group holds now, which is how it is found."),
                        "set", obj("type", "string", "enum", List.of("necessary", "sufficient"),
                                "description", "add_supertype, add_role, add_group, set_kind: the kind of set, necessary "
                                        + "by default. The first set of a kind is made when there is none."),
                        "ordinal", obj("type", "integer", "description", "Which set of that kind, from 0; 0 by default."),
                        "sufficient", obj("type", "boolean", "description", "set_kind: true makes the set sufficient, "
                                + "false necessary.")),
                "required", List.of("op"));
        return tool("propose_definition_change",
                "Change an EXISTING concept's stated definition, written UNCOMMITTED for the person to review in the "
                        + "Unpublished Changes tray of the journal and Publish or Discard there. Nothing is committed by "
                        + "this tool: never say the definition has changed until they publish it. Give the change "
                        + "EITHER as 'operations', each naming what it changes by its concepts (what no operation names "
                        + "is left exactly as stored), OR as 'definition', the whole koncept-definition block to leave "
                        + "(no proposed: or gap: lines; only the parts that differ are rewritten). A block is refused for a "
                        + "definition holding what the block cannot express (property sets, features, disjoints, "
                        + "universal or nested roles); use operations then. The result shows the definition before and "
                        + "after. Every change to one concept joins the same change, until it is published or discarded. "
                        + "Use it only when the person asks you to make or apply the change.",
                obj("type", "object",
                        "properties", obj(
                                "concept", strProp("The existing concept: SCTID, UUID or badge token."),
                                "operations", obj("type", "array", "items", operation,
                                        "description", "The operations, applied in order."),
                                "definition", strProp("The whole koncept-definition block body to leave, instead of "
                                        + "operations."),
                                "rationale", strProp("Why the change is proposed; kept with the change.")),
                        "required", List.of("concept")),
                this::proposeDefinitionChange);
    }

    private String proposeDefinitionChange(Map<String, Object> input) {
        ViewProperties properties = viewProperties();
        ViewCalculator calc = properties.calculator();
        EntityFacade concept = concept(str(input, "concept"), "the concept");
        String block = str(input, "definition");
        boolean hasOperations = input.get("operations") instanceof List<?> list && !list.isEmpty();
        if ((block == null || block.isBlank()) == !hasOperations) {
            throw new IllegalArgumentException("give either operations or a definition block, not both and not neither");
        }
        List<DefinitionOperation> operations = hasOperations ? definitionOperations((List<?>) input.get("operations")) : null;
        DefinitionEdited edited = onFxThread(() -> {
            Change change = change(properties, concept, str(input, "rationale"));
            return operations != null ? change.editDefinition(operations) : change.replaceDefinition(block);
        });
        String name = badge(calc, concept.nid());
        StringBuilder result = new StringBuilder();
        if (!edited.changed()) {
            result.append("No change: the stated definition of ").append(name).append(" already is what was asked.");
        } else {
            result.append("Wrote an uncommitted change to the stated definition of ").append(name).append(".\n")
                    .append("Before:\n```koncept-definition\n").append(edited.before()).append("```\n")
                    .append("After:\n```koncept-definition\n").append(edited.after()).append("```\n")
                    .append(waiting(calc, concept));
        }
        edited.notes().forEach(note -> result.append("\nNote (tell the person): ").append(note));
        return result.toString();
    }

    private static List<DefinitionOperation> definitionOperations(List<?> items) {
        List<DefinitionOperation> operations = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("an operation is not an object");
            }
            String op = string(map, "op");
            SetSelector set = new SetSelector("sufficient".equalsIgnoreCase(string(map, "set")), integer(map, "ordinal"));
            operations.add(switch (op == null ? "" : op) {
                case "add_supertype" -> new DefinitionOperation.AddSupertype(set, conceptFacade(string(map, "concept")));
                case "remove_supertype" -> new DefinitionOperation.RemoveSupertype(conceptFacade(string(map, "concept")));
                case "replace_supertype" -> new DefinitionOperation.ReplaceSupertype(conceptFacade(string(map, "concept")),
                        conceptFacade(string(map, "to")));
                case "add_role" -> new DefinitionOperation.AddRole(set, role(map.get("role")));
                case "remove_role" -> new DefinitionOperation.RemoveRole(role(map.get("role")));
                case "add_group" -> new DefinitionOperation.AddGroup(set, roles(map.get("roles")));
                case "remove_group" -> new DefinitionOperation.RemoveGroup(roles(map.get("group")));
                case "add_to_group" -> new DefinitionOperation.AddToGroup(roles(map.get("group")), role(map.get("role")));
                case "remove_from_group" -> new DefinitionOperation.RemoveFromGroup(roles(map.get("group")), role(map.get("role")));
                case "set_kind" -> new DefinitionOperation.SetKind(set, bool(map, "sufficient"));
                default -> throw new IllegalArgumentException("unknown operation '" + op + "'");
            });
        }
        return operations;
    }

    // ── propose_description_change ──────────────────────────────────────

    private AnthropicTool descriptionChange() {
        Map<String, Object> operation = obj("type", "object",
                "properties", obj(
                        "op", obj("type", "string", "enum", List.of("retire", "change_text", "change_case_significance",
                                "change_acceptability", "add_synonym", "add_textual_definition")),
                        "description", strProp("retire and the change_ operations: the description semantic's own UUID, "
                                + "as concept_semantics returns it for the Description pattern."),
                        "text", strProp("change_text, add_synonym, add_textual_definition: the text."),
                        "case_sensitive", obj("type", "boolean", "description",
                                "change_case_significance, add_synonym: whether the term's case is significant."),
                        "preferred", obj("type", "boolean", "description",
                                "change_acceptability, add_synonym: preferred (true) or acceptable (false) in US English.")),
                "required", List.of("op"));
        return tool("propose_description_change",
                "Change an EXISTING concept's descriptions, written UNCOMMITTED for the person to review in the "
                        + "Unpublished Changes tray and Publish or Discard there; never say a description has changed "
                        + "until they publish it. Retire a description (its US English acceptability is retired with "
                        + "it), change its text, case significance or acceptability, or add a synonym or a textual "
                        + "definition. Name a description by its own UUID: read the concept's descriptions first with "
                        + "concept_semantics (pattern 'Description'), and their acceptability on each description's UUID. "
                        + "Every operation is checked before anything is written. An edit that retires a synonym, "
                        + "changes an acceptability or adds a synonym must leave exactly one preferred synonym: to change "
                        + "which, demote the old and promote the new in the same call. The last fully specified name is "
                        + "never retired. Use it only when the person asks you to make the change.",
                obj("type", "object",
                        "properties", obj(
                                "concept", strProp("The existing concept: SCTID, UUID or badge token."),
                                "operations", obj("type", "array", "items", operation, "description", "The operations."),
                                "rationale", strProp("Why the change is proposed; kept with the change.")),
                        "required", List.of("concept", "operations")),
                this::proposeDescriptionChange);
    }

    private String proposeDescriptionChange(Map<String, Object> input) {
        ViewProperties properties = viewProperties();
        ViewCalculator calc = properties.calculator();
        EntityFacade concept = concept(str(input, "concept"), "the concept");
        if (!(input.get("operations") instanceof List<?> items) || items.isEmpty()) {
            throw new IllegalArgumentException("give at least one operation");
        }
        List<DescriptionOperation> operations = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("an operation is not an object");
            }
            String op = string(map, "op");
            operations.add(switch (op == null ? "" : op) {
                case "retire" -> new DescriptionOperation.Retire(description(string(map, "description")));
                case "change_text" -> new DescriptionOperation.ChangeText(description(string(map, "description")), string(map, "text"));
                case "change_case_significance" -> new DescriptionOperation.ChangeCaseSignificance(
                        description(string(map, "description")), bool(map, "case_sensitive"));
                case "change_acceptability" -> new DescriptionOperation.ChangeAcceptability(
                        description(string(map, "description")), bool(map, "preferred"));
                case "add_synonym" -> new DescriptionOperation.AddSynonym(string(map, "text"), bool(map, "preferred"),
                        bool(map, "case_sensitive"));
                case "add_textual_definition" -> new DescriptionOperation.AddTextualDefinition(string(map, "text"));
                default -> throw new IllegalArgumentException("unknown operation '" + op + "'");
            });
        }
        DescriptionsEdited edited = onFxThread(() -> change(properties, concept, str(input, "rationale")).editDescriptions(operations));
        String name = badge(calc, concept.nid());
        if (!edited.changed()) {
            return "No change: the descriptions of " + name + " already are what was asked.";
        }
        StringBuilder result = new StringBuilder("Wrote an uncommitted change to the descriptions of ").append(name).append(":");
        edited.changes().forEach(line -> result.append("\n- ").append(line));
        if (!edited.added().isEmpty()) {
            result.append("\nAdded descriptions, by their own UUID: ").append(edited.added().stream()
                    .map(added -> ComponentText.identifier(added.nid())).toList());
        }
        return result.append("\n").append(waiting(calc, concept)).toString();
    }

    // ── discard_change ──────────────────────────────────────────────────

    private AnthropicTool discardChange() {
        return tool("discard_change",
                "Withdraw the uncommitted change you made to an existing concept, its definition and description "
                        + "changes together: the concept is left as it is published. Use it only when the person asks to "
                        + "discard or withdraw your change. A change they already published is left alone.",
                obj("type", "object",
                        "properties", obj("concept", strProp("The concept the change is on: SCTID, UUID or badge token.")),
                        "required", List.of("concept")),
                input -> {
                    ViewCalculator calc = viewProperties().calculator();
                    EntityFacade concept = concept(str(input, "concept"), "the concept");
                    Change change = changes.get(concept.nid());
                    String name = badge(calc, concept.nid());
                    if (change == null) {
                        return "You have no change open on " + name + ".";
                    }
                    Status before = onFxThread(change::status);
                    boolean discarded = onFxThread(() -> ConceptChangeWriter.discard(change));
                    changes.remove(concept.nid());
                    if (discarded) {
                        return "Discarded your uncommitted change to " + name + ": nothing of it remains.";
                    }
                    return switch (before) {
                        case PUBLISHED -> "Your change to " + name + " was already published; it was left alone.";
                        case DISCARDED -> "Your change to " + name + " was already discarded.";
                        default -> "Your change to " + name + " had written nothing.";
                    };
                });
    }

    // ── pending_changes ─────────────────────────────────────────────────

    private AnthropicTool pendingChanges() {
        return tool("pending_changes",
                "List what is waiting to be published: every component with an unpublished version, grouped under the "
                        + "concept or pattern it belongs to, as the Unpublished Changes tray lists it, each marked as "
                        + "yours (a change you proposed) or not (the person's, or another session's). Read it before changing a concept the person "
                        + "may be editing, and to see what they changed after your proposal.",
                obj("type", "object",
                        "properties", obj("concept", strProp("Optional: only the changes under this concept.")),
                        "required", List.of()),
                input -> {
                    ViewCalculator calc = viewProperties().calculator();
                    String filter = str(input, "concept");
                    Long only = filter == null || filter.isBlank() ? null : concept(filter, "the concept").nid();
                    return onFxThread(() -> pending(calc, only));
                });
    }

    private String pending(ViewCalculator calc, Long only) {
        Set<Transaction> mine = new HashSet<>();
        changes.values().forEach(change -> {
            if (change.transaction() != null) {
                mine.add(change.transaction());
            }
        });
        Map<Long, List<String>> byRoot = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        for (Transaction transaction : Transaction.activeTransactions()) {
            transaction.forEachComponentInTransaction(nid -> {
                if (!seen.add(nid)) {
                    return;
                }
                Entity<?> entity = EntityHandle.get(nid).orNull();
                if (entity == null || entity instanceof StampEntity<?> || !entity.uncommitted()) {
                    return;
                }
                UnpublishedChange change = UnpublishedChange.of(entity, calc);
                if (only != null && change.rootNid() != only) {
                    return;
                }
                boolean yours = entity.versions().stream().map(EntityVersion.class::cast)
                        .filter(EntityVersion::uncommitted)
                        .anyMatch(version -> Transaction.forVersion(version).filter(mine::contains).isPresent());
                byRoot.computeIfAbsent(change.rootNid(), root -> new ArrayList<>()).add(
                        change.kind().label() + " " + change.type().label().toLowerCase() + " \"" + change.title() + "\""
                                + (change.type() == UnpublishedChange.Type.SEMANTIC ? " (" + change.subtitle() + ")" : "")
                                + " " + ComponentText.identifier(nid) + (yours ? " — yours" : " — not yours"));
            });
        }
        if (byRoot.isEmpty()) {
            return only == null ? "Nothing is waiting to be published." : "Nothing is waiting to be published under "
                    + badge(calc, only) + ".";
        }
        StringBuilder result = new StringBuilder("Waiting to be published (the Unpublished Changes tray):");
        byRoot.forEach((root, lines) -> {
            result.append("\n").append(badge(calc, root));
            lines.forEach(line -> result.append("\n- ").append(line));
        });
        return result.toString();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The change open on the concept, or a new one when there is none or it ended. Runs on the FX thread. */
    private Change change(ViewProperties properties, EntityFacade concept, String rationale) {
        Change open = changes.get(concept.nid());
        if (open != null && (open.status() == Status.EMPTY || open.status() == Status.OPEN)) {
            return open;
        }
        var edit = properties.nodeView().editCoordinate();
        Change change = ConceptChangeWriter.open(properties.calculator(), edit.getAuthorForChanges(),
                edit.getDefaultModule(), edit.getDefaultPath(), concept, rationale);
        changes.put(concept.nid(), change);
        return change;
    }

    private static String waiting(ViewCalculator calc, EntityFacade concept) {
        return "NOTHING IS COMMITTED: the change waits in the Unpublished Changes tray of the journal, under "
                + ComponentText.name(calc, concept.nid()) + ", where the Change Review window shows each field before "
                + "and after. Tell the person it is there for them to Publish or Discard.";
    }

    private ViewProperties viewProperties() {
        ViewProperties properties = view.get();
        if (properties == null) {
            throw new IllegalStateException("the assistant is not attached to a view yet");
        }
        return properties;
    }

    /** The existing concept an identifier names: an SCTID, a UUID, or a badge token. */
    private static ConceptFacade concept(String id, String what) {
        PublicId pid = publicId(id, what);
        long nid = EntityProxy.Concept.make(pid).nid();
        if (!(EntityHandle.get(nid).orNull() instanceof ConceptEntity<?>)) {
            throw new IllegalArgumentException(what + " " + id + " is not a concept");
        }
        return EntityProxy.Concept.make(nid);
    }

    private static ConceptFacade conceptFacade(String id) {
        return concept(id, "the concept");
    }

    /** The description semantic a UUID names. */
    private static EntityFacade description(String id) {
        PublicId pid = publicId(id, "the description");
        long nid = EntityProxy.Semantic.make(pid).nid();
        if (!(EntityHandle.get(nid).orNull() instanceof SemanticEntity<?> semantic)
                || semantic.patternNid() != KernelPattern.DESCRIPTION_PATTERN.nid()) {
            throw new IllegalArgumentException("the description " + id + " is not a description semantic; give the UUID "
                    + "concept_semantics returns for it");
        }
        return EntityProxy.Semantic.make(nid);
    }

    private static PublicId publicId(String id, String what) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(what + " is missing");
        }
        String value = id.strip();
        String kind = value.contains("-") ? "uuid" : "sctid";
        Matcher token = TOKEN.matcher(value);
        if (token.matches()) {
            kind = token.group(1);
            value = token.group(2);
        }
        PublicId pid = ProposedDefinition.resolve(kind, value);
        if (pid == null) {
            throw new IllegalArgumentException(what + " " + id + " is not in this knowledge base");
        }
        return pid;
    }

    private static DefinitionOperation.Role role(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("a role needs a type and a filler");
        }
        return new DefinitionOperation.Role(conceptFacade(string(map, "type")), conceptFacade(string(map, "filler")));
    }

    private static List<DefinitionOperation.Role> roles(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("a group needs its roles");
        }
        return list.stream().map(ConceptChangeTools::role).toList();
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean bool(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static int integer(Map<?, ?> map, String key) {
        return map.get(key) instanceof Number number ? number.intValue() : 0;
    }

    private static String badge(ViewCalculator calc, long nid) {
        return ComponentText.badge(calc, nid).orElseGet(() -> ComponentText.identified(calc, nid));
    }

    /** A tool whose refusals come back to the model as errors. */
    private static AnthropicTool tool(String name, String description, Map<String, Object> schema,
                                      Function<Map<String, Object>, String> body) {
        return new AnthropicTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return schema;
            }

            @Override
            public String execute(Map<String, Object> input) {
                try {
                    return body.apply(input);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    return "Error: " + e.getMessage();
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }
}
