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
package network.ike.komet.claude.anf;

import dev.ikm.komet.framework.controls.KonceptBadge;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.tinkar.coordinate.logic.PremiseType;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import network.ike.komet.claude.koncept.ConceptDefinition;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

/**
 * The inline detail view for one {@link AnfSlot}: it makes the grounded-vs-proposed distinction
 * unmistakable by showing, for a grounded concept, its real descriptions and stated/inferred
 * axioms; for a proposed {@link AnfSlot.Candidate}, only its provisional data with an explicit
 * "no concept yet"; and for an {@link AnfSlot.Clarify}, the open question. A grounded concept
 * always has descriptions and (usually) axioms; a proposal never fabricates them — that absence is
 * the anti-hallucination guarantee made visible.
 *
 * <p>Rendering is plugin-local and adds no module edge: descriptions come from
 * {@link ViewCalculator#getDescriptionsForComponent(int)} and axioms from the plugin's own
 * {@link ConceptDefinition} (which reads {@code view.getAxiomTreeForEntity}), shown as identicon
 * {@link KonceptBadge}s. It is built on the FX thread when a chip is clicked; the lookups are
 * in-memory store reads.
 */
public final class ConceptDetailPane {

    private static final String HEADER_STYLE = "-fx-font-weight: bold; -fx-font-size: 13;";
    private static final String SECTION_STYLE = "-fx-font-weight: bold; -fx-text-fill: #666666;";
    private static final String PROPOSED_STYLE = "-fx-text-fill: #b5651d;";
    private static final String MUTED_STYLE = "-fx-text-fill: #888888; -fx-font-style: italic;";

    private ConceptDetailPane() {
    }

