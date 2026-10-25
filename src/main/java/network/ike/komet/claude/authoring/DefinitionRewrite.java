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

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalAxiom;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpression;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpressionBuilder;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.komet.claude.authoring.DefinitionOperation.AddGroup;
import network.ike.komet.claude.authoring.DefinitionOperation.AddRole;
import network.ike.komet.claude.authoring.DefinitionOperation.AddSupertype;
import network.ike.komet.claude.authoring.DefinitionOperation.AddToGroup;
import network.ike.komet.claude.authoring.DefinitionOperation.RemoveFromGroup;
import network.ike.komet.claude.authoring.DefinitionOperation.RemoveGroup;
import network.ike.komet.claude.authoring.DefinitionOperation.RemoveRole;
import network.ike.komet.claude.authoring.DefinitionOperation.RemoveSupertype;
import network.ike.komet.claude.authoring.DefinitionOperation.ReplaceSupertype;
import network.ike.komet.claude.authoring.DefinitionOperation.SetKind;
import network.ike.komet.claude.authoring.DefinitionOperation.SetSelector;
import network.ike.komet.claude.authoring.DefinitionShape.Group;
import network.ike.komet.claude.authoring.DefinitionShape.Role;
import network.ike.komet.claude.authoring.DefinitionShape.RoleNode;
import network.ike.komet.claude.authoring.DefinitionShape.SetNode;
import network.ike.komet.claude.authoring.DefinitionShape.Supertype;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rewrites a stored stated definition into a target shape surgically
 * ({@code IKE-Network/ike-issues#1326}): only what differs is removed or added, and the rest of
 * the stored tree, what the grammar cannot express included, is left as it is.
 *
 * <ul>
 *   <li>A set the target continues stays, its kind changed when it changes.</li>
 *   <li>A supertype or ungrouped role the target does not hold is removed; one it adds is added
 *       to the set's {@code And}.</li>
 *   <li>A role group the target continues stays, its roles rewritten the same way; one left
 *       empty is removed, as is a set left empty that holds nothing the grammar cannot express.</li>
 * </ul>
 *
 * <p>Vertex identity is the store's: the write path correlates each new version of a
 * definition tree with the semantic's earlier versions and gives every vertex the UUID the
 * identity rule decides ({@code VertexIdentityWrite}, {@code IKE-Network/ike-issues#1310}). What
 * the rewrite leaves alone keeps its identity; a role given a new filler keeps the role's; a
 * replaced supertype, or a set whose kind changes, is a new vertex. The rewrite's part is to
 * change no more than the edit asks.
 *
 * <p>Every part is found by its vertex UUID, since removing a vertex renumbers the tree, and
 * none is changed in place: a stored tree's vertices are shared with the builder that copies it.
 */
final class DefinitionRewrite {

    private DefinitionRewrite() {
    }

    // ── Targets ─────────────────────────────────────────────────────────

    /**
     * The target the operations ask for: the stored shape, changed by each operation in order.
     *
     * @param stored     the stored shape
     * @param operations the operations
     * @param view       the view that names concepts in a refusal
     * @return the target
     * @throws IllegalArgumentException when an operation names what the definition does not
     *                                  hold, holds in more than one place, or already holds
     */
    static DefinitionShape apply(DefinitionShape stored, List<DefinitionOperation> operations, ViewCalculator view) {
        DefinitionShape target = stored.copy();
        for (DefinitionOperation operation : operations) {
            switch (operation) {
                case AddSupertype(SetSelector selector, ConceptFacade concept) -> {
                    SetNode set = set(target, selector, true);
                    if (set.supertypes.stream().anyMatch(supertype -> supertype.concept() == concept.nid())) {
                        throw new IllegalArgumentException(name(view, concept.nid()) + " is already a supertype in that set");
                    }
                    set.supertypes.add(new Supertype(concept.nid(), null));
                }
                case RemoveSupertype(ConceptFacade concept) -> {
                    SetNode set = holding(target, concept.nid(), view);
                    set.supertypes.removeIf(supertype -> supertype.concept() == concept.nid());
                }
                case ReplaceSupertype(ConceptFacade from, ConceptFacade to) -> {
                    SetNode set = holding(target, from.nid(), view);
                    if (set.supertypes.stream().anyMatch(supertype -> supertype.concept() == to.nid())) {
                        throw new IllegalArgumentException(name(view, to.nid()) + " is already a supertype in that set");
                    }
                    for (int i = 0; i < set.supertypes.size(); i++) {
                        if (set.supertypes.get(i).concept() == from.nid()) {
                            set.supertypes.set(i, new Supertype(to.nid(), null));
                        }
                    }
                }
                case AddRole(SetSelector selector, DefinitionOperation.Role role) -> {
                    SetNode set = set(target, selector, true);
                    if (set.roles.stream().anyMatch(node -> node.role().equals(role(role)))) {
                        throw new IllegalArgumentException("that set already holds " + text(view, role(role)));
                    }
                    set.roles.add(new RoleNode(role(role), null, null));
                }
                case RemoveRole(DefinitionOperation.Role role) -> {
                    Role wanted = role(role);
                    List<SetNode> holding = target.sets.stream()
                            .filter(set -> set.roles.stream().anyMatch(node -> node.role().equals(wanted))).toList();
                    one(holding, "an ungrouped role " + text(view, wanted)).roles.removeIf(node -> node.role().equals(wanted));
                }
                case AddGroup(SetSelector selector, List<DefinitionOperation.Role> roles) -> {
                    if (roles.isEmpty()) {
                        throw new IllegalArgumentException("a role group needs at least one role");
                    }
                    Group group = new Group();
                    roles.forEach(role -> group.roles.add(new RoleNode(role(role), null, null)));
                    set(target, selector, true).groups.add(group);
                }
                case RemoveGroup(List<DefinitionOperation.Role> roles) -> {
                    Group group = group(target, roles, view);
                    target.sets.forEach(set -> set.groups.remove(group));
                }
                case AddToGroup(List<DefinitionOperation.Role> roles, DefinitionOperation.Role role) -> {
                    Group group = group(target, roles, view);
                    if (group.roles.stream().anyMatch(node -> node.role().equals(role(role)))) {
                        throw new IllegalArgumentException("that role group already holds " + text(view, role(role)));
                    }
                    group.roles.add(new RoleNode(role(role), null, null));
                }
                case RemoveFromGroup(List<DefinitionOperation.Role> roles, DefinitionOperation.Role role) -> {
                    Group group = group(target, roles, view);
                    Role wanted = role(role);
                    RoleNode node = group.roles.stream().filter(r -> r.role().equals(wanted)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("that role group does not hold " + text(view, wanted)));
                    group.roles.remove(node);
                }
                case SetKind(SetSelector selector, boolean sufficient) -> set(target, selector, false).sufficient = sufficient;
            }
        }
        return target;
    }

    /**
     * Makes the target of an "after" block continue the stored definition: each set continues a
     * stored set of its kind, in order, then any stored set left, its kind changed; within a set,
     * a group continues the stored group holding the same roles, else the one it shares the most
     * roles with.
     *
     * @param stored the stored shape
     * @param target the block's shape, given origins here
     */
    static void align(DefinitionShape stored, DefinitionShape target) {
        List<SetNode> free = new ArrayList<>(stored.sets);
        for (SetNode set : target.sets) {
            free.stream().filter(candidate -> candidate.sufficient == set.sufficient).findFirst().ifPresent(match -> {
                continueSet(set, match);
                free.remove(match);
            });
        }
        for (SetNode set : target.sets) {
            if (set.origin == null && !free.isEmpty()) {
                continueSet(set, free.removeFirst());
            }
        }
    }

    private static void continueSet(SetNode set, SetNode stored) {
        set.origin = stored.origin;
        set.andVertex = stored.andVertex;
        List<Group> free = new ArrayList<>(stored.groups);
        for (Group group : set.groups) {
            List<Role> roles = group.roles.stream().map(RoleNode::role).toList();
            free.stream().filter(candidate -> candidate.holds(roles)).findFirst().ifPresent(match -> {
                continueGroup(group, match);
                free.remove(match);
            });
        }
        for (Group group : set.groups) {
            if (group.origin != null) {
                continue;
            }
            Group best = null;
            int bestShared = 0;
            for (Group candidate : free) {
                int shared = shared(candidate, group);
                if (shared > bestShared) {
                    best = candidate;
                    bestShared = shared;
                }
            }
            if (best != null) {
                continueGroup(group, best);
                free.remove(best);
            }
        }
    }

    private static void continueGroup(Group group, Group stored) {
        group.origin = stored.origin;
        group.andVertex = stored.andVertex;
    }

    private static int shared(Group a, Group b) {
        List<Role> left = new ArrayList<>(a.roles.stream().map(RoleNode::role).toList());
        int shared = 0;
        for (RoleNode node : b.roles) {
            if (left.remove(node.role())) {
                shared++;
            }
        }
        return shared;
    }

    // ── The rewrite ─────────────────────────────────────────────────────

    /**
     * The stored tree rewritten to the target, or null when the target is what is stored.
     *
     * @param tree   the stored tree
     * @param stored its shape
     * @param target the shape to leave, its sets and groups naming what they continue
     * @return the rewritten tree, or null when nothing changes
     */
    static DiTreeEntity rewrite(DiTreeEntity tree, DefinitionShape stored, DefinitionShape target) {
        Edits edits = new Edits();
        Map<UUID, SetNode> targetSets = new HashMap<>();
        target.sets.stream().filter(set -> set.origin != null).forEach(set -> targetSets.put(set.origin, set));

        for (SetNode set : stored.sets) {
            SetNode to = targetSets.get(set.origin);
            if ((to == null || to.isEmpty()) && set.opaque == 0) {
                edits.removals.add(set.origin);
                continue;
            }
            if (to == null) {
                to = new SetNode();
                to.sufficient = set.sufficient;
            }
            if (to.sufficient != set.sufficient) {
                edits.kinds.put(set.origin, to.sufficient);
            }
            List<Supertype> left = new ArrayList<>(set.supertypes);
            for (Supertype supertype : to.supertypes) {
                Supertype kept = left.stream().filter(s -> s.concept() == supertype.concept()).findFirst().orElse(null);
                if (kept != null) {
                    left.remove(kept);
                } else {
                    edits.supertypes.add(new NewSupertype(set.andVertex, supertype.concept()));
                }
            }
            left.forEach(supertype -> edits.removals.add(supertype.vertex()));
            roles(set.roles, to.roles, set.andVertex, edits);

            Map<UUID, Group> targetGroups = new HashMap<>();
            to.groups.stream().filter(group -> group.origin != null).forEach(group -> targetGroups.put(group.origin, group));
            for (Group group : set.groups) {
                Group toGroup = targetGroups.get(group.origin);
                if (toGroup == null || toGroup.roles.isEmpty()) {
                    edits.removals.add(group.origin);
                } else {
                    roles(group.roles, toGroup.roles, group.andVertex, edits);
                }
            }
            to.groups.stream().filter(group -> group.origin == null && !group.roles.isEmpty())
                    .forEach(group -> edits.groups.add(new NewGroup(set.andVertex,
                            group.roles.stream().map(RoleNode::role).toList())));
        }
        target.sets.stream().filter(set -> set.origin == null && !set.isEmpty()).forEach(edits.sets::add);
        return edits.isEmpty() ? null : edits.applyTo(tree);
    }

    /** The edits that turn stored roles into target roles under one {@code And}: an unchanged role stays, the rest are removed or added. */
    private static void roles(List<RoleNode> stored, List<RoleNode> target, UUID andVertex, Edits edits) {
        List<RoleNode> left = new ArrayList<>(stored);
        for (RoleNode node : target) {
            RoleNode kept = left.stream().filter(r -> r.role().equals(node.role())).findFirst().orElse(null);
            if (kept != null) {
                left.remove(kept);
            } else {
                edits.roles.add(new NewRole(andVertex, node.role()));
            }
        }
        left.forEach(node -> edits.removals.add(node.roleVertex()));
    }

    private record NewSupertype(UUID andVertex, long concept) {
    }

    private record NewRole(UUID andVertex, Role role) {
    }

    private record NewGroup(UUID andVertex, List<Role> roles) {
    }

    private static final class Edits {
        final List<UUID> removals = new ArrayList<>();
        final Map<UUID, Boolean> kinds = new LinkedHashMap<>();
        final List<NewSupertype> supertypes = new ArrayList<>();
        final List<NewRole> roles = new ArrayList<>();
        final List<NewGroup> groups = new ArrayList<>();
        final List<SetNode> sets = new ArrayList<>();

        boolean isEmpty() {
            return removals.isEmpty() && kinds.isEmpty() && supertypes.isEmpty() && roles.isEmpty()
                    && groups.isEmpty() && sets.isEmpty();
        }

        /** Removes first, one vertex at a time and each found anew, then adds to what is left. */
        DiTreeEntity applyTo(DiTreeEntity tree) {
            DiTreeEntity pruned = tree;
            for (UUID vertex : removals) {
                int index = index(pruned, vertex);
                if (index >= 0) {   // absent: it went with a set or group removed before it
                    pruned = pruned.removeVertex(index).build();
                }
            }
            // Additions only append vertices, so the pruned tree's indexes hold throughout.
            DiTreeEntity base = pruned;
            LogicalExpressionBuilder builder = new LogicalExpressionBuilder(base);
            kinds.forEach((set, sufficient) -> builder.changeSetType(
                    (LogicalAxiom.LogicalSet) builder.get(expectIndex(base, set)),
                    sufficient ? KernelConcept.SUFFICIENT_SET : KernelConcept.NECESSARY_SET));
            for (NewSupertype supertype : supertypes) {
                builder.addToFirstAnd(expectIndex(base, supertype.andVertex()), builder.ConceptAxiom(concept(supertype.concept())));
            }
            for (NewRole role : roles) {
                builder.addToFirstAnd(expectIndex(base, role.andVertex()), role(builder, role.role()));
            }
            for (NewGroup group : groups) {
                builder.addToFirstAnd(expectIndex(base, group.andVertex()), group(builder, group.roles()));
            }
            for (SetNode set : sets) {
                List<LogicalAxiom.Atom> atoms = new ArrayList<>();
                set.supertypes.forEach(supertype -> atoms.add(builder.ConceptAxiom(concept(supertype.concept()))));
                set.roles.forEach(node -> atoms.add(role(builder, node.role())));
                set.groups.stream().filter(group -> !group.roles.isEmpty()).forEach(group ->
                        atoms.add(group(builder, group.roles.stream().map(RoleNode::role).toList())));
                LogicalAxiom.Atom.Connective.And and = builder.And(atoms.toArray(LogicalAxiom.Atom[]::new));
                if (set.sufficient) {
                    builder.SufficientSet(and);
                } else {
                    builder.NecessarySet(and);
                }
            }
            return switch (builder.build().sourceGraph()) {
                case DiTreeEntity built -> built;
                case DiTreeEntity.Builder treeBuilder -> treeBuilder.build();
                default -> throw new IllegalStateException("Unexpected source graph type");
            };
        }
    }

    private static LogicalAxiom.Atom role(LogicalExpressionBuilder builder, Role role) {
        return builder.SomeRole(concept(role.type()), builder.ConceptAxiom(concept(role.filler())));
    }

    private static LogicalAxiom.Atom group(LogicalExpressionBuilder builder, List<Role> roles) {
        LogicalAxiom.Atom[] members = roles.stream()
                .map(role -> role(builder, role)).toArray(LogicalAxiom.Atom[]::new);
        return builder.SomeRole(KernelConcept.ROLE_GROUP, builder.And(members));
    }

    /** The index of the vertex with this UUID, or -1. */
    private static int index(DiTreeEntity tree, UUID vertex) {
        for (LogicalAxiom axiom : new LogicalExpression(tree).nodesOfType(LogicalAxiom.class)) {
            if (axiom.vertexUUID().equals(vertex)) {
                return axiom.vertexIndex();
            }
        }
        return -1;
    }

    private static int expectIndex(DiTreeEntity tree, UUID vertex) {
        int index = index(tree, vertex);
        if (index < 0) {
            throw new IllegalStateException("the definition has no vertex " + vertex);
        }
        return index;
    }

    // ── Finding what an operation names ─────────────────────────────────

    /** The selected set; the first of a kind is made when there is none and {@code make} allows it. */
    private static SetNode set(DefinitionShape target, SetSelector selector, boolean make) {
        List<SetNode> ofKind = target.sets.stream().filter(set -> set.sufficient == selector.sufficient()).toList();
        if (selector.ordinal() < ofKind.size()) {
            return ofKind.get(selector.ordinal());
        }
        if (make && selector.ordinal() == 0) {
            SetNode set = new SetNode();
            set.sufficient = selector.sufficient();
            target.sets.add(set);
            return set;
        }
        throw new IllegalArgumentException("the definition has " + ofKind.size() + " "
                + (selector.sufficient() ? "sufficient" : "necessary") + " set(s), so there is no set " + selector.ordinal());
    }

    /** The one set holding the supertype. */
    private static SetNode holding(DefinitionShape target, long concept, ViewCalculator view) {
        List<SetNode> holding = target.sets.stream()
                .filter(set -> set.supertypes.stream().anyMatch(supertype -> supertype.concept() == concept)).toList();
        if (holding.isEmpty()) {
            List<String> supertypes = target.sets.stream().flatMap(set -> set.supertypes.stream())
                    .map(supertype -> name(view, supertype.concept())).toList();
            throw new IllegalArgumentException(name(view, concept) + " is not a stated supertype; the stated supertypes are "
                    + supertypes);
        }
        return one(holding, "the supertype " + name(view, concept));
    }

    /** The one group holding exactly the roles. */
    private static Group group(DefinitionShape target, List<DefinitionOperation.Role> roles, ViewCalculator view) {
        List<Role> wanted = roles.stream().map(DefinitionRewrite::role).toList();
        List<Group> groups = target.sets.stream().flatMap(set -> set.groups.stream()).filter(group -> group.holds(wanted)).toList();
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("no role group holds exactly " + wanted.stream().map(role -> text(view, role)).toList());
        }
        return one(groups, "a role group of " + wanted.stream().map(role -> text(view, role)).toList());
    }

    private static <T> T one(List<T> found, String what) {
        if (found.isEmpty()) {
            throw new IllegalArgumentException("the definition holds no " + what);
        }
        if (found.size() > 1) {
            throw new IllegalArgumentException("the definition holds " + what + " in more than one place; "
                    + "state the whole definition as an after block instead");
        }
        return found.getFirst();
    }

    private static Role role(DefinitionOperation.Role role) {
        return new Role(role.type().nid(), role.filler().nid());
    }

    private static ConceptFacade concept(long nid) {
        return EntityProxy.Concept.make(nid);
    }

    private static String text(ViewCalculator view, Role role) {
        return name(view, role.type()) + " = " + name(view, role.filler());
    }

    private static String name(ViewCalculator view, long nid) {
        return ComponentText.name(view, nid);
    }
}
