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
package network.ike.komet.claude.anf.editor;

import dev.ikm.komet.kview.controls.ComponentItemNode;
import dev.ikm.komet.kview.controls.KLComponentControl;
import dev.ikm.komet.kview.controls.KLDiTreeControl;
import dev.ikm.komet.kview.controls.skin.KLDiTreeControlSkin;
import dev.ikm.tinkar.common.id.Nid;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.terms.ConceptFacade;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import network.ike.komet.claude.anf.model.AnfExpression;
import network.ike.komet.claude.koncept.ComponentText;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfProvisional;
import network.ike.komet.claude.anf.model.AnfRefusal;
import network.ike.komet.claude.anf.model.AnfSemantic;
import network.ike.komet.claude.anf.model.AnfSemantic.Cardinality;
import network.ike.komet.claude.anf.model.AnfStatement;
import network.ike.komet.claude.anf.model.AnfStatement.Attribute;
import network.ike.komet.claude.anf.model.AnfStatement.Circumstance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The skin of {@link AnfStatementControl}: the axiom control's editing skin with the ANF statement
 * adaptor layer (IKE-Network/ike-issues#1312). It overrides the vertex dispatch for the ANF meanings and
 * hands everything else to the parent, so:
 *
 * <ul>
 *     <li>the statement, its context, its circumstance and a request's repetition are clause
 *     rows with the parent's accent bar, disclosure and + button; the + button and the header's
 *     context menu offer the attributes the admission table lets that clause take and it does
 *     not yet carry;</li>
 *     <li>a concept attribute is a labelled row of the parent's chips, so picking a replacement,
 *     dropping a concept on it and the definition peek all work as they do in an axiom;</li>
 *     <li>a measure is a labelled bound editor with a unit chip;</li>
 *     <li>a nested logical expression (topic, subject of information, purpose, an author's role)
 *     is the axiom control itself, embedded compact: an edit inside it is spliced back as the
 *     value of the one ANF vertex holding it;</li>
 *     <li>the statement's kind is one segmented control, since the three kinds exclude each
 *     other;</li>
 *     <li>dropping a concept on a clause header offers the attributes it can become.</li>
 * </ul>
 *
 * <p>Every edit runs through {@link AnfExpressionBuilder}, whose refusals are shown under the tree
 * rather than reverted silently, and through the parent's tree-level edit path, so it can be
 * undone. Under the tree, the control also lists what the statement still lacks.</p>
 */
public class AnfStatementControlSkin extends KLDiTreeControlSkin {

    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");
    private static final PseudoClass PROVISIONAL = PseudoClass.getPseudoClass("provisional");

    /** The three statement kinds, in the order the kind control shows them. */
    private static final List<AnfSemantic> KINDS = List.of(AnfSemantic.PERFORMANCE, AnfSemantic.REQUEST,
            AnfSemantic.NARRATIVE);

    private VBox statusBox;
    private Label missingLabel;
    private Label refusalLabel;
    /** The adaptor view of the tree last rendered; rebuilt only when the tree changes. */
    private AnfExpression renderedExpression;
    private DiTreeEntity renderedTree;

    /**
     * @param control the control this skin attaches to
     */
    public AnfStatementControlSkin(AnfStatementControl control) {
        super(control);
        control.getStylesheets().add(AnfStatementControlSkin.class
                .getResource("anf-statement-control.css").toExternalForm());

        missingLabel = new Label();
        missingLabel.getStyleClass().add("anf-missing");
        missingLabel.setWrapText(true);
        refusalLabel = new Label();
        refusalLabel.getStyleClass().add("anf-refusal");
        refusalLabel.setWrapText(true);
        statusBox = new VBox(missingLabel, refusalLabel);
        statusBox.getStyleClass().add("anf-status");
        mainContainer.getChildren().add(statusBox);
        control.lastRefusalProperty().subscribe(message -> {
            refusalLabel.setText(message == null ? "" : message);
            showing(refusalLabel, message != null);
        });
        Runnable showMissing = () -> {
            List<String> missing = control.getMissing();
            missingLabel.setText(missing.isEmpty() ? "" : "Still needs " + String.join(", ", missing) + ".");
            showing(missingLabel, !missing.isEmpty() && isEditable() && control.isShowsMissing());
        };
        control.getMissing().subscribe(showMissing);
        control.showsMissingProperty().subscribe(showMissing);
        // The parent rendered once while this skin was still being constructed.
        rebuildTree(control.getValue());
    }

    /**
     * The name an attribute, clause or kind shows: its meaning's description without the
     * trailing semantic tag the starter set's fully qualified names carry, so
     * "Performance circumstance (IkeFoundation)" shows as "Performance circumstance".
     *
     * @param semantic the meaning
     * @return its display name
     */
    static String name(AnfSemantic semantic) {
        return semantic.describe().replaceFirst("\\s*\\([^()]*\\)\\s*$", "");
    }

    private static String lowerName(AnfSemantic semantic) {
        String name = name(semantic);
        return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private AnfStatementControl anfControl() {
        return (AnfStatementControl) getSkinnable();
    }

    private static void showing(Node node, boolean show) {
        node.setVisible(show);
        node.setManaged(show);
    }

    private AnfExpression expressionOf(DiTreeEntity tree) {
        if (tree != renderedTree) {
            renderedTree = tree;
            renderedExpression = tree == null ? null : AnfExpression.from(tree);
        }
        return renderedExpression;
    }

    private static boolean isAnf(EntityVertex vertex) {
        return AnfSemantic.covers(vertex.getMeaningNid());
    }

    @Override
    protected void afterRebuild(DiTreeEntity tree) {
        super.afterRebuild(tree);
        if (getSkinnable() instanceof AnfStatementControl control && tree != null && isAnf(tree.root())) {
            control.setMissing(new AnfExpressionBuilder(tree).completeness());
        }
    }

    /*=========================================================================*
     *                                                                         *
     * Edits                                                                   *
     *                                                                         *
     *=========================================================================*/

    /**
     * Applies an edit through the ANF builder and the parent's tree-level edit path (so it can be
     * undone). A refusal leaves the tree as it was, re-renders it (closing any open search
     * control) and is shown under the tree.
     *
     * @param edit the edit, applied to a builder over the current tree
     */
    protected void anfEdit(Consumer<AnfExpressionBuilder> edit) {
        try {
            applyTreeEdit(tree -> {
                AnfExpressionBuilder builder = new AnfExpressionBuilder(tree);
                edit.accept(builder);
                return builder.asDiTree();
            });
            anfControl().setLastRefusal(null);
        } catch (AnfRefusal refusal) {
            anfControl().setLastRefusal(refusal.getMessage());
            rebuildTree(getSkinnable().getValue());
        }
    }

    /** The adaptor for a vertex in a builder's live view, by the vertex's UUID. */
    private static <A extends AnfStatement> A in(AnfExpressionBuilder builder, EntityVertex vertex, Class<A> type) {
        AnfStatement node = builder.expression().vertex(vertex.asUuid());
        if (!type.isInstance(node)) {
            throw new AnfRefusal("The tree changed under the edit; try it again");
        }
        return type.cast(node);
    }

    /**
     * Chooses the concept of an ANF attribute: the edit a pick in its search control, or a drop
     * on its chip, applies. For a measure the concept is its unit; for a nested expression, the
     * expression becomes that one concept.
     *
     * @param vertex     the attribute's vertex in the current tree
     * @param conceptNid the chosen concept
     */
    void chooseConcept(EntityVertex vertex, long conceptNid) {
        applyChipEdit(vertex, ChipKind.CONCEPT_REFERENCE, conceptNid);
    }

    @Override
    protected void applyChipEdit(EntityVertex vertex, ChipKind kind, long newConceptNid) {
        if (!isAnf(vertex)) {
            super.applyChipEdit(vertex, kind, newConceptNid);
            return;
        }
        ConceptFacade concept = ConceptFacade.make(newConceptNid);
        anfEdit(builder -> {
            switch (builder.expression().vertex(vertex.asUuid())) {
                case Attribute.ConceptReference chip -> builder.updateConcept(chip, concept);
                case Attribute.Measure measure -> builder.updateMeasure(measure,
                        measure.lowerBound().orElse(null), measure.upperBound().orElse(null),
                        measure.includeLowerBound(), measure.includeUpperBound(),
                        measure.resolution().orElse(null), concept);
                case Attribute.Participant participant -> {
                    if (kind == ChipKind.ROLE_TYPE) {
                        builder.updateParticipant(participant, null, concept);
                    } else {
                        builder.updateParticipant(participant, concept, null);
                    }
                }
                case Attribute.Author author -> builder.updateAuthor(author, concept);
                case Attribute.Expression expression ->
                        builder.splice(expression, AnfExpressionBuilder.singleConceptExpression(concept));
                case Attribute.AssociatedStatement association -> builder.updateAssociationType(association, concept);
                case null, default -> throw new AnfRefusal("A concept cannot be chosen here");
            }
        });
    }

    @Override
    protected boolean admitsChild(EntityVertex clause, EntityVertex child, DiTreeEntity tree) {
        if (!isAnf(clause) || !isAnf(child)) {
            return super.admitsChild(clause, child, tree);
        }
        AnfSemantic parent = AnfSemantic.get(clause.getMeaningNid());
        AnfSemantic moving = AnfSemantic.get(child.getMeaningNid());
        return switch (parent.admits(moving)) {
            case NONE -> false;
            case MANY -> true;
            case ONE -> tree.successors(clause).stream().noneMatch(s -> s.getMeaningNid() == moving.nid);
        };
    }

    @Override
    protected Optional<EntityVertex> containerOf(EntityVertex clause, DiTreeEntity tree) {
        // An ANF clause holds its attributes directly; there is no conjunction beneath it.
        return isAnf(clause) ? Optional.of(clause) : super.containerOf(clause, tree);
    }

    /*=========================================================================*
     *                                                                         *
     * Rendering                                                               *
     *                                                                         *
     *=========================================================================*/

    @Override
    protected Node buildVertex(EntityVertex vertex, DiTreeEntity tree) {
        if (!isAnf(vertex)) {
            return super.buildVertex(vertex, tree);
        }
        AnfStatement node = expressionOf(tree).vertex(vertex.asUuid());
        return switch (node) {
            case AnfStatement.Root root ->
                    createClauseNode(vertex, tree, "anf-statement", "Statement");
            case AnfStatement.Context context ->
                    createClauseNode(vertex, tree, "anf-context", "Context");
            case Circumstance circumstance ->
                    createClauseNode(vertex, tree, "anf-circumstance", name(circumstance.semantic()));
            case Attribute.Repetition repetition ->
                    createClauseNode(vertex, tree, "anf-repetition", name(repetition.semantic()));
            case Attribute.ConceptReference chip -> conceptAttributeRow(vertex, chip);
            case Attribute.Measure measure -> measureRow(vertex, measure);
            case Attribute.Expression expression -> expressionRow(vertex, expression);
            case Attribute.Participant participant -> participantRow(vertex, participant);
            case Attribute.Author author -> authorRow(vertex, author);
            case Attribute.NarrativeText text -> textRow(vertex, text);
            case Attribute.AssociatedStatement association -> associationRow(vertex, association);
            case null, default -> super.buildVertex(vertex, tree);
        };
    }

    /** A labelled attribute row: accent bar, attribute name, then the value nodes. */
    private HBox attributeRow(EntityVertex vertex, AnfSemantic attribute, Node... value) {
        HBox row = new HBox(clauseBar("anf-attribute"), attributeLabel(attribute));
        row.getChildren().addAll(value);
        row.getStyleClass().addAll("ditree-concept-row", "anf-attribute-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setSpacing(4);
        if (isEditable()) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row.getChildren().add(spacer);
            row.setOnContextMenuRequested(e -> {
                if (e.getTarget() instanceof Node target && isWithinChip(target, row)) {
                    return;
                }
                attributeMenu(vertex, attribute).show(row, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        }
        return row;
    }

    private static Label attributeLabel(AnfSemantic attribute) {
        Label label = new Label(name(attribute));
        label.getStyleClass().add("anf-attribute-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    private static boolean isWithinChip(Node target, Node stop) {
        for (Node node = target; node != null && node != stop; node = node.getParent()) {
            if (node instanceof ComponentItemNode || node instanceof KLComponentControl) {
                return true;
            }
        }
        return false;
    }

    private ContextMenu attributeMenu(EntityVertex vertex, AnfSemantic attribute) {
        ContextMenu menu = newMenu();
        MenuItem remove = new MenuItem("Remove " + lowerName(attribute));
        remove.setOnAction(e -> anfEdit(builder -> builder.remove(in(builder, vertex, AnfStatement.class))));
        menu.getItems().add(remove);
        return menu;
    }

    /**
     * A chip for an ANF vertex: the parent's editing chip (search on click, drop to replace,
     * definition peek), with this attribute's menu in place of the axiom chip menu.
     */
    private Node anfChip(EntityVertex vertex, ConceptFacade concept, ChipKind kind, AnfSemantic attribute) {
        Node chip = createChipNode(vertex, concept.nid(), kind);
        if (chip instanceof ComponentItemNode itemNode && isEditable()) {
            itemNode.setContextMenuItemsSupplier(() -> {
                MenuItem change = new MenuItem("Choose " + lowerName(attribute));
                change.setOnAction(e -> startChipEdit(itemNode, vertex, kind));
                MenuItem remove = new MenuItem("Remove " + lowerName(attribute));
                remove.setOnAction(e -> anfEdit(builder -> builder.remove(in(builder, vertex, AnfStatement.class))));
                return List.of(change, new SeparatorMenuItem(), remove);
            });
        }
        return chip;
    }

    /**
     * The value of an attribute that has no concept yet: its candidate or question, and (when
     * editing) a search control whose pick settles it.
     */
    private Node unsettledValue(EntityVertex vertex, ChipKind kind, Optional<AnfProvisional> provisional,
                                String prompt) {
        HBox box = new HBox(4);
        box.setAlignment(Pos.CENTER_LEFT);
        provisional.ifPresent(p -> {
            Label label = new Label(switch (p) {
                case AnfProvisional.Candidate candidate -> "candidate: " + candidate.label();
                case AnfProvisional.Clarification clarification -> "asks: " + clarification.question();
            });
            label.getStyleClass().add("anf-provisional");
            label.pseudoClassStateChanged(PROVISIONAL, true);
            box.getChildren().add(label);
        });
        if (isEditable()) {
            KLComponentControl search = newSlot();
            Tooltip.install(search, new Tooltip(prompt));
            search.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(search, Priority.ALWAYS);
            search.entityProperty().subscribe((oldEntity, newEntity) -> {
                if (!KLComponentControl.isEmpty(newEntity)) {
                    applyChipEdit(vertex, kind, newEntity.nid());
                }
            });
            box.getChildren().add(search);
            HBox.setHgrow(box, Priority.ALWAYS);
        } else if (provisional.isEmpty()) {
            Label none = new Label("not stated");
            none.getStyleClass().add("anf-unstated");
            box.getChildren().add(none);
        }
        return box;
    }

    private Node conceptAttributeRow(EntityVertex vertex, Attribute.ConceptReference chip) {
        Node value = chip.concept()
                .map(concept -> anfChip(vertex, concept, ChipKind.CONCEPT_REFERENCE, chip.semantic()))
                .orElseGet(() -> unsettledValue(vertex, ChipKind.CONCEPT_REFERENCE, chip.provisional(),
                        "Choose " + lowerName(chip.semantic())));
        HBox.setHgrow(value, Priority.ALWAYS);
        return attributeRow(vertex, chip.semantic(), value);
    }

    private Node participantRow(EntityVertex vertex, Attribute.Participant participant) {
        List<Node> value = new ArrayList<>();
        value.add(participant.participant()
                .map(concept -> anfChip(vertex, concept, ChipKind.CONCEPT_REFERENCE, participant.semantic()))
                .orElseGet(() -> unsettledValue(vertex, ChipKind.CONCEPT_REFERENCE, participant.provisional(),
                        "Choose participant")));
        Label as = operator("as");
        value.add(as);
        value.add(participant.role()
                .map(role -> anfChip(vertex, role, ChipKind.ROLE_TYPE, participant.semantic()))
                .orElseGet(() -> unsettledValue(vertex, ChipKind.ROLE_TYPE, Optional.empty(), "Choose role")));
        return attributeRow(vertex, participant.semantic(), value.toArray(Node[]::new));
    }

    private Node associationRow(EntityVertex vertex, Attribute.AssociatedStatement association) {
        Node type = association.associationType()
                .map(concept -> anfChip(vertex, concept, ChipKind.FEATURE_TYPE, association.semantic()))
                .orElseGet(() -> unsettledValue(vertex, ChipKind.FEATURE_TYPE, Optional.empty(),
                        "Choose association"));
        // A statement is a semantic: its preferred name is composed from its pattern and the
        // component it is attached to, never the nid the store's default text falls back to.
        Label target = new Label(association.statement()
                .map(statement -> ComponentText.preferredName(Calculators.View.Default(), statement.nid()))
                .orElse("no statement"));
        target.getStyleClass().add("anf-statement-reference");
        return attributeRow(vertex, association.semantic(), type, operator("→"), target);
    }

    private Node textRow(EntityVertex vertex, Attribute.NarrativeText text) {
        if (!isEditable()) {
            Label label = new Label(text.text());
            label.setWrapText(true);
            label.getStyleClass().add("anf-narrative-text");
            return attributeRow(vertex, text.semantic(), label);
        }
        TextArea area = new TextArea(text.text());
        area.setWrapText(true);
        area.setPrefRowCount(3);
        area.getStyleClass().add("anf-narrative-text");
        HBox.setHgrow(area, Priority.ALWAYS);
        area.focusedProperty().subscribe(focused -> {
            if (!focused && !area.getText().equals(text.text())) {
                String newText = area.getText();
                anfEdit(builder -> builder.updateText(in(builder, vertex, Attribute.NarrativeText.class), newText));
            }
        });
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                area.setText(text.text());
                e.consume();
            }
        });
        return attributeRow(vertex, text.semantic(), area);
    }

    /*-------------------------------------------------------------------------*
     * Measures                                                                *
     *-------------------------------------------------------------------------*/

    private Node measureRow(EntityVertex vertex, Attribute.Measure measure) {
        List<Node> value = new ArrayList<>();
        String open = measure.includeLowerBound() ? "[" : "(";
        String close = measure.includeUpperBound() ? "]" : ")";
        if (!isEditable()) {
            value.add(operator(open + bound(measure.lowerBound()) + ", " + bound(measure.upperBound()) + close));
        } else {
            TextField lower = boundEditor(measure.lowerBound());
            TextField upper = boundEditor(measure.upperBound());
            Label openBracket = bracket(open, () -> commitMeasure(vertex, lower, upper,
                    !measure.includeLowerBound(), measure.includeUpperBound()));
            Label closeBracket = bracket(close, () -> commitMeasure(vertex, lower, upper,
                    measure.includeLowerBound(), !measure.includeUpperBound()));
            Runnable commit = () -> commitMeasure(vertex, lower, upper,
                    measure.includeLowerBound(), measure.includeUpperBound());
            for (TextField field : List.of(lower, upper)) {
                field.setOnAction(e -> commit.run());
                field.focusedProperty().subscribe(focused -> {
                    if (!focused && !lower.isFocused() && !upper.isFocused() && changed(measure, lower, upper)) {
                        commit.run();
                    }
                });
            }
            value.addAll(List.of(openBracket, lower, operator(","), upper, closeBracket));
        }
        value.add(measure.unit()
                .map(unit -> anfChip(vertex, unit, ChipKind.INTERVAL_UNIT, measure.semantic()))
                .orElseGet(() -> unsettledValue(vertex, ChipKind.INTERVAL_UNIT, measure.provisional(),
                        "Choose unit")));
        return attributeRow(vertex, measure.semantic(), value.toArray(Node[]::new));
    }

    private static String bound(Optional<BigDecimal> bound) {
        return bound.map(BigDecimal::toPlainString).orElse("∞");
    }

    private static TextField boundEditor(Optional<BigDecimal> bound) {
        TextField field = new TextField(bound.map(BigDecimal::toPlainString).orElse(""));
        field.setPromptText("∞");
        field.getStyleClass().add("ditree-bound-field");
        field.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("-?\\d*(\\.\\d*)?") ? change : null));
        return field;
    }

    private Label bracket(String glyph, Runnable toggle) {
        Label label = operator(glyph);
        label.getStyleClass().add("anf-bracket");
        label.setOnMouseClicked(e -> {
            toggle.run();
            e.consume();
        });
        return label;
    }

    private static BigDecimal parsed(TextField field) {
        String text = field.getText().strip();
        return text.isEmpty() || text.equals("-") || text.equals(".") ? null : new BigDecimal(text);
    }

    private static boolean changed(Attribute.Measure measure, TextField lower, TextField upper) {
        return !java.util.Objects.equals(parsed(lower), measure.lowerBound().orElse(null))
                || !java.util.Objects.equals(parsed(upper), measure.upperBound().orElse(null));
    }

    private void commitMeasure(EntityVertex vertex, TextField lower, TextField upper,
                               boolean includeLower, boolean includeUpper) {
        BigDecimal lowerBound = parsed(lower);
        BigDecimal upperBound = parsed(upper);
        anfEdit(builder -> {
            Attribute.Measure measure = in(builder, vertex, Attribute.Measure.class);
            builder.updateMeasure(measure, lowerBound, upperBound, includeLower, includeUpper,
                    measure.resolution().orElse(null), null);
        });
    }

    /*-------------------------------------------------------------------------*
     * Nested logical expressions                                              *
     *-------------------------------------------------------------------------*/

    private Node expressionRow(EntityVertex vertex, Attribute.Expression expression) {
        Node value = expression.expressionTree()
                .map(tree -> nestedExpression(tree, edited -> builder ->
                        builder.splice(in(builder, vertex, Attribute.Expression.class), edited)))
                .orElseGet(() -> unsettledValue(vertex, ChipKind.CONCEPT_REFERENCE, expression.provisional(),
                        "Choose " + lowerName(expression.semantic())));
        HBox.setHgrow(value, Priority.ALWAYS);
        return attributeRow(vertex, expression.semantic(), value);
    }

    private Node authorRow(EntityVertex vertex, Attribute.Author author) {
        List<Node> value = new ArrayList<>();
        value.add(author.participant()
                .map(concept -> anfChip(vertex, concept, ChipKind.CONCEPT_REFERENCE, author.semantic()))
                .orElseGet(() -> unsettledValue(vertex, ChipKind.CONCEPT_REFERENCE, author.provisional(),
                        "Choose author")));
        author.roleTree().ifPresent(roleTree -> {
            value.add(operator("as"));
            Node role = nestedExpression(roleTree, edited -> builder ->
                    builder.spliceRole(in(builder, vertex, Attribute.Author.class), edited));
            HBox.setHgrow(role, Priority.ALWAYS);
            value.add(role);
        });
        return attributeRow(vertex, author.semantic(), value.toArray(Node[]::new));
    }

    /**
     * The axiom control itself, compact and embedded, for a logical expression held by value:
     * it renders the expression's sets directly (no subject chip, since an expression names no
     * concept) and, when this control edits, edits with every axiom editing capability. Each
     * change of its value is spliced back through the ANF builder as one edit of this tree.
     *
     * @param tree   the nested logical expression
     * @param splice the ANF edit that writes an edited expression back
     * @return the embedded control
     */
    private Node nestedExpression(DiTreeEntity tree,
                                  java.util.function.Function<DiTreeEntity, Consumer<AnfExpressionBuilder>> splice) {
        KLDiTreeControl nested = new KLDiTreeControl();
        KLDiTreeControl outer = control();
        nested.getStyleClass().add("anf-nested-expression");
        nested.setCompactMode(true);
        nested.setComponentItemResolver(outer.getComponentItemResolver());
        nested.setDescriptionResolver(outer.getDescriptionResolver());
        nested.setDefinitionResolver(outer.getDefinitionResolver());
        if (isEditable()) {
            nested.setComponentSlotFactory(outer.getComponentSlotFactory());
            nested.setRuleActionsProvider(outer.getRuleActionsProvider());
        }
        nested.setValue(tree);
        nested.valueProperty().subscribe((oldTree, newTree) -> {
            if (newTree != null && isEditable()) {
                anfEdit(splice.apply(newTree));
            }
        });
        nested.setMaxWidth(Double.MAX_VALUE);
        return nested;
    }

    /*=========================================================================*
     *                                                                         *
     * Clauses: kind, additions, removal, concept drop                         *
     *                                                                         *
     *=========================================================================*/

    @Override
    protected void decorateClauseHeader(HBox header, VBox childrenBox, EntityVertex vertex) {
        if (!isAnf(vertex)) {
            super.decorateClauseHeader(header, childrenBox, vertex);
            return;
        }
        AnfStatement clause = expressionOf(getSkinnable().getValue()).vertex(vertex.asUuid());
        if (clause instanceof AnfStatement.Root root) {
            header.getChildren().add(kindControl(root));
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
        installConceptDrop(header, clause, vertex, childrenBox);
    }

    /** The statement's kind: one segmented control, since the three kinds exclude each other. */
    private Node kindControl(AnfStatement.Root root) {
        ToggleGroup group = new ToggleGroup();
        HBox segments = new HBox();
        segments.getStyleClass().add("anf-kind-control");
        for (AnfSemantic kind : KINDS) {
            ToggleButton segment = new ToggleButton(name(kind).replaceFirst(" circumstance$", ""));
            segment.getStyleClass().add("anf-kind-segment");
            segment.setToggleGroup(group);
            segment.setSelected(kind == root.kindSemantic());
            segment.setDisable(!isEditable());
            segment.setOnAction(e -> {
                if (kind == root.kindSemantic()) {
                    segment.setSelected(true);
                    return;
                }
                anfEdit(builder -> builder.changeKind(kind.meaning));
            });
            segments.getChildren().add(segment);
        }
        return segments;
    }

    /** What a clause can take that it does not yet carry, by the admission table. */
    private List<AnfSemantic> admissible(AnfStatement clause) {
        List<AnfSemantic> admissible = new ArrayList<>();
        for (AnfSemantic child : AnfSemantic.values()) {
            Cardinality cardinality = clause.semantic().admits(child);
            if (cardinality == Cardinality.NONE) {
                continue;
            }
            if (child.isCircumstance() && clause instanceof AnfStatement.Root root
                    && (child != root.kindSemantic() || root.circumstance().isPresent())) {
                continue;
            }
            if (cardinality == Cardinality.ONE
                    && clause.children().stream().anyMatch(existing -> existing.semantic() == child)) {
                continue;
            }
            admissible.add(child);
        }
        return admissible;
    }

    private ContextMenu clauseMenu(AnfStatement clause, EntityVertex vertex, VBox childrenBox) {
        ContextMenu menu = newMenu();
        for (AnfSemantic child : admissible(clause)) {
            MenuItem add = new MenuItem("Add " + lowerName(child));
            if (child == AnfSemantic.ASSOCIATED_STATEMENT) {
                // An association points at another statement's semantic; the editor has no
                // statement chooser yet, so the lift and the actions add these.
                add.setDisable(true);
            }
            add.setOnAction(e -> addAttribute(vertex, child, childrenBox));
            menu.getItems().add(add);
        }
        if (!(clause instanceof AnfStatement.Root)) {
            if (!menu.getItems().isEmpty()) {
                menu.getItems().add(new SeparatorMenuItem());
            }
            MenuItem remove = new MenuItem("Remove " + lowerName(clause.semantic()));
            remove.setOnAction(e -> anfEdit(builder -> builder.remove(in(builder, vertex, AnfStatement.class))));
            menu.getItems().add(remove);
        }
        return menu;
    }

    /**
     * Adds an attribute to a clause. Clauses and measures are added at once (a measure starts
     * unbounded, to be bounded in place); a concept-valued attribute mounts a template row whose
     * search control adds it once a concept is picked; a narrative's text mounts a text editor.
     */
    private void addAttribute(EntityVertex clauseVertex, AnfSemantic attribute, VBox childrenBox) {
        switch (attribute.shape) {
            case CLAUSE -> anfEdit(builder -> {
                AnfStatement clause = in(builder, clauseVertex, AnfStatement.class);
                if (attribute == AnfSemantic.CONTEXT) {
                    builder.context();
                } else if (clause instanceof Circumstance.Request request) {
                    builder.repetition(request);
                }
            });
            case CIRCUMSTANCE -> anfEdit(builder -> builder.circumstance());
            case MEASURE -> anfEdit(builder -> builder.measure(in(builder, clauseVertex, AnfStatement.class),
                    attribute, null, null, true, true, null, null));
            case CONCEPT_REFERENCE, EXPRESSION, PARTICIPANT, AUTHOR ->
                    mountConceptTemplate(clauseVertex, attribute, childrenBox);
            case TEXT -> mountTextTemplate(clauseVertex, childrenBox);
            case ROOT, ASSOCIATION -> {
                // the root is never added; an association needs a statement to point at
            }
        }
    }

    /** Adds a concept-valued attribute holding a concept, by its shape. */
    private static void addWithConcept(AnfExpressionBuilder builder, AnfStatement clause, AnfSemantic attribute,
                                       ConceptFacade concept) {
        switch (attribute.shape) {
            case CONCEPT_REFERENCE -> builder.conceptReference(clause, attribute, concept);
            case EXPRESSION -> builder.expression(clause, attribute, concept);
            case PARTICIPANT -> builder.participant(clause, attribute, concept, null);
            case AUTHOR -> {
                if (!(clause instanceof AnfStatement.Context context)) {
                    throw new AnfRefusal("An author belongs to the context");
                }
                builder.author(context, concept, null);
            }
            default -> throw new AnfRefusal("A " + lowerName(attribute) + " is not chosen as a concept");
        }
    }

    private void mountConceptTemplate(EntityVertex clauseVertex, AnfSemantic attribute, VBox childrenBox) {
        KLComponentControl search = newSlot();
        Tooltip.install(search, new Tooltip("Choose " + lowerName(attribute)));
        search.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox row = new HBox(4, clauseBar("anf-attribute"), attributeLabel(attribute), search);
        row.getStyleClass().addAll("ditree-concept-row", "anf-attribute-row", "anf-template-row");
        row.setAlignment(Pos.CENTER_LEFT);
        mountTemplate(childrenBox, row);
        search.entityProperty().subscribe((oldEntity, newEntity) -> {
            if (!KLComponentControl.isEmpty(newEntity)) {
                ConceptFacade concept = ConceptFacade.make(newEntity.nid());
                anfEdit(builder -> addWithConcept(builder, in(builder, clauseVertex, AnfStatement.class),
                        attribute, concept));
            }
        });
        onEscape(search, () -> childrenBox.getChildren().remove(row));
        focusSlot(search);
    }

    private void mountTextTemplate(EntityVertex clauseVertex, VBox childrenBox) {
        TextArea area = new TextArea();
        area.setWrapText(true);
        area.setPrefRowCount(3);
        area.setPromptText("Narrative text — Shortcut+Enter adds it");
        area.getStyleClass().add("anf-narrative-text");
        HBox.setHgrow(area, Priority.ALWAYS);
        HBox row = new HBox(4, clauseBar("anf-attribute"), attributeLabel(AnfSemantic.NARRATIVE_TEXT), area);
        row.getStyleClass().addAll("ditree-concept-row", "anf-attribute-row", "anf-template-row");
        mountTemplate(childrenBox, row);
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
                String text = area.getText();
                anfEdit(builder -> builder.narrativeText(
                        in(builder, clauseVertex, Circumstance.Narrative.class), text));
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                childrenBox.getChildren().remove(row);
                e.consume();
            }
        });
        area.requestFocus();
    }

    private static void mountTemplate(VBox childrenBox, Node row) {
        childrenBox.getChildren().add(row);
        showing(childrenBox, true);
    }

    /**
     * Makes a clause header take a dropped concept: the drop offers the concept-valued
     * attributes this clause can still take, and picking one adds it holding the concept.
     */
    private void installConceptDrop(HBox header, AnfStatement clause, EntityVertex clauseVertex, VBox childrenBox) {
        List<AnfSemantic> takesConcept = admissible(clause).stream()
                .filter(attribute -> switch (attribute.shape) {
                    case CONCEPT_REFERENCE, EXPRESSION, PARTICIPANT, AUTHOR -> true;
                    default -> false;
                })
                .toList();
        if (takesConcept.isEmpty()) {
            return;
        }
        header.setOnDragOver(e -> {
            if (hasDroppableComponent(e)) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        header.setOnDragEntered(e -> {
            if (hasDroppableComponent(e)) {
                header.pseudoClassStateChanged(DROP_TARGET, true);
            }
            e.consume();
        });
        header.setOnDragExited(e -> {
            header.pseudoClassStateChanged(DROP_TARGET, false);
            e.consume();
        });
        header.setOnDragDropped(e -> {
            long nid = extractDroppedNid(e);
            if (!Nid.isNone(nid)) {
                ConceptFacade concept = ConceptFacade.make(nid);
                ContextMenu menu = newMenu();
                for (AnfSemantic attribute : takesConcept) {
                    MenuItem item = new MenuItem("Add as " + lowerName(attribute));
                    item.setOnAction(a -> anfEdit(builder -> addWithConcept(builder,
                            in(builder, clauseVertex, AnfStatement.class), attribute, concept)));
                    menu.getItems().add(item);
                }
                menu.show(header, e.getScreenX(), e.getScreenY());
                e.setDropCompleted(true);
            }
            header.pseudoClassStateChanged(DROP_TARGET, false);
            e.consume();
        });
    }
}
