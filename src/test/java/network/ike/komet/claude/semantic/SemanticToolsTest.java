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
package network.ike.komet.claude.semantic;

import dev.ikm.tinkar.component.FieldDataType;
import network.ike.komet.claude.anthropic.AnthropicTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store-free tests of the runtime {@code emit_semantic} schema builder and its parse/ground/assemble
 * logic, driven by a fake {@link Grounder}. These gate each datatype-family branch — the
 * high-blast-radius piece of the semantic lift — without needing a datastore: schema generation per
 * family, grounded/candidate/clarify slots, kind enforcement, rejection of unresolved ids, literal
 * coercion, collections, omitted fields, and idempotent emit.
 */
class SemanticToolsTest {

    /** A fake grounder backed by id → kind, mimicking existence + kind enforcement, no store. */
    private static final class FakeGrounder implements Grounder {
        private final Map<String, ComponentSlot.Kind> known = new HashMap<>();

        FakeGrounder with(String id, ComponentSlot.Kind kind) {
            known.put(id, kind);
            return this;
        }

        @Override
        public Optional<ComponentSlot.Grounded> ground(String id, ComponentSlot.Kind requiredKind) {
            ComponentSlot.Kind kind = known.get(id);
            if (kind == null) {
                return Optional.empty();
            }
            if (requiredKind != null && requiredKind != kind) {
                return Optional.empty();
            }
            return Optional.of(new ComponentSlot.Grounded(id.hashCode(), kind, "pub-" + id, id, "Label " + id));
        }
    }

    /** A recording, de-duplicating sink mirroring the engine's. */
    private static final class RecordingSink implements SemanticTools.InstanceSink {
        final List<SemanticInstance> recorded = new ArrayList<>();

        @Override
        public boolean record(SemanticInstance instance) {
            if (recorded.contains(instance)) {
                return false;
            }
            recorded.add(instance);
            return true;
        }
    }

    private static PatternFields mixedPattern() {
        return new PatternFields(900, "Test pattern", List.of(
                new PatternFields.FieldMeta(0, "condition", "Associated condition", "what", FieldDataType.CONCEPT),
                new PatternFields.FieldMeta(1, "medication", "Associated medication", "drug", FieldDataType.IDENTIFIED_THING),
                new PatternFields.FieldMeta(2, "note", "Note", "free text", FieldDataType.STRING),
                new PatternFields.FieldMeta(3, "dose", "Dose", "amount", FieldDataType.FLOAT),
                new PatternFields.FieldMeta(4, "count", "Count", "how many", FieldDataType.INTEGER),
                new PatternFields.FieldMeta(5, "active", "Active", "flag", FieldDataType.BOOLEAN),
                new PatternFields.FieldMeta(6, "members", "Members", "set", FieldDataType.COMPONENT_ID_SET),
                new PatternFields.FieldMeta(7, "tree", "Tree", "logic", FieldDataType.DITREE)));
    }

    private static Map<String, Object> input(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> map, String key) {
        return (Map<String, Object>) map.get(key);
    }

    @Test
    void schemaHasOnePropertyPerFieldWithCorrectShape() {
        AnthropicTool tool = SemanticTools.emitSemantic(mixedPattern(), new FakeGrounder(), new RecordingSink());
        Map<String, Object> schema = tool.inputSchema();
        assertEquals("object", schema.get("type"));
        Map<String, Object> props = child(schema, "properties");

        // Component reference → three-way slot object.
        Map<String, Object> condition = child(props, "condition");
        assertEquals("object", condition.get("type"));
        Map<String, Object> condProps = child(condition, "properties");
        assertTrue(condProps.containsKey("grounded_component_id"));
        assertTrue(condProps.containsKey("candidate"));
        assertTrue(condProps.containsKey("clarify"));

        // Literals → typed scalars.
        assertEquals("string", child(props, "note").get("type"));
        assertEquals("number", child(props, "dose").get("type"));
        assertEquals("integer", child(props, "count").get("type"));
        assertEquals("boolean", child(props, "active").get("type"));

        // Collection → array of slots.
        assertEquals("array", child(props, "members").get("type"));
        assertTrue(child(child(props, "members"), "items").containsKey("properties"));

        // Deferred (structured) → clarify-only object.
        Map<String, Object> tree = child(props, "tree");
        assertEquals("object", tree.get("type"));
        assertTrue(child(tree, "properties").containsKey("clarify"));
        assertFalse(child(tree, "properties").containsKey("grounded_component_id"));
    }

    @Test
    void groundedConceptIsRecordedAndFullyGrounded() {
        RecordingSink sink = new RecordingSink();
        FakeGrounder grounder = new FakeGrounder().with("73211009", ComponentSlot.Kind.CONCEPT);
        AnthropicTool tool = SemanticTools.emitSemantic(mixedPattern(), grounder, sink);

        String result = tool.execute(input("condition", input("grounded_component_id", "73211009")));

        assertEquals("recorded", result);
        assertEquals(1, sink.recorded.size());
        SemanticInstance instance = sink.recorded.get(0);
        assertEquals(900, instance.patternNid());
        assertEquals(1, instance.fields().size());
        SemanticInstance.FieldValue field = instance.fields().get(0);
        assertEquals(0, field.index());
        assertTrue(field.value() instanceof SemanticFieldValue.Component component
                && component.slot() instanceof ComponentSlot.Grounded grounded
                && grounded.kind() == ComponentSlot.Kind.CONCEPT);
        assertTrue(instance.fullyGrounded());
    }

