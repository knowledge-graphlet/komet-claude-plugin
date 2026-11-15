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
import dev.ikm.tinkar.terms.EntityFacade;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import network.ike.komet.claude.anf.model.AnfExpression;
import network.ike.komet.document.ComponentText;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

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
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

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
            List<String> missing = new ArrayList<>(control.getMissing());
            if (control.isEnvelopeFromContext()) {
                missing.remove(AnfExpressionBuilder.MISSING_SUBJECT_OF_RECORD);
                missing.remove(AnfExpressionBuilder.MISSING_STATEMENT_TIME);
            }
            missingLabel.setText(missing.isEmpty() ? "" : "Still needs " + String.join(", ", missing) + ".");
            showing(missingLabel, !missing.isEmpty() && isEditable() && control.isShowsMissing());
        };
        control.getMissing().subscribe(showMissing);
        control.showsMissingProperty().subscribe(showMissing);
        control.envelopeFromContextProperty().subscribe(showMissing);
        // The parent rendered once while this skin was still being constructed.
        rebuildTree(control.getValue());
    }

    /**
     * The name an attribute, clause or kind shows: its meaning's description without the
     * trailing semantic tag the starter set's fully qualified names carry, so
     * "Performance circumstance (IkeFoundation)" shows as "Performance circumstance", and
     * without the "ANF" the attribute meanings' names begin with, so "ANF topic" shows as "Topic":
     * every row in the control is ANF.
     *
     * @param semantic the meaning
     * @return its display name
     */
    /**
     * The height the tree needs at a width: its rows are measured at the width they will be given, since the words of
     * a value with no concept wrap. A skin measures its children at no width in particular unless it says otherwise.
     */
    @Override
    protected double computePrefHeight(double width, double topInset, double rightInset, double bottomInset, double leftInset) {
        double inner = width < 0 ? -1 : Math.max(0, width - leftInset - rightInset);
        double height = 0;
        for (Node child : getChildren()) {
            if (child.isManaged()) {
                height = Math.max(height, child.prefHeight(inner));
            }
        }
        return topInset + height + bottomInset;
    }

    static String name(AnfSemantic semantic) {
        String name = semantic.describe().replaceFirst("\\s*\\([^()]*\\)\\s*$", "").replaceFirst("^ANF ", "");
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
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
            case AnfStatement.Root root -> statementClause(vertex, tree);
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

    /**
     * The statement's own clause, which begins with the sign a statement has wherever it is named,
     * where every other clause begins with its bar. A statement that is what must hold keeps a
     * bar before the sign, dotted, which says so.
     */
    private Node statementClause(EntityVertex vertex, DiTreeEntity tree) {
        Node clause = createClauseNode(vertex, tree, "anf-statement", "Statement");
        if (clause instanceof VBox box && !box.getChildren().isEmpty() && box.getChildren().getFirst() instanceof HBox header
                && !header.getChildren().isEmpty() && header.getChildren().getFirst().getStyleClass().contains("ditree-clause-bar")) {
            Node sign = StatementSign.node(SIGN_SIZE);
            if (anfControl().isMustHold()) {
                Node bar = header.getChildren().getFirst();
                bar.getStyleClass().add("anf-must-hold");
                MarkMeaning.install(bar, MarkMeaning.MUST_HOLD);
                header.getChildren().add(1, sign);
            } else {
                header.getChildren().set(0, sign);
            }
        }
        return clause;
    }

    /** The size of text the statement's sign stands beside in its form: the clause's label, in capitals. */
    private static final double SIGN_SIZE = 13;
    /** The size of a value's words in the form, which the mark of words with no concept stands beside. */
    private static final double WORDS_SIZE = 12;

    /**
     * A labelled attribute row: accent bar, attribute name, then the value nodes. A row with a
     * value that has no concept yet stands in a column, where that value's panel opens beneath it.
     */
    private Node attributeRow(EntityVertex vertex, AnfSemantic attribute, Node... value) {
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
        if (opening().isEmpty()) {
            return row;
        }
        VBox column = new VBox(row);
        column.getStyleClass().add("anf-unsettled-row");
        for (FindOrCreate find : opening()) {
            find.beneath(column, attribute);
        }
        opening().clear();
        return column;
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
     * The values with no concept yet that were built since the last attribute row, which mounts each one's panel
     * beneath itself. Made on first use: the superclass draws the tree before this class's fields are set.
     */
    private List<FindOrCreate> opening;

    private List<FindOrCreate> opening() {
        if (opening == null) {
            opening = new ArrayList<>();
        }
        return opening;
    }

    /**
     * The value of an attribute that has no concept yet: the words it has in place of one, in
     * full, with a mark that says a concept is still to be found. When editing, a click on the
     * value opens a panel beneath its row, where a concept is found or made
     * ({@link FindOrCreate}).
     */
    private Node unsettledValue(EntityVertex vertex, ChipKind kind, Optional<AnfProvisional> provisional,
                                String prompt) {
        if (!isEditable() && provisional.isEmpty()) {
            Label none = new Label("not stated");
            none.getStyleClass().add("anf-unstated");
            return none;
        }
        FindOrCreate find = new FindOrCreate(vertex, kind, provisional, prompt);
        if (isEditable()) {
            opening().add(find);
        }
        return find.value;
    }

    /** What an attribute's concept is, in a sentence, for a person who is about to find or make one. */
    private static String needOf(AnfSemantic attribute, ChipKind kind) {
        if (kind == ChipKind.INTERVAL_UNIT) {
            return "The unit is what the numbers count or measure: a unit of measure, a scale, or the things counted.";
        }
        if (kind == ChipKind.ROLE_TYPE) {
            return "The role is the part this participant has in what was done.";
        }
        return switch (attribute) {
            case TOPIC -> "The topic is what was done or looked for: an action, such as a procedure or an observation.";
            case SUBJECT_OF_INFORMATION -> "The subject of information is who or what the statement is about, when that is not the subject of record.";
            case SUBJECT_OF_RECORD -> "The subject of record is the person or thing whose record the statement is in.";
            case AUTHOR -> "The author is who makes the statement.";
            case STATEMENT_TYPE -> "The statement type says whether something was done or is asked for.";
            case PURPOSE -> "The purpose is why it was done or is asked for.";
            case METHOD -> "The method is how the result was obtained.";
            case DEVICE -> "The device is what was used to do it.";
            case BODY_SITE -> "The body site is where on the body it was done.";
            case LATERALITY -> "The laterality is which side of the body.";
            case APPROACH -> "The approach is the route taken to the site.";
            case SPECIMEN_TYPE -> "The specimen type is what kind of sample was examined.";
            case SPECIMEN_SOURCE_SITE -> "The specimen source site is where the sample was taken from.";
            case DISPOSITION -> "The disposition is what became of the action when no result was determined.";
            case HEALTH_RISK -> "The health risk is how the result bears on the subject's health.";
            case PRIORITY -> "The priority is how soon it is asked for.";
            case CONDITIONAL_TRIGGER -> "The conditional trigger is what must hold before the request applies.";
            case LOCATION -> "The location is where it was done or is to be done.";
            case PARTICIPANT -> "A participant is who took part in what was done.";
            case ASSOCIATED_STATEMENT -> "The association is how this statement stands to the other one.";
            default -> "Choose the concept for the " + lowerName(attribute) + ".";
        };
    }

    /**
     * A value with no concept yet, and the panel that opens beneath its row to settle it. The row
     * shows the words in full; the panel says what the attribute needs, lists the concepts
     * closest to the words, each with Use, and, where the control's host can write
     * ({@link AnfStatementControl#getConceptWriter()}), offers to make a new concept from the
     * words or to add an additional description to the concept used, a regular name with the
     * dialect and acceptability the person chooses. A candidate can also be kept as words.
     * Settling the value is an edit of the tree, which draws the row again, closed.
     */
    private final class FindOrCreate {
        private static final String OPEN = "Find or create ▸";
        private static final String CLOSE = "Close ▾";
        /** How many concepts the panel lists: the closest few, since more are found by better words. */
        private static final int LISTED = 6;

        final EntityVertex vertex;
        final ChipKind kind;
        final Optional<AnfProvisional> provisional;
        final String prompt;
        final HBox value = new HBox(4);
        final Label link = new Label(OPEN);
        VBox column;
        AnfSemantic attribute;
        VBox panel;
        TextField query;
        final VBox results = new VBox(2);
        final Label status = new Label();
        final Label problem = new Label();
        /** Whether the concept that is used is also given an additional description, and what that description is. */
        CheckBox alsoDescribe;
        TextField describedAs;
        ToggleGroup dialects;
        ToggleButton preferred;
        /** The search slot the panel's searches run through, as the tree's own search controls do. */
        KLComponentControl searcher;
        /** Counts the searches asked, so a slow answer to an earlier one is dropped. */
        int asked;
        /** The concepts listed, and the words they were found for. */
        List<ConceptFacade> listed = List.of();
        String listedFor = "";
        /** The listed concept that is selected: the one Use settles the value with, and an additional description is added to. */
        ConceptFacade selected;
        Button useSelected;

        FindOrCreate(EntityVertex vertex, ChipKind kind, Optional<AnfProvisional> provisional, String prompt) {
            this.vertex = vertex;
            this.kind = kind;
            this.provisional = provisional;
            this.prompt = prompt;
            value.setAlignment(Pos.CENTER_LEFT);
            value.getStyleClass().add("anf-unsettled");
            value.setId("anf-find-" + vertex.vertexIndex() + (kind == ChipKind.ROLE_TYPE ? "-role" : ""));
            if (!kept()) {
                // one mark for words the knowledge base has nothing for, whatever they turn out to be
                Node mark = WordsMark.node(WORDS_SIZE);
                mark.getStyleClass().add("anf-unsettled-mark");
                value.getChildren().add(mark);
            }
            Label said = new Label(said());
            said.getStyleClass().add(kept() ? "anf-kept-words" : "anf-provisional");
            said.pseudoClassStateChanged(PROVISIONAL, !kept());
            said.setWrapText(true);
            said.setMinWidth(0);
            value.getChildren().add(said);
            HBox.setHgrow(value, Priority.ALWAYS);
            if (isEditable()) {
                link.getStyleClass().add("anf-find-link");
                link.setMinWidth(Region.USE_PREF_SIZE);
                value.getChildren().add(link);
                value.setCursor(Cursor.HAND);
                value.setOnMouseClicked(e -> {
                    toggle();
                    e.consume();
                });
                Tooltip.install(value, new Tooltip(kept() ? "Kept as words. " + prompt : prompt));
            }
        }

        /** Whether the value is a candidate a person chose to keep as words. */
        private boolean kept() {
            return provisional.orElse(null) instanceof AnfProvisional.Candidate candidate
                    && AnfExpressionBuilder.KEPT_AS_WORDS.equals(candidate.disposition());
        }

        /** The words the value has, when it is a candidate: what a search begins with, and a new concept's name. */
        private String words() {
            return provisional.orElse(null) instanceof AnfProvisional.Candidate candidate ? candidate.label().strip() : "";
        }

        /** What the row says in place of a concept. */
        private String said() {
            return switch (provisional.orElse(null)) {
                case AnfProvisional.Candidate candidate -> candidate.label();
                case AnfProvisional.Clarification clarification -> "Asks: " + clarification.question();
                case null -> "No " + (prompt.startsWith("Choose ") ? prompt.substring("Choose ".length()) : "concept") + " yet";
            };
        }

        /** Stands the value's panel beneath its row, in the row's column. */
        void beneath(VBox column, AnfSemantic attribute) {
            this.column = column;
            this.attribute = attribute;
        }

        /** Opens the panel beneath the row, or closes it. */
        void toggle() {
            if (column == null) {
                return;
            }
            if (panel == null) {
                panel = panel();
            }
            if (column.getChildren().contains(panel)) {
                column.getChildren().remove(panel);
                link.setText(OPEN);
                return;
            }
            VBox.setMargin(panel, new javafx.geometry.Insets(2, 0, 6, 14));
            column.getChildren().add(panel);
            link.setText(CLOSE);
            search(query.getText());
            Platform.runLater(query::requestFocus);
            revealSoon();
        }

        /**
         * Brings the open panel into view once it is laid out, after each of the next two layouts, since a wrapped
         * label's height arrives in the second: a panel opened at the foot of a view is no use below it.
         */
        private void revealSoon() {
            javafx.scene.Scene scene = column == null ? null : column.getScene();
            if (scene == null) {
                return;
            }
            int[] layouts = {0};
            Runnable[] afterLayout = new Runnable[1];
            afterLayout[0] = () -> {
                reveal();
                if (++layouts[0] >= 2) {
                    scene.removePostLayoutPulseListener(afterLayout[0]);
                } else {
                    Platform.requestNextPulse();
                }
            };
            scene.addPostLayoutPulseListener(afterLayout[0]);
            Platform.requestNextPulse();
        }

        /**
         * Scrolls the nearest scroll pane the form stands in, by as little as shows the panel's foot, and never so far
         * that the value's own row leaves the view: a panel taller than the view shows from its row down.
         */
        private void reveal() {
            if (panel == null || panel.getScene() == null || !column.getChildren().contains(panel)) {
                return;
            }
            javafx.scene.control.ScrollPane scroll = null;
            for (Node at = column.getParent(); at != null && scroll == null; at = at.getParent()) {
                scroll = at instanceof javafx.scene.control.ScrollPane pane ? pane : null;
            }
            if (scroll == null || scroll.getContent() == null) {
                return;
            }
            Node content = scroll.getContent();
            javafx.geometry.Bounds stands = content.sceneToLocal(column.localToScene(column.getBoundsInLocal()));
            double viewport = scroll.getViewportBounds().getHeight();
            double range = content.getBoundsInLocal().getHeight() - viewport;
            if (stands == null || viewport <= 0 || range <= 0) {
                return;
            }
            double top = scroll.getVvalue() * range;
            double want = top;
            if (stands.getMaxY() + 6 > top + viewport) {
                want = stands.getMaxY() + 6 - viewport;
            }
            if (stands.getMinY() < want) {
                want = stands.getMinY();
            }
            if (Math.abs(want - top) >= 1) {
                scroll.setVvalue(Math.clamp(want / range, 0, 1));
            }
        }

        private VBox panel() {
            VBox built = new VBox(6);
            built.getStyleClass().add("anf-find-panel");
            built.setId("anf-find-panel");
            searcher = newSlot();
            Label need = new Label(needOf(attribute, kind));
            need.getStyleClass().add("anf-find-need");
            need.setWrapText(true);
            query = new TextField(words());
            query.setId("anf-find-query");
            query.setPromptText("Words to search for");
            // a search follows the typing once it rests, and Enter asks at once
            PauseTransition rest = new PauseTransition(javafx.util.Duration.millis(350));
            rest.setOnFinished(e -> search(query.getText()));
            query.textProperty().subscribe((was, now) -> rest.playFromStart());
            query.setOnAction(e -> {
                rest.stop();
                // Enter uses the selected concept while the list is the one found for these words, and searches otherwise
                if (selected != null && listedFor.equals(query.getText() == null ? "" : query.getText().strip())) {
                    use(selected);
                } else {
                    search(query.getText());
                }
            });
            // the arrow keys move the selection through the list while the words are typed
            query.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.UP) {
                    step(e.getCode() == KeyCode.DOWN ? 1 : -1);
                    e.consume();
                }
            });
            onEscape(query, this::toggle);
            status.getStyleClass().add("anf-find-status");
            status.setWrapText(true);
            results.setId("anf-find-results");
            problem.getStyleClass().add("anf-find-problem");
            problem.setWrapText(true);
            showing(problem, false);
            built.getChildren().addAll(need, query, heading("Closest concepts"), status, results);
            AnfStatementControl.ConceptWriter writer = anfControl().getConceptWriter();
            List<String> offered = writer == null ? List.of() : writer.dialects();
            if (!offered.isEmpty()) {
                alsoDescribe = new CheckBox("Add an additional Description to the selected concept");
                alsoDescribe.setId("anf-find-also-describe");
                alsoDescribe.setWrapText(true);
                Node describing = describing(offered);
                showing(describing, false);
                alsoDescribe.selectedProperty().subscribe(selected -> {
                    showing(describing, selected);
                    revealSoon();
                });
                built.getChildren().addAll(alsoDescribe, describing);
            }
            useSelected = new Button("Use the selected concept");
            useSelected.setId("anf-find-use");
            useSelected.setDisable(true);
            useSelected.setOnAction(e -> {
                if (selected != null) {
                    use(selected);
                }
            });
            built.getChildren().add(useSelected);
            if (writer != null) {
                built.getChildren().addAll(heading("None of these"), newConcept(writer));
            }
            built.getChildren().add(problem);
            if (provisional.orElse(null) instanceof AnfProvisional.Candidate && !kept()) {
                Button keep = new Button("Keep as words");
                keep.setId("anf-find-keep");
                Tooltip.install(keep, new Tooltip("Leave these words as they are: no concept is asked for"));
                keep.setOnAction(e -> anfEdit(builder -> builder.keepAsWords(in(builder, vertex, AnfStatement.class))));
                built.getChildren().add(keep);
            }
            return built;
        }

        private Label heading(String text) {
            Label heading = new Label(text);
            heading.getStyleClass().add("anf-find-heading");
            return heading;
        }

        /**
         * What the additional description is, shown while the box is ticked: its text, the dialect its
         * acceptability is in, and whether it is acceptable or preferred there. It is a regular name.
         */
        private Node describing(List<String> offered) {
            describedAs = new TextField(words());
            describedAs.setId("anf-find-description");
            describedAs.setPromptText("Text of the description");
            HBox.setHgrow(describedAs, Priority.ALWAYS);
            dialects = new ToggleGroup();
            HBox dialect = segments(dialects, offered, 0, "anf-find-dialect-");
            ToggleGroup acceptability = new ToggleGroup();
            HBox accepted = segments(acceptability, List.of("Acceptable", "Preferred"), 0, "anf-find-acceptability-");
            preferred = (ToggleButton) accepted.getChildren().get(1);
            Label says = new Label();
            says.getStyleClass().add("anf-find-status");
            says.setWrapText(true);
            Runnable say = () -> says.setText(preferred.isSelected()
                    ? "Added as a regular name. The regular name preferred in this dialect now becomes acceptable."
                    : "Added as a regular name.");
            acceptability.selectedToggleProperty().subscribe(toggle -> say.run());
            say.run();
            VBox rows = new VBox(4, labelled("Description", describedAs), labelled("Dialect", dialect), labelled("Acceptability", accepted), says);
            rows.getStyleClass().add("anf-find-describing");
            rows.setPadding(new javafx.geometry.Insets(0, 0, 2, 22));
            return rows;
        }

        private HBox labelled(String name, Node value) {
            Label label = new Label(name);
            label.getStyleClass().add("anf-find-status");
            label.setMinWidth(86);
            HBox row = new HBox(6, label, value);
            row.setAlignment(Pos.CENTER_LEFT);
            return row;
        }

        /** One segmented control over exclusive choices, one of them always chosen. */
        private HBox segments(ToggleGroup group, List<String> choices, int chosen, String id) {
            HBox segments = new HBox();
            segments.getStyleClass().add("anf-kind-control");
            for (int i = 0; i < choices.size(); i++) {
                ToggleButton segment = new ToggleButton(choices.get(i));
                segment.getStyleClass().add("anf-kind-segment");
                segment.setId(id + i);
                segment.setToggleGroup(group);
                segment.setSelected(i == chosen);
                // a click on the chosen segment leaves it chosen
                segment.setOnAction(e -> segment.setSelected(true));
                segments.getChildren().add(segment);
            }
            return segments;
        }

        /** The row that makes a concept from the words: its name, what it is a kind of, and Create. */
        private Node newConcept(AnfStatementControl.ConceptWriter writer) {
            TextField name = new TextField(words());
            name.setId("anf-find-name");
            name.setPromptText("Fully qualified name of the new concept");
            Tooltip.install(name, new Tooltip("The new concept's fully qualified name, which is also its preferred regular name"));
            HBox.setHgrow(name, Priority.ALWAYS);
            Label kindOf = new Label("a kind of");
            kindOf.getStyleClass().add("anf-find-status");
            kindOf.setMinWidth(Region.USE_PREF_SIZE);
            KLComponentControl parent = newSlot();
            parent.setId("anf-find-parent");
            Tooltip.install(parent, new Tooltip("What the new concept is a kind of"));
            HBox.setHgrow(parent, Priority.ALWAYS);
            Button create = new Button("Create");
            create.setId("anf-find-create");
            create.setMinWidth(Region.USE_PREF_SIZE);
            create.setOnAction(e -> {
                String fqn = name.getText() == null ? "" : name.getText().strip();
                if (fqn.isEmpty()) {
                    problem("Give the new concept a name.");
                    return;
                }
                if (KLComponentControl.isEmpty(parent.getEntity())) {
                    problem("Choose what the new concept is a kind of.");
                    return;
                }
                ConceptFacade made;
                try {
                    made = writer.create(fqn, ConceptFacade.make(parent.getEntity().nid()));
                } catch (RuntimeException failure) {
                    problem("The concept was not made: " + reason(failure));
                    return;
                }
                applyChipEdit(vertex, kind, made.nid());
            });
            // the name has a line of its own: beside the search for what it is a kind of, it would have no room
            HBox below = new HBox(6, kindOf, parent, create);
            below.setAlignment(Pos.CENTER_LEFT);
            VBox rows = new VBox(4, name, below);
            rows.getStyleClass().add("anf-find-new");
            return rows;
        }

        /** Says why something asked for in the panel was not done; empty takes the line away. */
        private void problem(String text) {
            problem.setText(text);
            showing(problem, !text.isEmpty());
        }

        private static String reason(Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && (cause.getMessage() == null || cause instanceof java.util.concurrent.CompletionException)) {
                cause = cause.getCause();
            }
            return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        }

        /** Lists the concepts closest to some words, searched off the application thread. */
        private void search(String text) {
            int mine = ++asked;
            String wanted = text == null ? "" : text.strip();
            results.getChildren().clear();
            listed = List.of();
            listedFor = "";
            marked();
            if (wanted.length() < 3) {
                selected = null;
                status("Type three letters or more to search.");
                return;
            }
            status("Searching …");
            Function<String, List<EntityFacade>> completer = searcher.getCompleter();
            if (completer == null) {
                status("This form has no search.");
                return;
            }
            CompletableFuture.supplyAsync(() -> completer.apply(wanted)).whenComplete((found, failure) -> Platform.runLater(() -> {
                if (mine != asked) {
                    return;                                          // a later search was asked since
                }
                if (failure != null) {
                    status("The search could not be run: " + reason(failure));
                    return;
                }
                List<ConceptFacade> concepts = found.stream().filter(ConceptFacade.class::isInstance)
                        .map(ConceptFacade.class::cast).limit(LISTED).toList();
                status(concepts.isEmpty() ? "No concept is found for these words." : "");
                listed = concepts;
                listedFor = wanted;
                for (int i = 0; i < concepts.size(); i++) {
                    results.getChildren().add(result(concepts.get(i), i));
                }
                // a concept selected before stays selected while it is still listed
                if (selected != null && concepts.stream().noneMatch(concept -> concept.nid() == selected.nid())) {
                    selected = null;
                }
                marked();
                revealSoon();                                        // the panel is taller by what was found
            }));
        }

        private void status(String text) {
            status.setText(text);
            showing(status, !text.isEmpty());
        }

        /** Selects a listed concept, or none: the one Use settles the value with. */
        private void select(ConceptFacade concept) {
            selected = concept;
            problem("");
            marked();
        }

        /** Moves the selection through the list by a row, from no selection to its first row or its last. */
        private void step(int by) {
            if (listed.isEmpty()) {
                return;
            }
            int at = -1;
            for (int i = 0; i < listed.size() && selected != null; i++) {
                at = listed.get(i).nid() == selected.nid() ? i : at;
            }
            int next = at < 0 ? (by > 0 ? 0 : listed.size() - 1) : Math.clamp(at + by, 0, listed.size() - 1);
            select(listed.get(next));
        }

        /** Shows which row is selected, and lets Use be pressed only while a listed concept is. */
        private void marked() {
            boolean any = false;
            for (Node row : results.getChildren()) {
                boolean is = selected != null && Long.valueOf(selected.nid()).equals(row.getUserData());
                row.pseudoClassStateChanged(SELECTED, is);
                any |= is;
            }
            if (useSelected != null) {
                useSelected.setDisable(!any);
            }
        }

        /**
         * One concept the search found, as a row to select: its identicon, its name, and what it is a
         * kind of. A click selects it, a double click uses it.
         */
        private Node result(ConceptFacade concept, int index) {
            ImageView icon = new ImageView(dev.ikm.komet.framework.Identicon.generateIdenticonImage(concept.publicId()));
            icon.setFitWidth(16);
            icon.setFitHeight(16);
            Function<EntityFacade, String> namer = searcher.getComponentNameRenderer();
            Label name = new Label(namer == null ? concept.description() : namer.apply(concept));
            name.getStyleClass().add("anf-find-result-name");
            name.setWrapText(true);
            name.setMinWidth(0);
            // what it is a kind of stands beneath the name, each in full, on as many lines as it needs
            VBox named = new VBox(name);
            named.setMinWidth(0);
            HBox.setHgrow(named, Priority.ALWAYS);
            String parent = parentOf(concept, namer);
            if (!parent.isEmpty()) {
                Label kindOf = new Label("a kind of " + parent);
                kindOf.getStyleClass().add("anf-find-status");
                kindOf.setWrapText(true);
                kindOf.setMinWidth(0);
                named.getChildren().add(kindOf);
            }
            // the tick is the selection's mark that does not rest on colour
            Label tick = new Label("\u2713");
            tick.getStyleClass().add("anf-find-result-tick");
            tick.setMinWidth(Region.USE_PREF_SIZE);
            HBox row = new HBox(6, icon, named, tick);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("anf-find-result");
            row.setId("anf-find-result-" + index);
            row.setUserData(concept.nid());
            row.setCursor(Cursor.HAND);
            row.setOnMouseClicked(e -> {
                select(concept);
                if (e.getClickCount() >= 2) {
                    use(concept);
                }
                e.consume();
            });
            Tooltip.install(row, new Tooltip("Click to select this concept. A double click uses it."));
            return row;
        }

        /** The first thing a concept is a kind of, by name, for telling like-named concepts apart; empty when it has none. */
        private static String parentOf(ConceptFacade concept, Function<EntityFacade, String> namer) {
            try {
                var parents = Calculators.View.Default().navigationCalculator().parentsOf(concept.nid());
                if (parents.isEmpty()) {
                    return "";
                }
                ConceptFacade parent = ConceptFacade.make(parents.get(0));
                return namer == null ? parent.description() : namer.apply(parent);
            } catch (RuntimeException unread) {
                return "";
            }
        }

        /** Settles the value with a concept that was found, adding the additional description to it first when that is asked. */
        private void use(ConceptFacade concept) {
            problem("");
            AnfStatementControl.ConceptWriter writer = anfControl().getConceptWriter();
            if (writer != null && alsoDescribe != null && alsoDescribe.isSelected()) {
                String text = describedAs.getText() == null ? "" : describedAs.getText().strip();
                if (text.isEmpty()) {
                    problem("Give the additional description its text.");
                    return;
                }
                try {
                    writer.addDescription(concept, text, ((ToggleButton) dialects.getSelectedToggle()).getText(), preferred.isSelected());
                } catch (RuntimeException failure) {
                    problem("The description was not added, and nothing was changed: " + reason(failure));
                    return;
                }
            }
            applyChipEdit(vertex, kind, concept.nid());
        }
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
        presence(measure).ifPresent(word -> {
            Label determination = new Label(word);
            determination.getStyleClass().add("anf-presence");
            determination.setStyle("-fx-font-weight: bold;");
            value.add(determination);
        });
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

    /**
     * "Present" or "Absent" when the measure is on the Presence scale: absence is {@code [0, 0]} and
     * presence a lower bound of one or more. The scale is known by its name, since the
     * starter set does not bind the Presence concept. The name is the control's own: a block's
     * local concept has none in the store, only the one its control resolves.
     */
    private Optional<String> presence(Attribute.Measure measure) {
        Optional<ConceptFacade> unit = measure.unit();
        if (unit.isEmpty()
                || !control().getDescriptionResolver().apply(unit.get().nid()).strip().toLowerCase(java.util.Locale.ROOT).startsWith("presence")) {
            return Optional.empty();
        }
        Optional<BigDecimal> lower = measure.lowerBound();
        Optional<BigDecimal> upper = measure.upperBound();
        if (lower.isPresent() && lower.get().signum() == 0 && upper.isPresent() && upper.get().signum() == 0) {
            return Optional.of("Absent");
        }
        if (lower.isPresent() && lower.get().compareTo(BigDecimal.ONE) >= 0) {
            return Optional.of("Present");
        }
        return Optional.empty();
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
