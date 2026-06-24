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

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.PrimitiveDataSearchResult;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.ViewCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.provider.search.Searcher;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.TinkarTerm;
import network.ike.komet.claude.anf.AnfSlot;
import network.ike.komet.claude.anthropic.AnthropicTool;

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
    private final Consumer<AnfSlot> onDiscovered;

    /**
     * Creates the tool set with no concept-discovery callback.
     *
     * @param viewSupplier supplies the current {@link ViewCalculator} (the
     *                     active window's view); must not be null
     */
    public GraphTools(Supplier<ViewCalculator> viewSupplier) {
        this(viewSupplier, slot -> {
        });
    }

    /**
     * Creates the tool set that reports each concept it confirms.
     *
     * @param viewSupplier supplies the current {@link ViewCalculator}; must not be null
     * @param onDiscovered receives a grounded slot whenever the {@code concept} tool
     *                     confirms a concept, so a UI can show a live inventory; a
     *                     null callback is treated as a no-op
     */
    public GraphTools(Supplier<ViewCalculator> viewSupplier, Consumer<AnfSlot> onDiscovered) {
        this.viewSupplier = Objects.requireNonNull(viewSupplier, "viewSupplier");
        this.onDiscovered = (onDiscovered == null) ? slot -> {
        } : onDiscovered;
    }

    /**
     * The read-only tools to register with the Anthropic client.
     *
     * @return an immutable list of tools
     */
    public List<AnthropicTool> tools() {
        return List.of(concept(), children(), parents(), descendants(),
                ancestors(), isA(), axioms(), search(), viewInfo(), debugConcept());
    }

    // ── Tools ───────────────────────────────────────────────────────────

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
                    int nid = resolve(str(in, "id"), v);
                    if (nid == NONE) {
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
                            + renderIds(v, v.parentsOf(nid).intStream().toArray(), DEFAULT_LIMIT);
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
                    int childNid = resolve(str(in, "child"), v);
                    int parentNid = resolve(str(in, "parent"), v);
                    if (childNid == NONE) {
                        return notFound(str(in, "child"));
                    }
                    if (parentNid == NONE) {
                        return notFound(str(in, "parent"));
                    }
                    boolean isa = childNid == parentNid
                            || v.ancestorsOf(childNid).intStream().anyMatch(n -> n == parentNid);
                    return (isa ? "YES — " : "NO — ")
                            + nameAndId(v, childNid) + (isa ? " is-a " : " is NOT a ") + nameAndId(v, parentNid);
                });
    }

    private AnthropicTool axioms() {
        return tool("axioms",
                "Show the logical definition (EL++ axioms / role groups) of a concept — both its "
                        + "stated form (as authored) and its inferred form (after classification). "
                        + "Call this to read how a concept is defined.",
                idSchema(),
                in -> {
                    ViewCalculator v = view();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    int nid = resolve(str(in, "id"), v);
                    if (nid == NONE) {
                        return notFound(str(in, "id"));
                    }
                    Latest<DiTreeEntity> stated = v.logicCalculator()
                            .getStatedLogicalExpressionForEntity(nid, v.stampCalculator());
                    Latest<DiTreeEntity> inferred = v.logicCalculator()
                            .getInferredLogicalExpressionForEntity(nid, v.stampCalculator());
                    StringBuilder sb = new StringBuilder(nameAndId(v, nid)).append('\n');
                    sb.append("Stated:\n").append(stated.isPresent()
                            ? stated.get().toString()
                            : "  (none on this view)").append('\n');
                    sb.append("Inferred:\n").append(inferred.isPresent()
                            ? inferred.get().toString()
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
                        return "Active view coordinate:\n" + v.viewCoordinateRecord();
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
                    int nid = resolve(str(in, "id"), v);
                    if (nid == NONE) {
                        return notFound(str(in, "id"));
                    }
                    EntityService es = EntityService.get();
                    StringBuilder sb = new StringBuilder(nameAndId(v, nid)).append("\n\n");

                    int[] rawInfNav = es.semanticNidsForComponentOfPattern(
                            nid, TinkarTerm.INFERRED_NAVIGATION_PATTERN.nid());
                    int[] rawStaNav = es.semanticNidsForComponentOfPattern(
                            nid, TinkarTerm.STATED_NAVIGATION_PATTERN.nid());
                    int[] rawInfAx = es.semanticNidsForComponentOfPattern(
                            nid, TinkarTerm.EL_PLUS_PLUS_INFERRED_AXIOMS_PATTERN.nid());
                    int[] rawStaAx = es.semanticNidsForComponentOfPattern(
                            nid, TinkarTerm.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid());
                    sb.append("RAW store semantics (no view filter):\n")
                            .append("  inferred-nav: ").append(rawInfNav.length).append('\n')
                            .append("  stated-nav: ").append(rawStaNav.length).append('\n')
                            .append("  inferred-axiom: ").append(rawInfAx.length).append('\n')
                            .append("  stated-axiom: ").append(rawStaAx.length).append('\n');
                    if (rawInfNav.length > 0) {
                        Latest<SemanticEntityVersion> latest = v.stampCalculator().latest(rawInfNav[0]);
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
                        sb.append(vc).append('\n');
                    }

                    sb.append("\nVERDICT: ");
                    if ((rawInfNav.length > 0 && parents == 0) || (rawInfAx.length > 0 && !infAx.isPresent())) {
                        sb.append("RAW DATA EXISTS BUT THIS VIEW RETURNS EMPTY — the tools are on the wrong view "
                                + "coordinate (its STAMP path/module filter excludes the loaded data; likely the "
                                + "DEFAULT view, not the journal view).");
                    } else if (rawInfNav.length == 0 && rawInfAx.length == 0) {
                        sb.append("No raw nav/axiom data under the standard patterns — data genuinely absent for "
                                + "this concept.");
                    } else {
                        sb.append("This view matches the raw data — coordinate is correct.");
                    }
                    return sb.toString();
                });
    }

    private AnthropicTool search() {
        return tool("search",
                "Full-text search concept descriptions (Lucene). Returns matching concepts with "
                        + "identifiers. Use this to find a concept when you don't have its code.",
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
                    Searcher searcher = null;
                    try {
                        searcher = new Searcher();
                        PrimitiveDataSearchResult[] results = searcher.search(query.trim(), Math.max(1, limit));
                        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
                        StringBuilder sb = new StringBuilder();
                        for (PrimitiveDataSearchResult r : results) {
                            // A full-text hit is a DESCRIPTION semantic (r.nid()); walk it up to the
                            // concept it describes. Return (and de-duplicate by) the concept, never the
                            // description, so the model grounds concepts — several matching descriptions
                            // of one concept collapse to a single result.
                            int conceptNid = toConceptNid(r.nid());
                            // Active-only: never offer a retired concept as a grounding option (#739).
                            if (conceptNid != 0 && isActive(v, conceptNid) && seen.add(conceptNid)) {
                                sb.append("  - ").append(nameAndId(v, conceptNid)).append('\n');
                            }
                        }
                        if (seen.isEmpty()) {
                            return "No matches for: " + query;
                        }
                        return sb.append("[").append(seen.size()).append(" concepts]").toString();
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
            int nid = resolve(str(in, "id"), v);
            if (nid == NONE) {
                return notFound(str(in, "id"));
            }
            int[] nids = switch (name) {
                case "children" -> v.childrenOf(nid).intStream().toArray();
                case "parents" -> v.parentsOf(nid).intStream().toArray();
                case "ancestors" -> v.ancestorsOf(nid).intStream().toArray();
                case "descendants" -> v.descendentsOf(nid).intStream().toArray();
                default -> new int[0];
            };
            return nameAndId(v, nid) + "\n" + renderIds(v, nids, DEFAULT_LIMIT);
        });
    }

    // ── Resolution + rendering helpers ──────────────────────────────────

    private static final int NONE = Integer.MIN_VALUE;
    private static final String NO_VIEW = "No active knowledge-base view is available.";

    private ViewCalculator view() {
        try {
            return viewSupplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Resolves an SCTID or UUID string to a nid, or {@link #NONE} if unknown. */
    private static int resolve(String id, ViewCalculator v) {
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
        int nid = toConceptNid(EntityService.get().nidForPublicId(PublicIds.of(uuid)));
        // A nid may be minted for an unknown id; treat "no name" as not present.
        if (v.getFullyQualifiedNameText(nid).isEmpty() && v.getDescriptionText(nid).isEmpty()) {
            return NONE;
        }
        return nid;
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
    private static int resolvePublicIdArray(String array, ViewCalculator v) {
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
        int nid = toConceptNid(EntityService.get().nidForPublicId(PublicIds.of(uuids.toArray(new UUID[0]))));
        if (v.getFullyQualifiedNameText(nid).isEmpty() && v.getDescriptionText(nid).isEmpty()) {
            return NONE;
        }
        return nid;
    }

    /**
     * Canonicalizes a nid to the <strong>concept</strong> it identifies. A description (or any other
     * semantic) walks up to the component it describes via
     * {@link SemanticEntity#topEnclosingComponentNid()}; a concept is returned unchanged. Full-text
     * search matches description semantics, and an identifier the model offers may be a description's —
     * but a description is not a valid ANF slot filler and cannot be dropped onto a concept target, so
     * every grounded slot must be the concept itself. This is the "a description walks up to its
     * concept" rule applied at the grounding boundary.
     *
     * @param nid any component nid
     * @return the concept nid (the nid itself when it is already a concept or cannot be resolved)
     */
    private static int toConceptNid(int nid) {
        try {
            Entity<?> entity = EntityHandle.getEntityOrThrow(nid);
            if (entity instanceof SemanticEntity<?> semantic) {
                return semantic.topEnclosingComponentNid();
            }
        } catch (RuntimeException ignored) {
            // not resolvable as an entity; fall back to the nid as given
        }
        return nid;
    }

    /**
     * Resolves an SCTID or UUID to a grounded ANF slot, or empty when the concept is
     * not present in the active view. Reuses the same existence-gated resolution as
     * the read-only tools, so a grounded slot is always a real, named concept — the
     * anti-hallucination guarantee the narrative lift depends on.
     *
     * @param id the SCTID or concept UUID to resolve
     * @param v  the active view calculator (may be null)
     * @return the grounded slot, or {@link Optional#empty()} if unresolved
     */
    public static Optional<AnfSlot.Grounded> resolveConcept(String id, ViewCalculator v) {
        if (v == null) {
            return Optional.empty();
        }
        int nid = resolve(id, v);
        if (nid == NONE) {
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
    public static boolean isActive(ViewCalculator v, int nid) {
        return v != null && v.stampCalculator().isLatestActive(nid);
    }

    /**
     * Builds a grounded slot from an already-resolved nid: its fully specified name
     * (falling back to the preferred description) and its best identifier (SCTID when
     * present, else the first public UUID).
     *
     * @param v   the active view calculator
     * @param nid the resolved concept nid
     * @return the grounded slot
     */
    public static AnfSlot.Grounded groundedOf(ViewCalculator v, int nid) {
        int conceptNid = toConceptNid(nid);
        String label = v.getFullyQualifiedNameText(conceptNid)
                .orElseGet(() -> v.getPreferredDescriptionTextWithFallbackOrNid(conceptNid));
        UUID[] uuids = PrimitiveData.publicId(conceptNid).asUuidArray();
        String publicId = publicIdKey(uuids, conceptNid);
        String sctid = sctidOf(v, conceptNid);
        String identifier;
        if (sctid != null) {
            identifier = sctid;
        } else {
            identifier = uuids.length > 0 ? uuids[0].toString() : "nid=" + conceptNid;
        }
        return new AnfSlot.Grounded(conceptNid, publicId, identifier, label);
    }

    /**
     * The durable round-trip key for a concept: its {@code PublicId} UUID array as comma-joined
     * UUIDs. Resolving these back through {@code PublicIds.of(...)} reconstructs the same concept
     * (and the same identicon) under any later coordinate — identity, not a terminology code.
     *
     * @param uuids      the concept's UUID array
     * @param conceptNid the concept nid, for the degenerate no-UUID fallback
     * @return the comma-joined UUID key (never null)
     */
    private static String publicIdKey(UUID[] uuids, int conceptNid) {
        if (uuids.length == 0) {
            return "nid=" + conceptNid;
        }
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < uuids.length; i++) {
            if (i > 0) {
                key.append(',');
            }
            key.append(uuids[i]);
        }
        return key.toString();
    }

    /**
     * Full-text searches concepts for an interactive type-ahead, returning concept-canonical grounded
     * slots (description hits walk up to their concept, deduped). Reuses the same resolution the
     * assistant's {@code search} tool does, so a typed field and the lift ground identically.
     *
     * @param query the search text
     * @param v     the active view; null yields no results
     * @param max   the maximum number of concepts to return
     * @return the matching grounded concepts, in score order (never null)
     */
    public static List<AnfSlot.Grounded> searchConcepts(String query, ViewCalculator v, int max) {
        if (v == null || query == null || query.isBlank() || max <= 0) {
            return List.of();
        }
        List<AnfSlot.Grounded> rows = new java.util.ArrayList<>();
        Searcher searcher = null;
        try {
            searcher = new Searcher();
            PrimitiveDataSearchResult[] hits = searcher.search(query.trim(), max * 4);
            LinkedHashSet<Integer> seen = new LinkedHashSet<>();
            for (PrimitiveDataSearchResult hit : hits) {
                int conceptNid = toConceptNid(hit.nid());
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

    /** Reports a discovered slot to the callback, never letting a callback error break a tool. */
    private void fireDiscovered(AnfSlot slot) {
        try {
            onDiscovered.accept(slot);
        } catch (RuntimeException ignored) {
            // a UI callback must never break the tool loop
        }
    }

    private static String nameAndId(ViewCalculator v, int nid) {
        String name = v.getFullyQualifiedNameText(nid)
                .orElseGet(() -> v.getPreferredDescriptionTextWithFallbackOrNid(nid));
        return name + "  [" + idString(v, nid) + "]";
    }

    /**
     * Renders a concept's identifier: its SCTID when the concept carries a SNOMED
     * identifier semantic, otherwise its first public UUID, otherwise the nid.
     */
    private static String idString(ViewCalculator v, int nid) {
        String sctid = sctidOf(v, nid);
        if (sctid != null) {
            return "SCTID " + sctid;
        }
        try {
            UUID[] uuids = PrimitiveData.publicId(nid).asUuidArray();
            if (uuids.length > 0) {
                return uuids[0].toString();
            }
        } catch (RuntimeException ignored) {
            // fall through to nid
        }
        return "nid=" + nid;
    }

    /**
     * Resolves the SNOMED CT identifier of a concept from its identifier semantic
     * ({@link TinkarTerm#IDENTIFIER_PATTERN} with source {@link TinkarTerm#SCTID}),
     * or {@code null} if the concept has no SCTID in this knowledge base.
     */
    private static String sctidOf(ViewCalculator v, int nid) {
        try {
            int[] idSemantics = EntityService.get()
                    .semanticNidsForComponentOfPattern(nid, TinkarTerm.IDENTIFIER_PATTERN.nid());
            for (int semanticNid : idSemantics) {
                Latest<SemanticEntityVersion> latest = v.stampCalculator().latest(semanticNid);
                if (latest.isPresent()) {
                    String value = null;
                    EntityFacade source = null;
                    for (Object field : latest.get().fieldValues()) {
                        if (field instanceof String s) {
                            value = s;
                        } else if (field instanceof EntityFacade ef) {
                            source = ef;
                        }
                    }
                    if (value != null && source != null && source.nid() == TinkarTerm.SCTID.nid()) {
                        return value;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // No resolvable SCTID; caller falls back to UUID.
        }
        return null;
    }

    private static String renderIds(ViewCalculator v, int[] nids, int limit) {
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
