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

import dev.ikm.komet.framework.Identicon;
import dev.ikm.komet.kview.controls.ComponentItem;
import dev.ikm.komet.kview.controls.KLComponentControl;
import dev.ikm.komet.kview.controls.KLDiTreeControl;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.claude.anf.model.AnfExpression;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfProvisional;
import network.ike.komet.claude.anf.model.AnfSemantic;
import network.ike.komet.claude.anf.model.AnfStatement;
import network.ike.komet.claude.anf.model.AnfStatement.Attribute;
import network.ike.komet.claude.anf.model.AnfStatement.Circumstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ANF statement control renders an ANF statement tree with the axiom control's rows
 * and styles and edits it through the ANF builder (IKE-Network/ike-issues#1312): attribute rows, a
 * nested logical expression as the axiom control itself, the kind as one segmented control, a
 * refused edit shown and left undone, chip edits and measure edits routed to the ANF builder,
 * nested expression edits spliced back, undo across all of them, and the missing parts listed.
 *
 * <p>The control is rendered in a scene that is never shown, so no window opens; the edits run
 * on the FX thread through the control's own nodes or the skin's edit entry points.</p>
 */
class AnfStatementControlTest {

    private static final UUID NAMESPACE = UUID.fromString("9a1e3c55-7d2b-5f40-8e6a-3b2c1d0e9f8a");
    private static final Map<Long, String> NAMES = new ConcurrentHashMap<>();

    private AnfStatementControl control;
    /** What the control's host writes for it, when a test gives it one; none by default, as a host that cannot write. */
    private AnfStatementControl.ConceptWriter writer;
    /** What a search for any words finds, in the search's order. */
    private List<EntityFacade> found = List.of();

    @BeforeAll
    static void start() {
        try {
            HeadlessFx.select();
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Started by another test class in this JVM.
        } catch (RuntimeException | Error noToolkit) {
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit);
        }
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
    }

    @AfterAll
    static void stop() {
        PrimitiveData.stop();
    }

    private static EntityProxy.Concept concept(String name) {
        EntityProxy.Concept concept = EntityProxy.Concept.make(name, UuidT5Generator.get(NAMESPACE, "anf-editor:" + name));
        NAMES.put(concept.nid(), name);
        return concept;
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(20, TimeUnit.SECONDS);
    }

    private static void fx(Runnable work) throws Exception {
        onFx(() -> {
            work.run();
            return null;
        });
    }

    /**
     * A performance: a context with a subject of record, a topic and a statement time, and a
     * circumstance with a result, a method and a purpose.
     */
    private static DiTreeEntity performance() {
        AnfExpressionBuilder builder = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        AnfStatement.Context context = builder.context();
        builder.participant(context, AnfSemantic.SUBJECT_OF_RECORD, concept("The patient"), concept("Patient role"));
        builder.expression(context, AnfSemantic.TOPIC, concept("Systolic blood pressure"));
        builder.measure(context, AnfSemantic.STATEMENT_TIME, new BigDecimal("1000"), new BigDecimal("1000"),
                true, true, null, concept("Seconds"));
        Circumstance circumstance = builder.circumstance();
        builder.measure(circumstance, AnfSemantic.RESULT, new BigDecimal("120"), new BigDecimal("120"),
                true, true, null, concept("mm[Hg]"));
        builder.conceptReference(circumstance, AnfSemantic.METHOD, concept("Auscultation"));
        builder.expression(circumstance, AnfSemantic.PURPOSE, concept("Screening"));
        return builder.asDiTree();
    }

    private void render(DiTreeEntity tree) throws Exception {
        fx(() -> {
            control = new AnfStatementControl();
            control.setComponentItemResolver(nid -> new ComponentItem(NAMES.getOrDefault(nid, "Concept"),
                    Identicon.generateIdenticonImage(PrimitiveData.publicId(nid))));
            control.setDescriptionResolver(nid -> NAMES.getOrDefault(nid, "Concept"));
            control.setComponentSlotFactory(() -> {
                KLComponentControl slot = new KLComponentControl();
                slot.setTypeAheadCompleter(words -> found);
                slot.setComponentNameRenderer(entity -> NAMES.getOrDefault(entity.nid(), "Concept"));
                return slot;
            });
            control.setConceptWriter(writer);
            control.setValue(tree);
            new Scene(new StackPane(control), 700, 600);
            control.applyCss();
            control.layout();
        });
    }

    private AnfStatementControlSkin skin() {
        return (AnfStatementControlSkin) control.getSkin();
    }

    private AnfExpression value() {
        return AnfExpression.from(control.getValue());
    }

    private <A extends AnfStatement> A only(Class<A> type) {
        List<A> nodes = value().nodesOfType(type).castToList();
        assertEquals(1, nodes.size(), "one " + type.getSimpleName());
        return nodes.getFirst();
    }

    private Attribute.ConceptReference method() {
        return value().nodesOfType(Attribute.ConceptReference.class).stream()
                .filter(chip -> chip.semantic() == AnfSemantic.METHOD).findFirst().orElseThrow();
    }

    private Attribute.Measure result() {
        return value().nodesOfType(Attribute.Measure.class).stream()
                .filter(measure -> measure.semantic() == AnfSemantic.RESULT).findFirst().orElseThrow();
    }

    private Attribute.Expression topic() {
        return value().nodesOfType(Attribute.Expression.class).stream()
                .filter(expression -> expression.semantic() == AnfSemantic.TOPIC).findFirst().orElseThrow();
    }

    private ToggleButton kindSegment(String label) {
        return control.lookupAll(".anf-kind-segment").stream()
                .map(ToggleButton.class::cast)
                .filter(segment -> segment.getText().equalsIgnoreCase(label))
                .findFirst().orElseThrow(() -> new AssertionError("no kind segment " + label));
    }

    @BeforeEach
    void clearNames() {
        control = null;
        writer = null;
        found = List.of();
    }

    /** A performance whose method is words, with no concept yet. */
    private static DiTreeEntity methodInWords() {
        AnfExpressionBuilder builder = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        builder.expression(builder.context(), AnfSemantic.TOPIC, concept("Removal of the implant"));
        builder.provisionalConceptReference(builder.circumstance(), AnfSemantic.METHOD,
                new AnfProvisional.Candidate("Clinical report", "Clinical report", "PENDING", List.of()));
        return builder.asDiTree();
    }

    /** Clicks the value that has no concept, as a person does to open or close its panel. */
    private void clickUnsettled() throws Exception {
        fx(() -> control.lookup(".anf-unsettled").getOnMouseClicked().handle(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true, false, false, null)));
    }

    /** Clicks a listed concept's row, as a person does to select it, or twice to use it. */
    private void clickRow(int index, int clicks) throws Exception {
        fx(() -> control.lookup("#anf-find-result-" + index).getOnMouseClicked().handle(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                MouseButton.PRIMARY, clicks, false, false, false, false, true, false, false, true, false, false, null)));
    }

    /** Which listed rows are selected, by their place in the list. */
    private List<Integer> selectedRows() throws Exception {
        return onFx(() -> {
            List<Node> rows = List.copyOf(control.lookupAll(".anf-find-result"));
            return java.util.stream.IntStream.range(0, rows.size())
                    .filter(i -> rows.get(i).getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("selected")))
                    .boxed().toList();
        });
    }

    /** Presses a key in the search field. */
    private void press(javafx.scene.input.KeyCode key) throws Exception {
        fx(() -> control.lookup("#anf-find-query").fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                "", "", key, false, false, false, false)));
    }

    private Node inPanel(String id) throws Exception {
        return onFx(() -> control.lookup("#anf-find-panel") == null ? null : control.lookup("#anf-find-panel").lookup(id));
    }

    /** The names of the concepts the panel lists, once its search has answered. */
    private List<String> listed() throws Exception {
        for (int tries = 0; tries < 100; tries++) {
            List<String> names = onFx(() -> control.lookupAll(".anf-find-result-name").stream().map(node -> ((Label) node).getText()).toList());
            if (!names.isEmpty()) {
                return names;
            }
            Thread.sleep(50);
        }
        return List.of();
    }

    @Test
    @DisplayName("A value with no concept shows its words in full, and a click opens a panel beneath its row")
    void aValueWithNoConceptOpensAPanelBeneathItsRow() throws Exception {
        found = List.of(concept("Auscultation"), concept("Palpation"));
        render(methodInWords());
        Node value = onFx(() -> control.lookup(".anf-unsettled"));
        assertNotNull(value, "the method's value");
        assertEquals(List.of("Clinical report"), onFx(() -> value.lookupAll(".label").stream().map(node -> ((Label) node).getText())
                .filter(text -> !text.startsWith("Find or create")).toList()), "the words as they are");
        assertEquals(List.of("?"), onFx(() -> ((javafx.scene.Parent) value.lookup(".anf-unsettled-mark")).getChildrenUnmodifiable().stream()
                .filter(javafx.scene.text.Text.class::isInstance).map(node -> ((javafx.scene.text.Text) node).getText()).toList()),
                "behind a question mark in a ring, the one mark of words the knowledge base has nothing for");
        assertEquals(1, (int) onFx(() -> (int) ((javafx.scene.Parent) value.lookup(".anf-unsettled-mark")).getChildrenUnmodifiable().stream()
                .filter(javafx.scene.shape.Circle.class::isInstance).count()));
        assertNull(onFx(() -> value.lookup(".anf-complex-mark")), "a method is a concept, not a group: no complex concept is wanted for it");
        assertTrue(onFx(() -> ((Label) value.lookup(".anf-provisional")).isWrapText()), "words too long for the row go on to a second line");
        assertTrue(onFx(() -> value.lookupAll(".ditree-slot").isEmpty()), "no search box stands in the row");
        assertNull(onFx(() -> control.lookup("#anf-find-panel")), "the panel waits for a click");

        clickUnsettled();
        Node panel = onFx(() -> control.lookup("#anf-find-panel"));
        assertNotNull(panel, "a click opens the panel");
        assertTrue(onFx(() -> panel.getParent().getStyleClass().contains("anf-unsettled-row")
                && panel.getParent().getChildrenUnmodifiable().indexOf(panel) == 1), "beneath the value's own row");
        assertEquals("Clinical report", onFx(() -> ((TextField) panel.lookup("#anf-find-query")).getText()), "the search begins with the words");
        assertTrue(onFx(() -> ((Label) panel.lookup(".anf-find-need")).getText()).contains("how the result was obtained"), "what a method is");
        assertEquals(List.of("Auscultation", "Palpation"), listed(), "the concepts the search finds, in its order");
        assertNull(inPanel("#anf-find-create"), "a host that cannot write offers no new concept");
        assertNull(inPanel("#anf-find-also-describe"), "and no additional description");

        // a listed concept is a row to select, and Use settles the value with the selected one
        assertTrue(onFx(() -> control.lookup(".anf-find-result").lookupAll(".button").isEmpty()), "a row has no button of its own");
        Button use = (Button) inPanel("#anf-find-use");
        assertEquals("Use the selected concept", onFx(use::getText));
        assertTrue(onFx(use::isDisable), "nothing is selected until a person selects it");
        assertEquals(List.of(), selectedRows());
        clickRow(1, 1);
        assertEquals(List.of(1), selectedRows(), "a click selects the row");
        assertFalse(onFx(use::isDisable));
        assertTrue(method().concept().isEmpty(), "and settles nothing yet");
        clickRow(0, 1);
        assertEquals(List.of(0), selectedRows(), "one row is selected at a time");
        fx(use::fire);
        assertEquals(concept("Auscultation").nid(), method().concept().orElseThrow().nid(), "Use settles the value with the selected concept");
        assertNull(onFx(() -> control.lookup("#anf-find-panel")), "and the row is drawn again, closed");
        assertNull(onFx(() -> control.lookup(".anf-unsettled")));

        assertTrue(onFx(control::undo));
        assertTrue(method().concept().isEmpty(), "undo gives the words back");
        clickUnsettled();
        assertNotNull(onFx(() -> control.lookup("#anf-find-panel")));
        clickUnsettled();
        assertNull(onFx(() -> control.lookup("#anf-find-panel")), "a second click closes the panel");
    }

    @Test
    @DisplayName("The arrow keys move the selection through the found concepts, Enter uses it, and so does a double click")
    void theSelectionIsMovedByKeysAndUsedByEnterOrADoubleClick() throws Exception {
        found = List.of(concept("Auscultation"), concept("Palpation"));
        render(methodInWords());
        clickUnsettled();
        assertEquals(List.of("Auscultation", "Palpation"), listed());
        press(javafx.scene.input.KeyCode.DOWN);
        assertEquals(List.of(0), selectedRows(), "down selects the first row when none is selected");
        press(javafx.scene.input.KeyCode.DOWN);
        assertEquals(List.of(1), selectedRows());
        press(javafx.scene.input.KeyCode.DOWN);
        assertEquals(List.of(1), selectedRows(), "and stops at the last");
        press(javafx.scene.input.KeyCode.UP);
        assertEquals(List.of(0), selectedRows());
        press(javafx.scene.input.KeyCode.DOWN);
        fx(() -> ((TextField) control.lookup("#anf-find-query")).getOnAction().handle(new javafx.event.ActionEvent()));
        assertEquals(concept("Palpation").nid(), method().concept().orElseThrow().nid(), "Enter uses the selected concept");

        assertTrue(onFx(control::undo));
        clickUnsettled();
        assertEquals(List.of("Auscultation", "Palpation"), listed());
        assertEquals(List.of(), selectedRows(), "a panel opened again has nothing selected");
        clickRow(0, 2);
        assertEquals(concept("Auscultation").nid(), method().concept().orElseThrow().nid(), "a double click selects the row and uses it");
    }

    @Test
    @DisplayName("The statement's form begins with the statement's sign, and each mark says what it is")
    void theFormBeginsWithTheSignAndItsMarksSayWhatTheyAre() throws Exception {
        render(methodInWords());
        Node sign = onFx(() -> control.lookup(".anf-statement-sign"));
        assertNotNull(sign, "the sign a statement has wherever it is named");
        assertTrue(onFx(() -> sign.getParent().getStyleClass().contains("ditree-clause-header")
                && sign.getParent().getChildrenUnmodifiable().indexOf(sign) == 0), "at the head of the statement's clause");
        assertNull(onFx(() -> control.lookup(".ditree-clause-bar.anf-statement")), "in place of the bar the clause began with");
        assertNotNull(onFx(() -> control.lookup(".ditree-clause-bar.anf-circumstance")), "the other clauses keep their bars");
        assertTrue(MarkMeaning.of(sign).startsWith("Statement\n"), MarkMeaning.of(sign));
        Node words = onFx(() -> control.lookup(".anf-unsettled-mark"));
        assertEquals(MarkMeaning.WORDS_ONLY, MarkMeaning.of(words), "the question mark says what it means");

        // a statement that is what must hold keeps a bar before its sign, dotted
        fx(() -> {
            AnfStatementControl holds = new AnfStatementControl();
            holds.setMustHold(true);
            holds.setComponentItemResolver(control.getComponentItemResolver());
            holds.setDescriptionResolver(control.getDescriptionResolver());
            holds.setValue(methodInWords());
            new Scene(new StackPane(holds), 700, 600);
            holds.applyCss();
            holds.layout();
            control = holds;
        });
        Node bar = onFx(() -> control.lookup(".ditree-clause-bar.anf-must-hold"));
        assertNotNull(bar, "what must hold has its bar");
        assertEquals(MarkMeaning.MUST_HOLD, MarkMeaning.of(bar));
        assertEquals(1, (int) onFx(() -> bar.getParent().getChildrenUnmodifiable().indexOf(control.lookup(".anf-statement-sign"))),
                "and the statement's sign after it");
    }

    @Test
    @DisplayName("A candidate is kept as words: no concept is asked for, and its mark goes")
    void aCandidateIsKeptAsWords() throws Exception {
        render(methodInWords());
        clickUnsettled();
        fx(() -> ((Button) control.lookup("#anf-find-keep")).fire());
        AnfProvisional.Candidate kept = (AnfProvisional.Candidate) method().provisional().orElseThrow();
        assertEquals(AnfExpressionBuilder.KEPT_AS_WORDS, kept.disposition());
        assertEquals("Clinical report", kept.label());
        assertNull(onFx(() -> control.lookup(".anf-unsettled-mark")), "words that are kept carry no mark");
        assertEquals("Clinical report", onFx(() -> ((Label) control.lookup(".anf-kept-words")).getText()));
        clickUnsettled();
        assertNull(onFx(() -> control.lookup("#anf-find-keep")), "words already kept are not offered to be kept");
        assertNotNull(onFx(() -> control.lookup("#anf-find-query")), "a concept can still be found for them");
    }

    @Test
    @DisplayName("Where the host can write, the words become a new concept, or an additional description of the concept used")
    void theWordsBecomeANewConceptOrAnAdditionalDescription() throws Exception {
        List<String> written = new ArrayList<>();
        writer = new AnfStatementControl.ConceptWriter() {
            @Override
            public ConceptFacade create(String name, ConceptFacade parent) {
                written.add("create " + name + " below " + NAMES.get(parent.nid()));
                return concept(name);
            }

            @Override
            public List<String> dialects() {
                return List.of("US English", "GB English");
            }

            @Override
            public void addDescription(ConceptFacade concept, String text, String dialect, boolean preferred) {
                written.add("describe " + NAMES.get(concept.nid()) + " as " + text + ", " + (preferred ? "preferred" : "acceptable") + " in " + dialect);
            }
        };
        found = List.of(concept("Auscultation"));
        render(methodInWords());
        clickUnsettled();
        assertEquals("Clinical report", onFx(() -> ((TextField) control.lookup("#anf-find-name")).getText()), "the new concept's name begins as the words");

        fx(() -> ((Button) control.lookup("#anf-find-create")).fire());
        assertEquals(List.of(), written, "nothing is made of a concept that is a kind of nothing");
        assertEquals("Choose what the new concept is a kind of.", onFx(() -> ((Label) control.lookup(".anf-find-problem")).getText()));
        assertTrue(method().concept().isEmpty());

        fx(() -> {
            ((KLComponentControl) control.lookup("#anf-find-parent")).setEntity(concept("Report"));
            ((Button) control.lookup("#anf-find-create")).fire();
        });
        assertEquals(List.of("create Clinical report below Report"), written);
        assertEquals(concept("Clinical report").nid(), method().concept().orElseThrow().nid(), "the value is the concept that was made");

        // the same words, added to a concept that was found as an additional description of it
        written.clear();
        render(methodInWords());
        clickUnsettled();
        assertEquals(List.of("Auscultation"), listed());
        CheckBox also = (CheckBox) onFx(() -> control.lookup("#anf-find-also-describe"));
        assertEquals("Add an additional Description to the selected concept", onFx(also::getText));
        Node text = onFx(() -> control.lookup("#anf-find-description"));
        assertFalse(onFx(() -> text.getParent().getParent().isVisible()), "what the description is waits for the box to be ticked");

        // ticked with nothing more chosen: the words, acceptable, in the first dialect the knowledge base offers
        fx(() -> also.setSelected(true));
        assertTrue(onFx(() -> text.getParent().getParent().isVisible()), "a ticked box shows the description's text, dialect and acceptability");
        assertEquals("Clinical report", onFx(() -> ((TextField) text).getText()));
        assertEquals(List.of("US English", "GB English"), onFx(() -> List.of(
                ((ToggleButton) control.lookup("#anf-find-dialect-0")).getText(), ((ToggleButton) control.lookup("#anf-find-dialect-1")).getText())));
        assertTrue(onFx(() -> ((ToggleButton) control.lookup("#anf-find-dialect-0")).isSelected()
                && ((ToggleButton) control.lookup("#anf-find-acceptability-0")).isSelected()), "the first dialect, and acceptable, until chosen otherwise");
        clickRow(0, 1);
        fx(() -> ((Button) control.lookup("#anf-find-use")).fire());
        assertEquals(List.of("describe Auscultation as Clinical report, acceptable in US English"), written);
        assertEquals(concept("Auscultation").nid(), method().concept().orElseThrow().nid());

        // another text, preferred, in the other dialect
        written.clear();
        render(methodInWords());
        clickUnsettled();
        assertEquals(List.of("Auscultation"), listed());
        fx(() -> {
            ((CheckBox) control.lookup("#anf-find-also-describe")).setSelected(true);
            ((TextField) control.lookup("#anf-find-description")).setText("Report of a clinic");
            ((ToggleButton) control.lookup("#anf-find-dialect-1")).fire();
            ((ToggleButton) control.lookup("#anf-find-acceptability-1")).fire();
        });
        clickRow(0, 1);
        fx(() -> ((Button) control.lookup("#anf-find-use")).fire());
        assertEquals(List.of("describe Auscultation as Report of a clinic, preferred in GB English"), written);

        // a description with no text is not added, and the value is left as it was
        written.clear();
        render(methodInWords());
        clickUnsettled();
        assertEquals(List.of("Auscultation"), listed());
        fx(() -> {
            ((CheckBox) control.lookup("#anf-find-also-describe")).setSelected(true);
            ((TextField) control.lookup("#anf-find-description")).setText("  ");
        });
        clickRow(0, 1);
        fx(() -> ((Button) control.lookup("#anf-find-use")).fire());
        assertEquals(List.of(), written);
        assertEquals("Give the additional description its text.", onFx(() -> ((Label) control.lookup(".anf-find-problem")).getText()));
        assertTrue(method().concept().isEmpty());
    }

    @Test
    @DisplayName("Every attribute is a row, and every nested expression is an axiom control")
    void rendersTheStatement() throws Exception {
        render(performance());
        int rows = onFx(() -> control.lookupAll(".anf-attribute-row").size());
        int nested = onFx(() -> (int) control.lookupAll(".anf-nested-expression").stream()
                .filter(node -> node instanceof KLDiTreeControl).count());
        assertEquals(6, rows, "subject of record, topic, statement time, result, method, purpose");
        assertEquals(2, nested, "the topic and the purpose");
        assertTrue(onFx(() -> kindSegment("Performance").isSelected()));
        assertTrue(onFx(() -> control.getMissing().isEmpty()), "the statement is complete");
    }

    @Test
    @DisplayName("A measure on the Presence scale reads Present by the control's name for it, not the store's")
    void presenceIsKnownByTheNameTheControlResolves() throws Exception {
        AnfExpressionBuilder builder = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        builder.measure(builder.circumstance(), AnfSemantic.RESULT, BigDecimal.ONE, null,
                true, false, null, concept("Presence"));
        render(builder.asDiTree());
        assertEquals("Present", onFx(() -> ((Label) control.lookup(".anf-presence")).getText()));
    }

    @Test
    @DisplayName("Changing the kind of an empty circumstance replaces it")
    void kindChangeReplacesAnEmptyCircumstance() throws Exception {
        AnfExpressionBuilder builder = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        builder.context();
        builder.circumstance();
        render(builder.asDiTree());

        fx(() -> kindSegment("Request").fire());
        assertEquals(AnfSemantic.REQUEST, value().root().kindSemantic());
        assertTrue(value().root().circumstance().orElseThrow() instanceof Circumstance.Request);
        assertNull(onFx(control::getLastRefusal));
    }

    @Test
    @DisplayName("A refused edit is shown and leaves the tree as it was")
    void refusalIsShownNotReverted() throws Exception {
        render(performance());
        DiTreeEntity before = control.getValue();

        fx(() -> kindSegment("Narrative").fire());
        assertEquals(before, control.getValue());
        String refusal = onFx(control::getLastRefusal);
        assertNotNull(refusal);
        assertTrue(refusal.contains("Remove"), refusal);
        assertTrue(onFx(() -> kindSegment("Performance").isSelected()), "the kind control shows the kind again");
        assertFalse(onFx(control::canUndo), "a refused edit is no step to undo");
    }

    @Test
    @DisplayName("A chip edit re-points the ANF attribute, and undo restores it")
    void chipEditRoutesToTheAnfBuilder() throws Exception {
        render(performance());
        ConceptFacade palpation = concept("Palpation");
        fx(() -> {
            var vertex = control.getValue().vertex(method().vertexIndex());
            skin().chooseConcept(vertex, palpation.nid());
        });
        assertEquals(palpation.nid(), method().concept().orElseThrow().nid());

        assertTrue(onFx(control::undo));
        assertEquals(concept("Auscultation").nid(), method().concept().orElseThrow().nid());
    }

    @Test
    @DisplayName("Editing a measure's bound in place updates the measure")
    void measureBoundEdit() throws Exception {
        render(performance());
        fx(() -> {
            Node resultRow = control.lookupAll(".anf-attribute-row").stream()
                    .filter(row -> row.lookupAll(".anf-attribute-label").stream()
                            .anyMatch(label -> ((javafx.scene.control.Label) label).getText()
                                    .equals(AnfStatementControlSkin.name(AnfSemantic.RESULT))))
                    .findFirst().orElseThrow();
            TextField lower = (TextField) resultRow.lookupAll(".ditree-bound-field").iterator().next();
            lower.setText("118");
            lower.fireEvent(new javafx.event.ActionEvent());
        });
        assertEquals(new BigDecimal("118"), result().lowerBound().orElseThrow());
        assertEquals(new BigDecimal("120"), result().upperBound().orElseThrow());
    }

    @Test
    @DisplayName("An edit inside a nested expression is spliced back as the attribute's value")
    void nestedExpressionEditIsSpliced() throws Exception {
        render(performance());
        DiTreeEntity diastolic = AnfExpressionBuilder.singleConceptExpression(concept("Diastolic blood pressure"));
        fx(() -> {
            KLDiTreeControl topicControl = (KLDiTreeControl) control.lookupAll(".anf-nested-expression").stream()
                    .filter(node -> node instanceof KLDiTreeControl nested
                            && nested.getValue().equals(topic().expressionTree().orElseThrow()))
                    .findFirst().orElseThrow();
            topicControl.setValue(diastolic);
        });
        assertEquals(diastolic, topic().expressionTree().orElseThrow());
        assertTrue(onFx(control::undo));
        assertEquals(concept("Systolic blood pressure").nid(), topic().singleConcept().orElseThrow().nid());
    }

    @Test
    @DisplayName("What a statement still lacks is listed")
    void missingPartsAreListed() throws Exception {
        render(new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE).asDiTree());
        List<String> missing = onFx(() -> List.copyOf(control.getMissing()));
        assertTrue(missing.contains("a context"), missing.toString());
    }

    @Test
    @DisplayName("The admission table decides structural moves")
    void admissionDecidesMoves() throws Exception {
        render(performance());
        boolean methodIntoContext = onFx(() -> {
            DiTreeEntity tree = control.getValue();
            return control.canMove(tree.vertex(method().vertexIndex()),
                    tree.vertex(only(AnfStatement.Context.class).vertexIndex()));
        });
        assertFalse(methodIntoContext, "a context takes no method");
    }
}