    @Test
    void conceptOnlyFieldRejectsASemanticReferent() {
        RecordingSink sink = new RecordingSink();
        // "SEM-1" exists but is a SEMANTIC; the condition field is concept-only → rejected.
        FakeGrounder grounder = new FakeGrounder().with("SEM-1", ComponentSlot.Kind.SEMANTIC);
        AnthropicTool tool = SemanticTools.emitSemantic(mixedPattern(), grounder, sink);

        String result = tool.execute(input("condition", input("grounded_component_id", "SEM-1")));

        assertTrue(result.startsWith("These identifiers did not resolve"), result);
        assertTrue(sink.recorded.isEmpty());
    }

    @Test
    void genericComponentFieldAcceptsASemanticReferent() {
        RecordingSink sink = new RecordingSink();
        FakeGrounder grounder = new FakeGrounder().with("SEM-1", ComponentSlot.Kind.SEMANTIC);
        AnthropicTool tool = SemanticTools.emitSemantic(mixedPattern(), grounder, sink);

        String result = tool.execute(input("medication", input("grounded_component_id", "SEM-1")));

        assertEquals("recorded", result);
        ComponentSlot slot = ((SemanticFieldValue.Component) sink.recorded.get(0).fields().get(0).value()).slot();
        assertInstanceOf(ComponentSlot.Grounded.class, slot);
        assertEquals(ComponentSlot.Kind.SEMANTIC, ((ComponentSlot.Grounded) slot).kind());
    }

    @Test
    void unresolvedIdentifierIsRejectedNotInvented() {
        RecordingSink sink = new RecordingSink();
        AnthropicTool tool = SemanticTools.emitSemantic(mixedPattern(), new FakeGrounder(), sink);

        String result = tool.execute(input("condition", input("grounded_component_id", "NOPE")));

        assertTrue(result.startsWith("These identifiers did not resolve"), result);
        assertTrue(sink.recorded.isEmpty(), "an unresolved id must never produce an instance");
    }

    @Test
    void candidateAndClarifyAreHonestGaps() {
        RecordingSink sink = new RecordingSink();
        AnthropicTool tool = SemanticTools.emitSemantic(mixedPattern(), new FakeGrounder(), sink);

        tool.execute(input(
                "condition", input("candidate", input("provisional_label", "Novel disorder", "text", "x")),
                "medication", input("clarify", input("question", "Which formulation?"))));

        SemanticInstance instance = sink.recorded.get(0);
        assertEquals(2, instance.fields().size());
        assertTrue(((SemanticFieldValue.Component) instance.fields().get(0).value()).slot()
                instanceof ComponentSlot.Candidate);
        assertTrue(((SemanticFieldValue.Component) instance.fields().get(1).value()).slot()
                instanceof ComponentSlot.Clarify);
        assertFalse(instance.fullyGrounded(), "a candidate or clarify leaves the instance not fully grounded");
    }

    @Test
    void literalsAreParsedToTheirTypes() {
        RecordingSink sink = new RecordingSink();
        AnthropicTool tool = SemanticTools.emitSemantic(mixedPattern(), new FakeGrounder(), sink);

        tool.execute(input("note", "hello", "dose", 2.5, "count", 3, "active", true));

        SemanticInstance instance = sink.recorded.get(0);
        assertEquals(4, instance.fields().size());
        assertEquals("hello", ((SemanticFieldValue.StringValue) value(instance, 2)).value());
        assertEquals(2.5, ((SemanticFieldValue.FloatValue) value(instance, 3)).value());
        assertEquals(3L, ((SemanticFieldValue.IntegerValue) value(instance, 4)).value());
        assertTrue(((SemanticFieldValue.BooleanValue) value(instance, 5)).value());
        assertTrue(instance.fullyGrounded());
    }

    @Test
    void componentCollectionGroundsEachMember() {
        RecordingSink sink = new RecordingSink();
        FakeGrounder grounder = new FakeGrounder()
                .with("73211009", ComponentSlot.Kind.CONCEPT)
                .with("SEM-1", ComponentSlot.Kind.SEMANTIC);
        AnthropicTool tool = SemanticTools.emitSemantic(mixedPattern(), grounder, sink);

        tool.execute(input("members", List.of(
                input("grounded_component_id", "73211009"),
                input("grounded_component_id", "SEM-1"))));

        SemanticFieldValue value = sink.recorded.get(0).fields().get(0).value();
        assertInstanceOf(SemanticFieldValue.Components.class, value);
        assertEquals(2, ((SemanticFieldValue.Components) value).slots().size());
    }

    @Test
    void omittedFieldsAreAbsentFromTheInstance() {
        RecordingSink sink = new RecordingSink();
        FakeGrounder grounder = new FakeGrounder().with("73211009", ComponentSlot.Kind.CONCEPT);
        AnthropicTool tool = SemanticTools.emitSemantic(mixedPattern(), grounder, sink);

        tool.execute(input("condition", input("grounded_component_id", "73211009")));

        assertEquals(1, sink.recorded.get(0).fields().size(),
                "only the field the request filled should appear");
    }

    @Test
    void duplicateEmitIsIdempotent() {
        RecordingSink sink = new RecordingSink();
        FakeGrounder grounder = new FakeGrounder().with("73211009", ComponentSlot.Kind.CONCEPT);
        AnthropicTool tool = SemanticTools.emitSemantic(mixedPattern(), grounder, sink);

        tool.execute(input("condition", input("grounded_component_id", "73211009")));
        String second = tool.execute(input("condition", input("grounded_component_id", "73211009")));

        assertEquals("This semantic was already recorded; do not emit it again.", second);
        assertEquals(1, sink.recorded.size());
    }

    private static SemanticFieldValue value(SemanticInstance instance, int fieldIndex) {
        return instance.fields().stream()
                .filter(f -> f.index() == fieldIndex)
                .findFirst().orElseThrow().value();
    }
}
