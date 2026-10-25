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
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalAxiom;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpression;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.komet.claude.authoring.ProposedDefinition.Proposal;
import network.ike.komet.claude.authoring.ProposedDefinition.Ref;
import network.ike.komet.claude.authoring.ProposedDefinition.RoleSpec;
import network.ike.komet.claude.authoring.ProposedDefinition.SetSpec;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A stated definition as the {@code koncept-definition} grammar sees it — necessary and
 * sufficient sets of supertypes, existential roles and role groups — with the vertex each part
 * is stored as ({@code IKE-Network/ike-issues#1326}).
 *
 * <p>A shape read from a stored tree ({@link #of(DiTreeEntity)}) knows each part's vertex UUID,
 * which is how an edit finds what it changes: removing a vertex renumbers the tree, so an index
 * does not survive the edit that removes another part. What the grammar cannot express — a
 * property set, a feature, an interval role, a disjoint, a universal or nested role — is
 * counted, not modelled, and an edit leaves it where it is.
 *
 * <p>A target shape, the definition an edit should leave, is a {@link #copy} of the stored one
 * changed by operations, or one read from an "after" block ({@link #of(Proposal)}). Its sets and
 * groups name the stored set or group they continue by its vertex UUID ({@code origin}); a part
 * with no origin is new.
 */
final class DefinitionShape {

    /** An existential role by its type and filler. */
    record Role(long type, long filler) {
    }

    /** A supertype and the vertex that holds it; the vertex is null in a target shape. */
    record Supertype(long concept, UUID vertex) {
    }

    /** A role and the vertices of the role and its filler; the vertices are null in a target shape. */
    record RoleNode(Role role, UUID roleVertex, UUID fillerVertex) {
    }

    /** A role group: the role-group role vertex it continues ({@code origin}), its {@code And}, and its roles. */
    static final class Group {
        UUID origin;
        UUID andVertex;
        final List<RoleNode> roles = new ArrayList<>();

        boolean holds(List<Role> exactly) {
            List<Role> left = new ArrayList<>(exactly);
            for (RoleNode node : roles) {
                if (!left.remove(node.role())) {
                    return false;
                }
            }
            return left.isEmpty();
        }
    }

    /** A necessary or sufficient set: the set vertex it continues ({@code origin}), its {@code And}, and its parts. */
    static final class SetNode {
        UUID origin;
        UUID andVertex;
        boolean sufficient;
        final List<Supertype> supertypes = new ArrayList<>();
        final List<RoleNode> roles = new ArrayList<>();
        final List<Group> groups = new ArrayList<>();
        /** Parts of the set the grammar cannot express, left where they are. */
        int opaque;

        boolean isEmpty() {
            return supertypes.isEmpty() && roles.isEmpty() && groups.stream().allMatch(group -> group.roles.isEmpty());
        }
    }

    final List<SetNode> sets = new ArrayList<>();
    /** Sets the grammar cannot express (property sets, inclusion sets, ...), left where they are. */
    int opaque;

    /**
     * The shape of a stored tree, with the vertex UUID of every part.
     *
     * @param tree a stated definition tree
     * @return its shape
     */
    static DefinitionShape of(DiTreeEntity tree) {
        DefinitionShape shape = new DefinitionShape();
        for (LogicalAxiom.LogicalSet set : new LogicalExpression(tree).definitionRoot().sets()) {
            boolean sufficient = set instanceof LogicalAxiom.LogicalSet.SufficientSet;
            if (!(sufficient || set instanceof LogicalAxiom.LogicalSet.NecessarySet)
                    || set.elements().size() != 1
                    || !(set.elements().getOnly() instanceof LogicalAxiom.Atom.Connective.And and)) {
                shape.opaque++;
                continue;
            }
            SetNode node = new SetNode();
            node.origin = set.vertexUUID();
            node.andVertex = and.vertexUUID();
            node.sufficient = sufficient;
            for (LogicalAxiom.Atom atom : and.elements()) {
                switch (atom) {
                    case LogicalAxiom.Atom.ConceptAxiom concept ->
                            node.supertypes.add(new Supertype(concept.concept().nid(), concept.vertexUUID()));
                    case LogicalAxiom.Atom.TypedAtom.Role role when isGroup(role) -> {
                        Group group = group(role);
                        if (group == null) {
                            node.opaque++;
                        } else {
                            node.groups.add(group);
                        }
                    }
                    case LogicalAxiom.Atom.TypedAtom.Role role when roleNode(role) != null -> node.roles.add(roleNode(role));
                    default -> node.opaque++;
                }
            }
            shape.sets.add(node);
        }
        return shape;
    }

    /**
     * The shape an "after" block asks for. Every token must name a concept the store holds: an
     * edit of an existing concept leaves out nothing silently, so an unresolved token, or a stated
     * gap, refuses the block.
     *
     * @param proposal the parsed block
     * @return its shape, with no origins
     * @throws IllegalArgumentException naming each token that does not resolve, and each gap
     */
    static DefinitionShape of(Proposal proposal) {
        List<String> unresolved = new ArrayList<>(proposal.gaps().stream().map(gap -> "gap: " + gap).toList());
        DefinitionShape shape = new DefinitionShape();
        for (SetSpec spec : proposal.sets()) {
            SetNode node = new SetNode();
            node.sufficient = spec.sufficient();
            for (Ref supertype : spec.supertypes()) {
                Long concept = nid(supertype, unresolved);
                if (concept != null) {
                    node.supertypes.add(new Supertype(concept, null));
                }
            }
            for (RoleSpec role : spec.roles()) {
                Role resolved = role(role, unresolved);
                if (resolved != null) {
                    node.roles.add(new RoleNode(resolved, null, null));
                }
            }
            for (List<RoleSpec> roles : spec.groups()) {
                Group group = new Group();
                for (RoleSpec role : roles) {
                    Role resolved = role(role, unresolved);
                    if (resolved != null) {
                        group.roles.add(new RoleNode(resolved, null, null));
                    }
                }
                node.groups.add(group);
            }
            shape.sets.add(node);
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalArgumentException("the definition names what this knowledge base does not hold, so it "
                    + "cannot replace the stored one: " + String.join("; ", unresolved));
        }
        return shape;
    }

    /**
     * A target shape that starts as this one: every set and group continues its stored one.
     *
     * @return the copy
     */
    DefinitionShape copy() {
        DefinitionShape copy = new DefinitionShape();
        copy.opaque = opaque;
        for (SetNode set : sets) {
            SetNode node = new SetNode();
            node.origin = set.origin;
            node.andVertex = set.andVertex;
            node.sufficient = set.sufficient;
            node.opaque = set.opaque;
            node.supertypes.addAll(set.supertypes);
            node.roles.addAll(set.roles);
            for (Group group : set.groups) {
                Group g = new Group();
                g.origin = group.origin;
                g.andVertex = group.andVertex;
                g.roles.addAll(group.roles);
                node.groups.add(g);
            }
            copy.sets.add(node);
        }
        return copy;
    }

    /** @return whether the grammar expresses all of it, so an "after" block can stand for it */
    boolean expressible() {
        return opaque == 0 && sets.stream().allMatch(set -> set.opaque == 0);
    }

    /** @return how many parts the grammar cannot express */
    int opaqueParts() {
        return opaque + sets.stream().mapToInt(set -> set.opaque).sum();
    }

    /** @return whether it holds nothing an edit could leave, expressible or not */
    boolean isEmpty() {
        return opaque == 0 && sets.stream().allMatch(set -> set.isEmpty() && set.opaque == 0);
    }

    /**
     * The shape as a {@code koncept-definition} block body, every concept a badge token. Parts the
     * grammar cannot express are not written.
     *
     * @param view the view that labels the tokens
     * @return the block body
     */
    String block(ViewCalculator view) {
        StringBuilder block = new StringBuilder();
        for (SetNode set : sets) {
            block.append(set.sufficient ? "sufficient" : "necessary").append('\n');
            set.supertypes.forEach(supertype -> block.append("  ").append(token(view, supertype.concept())).append('\n'));
            set.roles.forEach(role -> block.append("  ").append(line(view, role.role())).append('\n'));
            for (Group group : set.groups) {
                if (group.roles.isEmpty()) {
                    continue;
                }
                block.append("  group\n");
                group.roles.forEach(role -> block.append("    ").append(line(view, role.role())).append('\n'));
            }
        }
        return block.toString();
    }

    private static String line(ViewCalculator view, Role role) {
        return token(view, role.type()) + " = " + token(view, role.filler());
    }

    private static String token(ViewCalculator view, long nid) {
        return ComponentText.badge(view, nid).orElseGet(() -> ComponentText.identified(view, nid));
    }

    private static boolean isGroup(LogicalAxiom.Atom.TypedAtom.Role role) {
        return existential(role) && role.type().nid() == KernelConcept.ROLE_GROUP.nid();
    }

    private static boolean existential(LogicalAxiom.Atom.TypedAtom.Role role) {
        return role.roleOperator().nid() == KernelConcept.EXISTENTIAL_RESTRICTION.nid();
    }

    /** A role group whose every member the grammar expresses, or null. */
    private static Group group(LogicalAxiom.Atom.TypedAtom.Role role) {
        if (!(role.restriction() instanceof LogicalAxiom.Atom.Connective.And and)) {
            return null;
        }
        Group group = new Group();
        group.origin = role.vertexUUID();
        group.andVertex = and.vertexUUID();
        for (LogicalAxiom.Atom member : and.elements()) {
            RoleNode node = member instanceof LogicalAxiom.Atom.TypedAtom.Role memberRole && !isGroup(memberRole)
                    ? roleNode(memberRole) : null;
            if (node == null) {
                return null;
            }
            group.roles.add(node);
        }
        return group;
    }

    /** An existential role whose filler is a concept, or null. */
    private static RoleNode roleNode(LogicalAxiom.Atom.TypedAtom.Role role) {
        if (existential(role) && role.restriction() instanceof LogicalAxiom.Atom.ConceptAxiom filler) {
            return new RoleNode(new Role(role.type().nid(), filler.concept().nid()), role.vertexUUID(), filler.vertexUUID());
        }
        return null;
    }

    private static Role role(RoleSpec role, List<String> unresolved) {
        Long type = nid(role.type(), unresolved);
        Long filler = nid(role.filler(), unresolved);
        return type == null || filler == null ? null : new Role(type, filler);
    }

    /** The concept a token names, or null, with the token recorded, when the store does not hold it. */
    private static Long nid(Ref ref, List<String> unresolved) {
        PublicId pid = ProposedDefinition.resolve(ref.kind(), ref.value());
        if (pid == null) {
            unresolved.add(ref.display() + " is not in this knowledge base");
            return null;
        }
        return EntityProxy.Concept.make(pid).nid();
    }
}
