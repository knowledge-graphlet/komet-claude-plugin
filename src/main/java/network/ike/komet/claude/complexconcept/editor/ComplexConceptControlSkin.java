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
package network.ike.komet.claude.complexconcept.editor;

import dev.ikm.komet.kview.controls.ComponentItemNode;
import dev.ikm.komet.kview.controls.KLComponentControl;
import dev.ikm.komet.kview.controls.KLDiTreeControl;
import dev.ikm.komet.kview.controls.skin.KLDiTreeControlSkin;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.terms.ConceptFacade;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.editor.AnfStatementControl;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfRefusal;
import network.ike.komet.claude.anf.model.AnfSemantic;
import network.ike.komet.claude.complexconcept.model.ComplexConcept;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Definition;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Expansion;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Member;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Prerequisite;
import network.ike.komet.claude.complexconcept.model.ComplexConceptBuilder;
import network.ike.komet.claude.complexconcept.model.ComplexConceptExpression;
import network.ike.komet.claude.complexconcept.model.ComplexConceptRefusal;
import network.ike.komet.claude.complexconcept.model.ComplexConceptSemantic;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The skin of {@link ComplexConceptControl}: the axiom control's editing skin with the complex
 * concept adaptor layer (IKE-Network/ike-issues#1312; ike-lab-documents topic
 * {@code dev-ditree-editor-skin-family}). It overrides the vertex dispatch for the complex concept
 * meanings and hands everything else to the parent, so:
 *
 * <ul>
 *     <li>the specification and each expansion are clause rows with the parent's accent bar,
 *     disclosure and + button, whose menu offers what the clause can still take;</li>
 *     <li>the definition is the axiom control itself, embedded compact: an edit inside it is
 *     spliced back as the value of the one definition vertex;</li>
 *     <li>a uniform expansion's header carries its conjunction, AND or OR, as one segmented
 *     control, its measurement domain as a chip, and, for an OR conjunction only, how a non-absent
 *     value is handled as a second segmented control, since those three choices exclude each
 *     other too;</li>
 *     <li>a focused expansion's header carries its focus as a chip;</li>
 *     <li>a member is a chip; a prerequisite is its association chip beside the ANF statement
 *     control, embedded compact, whose edits are spliced back as the value of the one
 *     prerequisite vertex.</li>
 * </ul>
 *
 * <p>Every edit runs through {@link ComplexConceptBuilder}, whose refusals are shown under the
 * tree rather than reverted silently, and through the parent's tree-level edit path, so it can be
 * undone. Under the tree, the control also lists what the specification still lacks.</p>
 */
public class ComplexConceptControlSkin extends KLDiTreeControlSkin {

    private final Label missingLabel;
    private final Label refusalLabel;
    /** The adaptor view of the tree last rendered; rebuilt only when the tree changes. */
    private ComplexConceptExpression renderedExpression;
    private DiTreeEntity renderedTree;

    /**
     * @param control the control this skin attaches to
     */
    public ComplexConceptControlSkin(ComplexConceptControl control) {
        super(control);
        control.getStylesheets().add(ComplexConceptControlSkin.class
                .getResource("complex-concept-control.css").toExternalForm());

        missingLabel = new Label();
        missingLabel.getStyleClass().add("cc-missing");
        missingLabel.setWrapText(true);
        refusalLabel = new Label();
        refusalLabel.getStyleClass().add("cc-refusal");
        refusalLabel.setWrapText(true);
        VBox statusBox = new VBox(missingLabel, refusalLabel);
        statusBox.getStyleClass().add("cc-status");
        mainContainer.getChildren().add(statusBox);
        control.lastRefusalProperty().subscribe(message -> {
            refusalLabel.setText(message == null ? "" : message);
            showing(refusalLabel, message != null);
        });
        control.getMissing().subscribe(() -> {
            List<String> missing = control.getMissing();
            missingLabel.setText(missing.isEmpty() ? "" : "Still needs " + String.join(", ", missing) + ".");
            showing(missingLabel, !missing.isEmpty() && isEditable());
        });
        // The parent rendered once while this skin was still being constructed.
        rebuildTree(control.getValue());
    }

