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
import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.component.FieldDataType;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.FieldDefinitionForEntity;
import dev.ikm.tinkar.entity.PatternEntity;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.PatternFacade;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.authoring.SemanticProposalWriter.Draft;
import network.ike.komet.claude.koncept.ComponentText;
import network.ike.komet.claude.semantic.FieldFamily;
import org.eclipse.collections.api.list.ImmutableList;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

/**
 * The assistant's semantic hand-off {@code propose_semantic} writes a proposed semantic
 * uncommitted into the store, where the journal's Unpublished Changes panel lists it under the
 * component it is on, and {@code discard_semantic_proposal} withdraws one the person does not
 * want ({@code ikmdev/komet-desktop#210}).
 *
 * <p>A semantic is an instance of a pattern on a reference component, with one value per field
 * of the pattern. The pattern, the reference component and every component a field names are
 * resolved here against the open knowledge base; an identifier it does not hold refuses the whole
 * proposal, and nothing is written. Each value is judged against the pattern's field definition:
 * a component field takes one identifier of the kind the field allows, a component set or list
 * takes identifiers, and a literal field takes a value of its type. A field without a value
 * refuses the proposal: a semantic has a value for every field, and the assistant asks the person
 * rather than guessing. A pattern or component that is itself still unpublished refuses the
 * proposal too, unless the person has said to go ahead regardless.
 *
 * <p>The reference component may be a concept, a pattern or another semantic, since a semantic on
 * a semantic is how one record qualifies another (an Allowed Results record on a Test Performed
 * record, say).
 *
 * <p>The assistant is a proposer, never a committer: the person reviews the semantic from the
 * Unpublished Changes panel, whose change review window shows its fields, and commits it with
 * Publish.
 */
public final class SemanticProposalTools {

    private final Supplier<ViewProperties> view;

    /** The proposals this assistant wrote and has not seen discarded, by proposal id. */
    private final Map<String, Draft> proposals = new ConcurrentHashMap<>();

    /** The proposals this assistant discarded, so a later call is told so rather than not found. */
    private final Set<String> discarded = ConcurrentHashMap.newKeySet();

    /**
     * @param view the assistant's view, whose edit coordinate proposals are authored on; may
     *             supply null before the card is bound
     */
    public SemanticProposalTools(Supplier<ViewProperties> view) {
        this.view = view;
    }

    /**
     * The tools to register with the Anthropic client.
     *
     * @return the two tools
     */
    public List<AnthropicTool> tools() {
        return List.of(proposeSemantic(), discardProposal());
    }

    // ── propose_semantic ────────────────────────────────────────────────

    private AnthropicTool proposeSemantic() {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "propose_semantic";
            }