    /**
     * Renders the detail for a slot.
     *
     * @param slot the slot to inspect; must not be null
     * @param view the view (for live descriptions, axioms, and identicon badges); may be null, in
     *             which case a grounded concept renders presentation-only with no descriptions/axioms
     * @return a node for the detail pane
     */
    public static Node render(AnfSlot slot, ViewProperties view) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(10));
        switch (slot) {
            case AnfSlot.Grounded g -> renderGrounded(box, g, view);
            case AnfSlot.Candidate c -> renderCandidate(box, c, view);
            case AnfSlot.Clarify c -> renderClarify(box, c);
        }
        return box;
    }

    // ── Grounded: real descriptions + axioms ─────────────────────────────────

    private static void renderGrounded(VBox box, AnfSlot.Grounded g, ViewProperties view) {
        box.getChildren().add(header((view != null)
                ? new KonceptBadge(g.nid(), view)
                : new Label(g.label()), "Grounded concept · " + g.identifier()));
        if (view == null) {
            box.getChildren().add(muted("No view available to show descriptions or axioms."));
            return;
        }
        ViewCalculator v = view.calculator();
        box.getChildren().add(describe(g.nid(), v));
        box.getChildren().add(axioms(g.nid(), v, view));
    }

    private static Node describe(int conceptNid, ViewCalculator v) {
        VBox box = new VBox(2);
        box.getChildren().add(section("Descriptions"));
        String fsn = v.getFullyQualifiedNameText(conceptNid).orElse("");
        boolean any = false;
        for (SemanticEntity description : v.getDescriptionsForComponent(conceptNid)) {
            Latest<SemanticEntityVersion> latest = v.stampCalculator().latest(description.nid());
            if (latest.isPresent()) {
                String text = firstText(latest.get());
                if (text != null) {
                    Label row = new Label((text.equals(fsn) ? "• " : "· ") + text);
                    if (text.equals(fsn)) {
                        row.setStyle("-fx-font-weight: bold;");
                    }
                    box.getChildren().add(row);
                    any = true;
                }
            }
        }
        if (!any) {
            box.getChildren().add(muted("(no descriptions)"));
        }
        return box;
    }

    /** The text of a description version — its single String field. */
    private static String firstText(SemanticEntityVersion version) {
        for (Object field : version.fieldValues()) {
            if (field instanceof String text) {
                return text;
            }
        }
        return null;
    }

    private static Node axioms(int conceptNid, ViewCalculator v, ViewProperties view) {
        VBox box = new VBox(4);
        box.getChildren().add(section("Axioms"));
        box.getChildren().add(premise("Stated", conceptNid, v, view, PremiseType.STATED));
        box.getChildren().add(premise("Inferred", conceptNid, v, view, PremiseType.INFERRED));
        return box;
    }

    private static Node premise(String label, int conceptNid, ViewCalculator v, ViewProperties view,
                                PremiseType premise) {
        VBox box = new VBox(2);
        box.getChildren().add(subSection(label));
        Optional<ConceptDefinition> definition = ConceptDefinition.extract(conceptNid, v, premise);
        if (definition.isEmpty()) {
            box.getChildren().add(muted("primitive — no defining axioms on this view"));
            return box;
        }
        ConceptDefinition def = definition.get();
        box.getChildren().add(new Label(def.defined() ? "Defined (≡)" : "Primitive (⊑)"));
        if (def.supertypes().length > 0) {
            HBox supertypes = new HBox(4, new Label("is-a:"));
            for (int supertype : def.supertypes()) {
                supertypes.getChildren().add(new KonceptBadge(supertype, view));
            }
            box.getChildren().add(supertypes);
        }
        for (ConceptDefinition.Role role : def.ungroupedRoles()) {
            box.getChildren().add(roleRow(role, view));
        }
        int groupNumber = 1;
        for (List<ConceptDefinition.Role> group : def.roleGroups()) {
            VBox groupBox = new VBox(2);
            groupBox.setPadding(new Insets(0, 0, 0, 10));
            groupBox.getChildren().add(muted("group " + groupNumber++));
            for (ConceptDefinition.Role role : group) {
                groupBox.getChildren().add(roleRow(role, view));
            }
            box.getChildren().add(groupBox);
        }
        return box;
    }

    private static Node roleRow(ConceptDefinition.Role role, ViewProperties view) {
        return new HBox(4, new KonceptBadge(role.attributeNid(), view),
                new Label("→"), new KonceptBadge(role.valueNid(), view));
    }

    // ── Candidate: provisional, unmistakably not a concept ───────────────────

    private static void renderCandidate(VBox box, AnfSlot.Candidate c, ViewProperties view) {
        Label badge = new Label(c.provisionalLabel());
        badge.setStyle(PROPOSED_STYLE + " -fx-font-weight: bold;");
        box.getChildren().add(header(badge, "Candidate — no concept yet (awaiting curation)"));
        box.getChildren().add(field("Proposed name", c.provisionalLabel()));
        if (c.text() != null && !c.text().isBlank()) {
            box.getChildren().add(field("From narrative", c.text()));
        }
        box.getChildren().add(field("Disposition", c.disposition().name()));
        if (!c.nearestMatches().isEmpty()) {
            box.getChildren().add(section("Nearest existing concepts"));
            for (String id : c.nearestMatches()) {
                Optional<AnfSlot.Grounded> match = (view == null)
                        ? Optional.empty()
                        : network.ike.komet.claude.tools.GraphTools.resolveConcept(id, view.calculator());
                box.getChildren().add(match.isPresent() && view != null
                        ? new KonceptBadge(match.get().nid(), view)
                        : muted(id + " (unresolved)"));
            }
        }
        box.getChildren().add(muted("No stated/inferred axioms: this concept does not exist yet."));
    }

    // ── Clarify: the open question ───────────────────────────────────────────

    private static void renderClarify(VBox box, AnfSlot.Clarify c) {
        Label badge = new Label("? " + c.field());
        badge.setStyle("-fx-text-fill: #6a4c93; -fx-font-weight: bold;");
        box.getChildren().add(header(badge, "Clarify — a question for the author"));
        box.getChildren().add(field("Field", c.field()));
        box.getChildren().add(field("Question", c.question()));
    }

    // ── Shared bits ──────────────────────────────────────────────────────────

    private static Node header(Node badge, String caption) {
        VBox box = new VBox(2, badge);
        Label captionLabel = new Label(caption);
        captionLabel.setStyle(HEADER_STYLE);
        box.getChildren().add(0, captionLabel);
        return box;
    }

    private static Node field(String name, String value) {
        Label l = new Label(name + ": ");
        l.setStyle(SECTION_STYLE);
        Label valueLabel = new Label(value);
        valueLabel.setWrapText(true);
        HBox row = new HBox(4, l, valueLabel);
        return row;
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.setStyle(SECTION_STYLE);
        l.setPadding(new Insets(6, 0, 0, 0));
        return l;
    }

    private static Label subSection(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #999999;");
        return l;
    }

    private static Label muted(String text) {
        Label l = new Label(text);
        l.setStyle(MUTED_STYLE);
        l.setWrapText(true);
        return l;
    }
}
