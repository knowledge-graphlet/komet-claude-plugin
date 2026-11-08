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
package network.ike.komet.claude.tools;

import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.Nid;
import dev.ikm.tinkar.common.id.LongIdCollection;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.PrimitiveDataSearchResult;
import dev.ikm.tinkar.common.service.RemoteConceptSearchService;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import dev.ikm.tinkar.coordinate.stamp.StateSet;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.stamp.calculator.StampCalculatorWithCache;
import dev.ikm.tinkar.coordinate.view.ViewCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.FieldDefinitionForEntity;
import dev.ikm.tinkar.entity.PatternEntity;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.provider.grpc.GrpcSearchService;
import dev.ikm.tinkar.provider.search.Searcher;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import network.ike.komet.claude.anf.AnfValue;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.document.ComponentText;
import network.ike.komet.claude.koncept.DefinitionText;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Read-only knowledge-graph {@link AnthropicTool}s backed by a live
 * {@link ViewCalculator}. These let Claude resolve exact concept identifiers,
 * navigate the SNOMED taxonomy (inferred), read logical axioms, and search —
 * instead of transcribing codes from memory.
 *
 * <p>The calculator is obtained per call from a {@link Supplier}, so each tool
 * invocation reflects the assistant window's current view coordinate. Nothing
 * here mutates the knowledge base.
 *
 * <p>Identifiers accepted on input are an SCTID (resolved via the SNOMED type-5
 * UUID) or a concept UUID. Results render {@code name [SCTID …]} when the concept
 * carries a SNOMED identifier semantic, falling back to {@code name [UUID]}.
 */
public final class GraphTools {

    private static final int DEFAULT_LIMIT = 50;

    private final Supplier<ViewCalculator> viewSupplier;
    private final Consumer<AnfValue> onDiscovered;

    /**
     * Creates the tool set with no concept-discovery callback.
     *
     * @param viewSupplier supplies the current {@link ViewCalculator} (the
     *                     active window's view); must not be null
     */
    public GraphTools(Supplier<ViewCalculator> viewSupplier) {
        this(viewSupplier, conceptValue -> {
        });
    }

    /**
     * Creates the tool set that reports each concept it confirms.
     *
     * @param viewSupplier supplies the current {@link ViewCalculator}; must not be null
     * @param onDiscovered receives a grounded value whenever the {@code concept} tool
     *                     confirms a concept, so a UI can show a live inventory; a
     *                     null callback is treated as a no-op
     */
    public GraphTools(Supplier<ViewCalculator> viewSupplier, Consumer<AnfValue> onDiscovered) {
        this.viewSupplier = Objects.requireNonNull(viewSupplier, "viewSupplier");
        this.onDiscovered = (onDiscovered == null) ? conceptValue -> {
        } : onDiscovered;
    }

    /**
     * The read-only tools to register with the Anthropic client.
     *
     * @return an immutable list of tools
     */
    public List<AnthropicTool> tools() {
        return List.of(concept(), children(), parents(), descendants(),
                ancestors(), isA(), axioms(), search(), viewInfo(), debugConcept(),
                conceptSemantics(), semanticInfo(), referencingSemantics(), pattern(), fieldDataTypes(),
                changeHistory());
    }

    // ── Tools ───────────────────────────────────────────────────────────