            @Override
            public String description() {
                return """
                        Hand a proposed semantic to the person for authoring: write it UNCOMMITTED into the
                        knowledge base, where the journal's Unpublished Changes panel lists it under the
                        component it is on. THE PANEL IS WHERE THEY REVIEW IT: its change review window shows
                        each field's value, and publishes or discards the semantic. Nothing is committed by
                        this tool, and you must never claim the semantic exists: it exists only once the
                        person publishes it. A semantic is an instance of a pattern on a reference component
                        (a concept, a pattern or another semantic), with ONE VALUE PER FIELD of the pattern,
                        in the order `pattern` lists the fields: a component field takes the identifier of a
                        component of the kind the field allows, a component set or list field takes an array
                        of identifiers, and a string, integer, float or boolean field takes a value of that
                        type. Every field must have a value; when the person has not given one, ask them, never
                        guess or leave it out. Every identifier you pass must be one a tool returned in this
                        conversation; one the knowledge base does not hold refuses the whole proposal. A
                        pattern or component that is itself still unpublished refuses it too: tell the person
                        to publish those first, and pass allow_unpublished=true only when they say to go ahead
                        anyway. Use it only when the person asks to create or author the semantic you showed
                        them; to withdraw it afterwards use discard_semantic_proposal.
                        """;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return obj("type", "object",
                        "properties", obj(
                                "pattern", strProp("The pattern the semantic is an instance of: its UUID."),
                                "reference_component", strProp("""
                                        The component the semantic is on: the SCTID or UUID of a concept, a pattern
                                        or a semantic."""),
                                "fields", obj("type", "array",
                                        "description", """
                                                One value per field of the pattern, in the pattern's field order,
                                                exactly as many as the pattern has. A component field: the SCTID or
                                                UUID of the component (a string). A component set or list field: an
                                                array of SCTIDs or UUIDs. A string field: the text. An integer or
                                                float field: the number. A boolean field: true or false.""",
                                        "items", obj("description", """
                                                The field's value: an identifier string, an array of identifier
                                                strings, a string, a number or a boolean, as the field's data type
                                                requires.""")),
                                "editorial_note", strProp("""
                                        Optional rationale for the proposal: what the semantic records and where
                                        its values came from."""),
                                "allow_unpublished", obj("type", "boolean",
                                        "description", """
                                                Optional, default false. True ONLY when the person said to write the
                                                semantic although its pattern or a component it names is still
                                                unpublished.""")),
                        "required", List.of("pattern", "reference_component", "fields"));
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
        Entity<?> patternEntity = component(calc, str(input, "pattern"), "pattern");
        if (!(patternEntity instanceof PatternEntity<?>)) {
            throw new IllegalArgumentException("pattern " + ComponentText.identified(calc, patternEntity.nid())
                    + " is not a pattern; find the pattern with search and read it with the pattern tool");
        }
        PatternFacade pattern = EntityProxy.Pattern.make(patternEntity.nid());
        Entity<?> referencedEntity = component(calc, str(input, "reference_component"), "reference_component");
        EntityFacade referenced = proxy(referencedEntity);
        ImmutableList<? extends FieldDefinitionForEntity> definitions = fieldDefinitions(calc, patternEntity);
        List<Entity<?>> named = new ArrayList<>();
        List<Object> values = values(calc, input, definitions, named);
        String note = str(input, "editorial_note");
        List<Entity<?>> unpublished = unpublished(patternEntity, referencedEntity, named);
        if (!unpublished.isEmpty() && !bool(input, "allow_unpublished")) {
            List<String> names = new ArrayList<>();
            unpublished.forEach(entity -> names.add(ComponentText.identified(calc, entity.nid())));
            throw new IllegalStateException("""
                    the semantic names components that are still unpublished: %s. Ask the person to publish them from \
                    the Unpublished Changes panel first, then propose the semantic; pass allow_unpublished=true only \
                    if they say to write it regardless""".formatted(String.join("; ", names)));
        }
        Draft written = onFxThread(() -> {
            var edit = viewProperties.nodeView().editCoordinate();
            return SemanticProposalWriter.write(calc, edit.getAuthorForChanges(), edit.getDefaultModule(),
                    edit.getDefaultPath(), referenced, pattern, values, note);
        });
        String id = ComponentText.identifier(written.semantic().nid());
        proposals.put(id, written);
        String patternName = ComponentText.preferredName(calc, pattern.nid());
        String referencedName = ComponentText.identified(calc, referenced.nid());
        StringBuilder result = new StringBuilder("""
                Wrote an uncommitted proposal for a "%s" semantic on %s (proposal %s) with %d field value(s)"""
                .formatted(patternName, referencedName, id, values.size()));
        result.append(note == null || note.isBlank() ? "" : ", and an editorial note")
                .append(". NOTHING IS COMMITTED: it becomes a semantic only when the person presses Publish. ")
                .append("""
                        It is listed in the journal's Unpublished Changes panel under the component it is on: tell \
                        them it is waiting there, and that they review it in the panel's change review window \
                        (double-click the component to see each field's value, then Publish or Discard it). To \
                        withdraw it, use discard_semantic_proposal with this proposal id.""");
        if (!unpublished.isEmpty()) {
            result.append("""
                     WARNING: as the person asked, the semantic names components that are still unpublished; tell \
                    them those must be published before or with the semantic.""");
        }
        return result.toString();
    }

    // ── discard_semantic_proposal ───────────────────────────────────────

    private AnthropicTool discardProposal() {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "discard_semantic_proposal";
            }

