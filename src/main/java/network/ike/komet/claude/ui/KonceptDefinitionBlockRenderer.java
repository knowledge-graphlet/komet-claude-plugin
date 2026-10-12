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
package network.ike.komet.claude.ui;

import dev.ikm.komet.framework.Identicon;
import dev.ikm.komet.kview.controls.ComponentItem;
import dev.ikm.komet.kview.controls.KLReadOnlyDiTreeControl;
import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.logic.PremiseType;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelTerm;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.koncept.ComponentText;
import org.eclipse.collections.api.factory.primitive.LongObjectMaps;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code koncept-definition} fenced block — a concept definition the assistant proposes —
 * rendered by the axiom control the KL windows show stated and inferred definitions with,
 * {@link KLReadOnlyDiTreeControl} ({@code IKE-Network/ike-issues#1292}). A proposal therefore
 * reads exactly as a stored definition does, chips, glyphs and the hover peek at each concept's
 * own stated definition included, instead of being drawn as box art.
 *
 * <p>The block names its concepts with the same {@code k:} tokens as a {@code koncept-tree}
 * block, under a few structural words; indentation only scopes a role group:
 *
 * <pre>{@code
 * ```koncept-definition
 * proposed: Eosinophilic esophagitis unresponsive to dupilumab
 * necessary
 *   k:sctid=235599003[Eosinophilic esophagitis]
 *   group
 *     k:sctid=116676008[Associated morphology] = k:sctid=445520000[Eosinophilic infiltration]
 *     k:sctid=363698007[Finding site] = k:sctid=32849002[Esophageal structure]
 *   group
 *     k:sctid=263502005[Clinical course] = k:sctid=90734009[Chronic]
 * gap: no attribute relates a disorder to non-response to a specific substance
 * ```
 * }</pre>
 *
 * <ul>
 *   <li>{@code proposed: <name>} names the concept being proposed; it titles the control.</li>
 *   <li>{@code necessary} or {@code sufficient} opens a definition set; a block may hold several.</li>
 *   <li>Inside a set, a lone token is a supertype and {@code type = filler} an existential role.</li>
 *   <li>{@code group} opens a role group; the role lines indented beneath it belong to it.</li>
 *   <li>{@code gap: <text>} states a modelling gap — a role type or filler the knowledge base has
 *       no concept for.</li>
 * </ul>
 *
 * <p>A definition tree holds only concepts that exist, so a gap is never a node in it: the gaps
 * are listed beneath the control. A token whose identifier the store does not hold is a gap too —
 * its supertype or role is left out of the tree and named in that list, so a fabricated or
 * mistyped identifier is visible rather than silently drawn. A line that is none of the above
 * declines the whole block, which then renders as preformatted text, as a garbled
 * {@code koncept-tree} does.
 *
 * <p>Parsing is pure string work, done eagerly to decide whether the block is well formed;
 * resolution, the tree and the control are built lazily by the {@link BlockPiece} supplier on the
 * JavaFX application thread.
 */
final class KonceptDefinitionBlockRenderer implements BlockRenderer {

    /** The fenced-block language tag this renderer answers to. */
    static final String TAG = "koncept-definition";

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

    /** The view used to resolve names and peeked definitions; null declines every block. */
    private final ViewCalculator viewCalc;
    /** Base body font size (px); the gap list scales from it. */
    private final double base;

    /**
     * @param viewCalc the view that names each concept and supplies the peeked definitions; with
     *                 none, every block falls through to preformatted text
     * @param base     base body font size in px
     */
    KonceptDefinitionBlockRenderer(ViewCalculator viewCalc, double base) {
        this.viewCalc = viewCalc;
        this.base = base;
    }

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        if (viewCalc == null || !TAG.equals(KonceptTreeBlockRenderer.firstWord(info))) {
            return List.of();
        }
        String body = literal == null ? "" : literal;
        Proposal proposal = parse(body);
        if (proposal == null) {
            return List.of();
        }
        return List.of(new BlockPiece(() -> buildView(proposal), body.stripTrailing()));
    }

    // ── Parsing (pure; no store access) ─────────────────────────────────

    /** A concept as the block names it: token kind, identifier value, authoring label (or null). */
    record Ref(String kind, String value, String label) {

        /** The label when the block gave one, else {@code kind=value}. */
        String display() {
            return label == null || label.isBlank() ? kind + "=" + value : label;
        }
    }

    /** An existential role, {@code type = filler}. */
    record RoleSpec(Ref type, Ref filler) {
    }

    /** One definition set: its kind, supertypes, ungrouped roles and role groups, in block order. */
    record SetSpec(boolean sufficient, List<Ref> supertypes, List<RoleSpec> roles, List<List<RoleSpec>> groups) {
    }

    /** A parsed block: the proposed concept's name (or null), its sets, and the stated gaps. */
    record Proposal(String title, List<SetSpec> sets, List<String> gaps) {
    }

    /**
     * Parses a block body.
     *
     * @param literal the block body
     * @return the proposal, or null when a line is malformed or the block opens no definition set
     */
    static Proposal parse(String literal) {
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
     * The definition tree of a proposal, with the gaps that keep parts of it out.
     *
     * @param tree the tree of every supertype and role whose concepts resolve, or null when
     *             nothing in the block resolves
     * @param gaps the stated gaps, then one entry per supertype or role left out of the tree
     */
    record Built(DiTreeEntity tree, List<String> gaps) {
    }

    /**
     * Builds the definition tree of a proposal in the shape the store holds one — the shape
     * {@link KLReadOnlyDiTreeControl}'s skin and {@code ConceptDefinition} both read: a definition
     * root over one vertex per set, each over an {@code And} of concept references (supertypes)
     * and existential roles, a role group being a role whose type is {@link KernelTerm#ROLE_GROUP}
     * over an {@code And} of its roles.
     *
     * @param proposal the parsed block
     * @return the tree and the gaps
     */
    static Built build(Proposal proposal) {
        List<String> gaps = new ArrayList<>(proposal.gaps());
        DiTreeEntity.Builder builder = DiTreeEntity.builder();
        EntityVertex root = EntityVertex.make(KernelTerm.DEFINITION_ROOT);
        builder.setRoot(root);
        boolean any = false;

        for (SetSpec set : proposal.sets()) {
            EntityVertex and = EntityVertex.make(KernelTerm.AND);
            List<Edge> edges = new ArrayList<>();
            for (Ref supertype : set.supertypes()) {
                ConceptFacade concept = resolve(supertype);
                if (concept == null) {
                    gaps.add(supertype.display() + " — not in this knowledge base");
                } else {
                    edges.add(new Edge(conceptReference(concept), and));
                }
            }
            for (RoleSpec role : set.roles()) {
                addRole(role, and, edges, gaps);
            }
            for (List<RoleSpec> group : set.groups()) {
                EntityVertex groupRole = roleVertex(KernelTerm.ROLE_GROUP);
                EntityVertex groupAnd = EntityVertex.make(KernelTerm.AND);
                List<Edge> members = new ArrayList<>();
                for (RoleSpec role : group) {
                    addRole(role, groupAnd, members, gaps);
                }
                if (!members.isEmpty()) {
                    edges.add(new Edge(groupRole, and));
                    edges.add(new Edge(groupAnd, groupRole));
                    edges.addAll(members);
                }
            }
            if (edges.isEmpty()) {
                continue;
            }
            EntityVertex setVertex = EntityVertex.make(set.sufficient() ? KernelTerm.SUFFICIENT_SET : KernelTerm.NECESSARY_SET);
            builder.addEdge(setVertex, root);
            builder.addEdge(and, setVertex);
            for (Edge edge : edges) {
                builder.addEdge(edge.child(), edge.parent());
            }
            any = true;
        }
        return new Built(any ? builder.build() : null, gaps);
    }

    /** One edge of the tree being built. */
    private record Edge(EntityVertex child, EntityVertex parent) {
    }

    /**
     * Adds a role under {@code parent} — the role vertex and its filler's concept reference — or,
     * when its type or filler does not resolve, records the gap instead.
     */
    private static void addRole(RoleSpec role, EntityVertex parent, List<Edge> edges, List<String> gaps) {
        ConceptFacade type = resolve(role.type());
        ConceptFacade filler = resolve(role.filler());
        if (type == null || filler == null) {
            String missing = type == null && filler == null ? "type and filler are"
                    : type == null ? "type is" : "filler is";
            gaps.add(role.type().display() + " = " + role.filler().display()
                    + " — its " + missing + " not in this knowledge base");
            return;
        }
        EntityVertex roleVertex = roleVertex(type);
        edges.add(new Edge(roleVertex, parent));
        edges.add(new Edge(conceptReference(filler), roleVertex));
    }

    /** An existential role vertex of the given type. */
    private static EntityVertex roleVertex(ConceptFacade type) {
        EntityVertex role = EntityVertex.make(KernelTerm.ROLE);
        MutableLongObjectMap<Object> properties = LongObjectMaps.mutable.empty();
        properties.put(KernelTerm.ROLE_TYPE.nid(), type);
        properties.put(KernelTerm.ROLE_OPERATOR.nid(), KernelTerm.EXISTENTIAL_RESTRICTION);
        role.setProperties(properties);
        return role;
    }

    /** A concept reference vertex, holding the concept under its own meaning. */
    private static EntityVertex conceptReference(ConceptFacade concept) {
        EntityVertex reference = EntityVertex.make(KernelTerm.CONCEPT_REFERENCE);
        MutableLongObjectMap<Object> properties = LongObjectMaps.mutable.empty();
        properties.put(KernelTerm.CONCEPT_REFERENCE.nid(), concept);
        reference.setProperties(properties);
        return reference;
    }

    /** The concept a token names, or null when the open store does not hold it. */
    private static ConceptFacade resolve(Ref ref) {
        PublicId pid = KonceptTreeBlockRenderer.resolvePid(ref.kind(), ref.value());
        return pid == null ? null : EntityProxy.Concept.make(pid);
    }

    // ── View building (JavaFX application thread) ───────────────────────

    /** The control over the tree, with the gaps listed beneath it. */
    private Node buildView(Proposal proposal) {
        Built built = build(proposal);
        VBox column = new VBox(4);
        column.getStyleClass().add("koncept-definition");
        column.setCursor(Cursor.DEFAULT);
        column.setFillWidth(false);

        if (built.tree() != null) {
            KLReadOnlyDiTreeControl control = new KLReadOnlyDiTreeControl();
            control.setComponentItemResolver(nid -> {
                PublicId publicId = ComponentText.publicId(nid).orElse(null);
                return new ComponentItem(ComponentText.name(viewCalc, nid),
                        publicId == null ? null : Identicon.generateIdenticonImage(publicId),
                        publicId, EntityHandle.get(nid).isConcept());
            });
            control.setDescriptionResolver(nid -> ComponentText.name(viewCalc, nid));
            control.setDefinitionResolver(nid ->
                    viewCalc.getAxiomTreeForEntity(nid, PremiseType.STATED).orElse(null));
            control.setTitle(proposal.title() == null ? "Proposed definition" : "Proposed: " + proposal.title());
            control.setValue(built.tree());
            column.getChildren().add(control);
        }
        if (!built.gaps().isEmpty()) {
            Label heading = new Label(built.gaps().size() == 1 ? "Modelling gap" : "Modelling gaps");
            heading.setFont(Font.font(null, FontWeight.BOLD, base));
            column.getChildren().add(heading);
            for (String gap : built.gaps()) {
                Label entry = new Label("• " + gap);
                entry.setFont(Font.font(base));
                entry.setWrapText(true);
                column.getChildren().add(entry);
            }
        }
        if (column.getChildren().isEmpty()) {
            return new Region();
        }
        column.setMaxWidth(Double.MAX_VALUE);
        column.parentProperty().addListener((obs, oldParent, newParent) -> {
            column.maxWidthProperty().unbind();
            if (newParent instanceof Region host) {
                column.maxWidthProperty().bind(host.widthProperty());
            }
        });
        return column;
    }
}
