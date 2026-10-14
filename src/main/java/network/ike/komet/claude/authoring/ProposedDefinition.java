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

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalAxiom;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpressionBuilder;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A concept definition the assistant proposes, written as a {@code koncept-definition} block
 * ({@code IKE-Network/ike-issues#1292}): the grammar, its parser, and the definition tree a
 * parsed block builds. The block renderer previews the tree, and {@code propose_concept} stores
 * the same tree as the stated axioms of an uncommitted concept, so what the person reviewed is
 * what the window opens with.
 *
 * <p>The block names its concepts with the same {@code k:} tokens as a {@code koncept-tree}
 * block, under a few structural words; indentation only scopes a role group:
 *
 * <pre>{@code
 * proposed: Eosinophilic esophagitis unresponsive to dupilumab (disorder)
 * necessary
 *   k:sctid=235599003[Eosinophilic esophagitis]
 *   group
 *     k:sctid=116676008[Associated morphology] = k:sctid=445520000[Eosinophilic infiltration]
 *     k:sctid=363698007[Finding site] = k:sctid=32849002[Esophageal structure]
 *   group
 *     k:sctid=263502005[Clinical course] = k:sctid=90734009[Chronic]
 * gap: no attribute relates a disorder to non-response to a specific substance
 * }</pre>
 *
 * <ul>
 *   <li>{@code proposed: <name>} names the concept being proposed — its fully specified name.</li>
 *   <li>{@code necessary} or {@code sufficient} opens a definition set; a block may hold several.</li>
 *   <li>Inside a set, a lone token is a supertype and {@code type = filler} an existential role.</li>
 *   <li>{@code group} opens a role group; the role lines indented beneath it belong to it.</li>
 *   <li>{@code gap: <text>} states a modelling gap — a role type or filler the knowledge base has
 *       no concept for.</li>
 * </ul>
 *
 * <p>A definition tree holds only concepts that exist, so a gap is never a node in it. A token
 * whose identifier the store does not hold is a gap too: its supertype or role is left out of
 * the tree and named among the gaps, so a fabricated or mistyped identifier is visible rather
 * than silently drawn or stored. A line that is none of the above makes the block malformed.
 */
public final class ProposedDefinition {

    /** The fenced-block language tag of a proposed definition. */
    public static final String TAG = "koncept-definition";

    /** One token: kind, identifier value, optional bracketed label. Unanchored, for composition. */
    private static final String TOKEN_PATTERN =
            "k:\\s*(sctid|uuid|id)\\s*=\\s*([^\\[\\]=\\s]+)\\s*(?:\\[([^\\]]*)])?";
    /** A supertype line: one token. */
    private static final Pattern SUPERTYPE = Pattern.compile("^" + TOKEN_PATTERN + "$");
    /** A role line: {@code type = filler}, each a token. */
    private static final Pattern ROLE = Pattern.compile("^" + TOKEN_PATTERN + "\\s*=\\s*" + TOKEN_PATTERN + "$");
    /** A structural word line, with an optional trailing colon. */
    private static final Pattern WORD = Pattern.compile("^(necessary|sufficient|group)\\s*:?$", Pattern.CASE_INSENSITIVE);
    /** A {@code key: text} line for the proposal's name or a gap. */
    private static final Pattern KEYED = Pattern.compile("^(proposed|gap)\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private ProposedDefinition() {
    }

    // ── The grammar's parts ─────────────────────────────────────────────

    /**
     * A concept as the block names it.
     *
     * @param kind  the token kind: {@code sctid}, {@code uuid} or {@code id}
     * @param value the identifier: an SCTID, a UUID, or a comma-joined UUID array
     * @param label the authoring label, or null
     */
    public record Ref(String kind, String value, String label) {

        /** The label when the block gave one, else {@code kind=value}. */
        public String display() {
            return label == null || label.isBlank() ? kind + "=" + value : label;
        }
    }

    /** An existential role, {@code type = filler}. */
    public record RoleSpec(Ref type, Ref filler) {
    }

    /** One definition set: its kind, supertypes, ungrouped roles and role groups, in block order. */
    public record SetSpec(boolean sufficient, List<Ref> supertypes, List<RoleSpec> roles, List<List<RoleSpec>> groups) {
    }

    /** A parsed block: the proposed concept's name (or null), its sets, and the stated gaps. */
    public record Proposal(String title, List<SetSpec> sets, List<String> gaps) {
    }

    /**
     * The definition tree of a proposal, with the gaps that keep parts of it out.
     *
     * @param tree the tree of every supertype and role whose concepts resolve, or null when
     *             nothing in the block resolves
     * @param gaps the stated gaps, then one entry per supertype or role left out of the tree
     */
    public record Built(DiTreeEntity tree, List<String> gaps) {
    }

    // ── Parsing (pure; no store access) ─────────────────────────────────

    /**
     * Parses a block body.
     *
     * @param literal the block body
     * @return the proposal, or null when a line is malformed or the block opens no definition set
     */
    public static Proposal parse(String literal) {
        String title = null;
        List<SetSpec> sets = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        SetSpec set = null;
        List<RoleSpec> group = null;
        int groupIndent = -1;

        for (String line : literal.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            String text = line.strip();
            if (group != null && indent <= groupIndent) {
                group = null;
            }

            Matcher keyed = KEYED.matcher(text);
            if (keyed.matches()) {
                if (keyed.group(1).equalsIgnoreCase("proposed")) {
                    title = keyed.group(2).strip();
                } else {
                    gaps.add(keyed.group(2).strip());
                }
                continue;
            }
            Matcher word = WORD.matcher(text);
            if (word.matches()) {
                String kind = word.group(1).toLowerCase(Locale.ROOT);
                if (kind.equals("group")) {
                    if (set == null) {
                        return null;
                    }
                    group = new ArrayList<>();
                    set.groups().add(group);
                    groupIndent = indent;
                } else {
                    set = new SetSpec(kind.equals("sufficient"), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
                    sets.add(set);
                    group = null;
                }
                continue;
            }
            if (set == null) {
                return null;
            }
            Matcher role = ROLE.matcher(text);
            if (role.matches()) {
                RoleSpec spec = new RoleSpec(
                        new Ref(role.group(1), role.group(2), role.group(3)),
                        new Ref(role.group(4), role.group(5), role.group(6)));
                (group != null ? group : set.roles()).add(spec);
                continue;
            }
            Matcher supertype = SUPERTYPE.matcher(text);
            if (supertype.matches() && group == null) {
                set.supertypes().add(new Ref(supertype.group(1), supertype.group(2), supertype.group(3)));
                continue;
            }
            return null;
        }
        return sets.isEmpty() ? null : new Proposal(title, sets, gaps);
    }

    // ── The definition tree (store access) ──────────────────────────────

    /**
     * Builds the definition tree of a proposal with {@link LogicalExpressionBuilder}, the builder
     * Komet's own windows define concepts with, so the tree has the shape of a stored definition:
     * a definition root over one vertex per set, each over an {@code And} of concept references
     * (supertypes) and existential roles, a role group being a role whose type is
     * {@link KernelConcept#ROLE_GROUP} over an {@code And} of its roles.
     *
     * @param proposal the parsed block
     * @return the tree and the gaps
     */
    public static Built build(Proposal proposal) {
        List<String> gaps = new ArrayList<>(proposal.gaps());
        LogicalExpressionBuilder builder = new LogicalExpressionBuilder();
        boolean any = false;

        for (SetSpec set : proposal.sets()) {
            List<LogicalAxiom.Atom> conjuncts = new ArrayList<>();
            for (Ref supertype : set.supertypes()) {
                ConceptFacade concept = resolveConcept(supertype);
                if (concept == null) {
                    gaps.add(supertype.display() + " — not in this knowledge base");
                } else {
                    conjuncts.add(builder.ConceptAxiom(concept));
                }
            }
            for (RoleSpec role : set.roles()) {
                LogicalAxiom.Atom atom = role(builder, role, gaps);
                if (atom != null) {
                    conjuncts.add(atom);
                }
            }
            for (List<RoleSpec> group : set.groups()) {
                List<LogicalAxiom.Atom> members = new ArrayList<>();
                for (RoleSpec role : group) {
                    LogicalAxiom.Atom atom = role(builder, role, gaps);
                    if (atom != null) {
                        members.add(atom);
                    }
                }
                if (!members.isEmpty()) {
                    conjuncts.add(builder.SomeRole(KernelConcept.ROLE_GROUP,
                            builder.And(members.toArray(LogicalAxiom.Atom[]::new))));
                }
            }
            if (conjuncts.isEmpty()) {
                continue;
            }
            LogicalAxiom.Atom.Connective.And and = builder.And(conjuncts.toArray(LogicalAxiom.Atom[]::new));
            if (set.sufficient()) {
                builder.SufficientSet(and);
            } else {
                builder.NecessarySet(and);
            }
            any = true;
        }
        return new Built(any ? tree(builder) : null, gaps);
    }

    /**
     * A role as an existential restriction, or null — with a gap recorded — when its type or
     * filler does not resolve.
     */
    private static LogicalAxiom.Atom role(LogicalExpressionBuilder builder, RoleSpec role, List<String> gaps) {
        ConceptFacade type = resolveConcept(role.type());
        ConceptFacade filler = resolveConcept(role.filler());
        if (type == null || filler == null) {
            String missing = type == null && filler == null ? "type and filler are"
                    : type == null ? "type is" : "filler is";
            gaps.add(role.type().display() + " = " + role.filler().display()
                    + " — its " + missing + " not in this knowledge base");
            return null;
        }
        return builder.SomeRole(type, builder.ConceptAxiom(filler));
    }

    /** The tree a builder has built, whichever form the expression holds it in. */
    private static DiTreeEntity tree(LogicalExpressionBuilder builder) {
        return switch (builder.build().sourceGraph()) {
            case DiTreeEntity tree -> tree;
            case DiTreeEntity.Builder treeBuilder -> treeBuilder.build();
            default -> throw new IllegalStateException("Unexpected source graph type");
        };
    }

    /** The concept a token names, or null when the open store does not hold it. */
    private static ConceptFacade resolveConcept(Ref ref) {
        PublicId pid = resolve(ref.kind(), ref.value());
        return pid == null ? null : EntityProxy.Concept.make(pid);
    }

    /**
     * Resolves a token to a {@link PublicId} that actually exists in the store, or null. The
     * existence gate is what makes a token identity-native: a fabricated id never draws a chip
     * or enters a tree.
     *
     * <p>A {@code nid} token is never resolved. A nid is local to the store that assigned it,
     * and the text can have been written against another store, where the same number names a
     * different component or none ({@code IKE-Network/ike-issues#1170}).
     *
     * @param kind  the token kind: {@code sctid}, {@code uuid}, {@code id}, or the earlier {@code nid}
     * @param value the id value as written
     * @return the public id of a component the open store holds, or null
     */
    public static PublicId resolve(String kind, String value) {
        try {
            PublicId pid = switch (kind) {
                case "sctid" -> PublicIds.of(UuidUtil.fromSNOMED(value));
                case "nid" -> null;
                default -> uuidPublicId(value); // uuid | id
            };
            if (pid != null && PrimitiveData.get().hasPublicId(pid)) {
                return pid;
            }
        } catch (RuntimeException ignored) {
            // Unresolvable / non-existent id.
        }
        return null;
    }

    /** Builds a {@link PublicId} from a single UUID or a comma-joined UUID array. */
    private static PublicId uuidPublicId(String value) {
        String[] parts = value.split(",");
        UUID[] uuids = new UUID[parts.length];
        for (int i = 0; i < parts.length; i++) {
            uuids[i] = UUID.fromString(parts[i].trim());
        }
        return PublicIds.of(uuids);
    }
}
