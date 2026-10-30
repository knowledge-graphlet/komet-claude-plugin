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

import dev.ikm.komet.framework.Identicon;
import dev.ikm.komet.kview.controls.ComponentItem;
import dev.ikm.komet.kview.controls.KLComponentControl;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.terms.EntityProxy;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.editor.AnfStatementControl;
import network.ike.komet.claude.anf.model.AnfExpression;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfSemantic;
import network.ike.komet.claude.anf.model.AnfStatement;
import network.ike.komet.claude.complexconcept.model.ComplexConcept;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Expansion;
import network.ike.komet.claude.complexconcept.model.ComplexConceptBuilder;
import network.ike.komet.claude.complexconcept.model.ComplexConceptExpression;
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
 * The complex concept control renders a complex concept specification with the axiom control's
 * rows and styles and edits it through the complex concept builder (IKE-Network/ike-issues#1312):
 * the definition as the axiom control itself, each prerequisite as the ANF statement control, the
 * conjunction and the OR policy as segmented controls, a refused edit shown and left undone, chip
 * edits routed to the builder, nested statement edits spliced back, undo across them, and the
 * missing parts listed.
 *
 * <p>The control is rendered in a scene that is never shown, so no window opens; the edits run on
 * the FX thread through the control's own nodes or the skin's edit entry points.</p>
 */
class ComplexConceptControlTest {

    private static final UUID NAMESPACE = UUID.fromString("2b7c9e41-6a0d-5f83-9b1e-4c5d6e7f8a90");
    private static final Map<Long, String> NAMES = new ConcurrentHashMap<>();

    private ComplexConceptControl control;

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

    @BeforeEach
    void clear() {
        control = null;
    }

    private static EntityProxy.Concept concept(String name) {
        EntityProxy.Concept concept = EntityProxy.Concept.make(name, UuidT5Generator.get(NAMESPACE, "cc-editor:" + name));
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

    private static DiTreeEntity prerequisite(String topic, String lower, String upper, String unit) {
        AnfExpressionBuilder statement = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        statement.expression(statement.context(), AnfSemantic.TOPIC, concept(topic));
        statement.measure(statement.circumstance(), AnfSemantic.RESULT, new BigDecimal(lower),
                upper == null ? null : new BigDecimal(upper), true, upper != null, null, concept(unit));
        return statement.asDiTree();
    }

    /** Sitting systolic blood pressure: a focused expansion with three preconditions. */
    private static DiTreeEntity sittingSystolic() {
        ComplexConceptBuilder builder = new ComplexConceptBuilder();
        builder.definition(AnfExpressionBuilder.singleConceptExpression(concept("Systolic blood pressure observation")));
        Expansion.Focused focused = builder.focusedExpansion(concept("Systolic blood pressure observation"));
        builder.prerequisite(focused, prerequisite("Seated position", "1", "1", "Presence"), IkeConcept.PRECONDITION_ASSOCIATION);
        builder.prerequisite(focused, prerequisite("Seated duration", "5", null, "Minutes"), IkeConcept.PRECONDITION_ASSOCIATION);
        builder.prerequisite(focused, prerequisite("Recent voiding", "1", "1", "Presence"), IkeConcept.PRECONDITION_ASSOCIATION);
        return builder.asDiTree();
    }

    /** NVD screening: a uniform OR expansion of three members, with no policy yet. */
    private static DiTreeEntity nvdScreening() {
        ComplexConceptBuilder builder = new ComplexConceptBuilder();
        builder.definition(AnfExpressionBuilder.singleConceptExpression(concept("Gastrointestinal symptom screening")));
        Expansion.Uniform uniform = builder.uniformExpansion(IkeConcept.OR_CONJUNCTION, concept("Presence"));
        for (String member : List.of("Nausea assessment", "Vomiting assessment", "Diarrhea assessment")) {
            builder.member(uniform, concept(member));
        }
        return builder.asDiTree();
    }

    private void render(DiTreeEntity tree, boolean editable) throws Exception {
        fx(() -> {
            control = new ComplexConceptControl();
            control.setComponentItemResolver(nid -> new ComponentItem(NAMES.getOrDefault(nid, "Concept"),
                    Identicon.generateIdenticonImage(PrimitiveData.publicId(nid))));
            control.setDescriptionResolver(nid -> NAMES.getOrDefault(nid, "Concept"));
            if (editable) {
                control.setComponentSlotFactory(KLComponentControl::new);
            }
            control.setValue(tree);
            new Scene(new StackPane(control), 900, 700);
            control.applyCss();
            control.layout();
        });
    }

    private ComplexConceptControlSkin skin() {
        return (ComplexConceptControlSkin) control.getSkin();
    }

    private ComplexConceptExpression value() {
        return ComplexConceptExpression.from(control.getValue());
    }

    private ToggleButton segment(String label) {
        return control.lookupAll(".cc-segment").stream()
                .map(ToggleButton.class::cast)
                .filter(segment -> segment.getText().equalsIgnoreCase(label))
                .findFirst().orElseThrow(() -> new AssertionError("no segment " + label));
    }

    @Test
    @DisplayName("The definition is an axiom control and each prerequisite an ANF statement control")
    void rendersAFocusedSpecification() throws Exception {
        render(sittingSystolic(), true);
        assertEquals(1, (int) onFx(() -> control.lookupAll(".cc-nested-definition").size()));
        List<AnfStatementControl> statements = onFx(() -> control.lookupAll(".cc-nested-statement").stream()
                .map(AnfStatementControl.class::cast).toList());
        assertEquals(3, statements.size(), "one statement control per prerequisite");
        assertFalse(statements.getFirst().isShowsMissing(), "a prerequisite does not list a subject of record as missing");
        assertTrue(onFx(() -> control.getMissing().isEmpty()), "the specification is complete");
        assertNull(onFx(control::getLastRefusal));
    }

    @Test
    @DisplayName("The conjunction and the OR policy are segmented controls, and the safety rule holds")
    void theOrPolicyIsChosenAndGuardsTheConjunction() throws Exception {
        render(nvdScreening(), true);
        assertTrue(onFx(() -> segment("OR").isSelected()));
        assertEquals(List.of("how the OR conjunction handles a non-absent value"),
                onFx(() -> List.copyOf(control.getMissing())));

        fx(() -> segment("Refuse expansion").fire());
        Expansion.Uniform uniform = (Expansion.Uniform) value().root().expansion().orElseThrow();
        assertEquals(IkeConcept.REFUSE_EXPANSION.nid(), uniform.unsafeDistributionPolicy().orElseThrow().nid());
        assertTrue(onFx(() -> control.getMissing().isEmpty()));

        DiTreeEntity before = onFx(control::getValue);
        fx(() -> segment("AND").fire());
        String refusal = onFx(control::getLastRefusal);
        assertNotNull(refusal, "an AND conjunction keeping a policy is refused");
        assertTrue(refusal.contains("remove the policy"), refusal);
        assertEquals(before, onFx(control::getValue), "a refused edit leaves the tree as it was");
    }

    @Test
    @DisplayName("A chosen concept is routed to the builder, which refuses a repeated member")
    void chipEditsAreRoutedToTheBuilder() throws Exception {
        render(nvdScreening(), true);
        ComplexConcept.Member diarrhea = ((Expansion.Uniform) value().root().expansion().orElseThrow()).members().get(2);
        long abdominalPain = concept("Abdominal pain assessment").nid();
        fx(() -> skin().chooseConcept(control.getValue().vertex(diarrhea.vertexIndex()), abdominalPain));
        ComplexConcept.Member changed = ((Expansion.Uniform) value().root().expansion().orElseThrow()).members().get(2);
        assertEquals(abdominalPain, changed.phenomenon().orElseThrow().nid());
        assertEquals(diarrhea.vertexUUID(), changed.vertexUUID(), "the member keeps its identity");

        long nausea = concept("Nausea assessment").nid();
        fx(() -> skin().chooseConcept(control.getValue().vertex(changed.vertexIndex()), nausea));
        assertNotNull(onFx(control::getLastRefusal), "a phenomenon is a member at most once");
    }

    @Test
    @DisplayName("An edit inside a prerequisite's statement control is spliced back and undone")
    void aNestedStatementEditIsSplicedBack() throws Exception {
        render(sittingSystolic(), true);
        DiTreeEntity before = onFx(control::getValue);
        AnfStatementControl seatedDuration = onFx(() -> (AnfStatementControl) control.lookupAll(".cc-nested-statement")
                .stream().toList().get(1));
        DiTreeEntity edited = onFx(() -> {
            AnfExpressionBuilder statement = new AnfExpressionBuilder(seatedDuration.getValue());
            AnfStatement.Attribute.Measure result = AnfExpression.from(seatedDuration.getValue()).root().circumstance()
                    .map(c -> ((AnfStatement.Circumstance.Performance) c).result().orElseThrow()).orElseThrow();
            statement.updateMeasure((AnfStatement.Attribute.Measure) statement.expression().vertex(result.vertexUUID()),
                    new BigDecimal("10"), null, true, false, null, null);
            return statement.asDiTree();
        });
        fx(() -> seatedDuration.setValue(edited));

        ComplexConcept.Prerequisite prerequisite =
                ((Expansion.Focused) value().root().expansion().orElseThrow()).prerequisites().get(1);
        AnfStatement.Attribute.Measure spliced = prerequisite.statement().orElseThrow().root().circumstance()
                .map(c -> ((AnfStatement.Circumstance.Performance) c).result().orElseThrow()).orElseThrow();
        assertEquals(new BigDecimal("10"), spliced.lowerBound().orElseThrow(), "ten minutes or more");

        assertTrue(onFx(control::canUndo));
        fx(control::undo);
        assertEquals(before, onFx(control::getValue), "undo restores the specification");
    }

    @Test
    @DisplayName("Without a search factory the specification is read-only")
    void readOnlyHasNoAdditionsAndDisabledSegments() throws Exception {
        render(nvdScreening(), false);
        assertEquals(0, (int) onFx(() -> control.lookupAll(".ditree-add-button").size()), "no + buttons");
        assertTrue(onFx(() -> segment("OR").isDisabled()), "the conjunction cannot be changed");
    }
}
