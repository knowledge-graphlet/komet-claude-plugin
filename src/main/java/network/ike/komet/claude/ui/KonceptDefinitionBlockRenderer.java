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
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.authoring.ProposedDefinition;
import network.ike.komet.claude.authoring.ProposedDefinition.Built;
import network.ike.komet.claude.authoring.ProposedDefinition.Proposal;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.List;

/**
 * A {@code koncept-definition} fenced block — a concept definition the assistant proposes —
 * rendered by the axiom control the KL windows show stated and inferred definitions with,
 * {@link KLReadOnlyDiTreeControl} ({@code IKE-Network/ike-issues#1292}). A proposal therefore
 * reads exactly as a stored definition does, chips, glyphs and the hover peek at each concept's
 * own stated definition included, instead of being drawn as box art.
 *
 * <p>The grammar, its parser and the tree are {@link ProposedDefinition}'s, shared with
 * {@code propose_concept}, so the preview is the tree that tool would store. Modelling gaps are
 * listed beneath the control. A malformed block declines, and renders as preformatted text, as
 * a garbled {@code koncept-tree} does.
 *
 * <p>Parsing is pure string work, done eagerly to decide whether the block is well formed;
 * resolution, the tree and the control are built lazily by the {@link BlockPiece} supplier on the
 * JavaFX application thread.
 */
final class KonceptDefinitionBlockRenderer implements BlockRenderer {

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
        if (viewCalc == null || !ProposedDefinition.TAG.equals(KonceptTreeBlockRenderer.firstWord(info))) {
            return List.of();
        }
        String body = literal == null ? "" : literal;
        Proposal proposal = ProposedDefinition.parse(body);
        if (proposal == null) {
            return List.of();
        }
        return List.of(new BlockPiece(() -> buildView(proposal), body.stripTrailing()));
    }

    // ── View building (JavaFX application thread) ───────────────────────

    /** The control over the tree, with the gaps listed beneath it. */
    private Node buildView(Proposal proposal) {
        Built built = ProposedDefinition.build(proposal);
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
