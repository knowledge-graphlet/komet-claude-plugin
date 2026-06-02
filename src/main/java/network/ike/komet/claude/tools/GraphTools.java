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
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.provider.search.Searcher;
import network.ike.komet.claude.anthropic.AnthropicTool;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
 * UUID) or a concept UUID. Results currently render {@code name [UUID]};
 * reverse SCTID rendering (nid → SCTID via the identifier semantic) is a
 * planned follow-up.
 */
public final class GraphTools {

    private static final int DEFAULT_LIMIT = 50;

    private final Supplier<ViewCalculator> viewSupplier;

    /**
     * Creates the tool set.
     *
     * @param viewSupplier supplies the current {@link ViewCalculator} (the
     *                     active window's view); must not be null
     */
    public GraphTools(Supplier<ViewCalculator> viewSupplier) {
        this.viewSupplier = Objects.requireNonNull(viewSupplier, "viewSupplier");
    }

    /**
     * The read-only tools to register with the Anthropic client.
     *
     * @return an immutable list of tools
     */
    public List<AnthropicTool> tools() {
        return List.of(concept(), children(), parents(), descendants(),
                ancestors(), isA(), axioms(), search());
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
                    return nameAndId(v, nid) + "\nParents:\n"
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
                "Show the inferred logical definition (EL++ axioms / role groups) of a concept. "
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
                    var latest = v.logicCalculator()
                            .getInferredLogicalExpressionForEntity(nid, v.stampCalculator());
                    if (!latest.isPresent()) {
                        return "No inferred logical definition for " + nameAndId(v, nid)
                                + " (is the store classified?).";
                    }
                    return nameAndId(v, nid) + "\n" + latest.get();
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
                            if (seen.add(r.nid())) {
                                sb.append("  - ").append(nameAndId(v, r.nid())).append('\n');
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
        int nid = EntityService.get().nidForPublicId(PublicIds.of(uuid));
        // A nid may be minted for an unknown id; treat "no name" as not present.
        if (v.getFullyQualifiedNameText(nid).isEmpty() && v.getDescriptionText(nid).isEmpty()) {
            return NONE;
        }
        return nid;
    }

    private static String nameAndId(ViewCalculator v, int nid) {
        String name = v.getFullyQualifiedNameText(nid)
                .orElseGet(() -> v.getPreferredDescriptionTextWithFallbackOrNid(nid));
        return name + "  [" + idString(nid) + "]";
    }

    /** First public UUID for a nid (reverse SCTID is a planned follow-up). */
    private static String idString(int nid) {
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