    /**
     * A pattern's definition: its meaning, its purpose, and each field's meaning, purpose and data
     * type in order. The identifiers it returns are what a proposed pattern is modelled on, and what
     * {@code propose_pattern} takes.
     */
    private AnthropicTool pattern() {
        return tool("pattern",
                """
                        Read a pattern by its UUID: its name, meaning, purpose, and each field's meaning, \
                        purpose and data type, in order, every concept with its identifier. Find a \
                        pattern with `search` first. Call this to model a new pattern on existing ones \
                        and to confirm the concepts a pattern names before you reuse them.
                        """,
                objectSchema(Map.of("id", strProp("the pattern's UUID")), List.of("id")),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    long nid = resolve(str(in, "id"), v);
                    if (Nid.isNone(nid)) {
                        return notFound(str(in, "id"));
                    }
                    Latest<PatternEntityVersion> latest = v.latestPatternEntityVersion(nid);
                    if (!latest.isPresent()) {
                        return nameAndId(v, nid) + " is not a pattern in this view.";
                    }
                    PatternEntityVersion version = latest.get();
                    StringBuilder sb = new StringBuilder(nameAndId(v, nid)).append('\n')
                            .append("Meaning: ").append(nameAndId(v, version.semanticMeaningNid())).append('\n')
                            .append("Purpose: ").append(nameAndId(v, version.semanticPurposeNid())).append('\n')
                            .append("Fields:\n");
                    var definitions = version.fieldDefinitions();
                    for (int i = 0; i < definitions.size(); i++) {
                        FieldDefinitionForEntity field = definitions.get(i);
                        sb.append("  ").append(i + 1).append(". meaning ").append(nameAndId(v, field.meaningNid()))
                                .append("; purpose ").append(nameAndId(v, field.purposeNid()))
                                .append("; data type ").append(nameAndId(v, field.dataTypeNid())).append('\n');
                    }
                    if (definitions.isEmpty()) {
                        sb.append("  (none)\n");
                    }
                    return sb.append('[').append(definitions.size()).append(" fields]").toString();
                });
    }

    /** The data types a pattern field may have: the children of Komet's "Display Fields". */
    private AnthropicTool fieldDataTypes() {
        return tool("field_data_types",
                """
                        List the data types a pattern field may have, each with its identifier: the choices \
                        Komet's pattern window offers. A field's data_type in `propose_pattern` must be \
                        one of these.
                        """,
                objectSchema(Map.of(), List.of()),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    long root = KometConcept.DISPLAY_FIELDS.nid();
                    return nameAndId(v, root) + "\n" + renderIds(v, v.childrenOf(root).longStream().toArray(), DEFAULT_LIMIT);
                });
    }

    private AnthropicTool concept() {
        return tool("concept",
                "Resolve a concept by SCTID or UUID and return its fully specified name, "
                        + "identifier, and immediate parents. Call this to confirm the exact "
                        + "concept (and its code) before using it in an answer.",
                idSchema(),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    long nid = resolve(str(in, "id"), v);
                    if (Nid.isNone(nid)) {
                        return notFound(str(in, "id"));
                    }
                    // Active-only grounding (#739): a retired concept is reported but NOT discovered
                    // into the inventory, and the model is told not to ground to it.
                    boolean active = isActive(v, toConceptNid(nid));
                    if (active) {
                        fireDiscovered(groundedOf(v, nid));
                    }
                    String retiredNote = active ? ""
                            : "\n(INACTIVE — retired in this view; do not ground to this concept; "
                              + "find an ACTIVE concept or propose a candidate)";
                    return nameAndId(v, nid) + retiredNote + "\nParents:\n"
                            + renderIds(v, v.parentsOf(nid).longStream().toArray(), DEFAULT_LIMIT);
                });
    }

    private AnthropicTool children() {
        return navTool("children",
                "List the immediate (inferred) children of a concept, each with its identifier.");
    }

    private AnthropicTool parents() {
        return navTool("parents",
                "List the immediate (inferred) parents of a concept, each with its identifier.");
    }

    private AnthropicTool ancestors() {
        return navTool("ancestors",
                "List the transitive (inferred) ancestors of a concept (the >> set).");
    }

    private AnthropicTool descendants() {
        return navTool("descendants",
                "List the transitive (inferred) descendants of a concept (the << set, minus self) "
                        + "— the membership for a value set.");
    }

    private AnthropicTool isA() {
        return tool("is_a",
                "Test whether one concept is-a (a descendant of, inferred) another. "
                        + "Args: child and parent, each an SCTID or UUID.",
                objectSchema(Map.of(
                        "child", strProp("the candidate subtype, as SCTID or UUID"),
                        "parent", strProp("the candidate supertype, as SCTID or UUID")),
                        List.of("child", "parent")),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    long childNid = resolve(str(in, "child"), v);
                    long parentNid = resolve(str(in, "parent"), v);
                    if (Nid.isNone(childNid)) {
                        return notFound(str(in, "child"));
                    }
                    if (Nid.isNone(parentNid)) {
                        return notFound(str(in, "parent"));
                    }
                    boolean isa = childNid == parentNid
                            || v.ancestorsOf(childNid).longStream().anyMatch(n -> n == parentNid);
                    return (isa ? "YES — " : "NO — ")
                            + nameAndId(v, childNid) + (isa ? " is-a " : " is NOT a ") + nameAndId(v, parentNid);
                });
    }

    private AnthropicTool axioms() {
        return tool("axioms",
                "Show the logical definition (EL++ axioms / role groups) of a concept — both its "
                        + "stated form (as authored) and its inferred form (after classification). "
                        + "Every concept the definition refers to — supertype, role type, role "
                        + "filler — carries its identifier, as `name [SCTID x]` or `name [UUID x]`, "
                        + "so it can be badged or passed to another tool without a search. "
                        + "Call this to read how a concept is defined, and on a concept's nearest "
                        + "peers before proposing a definition modelled on them.",
                idSchema(),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    long nid = resolve(str(in, "id"), v);
                    if (Nid.isNone(nid)) {
                        return notFound(str(in, "id"));
                    }
                    Latest<DiTreeEntity> stated = v.logicCalculator()
                            .getStatedLogicalExpressionForEntity(nid, v.stampCalculator());
                    Latest<DiTreeEntity> inferred = v.logicCalculator()
                            .getInferredLogicalExpressionForEntity(nid, v.stampCalculator());
                    StringBuilder sb = new StringBuilder(nameAndId(v, nid)).append('\n');
                    sb.append("Stated:\n").append(stated.isPresent()
                            ? DefinitionText.tree(stated.get(), v)
                            : "  (none on this view)").append('\n');
                    sb.append("Inferred:\n").append(inferred.isPresent()
                            ? DefinitionText.tree(inferred.get(), v)
                            : "  (none on this view — the concept may be primitive, or classification "
                              + "is not on this view's path; call view_info to see the active coordinate)");
                    return sb.toString();
                });
    }

    private AnthropicTool viewInfo() {
        return tool("view_info",
                "Report the active view coordinate this assistant is querying — its path, modules, "
                        + "time, language, and navigation premise. Call this when navigation or axioms "
                        + "come back empty for a concept you can see in Komet's own panels: the panels "
                        + "and this assistant may be resolving against different paths or premises, and "
                        + "this shows exactly which coordinate the tools use.",
                objectSchema(Map.of(), List.of()),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    try {
                        return "Active view coordinate:\n" + v.viewCoordinateRecord().toUserString();
                    } catch (RuntimeException e) {
                        return "Active view coordinate:\n" + VIEW_NOT_DESCRIBED;
                    }
                });
    }

    private AnthropicTool debugConcept() {
        return tool("debug_concept",
                "DIAGNOSTIC. For one concept, dump (a) the raw navigation + axiom semantics straight from "
                        + "the store under the standard patterns, (b) what THIS view's calculator returns, and "
                        + "(c) the view's coordinate. Use when parents or axioms come back empty for a concept "
                        + "visible in Komet's panels — it proves whether the data is missing or this view's "
                        + "coordinate is wrong (e.g. the assistant is on the default view, not the journal view).",
                idSchema(),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    long nid = resolve(str(in, "id"), v);
                    if (Nid.isNone(nid)) {
                        return notFound(str(in, "id"));
                    }
                    EntityService es = EntityService.get();
                    StringBuilder sb = new StringBuilder(nameAndId(v, nid)).append("\n\n");

                    List<SemanticEntity<SemanticEntityVersion>> rawInfNav = es.semanticsForComponentOfPattern(
                            nid, KernelPattern.INFERRED_NAVIGATION_PATTERN.nid()).toList();
                    long rawStaNav = es.semanticsForComponentOfPattern(
                            nid, KernelPattern.STATED_NAVIGATION_PATTERN.nid()).count();
                    long rawInfAx = es.semanticsForComponentOfPattern(
                            nid, KernelPattern.EL_PLUS_PLUS_INFERRED_AXIOMS_PATTERN.nid()).count();
                    long rawStaAx = es.semanticsForComponentOfPattern(
                            nid, KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid()).count();
                    sb.append("RAW store semantics (no view filter):\n")
                            .append("  inferred-nav: ").append(rawInfNav.size()).append('\n')
                            .append("  stated-nav: ").append(rawStaNav).append('\n')
                            .append("  inferred-axiom: ").append(rawInfAx).append('\n')
                            .append("  stated-axiom: ").append(rawStaAx).append('\n');
                    if (!rawInfNav.isEmpty()) {
                        Latest<SemanticEntityVersion> latest = v.stampCalculator().latest(rawInfNav.getFirst());
                        sb.append("  inferred-nav[0] latest present under THIS view's STAMP? ")
                                .append(latest.isPresent()).append('\n');
                    }

                    int parents = v.parentsOf(nid).size();
                    Latest<DiTreeEntity> infAx = v.logicCalculator()
                            .getInferredLogicalExpressionForEntity(nid, v.stampCalculator());
                    Latest<DiTreeEntity> staAx = v.logicCalculator()
                            .getStatedLogicalExpressionForEntity(nid, v.stampCalculator());
                    sb.append("\nTHIS view's calculator returns:\n")
                            .append("  parents: ").append(parents).append('\n')
                            .append("  inferred axioms present: ").append(infAx.isPresent()).append('\n')
                            .append("  stated axioms present: ").append(staAx.isPresent()).append('\n');

                    ViewCoordinateRecord vc = v.viewCoordinateRecord();
                    sb.append("\nView coordinate:\n");
                    try {
                        sb.append(vc.toUserString()).append('\n');
                    } catch (RuntimeException e) {
                        sb.append(VIEW_NOT_DESCRIBED).append('\n');
                    }

                    sb.append("\nVERDICT: ");
                    if ((!rawInfNav.isEmpty() && parents == 0) || (rawInfAx > 0 && !infAx.isPresent())) {
                        sb.append("RAW DATA EXISTS BUT THIS VIEW RETURNS EMPTY — the tools are on the wrong view "
                                + "coordinate (its STAMP path/module filter excludes the loaded data; likely the "
                                + "DEFAULT view, not the journal view).");
                    } else if (rawInfNav.isEmpty() && rawInfAx == 0) {
                        sb.append("No raw nav/axiom data under the standard patterns — data genuinely absent for "
                                + "this concept.");
                    } else {
                        sb.append("This view matches the raw data — coordinate is correct.");
                    }
                    return sb.toString();
                });
    }

    /** Max semantics rendered by {@link #conceptSemantics} before the rest are summarized away. */
    private static final int MAX_SEMANTICS = 25;

    private AnthropicTool conceptSemantics() {
        return tool("concept_semantics",
                "List the semantics (pattern instances) attached to a concept — each with its "
                        + "pattern name, its own UUID (written `semantic [UUID]`, the id `semantic_info` "
                        + "takes), and its named field values. This is how you "
                        + "read structured data hanging off a concept: Test Performed records "
                        + "(analyte, specimen, limit of detection, units), identifiers, comments, and "
                        + "so on. Call this after resolving a concept when the question is about "
                        + "attached data rather than the taxonomy. Pass 'pattern' to return only "
                        + "semantics whose pattern name contains that text — do this when you know "
                        + "which pattern you want, since concepts often carry many semantics. "
                        + "IMPORTANT: semantics can themselves carry semantics, so you can pass a "
                        + "semantic's own UUID here to see what is attached to IT. For example, the "
                        + "allowed/acceptable result values for a test are an 'Allowed Results "
                        + "Pattern' semantic attached to that test's Test Performed semantic — not "
                        + "to the device concept and not a field of the Test Performed record. So "
                        + "to find acceptable results: resolve the concept, call this tool with "
                        + "pattern='Test Performed', then call it again on the returned semantic's "
                        + "UUID. A semantic whose latest version is inactive is listed too, marked "
                        + "'(retired)' with the field values it was retired with — so a description "
                        + "or acceptability you retired shows as retired, not as its last active text.",
                objectSchema(Map.of(
                                "id", strProp("a concept's SCTID or UUID, or a semantic instance's own "
                                        + "UUID (to list the semantics attached to that semantic)"),
                                "pattern", strProp("optional: only return semantics whose pattern name "
                                        + "contains this text, e.g. 'Test Performed'")),
                        List.of("id")),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    String id = str(in, "id");
                    if (id == null || id.isBlank()) {
                        return "No id provided.";
                    }
                    String trimmed = id.trim();
                    // Semantic traversal (semanticsForComponent) is not served
                    // over gRPC, so the nid-based path below returns nothing even
                    // though concept lookups work. The service does the traversal
                    // remotely and returns the same shape.
                    if (GrpcSearchService.isActive()) {
                        return grpcConceptSemantics(trimmed, str(in, "pattern"), v);
                    }
                    long targetNid;
                    try {
                        // A UUID is used AS GIVEN — never canonicalized to its enclosing concept.
                        // Semantics can be attached to a semantic (e.g. Allowed Results on a Test
                        // Performed record), and walking up to the concept would silently return
                        // the wrong entity's semantics.
                        PublicId given = PublicIds.of(UUID.fromString(trimmed));
                        if (!held(given)) {
                            return notFound(trimmed);
                        }
                        targetNid = EntityService.get().nidForPublicId(given);
                    } catch (IllegalArgumentException notUuid) {
                        // An SCTID always denotes a concept, so resolving is safe here.
                        long nid = resolve(trimmed, v);
                        if (Nid.isNone(nid)) {
                            return notFound(trimmed);
                        }
                        targetNid = toConceptNid(nid);
                    }
                    String label = labelFor(v, targetNid);
                    String pattern = str(in, "pattern");
                    try {
                        List<SemanticInfo> semantics = semanticsFor(targetNid, pattern, v);
                        if (semantics.isEmpty()) {
                            return (pattern == null || pattern.isBlank())
                                    ? "No semantics attached to " + label
                                    : "No semantics matching pattern '" + pattern + "' on "
                                            + label + ". Call without 'pattern' to see "
                                            + "which patterns are present.";
                        }
                        StringBuilder sb = new StringBuilder(label).append('\n');
                        int shown = Math.min(semantics.size(), MAX_SEMANTICS);
                        for (int i = 0; i < shown; i++) {
                            renderSemantic(sb, semantics.get(i));
                        }
                        if (semantics.size() > shown) {
                            sb.append("… (").append(semantics.size() - shown)
                                    .append(" more not shown; narrow with 'pattern')\n");
                        }
                        return sb.append('[').append(semantics.size()).append(" semantics]").toString();
                    } catch (Exception e) {
                        return "Error retrieving semantics: " + e.getMessage();
                    }
                });
    }

    private AnthropicTool semanticInfo() {
        return tool("semantic_info",
                "Read the field values of a specific semantic instance by its own UUID — e.g. a "
                        + "Test Performed record, a comment, an identifier. Use this when a UUID "
                        + "identifies a semantic/pattern instance rather than a concept (the concept "
                        + "tool will say so if it does), or when you already have a semantic's UUID "
                        + "and need its field values (e.g. limit of detection, units of measure).",
                objectSchema(Map.of("id", strProp("the semantic instance's UUID (not the concept it's attached to)")),
                        List.of("id")),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    String id = str(in, "id");
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(id == null ? "" : id.trim());
                    } catch (IllegalArgumentException e) {
                        return "Not a valid UUID: " + id;
                    }
                    if (GrpcSearchService.isActive()) {
                        return grpcSemanticInfo(uuid);
                    }
                    try {
                        PublicId given = PublicIds.of(uuid);
                        SemanticInfo info = held(given)
                                ? semanticFor(EntityService.get().nidForPublicId(given), v) : null;
                        if (info == null) {
                            return "No semantic found for " + uuid
                                    + " (it may be a concept — use concept_semantics — or have no "
                                    + "version visible under the active view).";
                        }
                        StringBuilder sb = new StringBuilder();
                        renderSemantic(sb, info);
                        return sb.toString();
                    } catch (Exception e) {
                        return "Error retrieving semantic: " + e.getMessage();
                    }
                });
    }

    /**
     * How a component changed over time, version by version, newest first: the reads the other
     * tools cannot give, since they show only the latest version under the view
     * (knowledge-graphlet/komet-claude-plugin#1).
     */
    private AnthropicTool changeHistory() {
        return tool("change_history",
                """
                        Show how a concept, pattern or semantic changed over time: each version, newest \
                        first, with when it was made and by whom, and each field that changed from the \
                        version before (before ➞ after). A change to a logical definition is shown as the \
                        edits to its tree. Call this to answer how, when or by whom something changed, or \
                        what it said before; the other tools show only the latest version.
                        """,
                objectSchema(Map.of("id", strProp("the UUID or SCTID of the concept, pattern or semantic")),
                        List.of("id")),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    if (GrpcSearchService.isActive()) {
                        return "Change history is read from a local knowledge base, and this view reads "
                                + "through the gRPC service, which does not serve a component's versions.";
                    }
                    Component component;
                    try {
                        component = component(str(in, "id"), v);
                    } catch (IllegalArgumentException e) {
                        return e.getMessage();
                    }
                    try {
                        return v.stampCalculator().changeChronology(component.entity()).toString(v, true);
                    } catch (RuntimeException e) {
                        return "Could not read the change history of " + component.kind().word() + " "
                                + component.name() + ": " + e.getMessage();
                    }
                });
    }

    /** Most semantics {@link #referencingSemantics} lists unless told otherwise. */
    private static final int DEFAULT_REFERENCE_LIMIT = 25;

    /** How long {@link #referencingSemantics} searches field values before saying where it stopped. */
    private static final Duration REFERENCE_SEARCH_BUDGET = Duration.ofSeconds(20);

    /** How many semantics are read between looks at the clock. */
    private static final int SEMANTICS_PER_CLOCK_CHECK = 1024;

    private AnthropicTool referencingSemantics() {
        return tool("referencing_semantics",
                "List the semantics that point at a component: those attached to it, and those "
                        + "that hold it in a field value or in a definition tree, so you can see "
                        + "what uses it and not only what hangs off it. Each is listed with its "
                        + "pattern, its own UUID (written `semantic [UUID]`, for `semantic_info`), "
                        + "what it is attached to, and how it points here. Field values are read "
                        + "at each semantic's latest version in this view. No index covers field "
                        + "values, so this searches the patterns, smallest first, for up to "
                        + "20 seconds; the answer says when it stopped early, and then more may "
                        + "exist. Pass 'pattern' to search one pattern. The instances of a pattern "
                        + "are not listed for naming that pattern: a semantic points at a pattern "
                        + "only through a field or by being attached to it.",
                objectSchema(Map.of(
                                "id", strProp("the component: a concept's SCTID or UUID, or a pattern's or "
                                        + "semantic's own UUID"),
                                "pattern", strProp("optional: only semantics whose pattern name contains "
                                        + "this text, e.g. 'Test Performed'"),
                                "limit", Map.of("type", "integer", "description",
                                        "max semantics listed (default " + DEFAULT_REFERENCE_LIMIT + ")")),
                        List.of("id")),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    // Semantic traversal is not served over gRPC (see conceptSemantics), and there
                    // the service has no call for it.
                    if (GrpcSearchService.isActive()) {
                        return "referencing_semantics needs a local knowledge base; this one is served over "
                                + "gRPC, which offers no such search. Nothing was searched.";
                    }
                    Component target;
                    try {
                        target = component(str(in, "id"), v);
                    } catch (IllegalArgumentException e) {
                        return e.getMessage();
                    }
                    try {
                        References found = references(target.entity().nid(), str(in, "pattern"),
                                Math.max(1, intOr(in, "limit", DEFAULT_REFERENCE_LIMIT)), v, REFERENCE_SEARCH_BUDGET);
                        return render(target, found);
                    } catch (RuntimeException e) {
                        return "Error searching for references: " + e.getMessage();
                    }
                });
    }

    /**
     * A semantic that points at a component.
     *
     * @param pattern    its pattern's name
     * @param semanticId its own UUID
     * @param retired    whether its latest version is inactive
     * @param attachedTo the component it is attached to, named and identified
     * @param via        how it points there: {@code reference}, or {@code field "<name>"} per field
     */
    record Referrer(String pattern, String semanticId, boolean retired, String attachedTo, List<String> via) {}

    /**
     * What a search for references found, and how far it got.
     *
     * @param referrers the semantics found, those attached first
     * @param searched  the semantics whose field values were read
     * @param total     the semantics in the patterns selected
     * @param atLimit   true when the search stopped because the limit was reached
     * @param outOfTime true when the search stopped because the budget ran out
     */
    record References(List<Referrer> referrers, long searched, long total, boolean atLimit, boolean outOfTime) {}

    /**
     * The semantics pointing at a component. The attached ones come from the store's index. The
     * rest are found by reading semantics, since nothing indexes field values; patterns are taken
     * smallest first, so a search that runs out of time has covered as many patterns as it could,
     * and a pattern of a few records is not left waiting behind a hundred million descriptions.
     *
     * @param targetNid     the component
     * @param patternFilter optional case-insensitive substring of the pattern name
     * @param limit         the most semantics to find
     * @param v             the view whose latest versions are read
     * @param budget        how long to read field values
     * @return what was found
     */
    static References references(long targetNid, String patternFilter, int limit, ViewCalculator v, Duration budget) {
        String filter = (patternFilter == null || patternFilter.isBlank()) ? null : patternFilter.trim().toLowerCase();
        LinkedHashMap<Long, Referrer> found = new LinkedHashMap<>();

        Iterator<SemanticEntity<SemanticEntityVersion>> attached =
                EntityService.get().semanticsForComponent(targetNid).iterator();
        while (attached.hasNext() && found.size() < limit) {
            SemanticEntity<SemanticEntityVersion> semantic = attached.next();
            if (selected(semantic.patternNid(), filter, v)) {
                referrer(semantic, List.of("reference"), v).ifPresent(r -> found.put(semantic.nid(), r));
            }
        }
        boolean atLimit = attached.hasNext();

        List<Long> patterns = new ArrayList<>();
        EntityService.get().forEachPatternEntity(pattern -> {
            if (selected(pattern.nid(), filter, v)) {
                patterns.add(pattern.nid());
            }
        });
        Map<Long, Integer> sizes = new HashMap<>();
        patterns.forEach(pattern -> sizes.put(pattern, EntityService.get().countSemanticsOfPattern(pattern)));
        patterns.sort(Comparator.comparing(sizes::get));
        long total = sizes.values().stream().mapToLong(Integer::longValue).sum();

        long deadline = System.nanoTime() + budget.toNanos();
        long searched = 0;
        boolean outOfTime = false;
        search:
        for (long patternNid : patterns) {
            Iterator<SemanticEntity<SemanticEntityVersion>> semantics =
                    EntityService.get().semanticsOfPattern(patternNid).iterator();
            while (semantics.hasNext()) {
                if (searched % SEMANTICS_PER_CLOCK_CHECK == 0 && System.nanoTime() - deadline >= 0) {
                    outOfTime = true;
                    break search;
                }
                SemanticEntity<SemanticEntityVersion> semantic = semantics.next();
                searched++;
                if (!anyVersionHolds(semantic, targetNid)) {
                    continue;
                }
                Optional<SemanticEntityVersion> version = latestOfAnyState(semantic.nid(), v);
                if (version.isEmpty()) {
                    continue;
                }
                List<String> via = fieldsHolding(targetNid, version.get(), fieldNamesFor(patternNid, v));
                if (via.isEmpty()) {
                    continue;
                }
                Referrer already = found.get(semantic.nid());
                if (already != null) {
                    List<String> both = new ArrayList<>(already.via());
                    both.addAll(via);
                    found.put(semantic.nid(), new Referrer(already.pattern(), already.semanticId(),
                            already.retired(), already.attachedTo(), both));
                } else if (found.size() >= limit) {
                    atLimit = true;
                    break search;
                } else {
                    referrer(semantic, via, v).ifPresent(r -> found.put(semantic.nid(), r));
                }
            }
        }
        return new References(List.copyOf(found.values()), searched, total, atLimit, outOfTime);
    }

    /** Whether a pattern passes the name filter; every pattern passes when there is none. */
    private static boolean selected(long patternNid, String filter, ViewCalculator v) {
        return filter == null || ComponentText.preferredName(v, patternNid).toLowerCase().contains(filter);
    }

    /** The row for a semantic, or empty when it has no version under the view. */
    private static Optional<Referrer> referrer(SemanticEntity<SemanticEntityVersion> semantic, List<String> via,
                                               ViewCalculator v) {
        return latestOfAnyState(semantic.nid(), v).map(version -> new Referrer(
                ComponentText.preferredName(v, semantic.patternNid()),
                ComponentText.leastUuid(semantic.nid()).map(UUID::toString).orElse(""),
                version.stamp().state() != State.ACTIVE,
                ComponentText.preferredName(v, semantic.referencedComponentNid()) + ' '
                        + ComponentText.typedIdentifier(v, semantic.referencedComponentNid()),
                via));
    }

    /** The semantic's latest version at the view's position and modules, in either state. */
    private static Optional<SemanticEntityVersion> latestOfAnyState(long semanticNid, ViewCalculator v) {
        Latest<SemanticEntityVersion> latest = anyState(v).latest(semanticNid);
        return latest.isPresent() ? Optional.of(latest.get()) : Optional.empty();
    }

    /** The view's stamp coordinate in either state, so a retired component still reads as itself. */
    private static StampCalculatorWithCache anyState(ViewCalculator v) {
        return StampCalculatorWithCache.getCalculator(
                v.viewCoordinateRecord().stampCoordinate().withAllowedStates(StateSet.ACTIVE_AND_INACTIVE));
    }

    /** Whether any version of a semantic holds the component: a cheap test, before the view is asked. */
    private static boolean anyVersionHolds(SemanticEntity<SemanticEntityVersion> semantic, long targetNid) {
        for (SemanticEntityVersion version : semantic.versions()) {
            for (Object value : version.fieldValues()) {
                if (holds(value, targetNid)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How a version points at the component: one {@code field "<name>"} per field that holds it. */
    private static List<String> fieldsHolding(long targetNid, SemanticEntityVersion version, List<String> fieldNames) {
        List<String> via = new ArrayList<>();
        Object[] values = version.fieldValues().toArray();
        for (int i = 0; i < values.length; i++) {
            if (holds(values[i], targetNid)) {
                via.add("field \"" + (i < fieldNames.size() ? fieldNames.get(i) : "field " + i) + '"');
            }
        }
        return via;
    }

    /**
     * Whether a field value holds a component: is it, lists it, or names it as a property of a
     * vertex of a definition tree. A vertex's meaning is not counted: it is the grammar of the
     * tree, such as a role or an AND, not a component the definition refers to.
     */
    private static boolean holds(Object value, long targetNid) {
        return switch (value) {
            case EntityFacade facade -> facade.nid() == targetNid;
            case LongIdCollection ids -> ids.contains(targetNid);
            case DiTreeEntity tree -> tree.vertexMap().anySatisfy(vertex -> holds(vertex, targetNid));
            case EntityVertex vertex -> vertex.properties().anySatisfy(property -> holds(property, targetNid));
            case null, default -> false;
        };
    }

    /** The search's answer: what points at the component, then how far the search got. */
    static String render(Component target, References found) {
        StringBuilder sb = new StringBuilder(target.name()).append("  [").append(target.uuid()).append("]\n");
        if (found.referrers().isEmpty()) {
            sb.append("No semantics found pointing at it.\n");
        }
        for (Referrer referrer : found.referrers()) {
            renderHeading(sb, referrer.pattern(), referrer.semanticId(), referrer.retired());
            sb.append("  attached to: ").append(referrer.attachedTo()).append('\n')
                    .append("  points here by: ").append(String.join(", ", referrer.via())).append('\n');
        }
        sb.append('[').append(found.referrers().size()).append(" semantics; read ")
                .append(found.searched()).append(" of ").append(found.total()).append(" semantics");
        if (found.outOfTime()) {
            sb.append("; STOPPED FOR TIME with the largest patterns unread, so more may exist. Narrow with 'pattern'.");
        } else if (found.atLimit()) {
            sb.append("; STOPPED AT THE LIMIT, so more may exist. Narrow with 'pattern' or raise 'limit'.");
        }
        return sb.append(']').toString();
    }

    // ── gRPC-mode variants ───────────────────────────────────────────────
    // Concept lookups resolve in gRPC mode, but semantic traversal
    // (semanticsForComponent) is not served, so the nid-based path above
    // finds nothing. The service performs the traversal server-side and returns
    // records of identical shape, so both modes render through renderSemantic
    // and produce identical output.

    /**
     * {@link #conceptSemantics} against the gRPC service.
     *
     * <p>Takes the id as text because the service accepts a UUID directly: unlike the local
     * path there is no nid to resolve. An SCTID still has to be resolved to a UUID first, and
     * that resolution goes through the view — which in gRPC mode is served remotely too.
     *
     * @param trimmed the concept SCTID/UUID, or a semantic's own UUID
     * @param pattern optional pattern-name substring filter
     * @param v       the active view calculator, for labels and SCTID resolution
     * @return the rendered semantics, or a diagnostic message
     */
    private static String grpcConceptSemantics(String trimmed, String pattern,
                                              ViewCalculator v) {
        UUID target;
        try {
            // A UUID is used AS GIVEN — never canonicalized to its enclosing
            // concept, so a semantic's own UUID lists what is attached to IT.
            target = UUID.fromString(trimmed);
        } catch (IllegalArgumentException notUuid) {
            // An SCTID always denotes a concept, so resolving is safe here.
            long nid = resolve(trimmed, v);
            if (Nid.isNone(nid)) {
                return notFound(trimmed);
            }
            // Any of the concept's UUIDs finds it in the remote store; the least is the one
            // ComponentText names it by.
            Optional<UUID> least = ComponentText.leastUuid(toConceptNid(nid));
            if (least.isEmpty()) {
                return "No public UUID for '" + trimmed + "'.";
            }
            target = least.get();
        }
        try {
            List<GrpcSearchService.SemanticInfo> semantics =
                    GrpcSearchService.get().conceptSemantics(target, pattern);
            String label = labelFor(v, target);
            if (semantics.isEmpty()) {
                return (pattern == null || pattern.isBlank())
                        ? "No semantics attached to " + label
                        : "No semantics matching pattern '" + pattern + "' on "
                                + label + ". Call without 'pattern' to see "
                                + "which patterns are present.";
            }
            StringBuilder sb = new StringBuilder(label).append('\n');
            int shown = Math.min(semantics.size(), MAX_SEMANTICS);
            for (int i = 0; i < shown; i++) {
                renderSemantic(sb, convert(semantics.get(i)));
            }
            if (semantics.size() > shown) {
                sb.append("… (").append(semantics.size() - shown)
                        .append(" more not shown; narrow with 'pattern')\n");
            }
            return sb.append('[').append(semantics.size()).append(" semantics]").toString();
        } catch (Exception e) {
            return "Error retrieving semantics: " + e.getMessage();
        }
    }

    /**
     * {@link #search} against the gRPC service.
     *
     * <p>Uses {@code searchGrouped} rather than the {@code SearchService.search} contract:
     * that method documents "NIDs are 0 since the local entity store is ephemeral", and the
     * local rendering path keys entirely off the nid — every hit would be discarded by the
     * {@code conceptNid != 0} guard and the tool would still report no matches.
     *
     * <p>{@code GroupedResult} is already one row per concept (the local path has to
     * de-duplicate description hits up to their concept itself), and carries the
     * {@code active} flag that stands in for the local {@code isActive} check. Output format
     * matches {@link #nameAndId}: name followed by a bracketed identifier, so the model sees
     * the same shape in both modes and can feed the UUID straight into
     * {@link #conceptSemantics}.
     *
     * @param query the search text
     * @param limit maximum results
     * @return the rendered matches, or a diagnostic message
     */
    private static String grpcSearch(String query, int limit) {
        try {
            List<RemoteConceptSearchService.GroupedResult> results =
                    GrpcSearchService.get().searchGrouped(
                            query, limit, RemoteConceptSearchService.SortOption.TOP_COMPONENT);
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (RemoteConceptSearchService.GroupedResult r : results) {
                // Never offer a retired concept as a grounding option (#739) — the remote
                // equivalent of the local isActive(v, nid) filter.
                if (!r.active()) {
                    continue;
                }
                String id = (r.publicId() == null || r.publicId().isEmpty())
                        ? "no public id"
                        : r.publicId().getFirst();
                sb.append("  - ").append(r.fullyQualifiedName())
                        .append("  [").append(id).append("]\n");
                shown++;
            }
            if (shown == 0) {
                return "No matches for: " + query;
            }
            return sb.append('[').append(shown).append(" concepts]").toString();
        } catch (Exception e) {
            return "Search error: " + e.getMessage();
        }
    }

    /**
     * {@link #semanticInfo} against the gRPC service.
     *
     * @param uuid the semantic instance's own UUID
     * @return the rendered semantic, or a diagnostic message
     */
    private static String grpcSemanticInfo(UUID uuid) {
        try {
            GrpcSearchService.SemanticInfo info =
                    GrpcSearchService.get().semanticInfo(uuid);
            if (info == null) {
                return "No semantic found for " + uuid
                        + " (it may be a concept — use concept_semantics).";
            }
            StringBuilder sb = new StringBuilder();
            renderSemantic(sb, convert(info));
            return sb.toString();
        } catch (Exception e) {
            return "Error retrieving semantic: " + e.getMessage();
        }
    }

    /**
     * Adapt the service's record to the local one. The two carry identical
     * components; keeping a single local type means {@link #renderSemantic} has
     * one signature and the two modes cannot drift in how they format output.
     *
     * @param info the service record
     * @return the equivalent local record
     */
    private static SemanticInfo convert(GrpcSearchService.SemanticInfo info) {
        List<NamedField> fields = info.fields().stream()
                .map(f -> new NamedField(f.name(), f.value()))
                .toList();
        // The service reports no state; its semantics read as before, unmarked.
        return new SemanticInfo(info.patternName(), info.semanticId(), false, fields);
    }

    /**
     * One semantic field: its name (the field's meaning concept, from the governing pattern's
     * field definitions) and its formatted value.
     */
    record NamedField(String name, String value) {}

    /**
     * A semantic instance: pattern name, the semantic's own UUID, whether its latest version is
     * inactive, and that version's named field values.
     */
    record SemanticInfo(String patternName, String semanticId, boolean retired, List<NamedField> fields) {}

    /**
     * The semantics attached to {@code componentNid}.
     *
     * <p>{@code semanticsForComponent} works for any component, so passing a semantic's nid
     * lists the semantics attached to <em>it</em> (e.g. Allowed Results on a Test Performed
     * record) rather than only concept-level annotations.
     *
     * @param componentNid  the concept or semantic whose attached semantics are wanted
     * @param patternFilter optional case-insensitive substring matched against the pattern name
     * @param v             the active view, used for stamp/language resolution
     * @return the attached semantics, filtered when a pattern filter is given
     */
    private static List<SemanticInfo> semanticsFor(long componentNid, String patternFilter, ViewCalculator v) {
        String filter = (patternFilter == null || patternFilter.isBlank())
                ? null : patternFilter.trim().toLowerCase();
        List<SemanticInfo> results = new java.util.ArrayList<>();
        for (SemanticEntity<SemanticEntityVersion> semantic
                : EntityService.get().semanticsForComponent(componentNid).toList()) {
            SemanticInfo info = semanticFor(semantic.nid(), v);
            if (info != null && (filter == null || info.patternName().toLowerCase().contains(filter))) {
                results.add(info);
            }
        }
        return results;
    }

    /**
     * Reads one semantic: its pattern name, own UUID, whether it is retired, and field values
     * paired with the field names from the pattern's field definitions.
     *
     * <p>The latest version is taken at the view's position and modules but in either state. A
     * view that allows only active versions would otherwise answer with a retired semantic's last
     * active version, and a retirement would read as never having happened.
     *
     * @param semanticNid the semantic's nid
     * @param v           the active view
     * @return the semantic, or {@code null} when the nid is not a semantic or has no version
     *         visible under this view's coordinate
     */
    private static SemanticInfo semanticFor(long semanticNid, ViewCalculator v) {
        try {
            Entity<?> entity = EntityHandle.getEntityOrThrow(semanticNid);
            if (!(entity instanceof SemanticEntity<?> semantic)) {
                return null;
            }
            Latest<SemanticEntityVersion> latest = anyState(v).latest(semanticNid);
            if (!latest.isPresent()) {
                return null;
            }
            boolean retired = latest.get().stamp().state() != State.ACTIVE;
            String patternName = ComponentText.preferredName(v, semantic.patternNid());
            List<String> fieldNames = fieldNamesFor(semantic.patternNid(), v);

            List<NamedField> fields = new java.util.ArrayList<>();
            Object[] values = latest.get().fieldValues().toArray();
            for (int i = 0; i < values.length; i++) {
                String name = i < fieldNames.size() ? fieldNames.get(i) : "field " + i;
                fields.add(new NamedField(name, fieldValue(values[i], v)));
            }
            String semanticId = ComponentText.leastUuid(semantic.nid()).map(UUID::toString).orElse("");
            return new SemanticInfo(patternName, semanticId, retired, fields);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Field names of a pattern, in definition order — each field's meaning concept. */
    private static List<String> fieldNamesFor(long patternNid, ViewCalculator v) {
        try {
            Latest<PatternEntityVersion> pattern = v.stampCalculator().latest(patternNid);
            if (!pattern.isPresent()) {
                return List.of();
            }
            List<String> names = new java.util.ArrayList<>();
            pattern.get().fieldDefinitions().forEach(fd ->
                    names.add(ComponentText.preferredName(v, fd.meaningNid())));
            return names;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Formats a field value. Component references render as {@code name [SCTID x]} /
     * {@code [UUID x]} and never as a nid — a nid is local to one store, and an unlabelled number
     * beside a clinical concept name reads like a terminology code.
     */
    private static String fieldValue(Object value, ViewCalculator v) {
        if (value == null) {
            return "";
        }
        if (value instanceof LongIdCollection ids) {
            StringBuilder sb = new StringBuilder("[");
            long[] nids = ids.toArray();
            for (int i = 0; i < nids.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(ComponentText.preferredName(v, nids[i]))
                        .append(' ').append(ComponentText.typedIdentifier(v, nids[i]));
            }
            return sb.append(']').toString();
        }
        if (value instanceof EntityFacade facade) {
            return ComponentText.preferredName(v, facade.nid())
                    + ' ' + ComponentText.typedIdentifier(v, facade.nid());
        }
        // A definition tree or a vertex names components at every node, and its own toString()
        // writes their nids; DefinitionText writes the same structure with no nid in it.
        if (value instanceof DiTreeEntity tree) {
            return '\n' + DefinitionText.tree(tree, v);
        }
        if (value instanceof EntityVertex vertex) {
            return DefinitionText.vertex(vertex, v);
        }
        return value.toString();
    }

    /**
     * A display label for an entity identified by UUID — the gRPC-mode counterpart of
     * {@link #labelFor(ViewCalculator, long)}, which cannot be used there because a semantic's
     * UUID has no resolvable nid.
     *
     * <p>Resolution is attempted anyway, since concept lookups do work in gRPC mode and yield a
     * real name; the bare UUID is only the fallback. Without this, every gRPC-mode heading
     * degraded to a raw UUID.
     */
    private static String labelFor(ViewCalculator v, UUID uuid) {
        try {
            long nid = EntityService.get().nidForPublicId(PublicIds.of(uuid));
            String name = v.getFullyQualifiedNameText(nid)
                    .or(() -> v.getDescriptionText(nid))
                    .orElse(null);
            if (name != null && !name.isBlank()) {
                return name + "  [" + uuid + "]";
            }
        } catch (RuntimeException ignored) {
            // Not resolvable (e.g. a semantic in gRPC mode); the UUID is the label.
        }
        return uuid.toString();
    }

    /**
     * A display label for an entity. Unlike {@link #nameAndId} this never canonicalizes to a
     * concept, so it stays correct when the nid identifies a semantic (which has no name).
     */
    private static String labelFor(ViewCalculator v, long nid) {
        String name = v.getFullyQualifiedNameText(nid)
                .or(() -> v.getDescriptionText(nid))
                .orElse(null);
        String id = ComponentText.identifier(nid);
        return (name == null || name.isBlank()) ? id : name + "  [" + id + "]";
    }

    /** Renders one semantic as its pattern, UUID, and named fields. */
    private static void renderSemantic(StringBuilder sb, SemanticInfo info) {
        renderHeading(sb, info.patternName(), info.semanticId(), info.retired());
        if (info.fields().isEmpty()) {
            sb.append("  (no field values)\n");
            return;
        }
        for (NamedField field : info.fields()) {
            sb.append("  - ").append(field.name()).append(": ").append(field.value()).append('\n');
        }
    }

    /**
     * A semantic's heading line. Its UUID is labelled: bare, beside a pattern's name, it read as
     * the pattern's, and {@code semantic_info} is called with it.
     */
    private static void renderHeading(StringBuilder sb, String patternName, String semanticId, boolean retired) {
        sb.append("Pattern: ").append(patternName);
        if (!semanticId.isEmpty()) {
            sb.append("  semantic [").append(semanticId).append(']');
        }
        if (retired) {
            sb.append("  (retired)");
        }
        sb.append('\n');
    }

    private AnthropicTool search() {
        return tool("search",
                "Full-text search descriptions (Lucene). Returns the matching concepts and patterns "
                        + "with identifiers. Use this to find a concept or a pattern when you don't have "
                        + "its code.",
                objectSchema(Map.of(
                        "query", strProp("the text to search for"),
                        "limit", Map.of("type", "integer", "description", "max results (default 50)")),
                        List.of("query")),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    String query = str(in, "query");
                    if (query == null || query.isBlank()) {
                        return "No query provided.";
                    }
                    int limit = intOr(in, "limit", DEFAULT_LIMIT);
                    // In gRPC mode there is no local Lucene index — the data lives on the
                    // remote service — so `new Searcher()` matches nothing and the tool
                    // reports "No matches" for concepts Komet's own search panel can see.
                    if (GrpcSearchService.isActive()) {
                        return grpcSearch(query.trim(), Math.max(1, limit));
                    }
                    Searcher searcher = null;
                    try {
                        searcher = new Searcher();
                        PrimitiveDataSearchResult[] results = searcher.search(query.trim(), Math.max(1, limit));
                        LinkedHashSet<Long> seen = new LinkedHashSet<>();
                        StringBuilder sb = new StringBuilder();
                        for (PrimitiveDataSearchResult r : results) {
                            // A full-text hit is a DESCRIPTION semantic (r.nid()); walk it up to the
                            // concept it describes. Return (and de-duplicate by) the concept, never the
                            // description, so the model grounds concepts — several matching descriptions
                            // of one concept collapse to a single result.
                            long conceptNid = toConceptNid(r.nid());
                            // Active-only: never offer a retired concept as a grounding option (#739).
                            if (conceptNid != 0 && isActive(v, conceptNid) && seen.add(conceptNid)) {
                                sb.append("  - ").append(nameAndId(v, conceptNid)).append('\n');
                            }
                        }
                        if (seen.isEmpty()) {
                            return "No matches for: " + query;
                        }
                        return sb.append("[").append(seen.size()).append(" matches]").toString();
                    } catch (Exception e) {
                        return "Search error: " + e.getMessage();
                    } finally {
                        if (searcher != null) {
                            try {
                                searcher.close();
                            } catch (Exception ignored) {
                                // best-effort close of the Lucene reader
                            }
                        }
                    }
                });
    }

    // ── Shared navigation tool (children/parents/ancestors/descendants) ──

    private AnthropicTool navTool(String name, String description) {
        return tool(name, description, idSchema(), in -> {
            ViewCalculator v = view();
            if (v == null) {
                return NO_VIEW;
            }
            long nid = resolve(str(in, "id"), v);
            if (Nid.isNone(nid)) {
                return notFound(str(in, "id"));
            }
            long[] nids = switch (name) {
                case "children" -> v.childrenOf(nid).longStream().toArray();
                case "parents" -> v.parentsOf(nid).longStream().toArray();
                case "ancestors" -> v.ancestorsOf(nid).longStream().toArray();
                case "descendants" -> v.descendentsOf(nid).longStream().toArray();
                default -> new long[0];
            };
            return nameAndId(v, nid) + "\n" + renderIds(v, nids, DEFAULT_LIMIT);
        });
    }

    // ── Resolution + rendering helpers ──────────────────────────────────

    private static final long NONE = Integer.MIN_VALUE;
    private static final String NO_VIEW = "No active knowledge-base view is available.";
    /**
     * What a tool reports when the view coordinate cannot write its own description. The
     * coordinate record is not printed in its place: its {@code toString()} writes nids.
     */
    private static final String VIEW_NOT_DESCRIBED = "(the view coordinate could not be described)";

    private ViewCalculator view() {
        try {
            return viewSupplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Resolves an SCTID or UUID string to a nid, or {@link #NONE} if unknown. */
    private static long resolve(String id, ViewCalculator v) {
        if (id == null || id.isBlank()) {
            return NONE;
        }
        String trimmed = id.trim();
        // A comma-joined PublicId UUID array — the durable ANF-in-adoc @id key.
        if (trimmed.indexOf(',') >= 0) {
            return resolvePublicIdArray(trimmed, v);
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(trimmed);
        } catch (IllegalArgumentException notUuid) {
            try {
                uuid = UuidUtil.fromSNOMED(trimmed);
            } catch (RuntimeException notSctid) {
                return NONE;
            }
        }
        PublicId publicId = PublicIds.of(uuid);
        if (!held(publicId)) {
            return NONE;
        }
        long nid = toConceptNid(EntityService.get().nidForPublicId(publicId));
        // A component this view does not name is treated as not present.
        if (v.getFullyQualifiedNameText(nid).isEmpty() && v.getDescriptionText(nid).isEmpty()) {
            return NONE;
        }
        return nid;
    }

    /**
     * Whether the store holds a public id, asked before asking for its nid: for an id it does not
     * hold, that would mint one, which outlives the session with no record behind it, or be
     * refused with an exception by a store keyed by pattern. The model can pass any identifier.
     *
     * <p>Over gRPC, {@code hasPublicId} knows only the ids already fetched, and there is no local
     * store to mint into, so every id is let through to the service.
     *
     * @param publicId the public id
     * @return true when its nid can be asked for
     */
    public static boolean held(PublicId publicId) {
        return GrpcSearchService.isActive() || PrimitiveData.get().hasPublicId(publicId);
    }

    /**
     * Resolves a comma-joined {@code PublicId} UUID array (the ANF-in-adoc {@code @id} key) to a
     * concept nid, or {@link #NONE} when a part is not a valid UUID or the concept is not named in
     * this view. Rebuilding the {@code PublicId} from the full array reconstructs the same concept
     * the badge was drawn for.
     *
     * @param array the comma-joined UUIDs
     * @param v     the active view
     * @return the concept nid, or {@link #NONE}
     */
    private static long resolvePublicIdArray(String array, ViewCalculator v) {
        String[] parts = array.split(",");
        java.util.List<UUID> uuids = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            try {
                uuids.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException notUuid) {
                return NONE;
            }
        }
        if (uuids.isEmpty()) {
            return NONE;
        }
        PublicId publicId = PublicIds.of(uuids.toArray(new UUID[0]));
        if (!held(publicId)) {
            return NONE;
        }
        long nid = toConceptNid(EntityService.get().nidForPublicId(publicId));
        if (v.getFullyQualifiedNameText(nid).isEmpty() && v.getDescriptionText(nid).isEmpty()) {
            return NONE;
        }
        return nid;
    }

    /** What a component is, in the words a tool's input and answer use. */
    public enum ComponentKind {
        /** A concept. */
        CONCEPT,
        /** A pattern. */
        PATTERN,
        /** A semantic: an instance of a pattern. */
        SEMANTIC;

        /** The word for this kind. */
        public String word() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * A component as a view shows it.
     *
     * @param entity  the component
     * @param kind    what it is
     * @param name    its name under the view; a semantic's is its text
     * @param uuid    its own UUID
     * @param retired whether its latest version under the view is inactive
     */
    public record Component(Entity<?> entity, ComponentKind kind, String name, String uuid, boolean retired) {}

    /**
     * Resolves an SCTID, a UUID, or a comma-joined {@code PublicId} to the component it names,
     * taken as given: unlike {@link #resolve}, a semantic stays a semantic and is not walked up to
     * its concept. Nothing is written, not even a nid for an identifier the store has not seen.
     *
     * <p>A component is found when the store holds it and it has a version, in either state, at
     * the view's position and modules, the versions the read tools show.
     *
     * @param id the identifier
     * @param v  the view
     * @return the component
     * @throws IllegalArgumentException when it does not resolve, with what to tell the model
     */
    public static Component component(String id, ViewCalculator v) {
        if (!inStore(id)) {
            throw new IllegalArgumentException(notInStore(id));
        }
        long nid = EntityService.get().nidForPublicId(publicIdOf(id.trim()));
        Entity<?> entity = EntityHandle.get(nid).orNull();
        ComponentKind kind = switch (entity) {
            case ConceptEntity<?> _ -> ComponentKind.CONCEPT;
            case PatternEntity<?> _ -> ComponentKind.PATTERN;
            case SemanticEntity<?> _ -> ComponentKind.SEMANTIC;
            case null -> throw new IllegalArgumentException(notInStore(id));
            default -> throw new IllegalArgumentException(
                    "'" + id + "' identifies a STAMP, which is not a concept, pattern or semantic.");
        };
        Latest<EntityVersion> latest = anyState(v).latest(nid);
        if (!latest.isPresent()) {
            throw new IllegalArgumentException("'" + id + "' is a " + kind.word() + " with no version in this "
                    + "view, so the read tools do not show it. Call view_info to see the coordinate.");
        }
        return new Component(entity, kind, ComponentText.fullyQualifiedName(v, nid),
                ComponentText.identifier(nid), latest.get().stamp().state() != State.ACTIVE);
    }

    /**
     * Whether the store holds an identifier, in any state and under any view: the test for a
     * writer, which must refuse what is not there. {@link #resolveConcept} answers empty for that
     * and for a retired concept alike.
     *
     * @param id an SCTID, a UUID, or a comma-joined {@code PublicId}
     * @return true when the store holds it
     */
    public static boolean inStore(String id) {
        PublicId publicId = id == null || id.isBlank() ? null : publicIdOf(id.trim());
        return publicId != null && held(publicId);
    }

    /** What is said of an identifier the store does not hold. */
    private static String notInStore(String id) {
        return "No component found for '" + id + "' in this knowledge base "
                + "(accepts an SCTID, or a concept's, pattern's or semantic's UUID).";
    }

    /** The public id an identifier spells, or null when it is not one. */
    private static PublicId publicIdOf(String id) {
        try {
            if (id.indexOf(',') >= 0) {
                return PublicIds.of(java.util.Arrays.stream(id.split(","))
                        .map(part -> UUID.fromString(part.trim())).toArray(UUID[]::new));
            }
            return PublicIds.of(UUID.fromString(id));
        } catch (IllegalArgumentException notUuid) {
            return id.indexOf(',') >= 0 ? null : PublicIds.of(UuidUtil.fromSNOMED(id));
        }
    }

    /**
     * Canonicalizes a nid to the <strong>concept</strong> it identifies. A description (or any other
     * semantic) walks up to the component it describes via
     * {@link SemanticEntity#topEnclosingComponentNid()}; a concept is returned unchanged. Full-text
     * search matches description semantics, and an identifier the model offers may be a description's —
     * but a description is not a valid ANF value and cannot be dropped onto a concept target, so
     * every grounded value must be the concept itself. This is the "a description walks up to its
     * concept" rule applied at the grounding boundary.
     *
     * @param nid any component nid
     * @return the concept nid (the nid itself when it is already a concept or cannot be resolved)
     */
    private static long toConceptNid(long nid) {
        return network.ike.komet.document.LocalConcepts.conceptNid(nid);
    }

    /**
     * Resolves an SCTID or UUID to a grounded ANF value, or empty when the concept is
     * not present in the active view. Reuses the same existence-gated resolution as
     * the read-only tools, so a grounded value is always a real, named concept — the
     * anti-hallucination guarantee the narrative lift depends on.
     *
     * @param id the SCTID or concept UUID to resolve
     * @param v  the active view calculator (may be null)
     * @return the grounded value, or {@link Optional#empty()} if unresolved
     */
    public static Optional<AnfValue.Grounded> resolveConcept(String id, ViewCalculator v) {
        if (v == null) {
            return Optional.empty();
        }
        long nid = resolve(id, v);
        if (Nid.isNone(nid)) {
            return Optional.empty();
        }
        // Active-only grounding: a retired concept must never ground into the ANF — the lift
        // grounds to an ACTIVE concept or proposes a candidate (ike-issues#739).
        if (!isActive(v, toConceptNid(nid))) {
            return Optional.empty();
        }
        return Optional.of(groundedOf(v, nid));
    }

    /**
     * Whether a concept's latest version is active in this view — the gate for active-only
     * grounding (ike-issues#739). Delegates to the existing
     * {@link dev.ikm.tinkar.coordinate.stamp.calculator.StampCalculator#isLatestActive(int)}
     * (which honours the view's stamp coordinate); not a reimplementation.
     *
     * @param v   the view calculator; {@code null} yields {@code false}
     * @param nid the concept nid
     * @return {@code true} if the concept's latest version is active
     */
    public static boolean isActive(ViewCalculator v, long nid) {
        return v != null && v.stampCalculator().isLatestActive(nid);
    }

    /**
     * Builds a grounded value from an already-resolved nid: its fully specified name
     * (falling back to the preferred description) and its best identifier (SCTID when
     * present, else the first public UUID).
     *
     * @param v   the active view calculator
     * @param nid the resolved concept nid
     * @return the grounded value
     * @throws IllegalStateException if the store has no public id for the concept; a value's key
     *                               is the public id, never the nid
     */
    public static AnfValue.Grounded groundedOf(ViewCalculator v, long nid) {
        long conceptNid = toConceptNid(nid);
        String label = ComponentText.fullyQualifiedName(v, conceptNid);
        // The durable round-trip key is the public id; a concept without one cannot be grounded.
        PublicId publicId = ComponentText.publicId(conceptNid).orElseThrow(() ->
                new IllegalStateException("A concept the store has no public id for cannot be grounded"));
        String sctid = ComponentText.sctid(v, conceptNid).orElse(null);
        String identifier = sctid != null ? sctid : publicId.leastUuid().toString();
        return new AnfValue.Grounded(conceptNid, ComponentText.publicIdKey(publicId), identifier, label);
    }

    /**
     * Full-text searches concepts for an interactive type-ahead, returning concept-canonical grounded
     * values (description hits walk up to their concept, deduped). Reuses the same resolution the
     * assistant's {@code search} tool does, so a typed field and the lift ground identically.
     *
     * @param query the search text
     * @param v     the active view; null yields no results
     * @param max   the maximum number of concepts to return
     * @return the matching grounded concepts, in score order (never null)
     */
    public static List<AnfValue.Grounded> searchConcepts(String query, ViewCalculator v, int max) {
        if (v == null || query == null || query.isBlank() || max <= 0) {
            return List.of();
        }
        List<AnfValue.Grounded> rows = new java.util.ArrayList<>();
        Searcher searcher = null;
        try {
            searcher = new Searcher();
            PrimitiveDataSearchResult[] hits = searcher.search(query.trim(), max * 4);
            LinkedHashSet<Long> seen = new LinkedHashSet<>();
            for (PrimitiveDataSearchResult hit : hits) {
                long conceptNid = toConceptNid(hit.nid());
                if (conceptNid != 0 && seen.add(conceptNid)) {
                    rows.add(groundedOf(v, conceptNid));
                    if (rows.size() >= max) {
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // search is best-effort for an interactive field; return what we have
        } finally {
            if (searcher != null) {
                try {
                    searcher.close();
                } catch (Exception ignored) {
                    // best-effort close of the Lucene reader
                }
            }
        }
        return rows;
    }

    /**
     * The concept the store holds under exactly a fully qualified name, letter case and runs of
     * white space aside: a block's label is its concept's name, so a concept already held under it
     * is that concept.
     *
     * @param fqn the fully qualified name
     * @param v   the view the descriptions are read under
     * @return the concept's nid, empty when no active concept has that name
     */
    public static java.util.OptionalLong conceptNamedNid(String fqn, ViewCalculator v) {
        return network.ike.komet.document.LocalConcepts.conceptNamedNid(fqn, v);
    }

    /** {@link #conceptNamedNid} as an optional. */
    public static java.util.Optional<Long> conceptNamed(String fqn, ViewCalculator v) {
        java.util.OptionalLong nid = conceptNamedNid(fqn, v);
        return nid.isPresent() ? java.util.Optional.of(nid.getAsLong()) : java.util.Optional.empty();
    }

    /** Reports a discovered value to the callback, never letting a callback error break a tool. */
    private void fireDiscovered(AnfValue conceptValue) {
        try {
            onDiscovered.accept(conceptValue);
        } catch (RuntimeException ignored) {
            // a UI callback must never break the tool loop
        }
    }

    private static String nameAndId(ViewCalculator v, long nid) {
        return ComponentText.fullyQualifiedName(v, nid) + "  [" + idString(v, nid) + "]";
    }

    /**
     * Renders a concept's identifier: its SCTID when the concept carries a SNOMED
     * identifier semantic, otherwise its first public UUID. A concept the store has no public id
     * for is marked as unidentified; the text never holds a nid.
     */
    private static String idString(ViewCalculator v, long nid) {
        String sctid = ComponentText.sctid(v, nid).orElse(null);
        if (sctid != null) {
            return "SCTID " + sctid;
        }
        return ComponentText.identifier(nid);
    }


    private static String renderIds(ViewCalculator v, long[] nids, int limit) {
        if (nids.length == 0) {
            return "  (none)";
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(nids.length, limit);
        for (int i = 0; i < shown; i++) {
            sb.append("  - ").append(nameAndId(v, nids[i])).append('\n');
        }
        if (nids.length > limit) {
            sb.append("  … (").append(nids.length - limit).append(" more)\n");
        }
        return sb.append("[").append(nids.length).append(" total]").toString();
    }

    private static String notFound(String id) {
        return "No concept found for '" + id + "' (accepts an SCTID or a concept UUID).";
    }

    // ── Schema + arg helpers ────────────────────────────────────────────

    private static Map<String, Object> idSchema() {
        return objectSchema(Map.of("id", strProp("an SCTID (e.g. 414916001) or a concept UUID")),
                List.of("id"));
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    private static Map<String, Object> strProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static String str(Map<String, Object> in, String key) {
        Object o = in.get(key);
        return o == null ? null : o.toString();
    }

    private static int intOr(Map<String, Object> in, String key, int fallback) {
        Object o = in.get(key);
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static AnthropicTool tool(String name, String description,
                                      Map<String, Object> schema,
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
                return body.apply(input);
            }
        };
    }
}