            @Override
            public String description() {
                return """
                        Withdraw an uncommitted proposal propose_semantic wrote, rolling back everything it wrote.
                        Use it only when the person asks to discard or withdraw that proposal, or to start it
                        over. A proposal they already published is a committed semantic and is left alone.
                        """;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return obj("type", "object",
                        "properties", obj("proposal", strProp("The proposal id propose_semantic returned.")),
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
                    return "Error: no open semantic proposal " + id + " from this assistant";
                }
                try {
                    boolean withdrawn = onFxThread(() -> SemanticProposalWriter.discard(draft));
                    proposals.remove(id);
                    if (withdrawn || draft.status() == SemanticProposalWriter.Status.DISCARDED) {
                        discarded.add(id);
                    }
                    return withdrawn
                            ? "Discarded proposal %s: nothing of it remains.".formatted(id)
                            : "Proposal %s was already published; it is a committed semantic and was left alone.".formatted(id);
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The pattern's field definitions on the view, or from its latest version when the view does not see it yet. */
    private static ImmutableList<? extends FieldDefinitionForEntity> fieldDefinitions(ViewCalculator calc,
                                                                                      Entity<?> pattern) {
        Latest<PatternEntityVersion> latest = calc.latestPatternEntityVersion(pattern.nid());
        if (latest.isPresent()) {
            return latest.get().fieldDefinitions();
        }
        return ((PatternEntityVersion) pattern.versions().getLast()).fieldDefinitions();
    }

    /**
     * The values under {@code fields}, each converted to the form the store holds for its field's
     * data type, with every component a field names added to {@code named}.
     */
    private static List<Object> values(ViewCalculator calc, Map<String, Object> input,
                                       ImmutableList<? extends FieldDefinitionForEntity> definitions,
                                       List<Entity<?>> named) {
        if (!(input.get("fields") instanceof List<?> list)) {
            throw new IllegalArgumentException("the proposal has no fields list");
        }
        if (list.size() != definitions.size()) {
            throw new IllegalArgumentException("the pattern has " + definitions.size() + " field(s) but " + list.size()
                    + " value(s) were given; give one value per field, in the pattern's field order");
        }
        List<Object> values = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            FieldDefinitionForEntity definition = definitions.get(i);
            String at = "field " + (i + 1) + " (" + ComponentText.preferredName(calc, definition.meaningNid()) + ")";
            Object raw = list.get(i);
            if (raw == null) {
                throw new IllegalArgumentException(at + " has no value; ask the person for it rather than guessing");
            }
            values.add(value(calc, definition, raw, at, named));
        }
        return values;
    }

    /** One field's value in the form the store holds for the field's data type. */
    private static Object value(ViewCalculator calc, FieldDefinitionForEntity definition, Object raw, String at,
                                List<Entity<?>> named) {
        FieldDataType dataType = definition.fieldDataType();
        FieldFamily family = FieldFamily.of(dataType);
        if (family.isComponentReference()) {
            if (raw instanceof List<?>) {
                throw new IllegalArgumentException(at + " takes one component identifier, not an array");
            }
            Entity<?> entity = component(calc, raw.toString(), at);
            checkKind(calc, entity, family, at);
            named.add(entity);
            return proxy(entity);
        }
        if (family.isComponentCollection()) {
            if (!(raw instanceof List<?> ids)) {
                throw new IllegalArgumentException(at + " takes an array of component identifiers");
            }
            Set<Long> nids = new LinkedHashSet<>();
            for (Object id : ids) {
                if (id == null) {
                    throw new IllegalArgumentException(at + " has an empty member");
                }
                Entity<?> entity = component(calc, id.toString(), at);
                named.add(entity);
                nids.add(entity.nid());
            }
            long[] array = nids.stream().mapToLong(Long::longValue).toArray();
            return family == FieldFamily.COMPONENT_SET ? LongIds.set.of(array) : LongIds.list.of(array);
        }
        return switch (family) {
            case STRING -> {
                if (raw instanceof List<?>) {
                    throw new IllegalArgumentException(at + " takes a string, not an array");
                }
                yield raw.toString();
            }
            case INTEGER -> {
                long value = integer(raw, at);
                if (dataType == FieldDataType.LONG) {
                    yield value;
                }
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(at + " takes an integer; " + value + " is out of range");
                }
                yield (int) value;
            }
            case FLOAT -> {
                BigDecimal value = decimal(raw, at);
                yield dataType == FieldDataType.DECIMAL ? value : (Object) value.floatValue();
            }
            case BOOLEAN -> {
                if (raw instanceof Boolean b) {
                    yield b;
                }
                String text = raw.toString().strip();
                if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
                    yield Boolean.parseBoolean(text);
                }
                throw new IllegalArgumentException(at + " takes true or false");
            }
            default -> throw new IllegalArgumentException(at + " has data type "
                    + ComponentText.preferredName(calc, definition.dataTypeNid())
                    + ", which this tool cannot write; the person authors that field in Komet");
        };
    }

    private static long integer(Object raw, String at) {
        if (raw instanceof Number number) {
            double d = number.doubleValue();
            if (d != Math.rint(d)) {
                throw new IllegalArgumentException(at + " takes an integer, not " + raw);
            }
            return number.longValue();
        }
        try {
            return Long.parseLong(raw.toString().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(at + " takes an integer, not \"" + raw + "\"");
        }
    }

    private static BigDecimal decimal(Object raw, String at) {
        if (raw instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(raw.toString().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(at + " takes a number, not \"" + raw + "\"");
        }
    }

    /** Refuses a component of a kind the field does not allow. */
    private static void checkKind(ViewCalculator calc, Entity<?> entity, FieldFamily family, String at) {
        boolean allowed = switch (family) {
            case CONCEPT_REF -> entity instanceof ConceptEntity<?>;
            case SEMANTIC_REF -> entity instanceof SemanticEntity<?>;
            case PATTERN_REF -> entity instanceof PatternEntity<?>;
            default -> true;
        };
        if (!allowed) {
            String kind = switch (family) {
                case CONCEPT_REF -> "a concept";
                case SEMANTIC_REF -> "a semantic";
                case PATTERN_REF -> "a pattern";
                default -> "a component";
            };
            throw new IllegalArgumentException(at + " takes " + kind + "; " + ComponentText.identified(calc, entity.nid())
                    + " is not one");
        }
    }

    /** The proxy of a component's own kind, the form the field controls hold. */
    private static EntityFacade proxy(Entity<?> entity) {
        return switch (entity) {
            case ConceptEntity<?> concept -> EntityProxy.Concept.make(concept.nid());
            case PatternEntity<?> pattern -> EntityProxy.Pattern.make(pattern.nid());
            case SemanticEntity<?> semantic -> EntityProxy.Semantic.make(semantic.nid());
            default -> throw new IllegalArgumentException(entity + " is neither a concept, a pattern nor a semantic");
        };
    }

    /**
     * The component an identifier names, refused when the knowledge base does not hold it.
     */
    private static Entity<?> component(ViewCalculator calc, String id, String what) {
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
        Entity<?> entity = EntityHandle.get(nid).orNull();
        if (entity == null) {
            throw new IllegalArgumentException(what + " " + value + " is not a component in this knowledge base");
        }
        if (!(entity instanceof ConceptEntity<?> || entity instanceof PatternEntity<?> || entity instanceof SemanticEntity<?>)) {
            throw new IllegalArgumentException(what + " " + ComponentText.identified(calc, nid)
                    + " is neither a concept, a pattern nor a semantic");
        }
        return entity;
    }

    /** The components the semantic names that are still uncommitted, each once. */
    private static List<Entity<?>> unpublished(Entity<?> pattern, Entity<?> referenced, List<Entity<?>> named) {
        List<Entity<?>> all = new ArrayList<>(List.of(pattern, referenced));
        all.addAll(named);
        List<Entity<?>> unpublished = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>();
        for (Entity<?> entity : all) {
            if (seen.add(entity.nid()) && entity.uncommitted()) {
                unpublished.add(entity);
            }
        }
        return unpublished;
    }

    private static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return Boolean.TRUE.equals(v) || (v != null && "true".equalsIgnoreCase(v.toString()));
    }
}