    /**
     * The name a meaning or value shows: its description without the trailing semantic tag the
     * starter set's fully qualified names carry.
     *
     * @param concept the meaning or value
     * @return its display name
     */
    static String name(ConceptFacade concept) {
        return concept.description().replaceFirst("\\s*\\([^()]*\\)\\s*$", "");
    }

    private static String lowerName(ComplexConceptSemantic semantic) {
        String name = name(semantic.meaning);
        return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private ComplexConceptControl ccControl() {
        return (ComplexConceptControl) getSkinnable();
    }

    private static void showing(Node node, boolean show) {
        node.setVisible(show);
        node.setManaged(show);
    }

    private ComplexConceptExpression expressionOf(DiTreeEntity tree) {
        if (tree != renderedTree) {
            renderedTree = tree;
            renderedExpression = tree == null ? null : ComplexConceptExpression.from(tree);
        }
        return renderedExpression;
    }

    private static boolean isComplexConcept(EntityVertex vertex) {
        return ComplexConceptSemantic.covers(vertex.getMeaningNid());
    }

    @Override
    protected void afterRebuild(DiTreeEntity tree) {
        super.afterRebuild(tree);
        if (getSkinnable() instanceof ComplexConceptControl control && tree != null && isComplexConcept(tree.root())) {
            control.setMissing(new ComplexConceptBuilder(tree).completeness());
        }
    }

    /*=========================================================================*
     *                                                                         *
     * Edits                                                                   *
     *                                                                         *
     *=========================================================================*/

    /**
     * Applies an edit through the complex concept builder and the parent's tree-level edit path
     * (so it can be undone). A refusal leaves the tree as it was, re-renders it (closing any open
     * search control) and is shown under the tree.
     *
     * @param edit the edit, applied to a builder over the current tree
     */
    protected void ccEdit(Consumer<ComplexConceptBuilder> edit) {
        try {
            applyTreeEdit(tree -> {
                ComplexConceptBuilder builder = new ComplexConceptBuilder(tree);
                edit.accept(builder);
                return builder.asDiTree();
            });
            ccControl().setLastRefusal(null);
        } catch (ComplexConceptRefusal | AnfRefusal refusal) {
            ccControl().setLastRefusal(refusal.getMessage());
            rebuildTree(getSkinnable().getValue());
        }
    }

    /** The adaptor for a vertex in a builder's live view, by the vertex's UUID. */
    private static <A extends ComplexConcept> A in(ComplexConceptBuilder builder, EntityVertex vertex, Class<A> type) {
        ComplexConcept node = builder.expression().vertex(vertex.asUuid());
        if (!type.isInstance(node)) {
            throw new ComplexConceptRefusal("The tree changed under the edit; try it again");
        }
        return type.cast(node);
    }

    /**
     * Chooses the concept of a vertex: the edit a pick in its search control, or a drop on its
     * chip, applies. For a member it is the phenomenon; for an expansion, its measurement domain
     * or focus; for a prerequisite, its association; for the definition, the one parent concept.
     *
     * @param vertex     the vertex in the current tree
     * @param conceptNid the chosen concept
     */
    void chooseConcept(EntityVertex vertex, long conceptNid) {
        applyChipEdit(vertex, ChipKind.CONCEPT_REFERENCE, conceptNid);
    }

    @Override
    protected void applyChipEdit(EntityVertex vertex, ChipKind kind, long newConceptNid) {
        if (!isComplexConcept(vertex)) {
            super.applyChipEdit(vertex, kind, newConceptNid);
            return;
        }
        ConceptFacade concept = ConceptFacade.make(newConceptNid);
        ccEdit(builder -> {
            switch (builder.expression().vertex(vertex.asUuid())) {
                case Member member -> builder.updateMember(member, concept);
                case Expansion.Uniform uniform -> builder.setMeasurementDomain(uniform, concept);
                case Expansion.Focused focused -> builder.setFocus(focused, concept);
                case Prerequisite prerequisite -> builder.updateAssociation(prerequisite, concept);
                case Definition definition ->
                        builder.spliceDefinition(definition, AnfExpressionBuilder.singleConceptExpression(concept));
                case null, default -> throw new ComplexConceptRefusal("A concept cannot be chosen here");
            }
        });
    }

    @Override
    protected boolean admitsChild(EntityVertex clause, EntityVertex child, DiTreeEntity tree) {
        if (!isComplexConcept(clause) || !isComplexConcept(child)) {
            return super.admitsChild(clause, child, tree);
        }
        ComplexConceptSemantic parent = ComplexConceptSemantic.get(clause.getMeaningNid());
        ComplexConceptSemantic moving = ComplexConceptSemantic.get(child.getMeaningNid());
        return switch (parent.admits(moving)) {
            case NONE -> false;
            case MANY -> true;
            case ONE -> tree.successors(clause).stream().noneMatch(s -> s.getMeaningNid() == moving.nid);
        };
    }

    @Override
    protected Optional<EntityVertex> containerOf(EntityVertex clause, DiTreeEntity tree) {
        // A specification clause holds its children directly; there is no conjunction beneath it.
        return isComplexConcept(clause) ? Optional.of(clause) : super.containerOf(clause, tree);
    }

    /*=========================================================================*
     *                                                                         *
     * Rendering                                                               *
     *                                                                         *
     *=========================================================================*/

    @Override
    protected Node buildVertex(EntityVertex vertex, DiTreeEntity tree) {
        if (!isComplexConcept(vertex)) {
            return super.buildVertex(vertex, tree);
        }
        ComplexConcept node = expressionOf(tree).vertex(vertex.asUuid());
        return switch (node) {
            case ComplexConcept.Root root -> createClauseNode(vertex, tree, "cc-specification", "Complex concept");
            case Expansion.Uniform uniform -> createClauseNode(vertex, tree, "cc-uniform", "Uniform expansion");
            case Expansion.Focused focused -> createClauseNode(vertex, tree, "cc-focused", "Focused expansion");
            case Definition definition -> definitionRow(vertex, definition);
            case Member member -> memberRow(vertex, member);
            case Prerequisite prerequisite -> prerequisiteRow(vertex, prerequisite);
            case null, default -> super.buildVertex(vertex, tree);
        };
    }

    /** A labelled row: accent bar, the meaning's name, then the value nodes, with a remove menu. */
    private HBox row(EntityVertex vertex, ComplexConceptSemantic semantic, Node... value) {
        HBox row = new HBox(clauseBar("cc-row"), rowLabel(semantic));
        row.getChildren().addAll(value);
        row.getStyleClass().addAll("ditree-concept-row", "cc-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setSpacing(4);
        if (isEditable()) {
            row.setOnContextMenuRequested(e -> {
                if (e.getTarget() instanceof Node target && isWithinChip(target, row)) {
                    return;
                }
                removeMenu(vertex, semantic).show(row, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        }
        return row;
    }

    private static Label rowLabel(ComplexConceptSemantic semantic) {
        Label label = new Label(name(semantic.meaning));
        label.getStyleClass().add("cc-row-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    private static boolean isWithinChip(Node target, Node stop) {
        for (Node node = target; node != null && node != stop; node = node.getParent()) {
            if (node instanceof ComponentItemNode || node instanceof KLComponentControl || node instanceof KLDiTreeControl) {
                return true;
            }
        }
        return false;
    }

    private ContextMenu removeMenu(EntityVertex vertex, ComplexConceptSemantic semantic) {
        ContextMenu menu = newMenu();
        MenuItem remove = new MenuItem("Remove " + lowerName(semantic));
        remove.setOnAction(e -> ccEdit(builder -> builder.remove(in(builder, vertex, ComplexConcept.class))));
        menu.getItems().add(remove);
        return menu;
    }

    /**
     * A chip for a complex concept vertex: the parent's editing chip (search on click, drop to
     * replace, definition peek), with this vertex's menu in place of the axiom chip menu.
     */
    private Node ccChip(EntityVertex vertex, ConceptFacade concept, ChipKind kind, String choose,
                        ComplexConceptSemantic removable) {
        Node chip = createChipNode(vertex, concept.nid(), kind);
        if (chip instanceof ComponentItemNode itemNode && isEditable()) {
            itemNode.setContextMenuItemsSupplier(() -> {
                MenuItem change = new MenuItem(choose);
                change.setOnAction(e -> startChipEdit(itemNode, vertex, kind));
                if (removable == null) {
                    return List.of(change);
                }
                MenuItem remove = new MenuItem("Remove " + lowerName(removable));
                remove.setOnAction(e -> ccEdit(builder -> builder.remove(in(builder, vertex, ComplexConcept.class))));
                return List.of(change, new SeparatorMenuItem(), remove);
            });
        }
        return chip;
    }

    /** A search control whose pick is the chip edit of a vertex that has no concept yet. */
    private Node unchosen(EntityVertex vertex, ChipKind kind, String prompt) {
        if (!isEditable()) {
            Label none = new Label("not stated");
            none.getStyleClass().add("cc-unstated");
            return none;
        }
        KLComponentControl search = newSlot();
        Tooltip.install(search, new Tooltip(prompt));
        search.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(search, Priority.ALWAYS);
        search.entityProperty().subscribe((oldEntity, newEntity) -> {
            if (!KLComponentControl.isEmpty(newEntity)) {
                applyChipEdit(vertex, kind, newEntity.nid());
            }
        });
        return search;
    }

    private Node definitionRow(EntityVertex vertex, Definition definition) {
        Node value = definition.expressionTree()
                .<Node>map(tree -> embedded(axiomControl(tree), edited -> builder ->
                        builder.spliceDefinition(in(builder, vertex, Definition.class), edited)))
                .orElseGet(() -> unchosen(vertex, ChipKind.CONCEPT_REFERENCE, "Choose the parent phenomenon"));
        HBox.setHgrow(value, Priority.ALWAYS);
        return row(vertex, definition.semantic(), value);
    }

    private Node memberRow(EntityVertex vertex, Member member) {
        Node value = member.phenomenon()
                .map(phenomenon -> ccChip(vertex, phenomenon, ChipKind.CONCEPT_REFERENCE, "Choose phenomenon",
                        ComplexConceptSemantic.MEMBER))
                .orElseGet(() -> unchosen(vertex, ChipKind.CONCEPT_REFERENCE, "Choose phenomenon"));
        HBox.setHgrow(value, Priority.ALWAYS);
        return row(vertex, member.semantic(), value);
    }

    private Node prerequisiteRow(EntityVertex vertex, Prerequisite prerequisite) {
        Node association = prerequisite.association()
                .map(concept -> ccChip(vertex, concept, ChipKind.FEATURE_TYPE, "Choose association",
                        ComplexConceptSemantic.PREREQUISITE))
                .orElseGet(() -> unchosen(vertex, ChipKind.FEATURE_TYPE, "Choose association"));
        Node statement = prerequisite.statementTree()
                .<Node>map(tree -> embedded(statementControl(tree), edited -> builder ->
                        builder.spliceStatement(in(builder, vertex, Prerequisite.class), edited)))
                .orElseGet(() -> {
                    Label none = new Label("no statement");
                    none.getStyleClass().add("cc-unstated");
                    return none;
                });
        HBox.setHgrow(statement, Priority.ALWAYS);
        VBox value = new VBox(2, association, statement);
        value.getStyleClass().add("cc-prerequisite");
        HBox.setHgrow(value, Priority.ALWAYS);
        return row(vertex, prerequisite.semantic(), value);
    }

    /*-------------------------------------------------------------------------*
     * Embedded controls                                                       *
     *-------------------------------------------------------------------------*/

    /** The axiom control, compact, for the definition. */
    private KLDiTreeControl axiomControl(DiTreeEntity tree) {
        KLDiTreeControl nested = new KLDiTreeControl();
        nested.getStyleClass().add("cc-nested-definition");
        share(nested);
        nested.setValue(tree);
        return nested;
    }

    /**
     * The ANF statement control, compact, for a prerequisite: a definitional template carries no
     * subject of record or statement time, so it does not list them as missing.
     */
    private AnfStatementControl statementControl(DiTreeEntity tree) {
        AnfStatementControl nested = new AnfStatementControl();
        nested.getStyleClass().add("cc-nested-statement");
        nested.setShowsMissing(false);
        share(nested);
        nested.setValue(tree);
        return nested;
    }

    /** Gives an embedded control this control's resolvers and, when editing, its search and rules. */
    private void share(KLDiTreeControl nested) {
        KLDiTreeControl outer = control();
        nested.setCompactMode(true);
        nested.setComponentItemResolver(outer.getComponentItemResolver());
        nested.setDescriptionResolver(outer.getDescriptionResolver());
        nested.setDefinitionResolver(outer.getDefinitionResolver());
        if (isEditable()) {
            nested.setComponentSlotFactory(outer.getComponentSlotFactory());
            nested.setRuleActionsProvider(outer.getRuleActionsProvider());
        }
        nested.setMaxWidth(Double.MAX_VALUE);
    }

    /**
     * Splices each change of an embedded control's value back through the complex concept builder
     * as one edit of this tree.
     */
    private <C extends KLDiTreeControl> C embedded(C nested,
                                                   Function<DiTreeEntity, Consumer<ComplexConceptBuilder>> splice) {
        nested.valueProperty().subscribe((oldTree, newTree) -> {
            if (newTree != null && isEditable()) {
                ccEdit(splice.apply(newTree));
            }
        });
        return nested;
    }

    /*=========================================================================*
     *                                                                         *
     * Clauses: header controls, additions, removal                            *
     *                                                                         *
     *=========================================================================*/

    @Override
    protected void decorateClauseHeader(HBox header, VBox childrenBox, EntityVertex vertex) {
        if (!isComplexConcept(vertex)) {
            super.decorateClauseHeader(header, childrenBox, vertex);
            return;
        }
        ComplexConcept clause = expressionOf(getSkinnable().getValue()).vertex(vertex.asUuid());
        switch (clause) {
            case Expansion.Uniform uniform -> uniformHeader(header, vertex, uniform);
            case Expansion.Focused focused -> header.getChildren().add(focused.focus()
                    .map(focus -> ccChip(vertex, focus, ChipKind.CONCEPT_REFERENCE, "Choose focus", null))
                    .orElseGet(() -> unchosen(vertex, ChipKind.CONCEPT_REFERENCE, "Choose focus")));
            case null, default -> { }
        }
        if (!isEditable()) {
            return;
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        StackPane addButton = createAddButton();
        header.getChildren().addAll(spacer, addButton);
        addButton.setOnMouseClicked(e -> {
            clauseMenu(clause, vertex, childrenBox).show(addButton, Side.BOTTOM, 0, 2);
            e.consume();
        });
        header.setOnContextMenuRequested(e -> {
            clauseMenu(clause, vertex, childrenBox).show(header, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    /** A uniform expansion's header: conjunction, measurement domain, and the OR policy. */
    private void uniformHeader(HBox header, EntityVertex vertex, Expansion.Uniform uniform) {
        ConceptFacade conjunction = uniform.conjunction().orElse(null);
        header.getChildren().add(segmented("cc-conjunction", List.of(IkeConcept.AND_CONJUNCTION, IkeConcept.OR_CONJUNCTION),
                conjunction, picked -> ccEdit(builder ->
                        builder.setConjunction(in(builder, vertex, Expansion.Uniform.class), picked))));
        Label over = operator("over");
        header.getChildren().add(over);
        header.getChildren().add(uniform.measurementDomain()
                .map(domain -> ccChip(vertex, domain, ChipKind.INTERVAL_UNIT, "Choose measurement domain", null))
                .orElseGet(() -> unchosen(vertex, ChipKind.INTERVAL_UNIT, "Choose measurement domain")));
        if (conjunction != null && conjunction.nid() == IkeConcept.OR_CONJUNCTION.nid()) {
            header.getChildren().add(operator("if present:"));
            header.getChildren().add(segmented("cc-policy", List.of(IkeConcept.REFUSE_EXPANSION,
                            IkeConcept.FLAG_AS_INDETERMINATE_POSITIVE, IkeConcept.RETAIN_COMPOUND_CODE),
                    uniform.unsafeDistributionPolicy().orElse(null), picked -> ccEdit(builder ->
                            builder.setUnsafeDistributionPolicy(in(builder, vertex, Expansion.Uniform.class), picked))));
        }
    }

    /**
     * One segmented control for choices that exclude each other: selecting a segment applies its
     * value; the selected segment stays selected.
     */
    private Node segmented(String styleClass, List<ConceptFacade> choices, ConceptFacade selected,
                           Consumer<ConceptFacade> pick) {
        ToggleGroup group = new ToggleGroup();
        HBox segments = new HBox();
        segments.getStyleClass().addAll("cc-segmented", styleClass);
        for (ConceptFacade choice : choices) {
            ToggleButton segment = new ToggleButton(name(choice).replaceFirst(" conjunction$", ""));
            segment.getStyleClass().add("cc-segment");
            segment.setToggleGroup(group);
            boolean isSelected = selected != null && selected.nid() == choice.nid();
            segment.setSelected(isSelected);
            segment.setDisable(!isEditable());
            segment.setOnAction(e -> {
                if (isSelected) {
                    segment.setSelected(true);
                    return;
                }
                pick.accept(choice);
            });
            segments.getChildren().add(segment);
        }
        return segments;
    }

    private ContextMenu clauseMenu(ComplexConcept clause, EntityVertex vertex, VBox childrenBox) {
        ContextMenu menu = newMenu();
        switch (clause) {
            case ComplexConcept.Root root -> {
                if (root.definition().isEmpty()) {
                    menu.getItems().add(item("Add definition", () -> mountConceptTemplate(childrenBox,
                            ComplexConceptSemantic.DEFINITION, "Choose the parent phenomenon",
                            concept -> builder -> builder.definition(AnfExpressionBuilder.singleConceptExpression(concept)))));
                }
                if (root.expansion().isEmpty()) {
                    menu.getItems().add(item("Add uniform expansion", () -> ccEdit(builder ->
                            builder.uniformExpansion(IkeConcept.AND_CONJUNCTION, null))));
                    menu.getItems().add(item("Add focused expansion", () -> mountConceptTemplate(childrenBox,
                            ComplexConceptSemantic.FOCUSED_EXPANSION, "Choose focus",
                            concept -> builder -> builder.focusedExpansion(concept))));
                }
            }
            case Expansion.Uniform uniform -> menu.getItems().add(item("Add member", () -> mountConceptTemplate(
                    childrenBox, ComplexConceptSemantic.MEMBER, "Choose phenomenon", concept -> builder ->
                            builder.member(in(builder, vertex, Expansion.Uniform.class), concept))));
            case Expansion.Focused focused -> menu.getItems().add(item("Add prerequisite", () -> mountConceptTemplate(
                    childrenBox, ComplexConceptSemantic.PREREQUISITE, "Choose the prerequisite's topic",
                    concept -> builder -> builder.prerequisite(in(builder, vertex, Expansion.Focused.class),
                            prerequisiteTemplate(concept), IkeConcept.PRECONDITION_ASSOCIATION))));
            case null, default -> { }
        }
        if (clause != null && !(clause instanceof ComplexConcept.Root)) {
            if (!menu.getItems().isEmpty()) {
                menu.getItems().add(new SeparatorMenuItem());
            }
            menu.getItems().add(item("Remove " + lowerName(clause.semantic()), () -> ccEdit(builder ->
                    builder.remove(in(builder, vertex, ComplexConcept.class)))));
        }
        return menu;
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }

    /**
     * A new prerequisite's statement: a performance whose topic is the chosen condition, to be
     * given its fixed value in the embedded statement control.
     */
    static DiTreeEntity prerequisiteTemplate(ConceptFacade topic) {
        AnfExpressionBuilder statement = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        statement.expression(statement.context(), AnfSemantic.TOPIC, topic);
        statement.circumstance();
        return statement.asDiTree();
    }

    /** A template row whose search control applies an edit once a concept is picked. */
    private void mountConceptTemplate(VBox childrenBox, ComplexConceptSemantic semantic, String prompt,
                                      Function<ConceptFacade, Consumer<ComplexConceptBuilder>> add) {
        KLComponentControl search = newSlot();
        Tooltip.install(search, new Tooltip(prompt));
        search.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox row = new HBox(4, clauseBar("cc-row"), rowLabel(semantic), search);
        row.getStyleClass().addAll("ditree-concept-row", "cc-row", "cc-template-row");
        row.setAlignment(Pos.CENTER_LEFT);
        childrenBox.getChildren().add(row);
        showing(childrenBox, true);
        search.entityProperty().subscribe((oldEntity, newEntity) -> {
            if (!KLComponentControl.isEmpty(newEntity)) {
                ccEdit(add.apply(ConceptFacade.make(newEntity.nid())));
            }
        });
        onEscape(search, () -> childrenBox.getChildren().remove(row));
        focusSlot(search);
    }
}
