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
import dev.ikm.tinkar.terms.EntityProxy;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.model.AnfExpression;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
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

    @BeforeAll
    static void start() {
        try {
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
            control.setComponentSlotFactory(KLComponentControl::new);
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
