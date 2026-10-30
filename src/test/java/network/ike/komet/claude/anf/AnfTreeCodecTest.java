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

import dev.ikm.tinkar.entity.EntityCodec3;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import io.activej.bytebuf.ByteBuf;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.model.AnfExpression;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfProvisional;
import network.ike.komet.claude.anf.model.AnfSemantic;
import network.ike.komet.claude.anf.model.AnfStatement.Attribute;
import network.ike.komet.claude.anf.model.AnfStatement.Circumstance.Narrative;
import network.ike.komet.claude.anf.model.AnfStatement.Circumstance.Performance;
import network.ike.komet.claude.anf.model.AnfStatement.Context;
import network.ike.komet.claude.anf.model.AnfStatement.Root;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static network.ike.komet.claude.anf.AnfTestStore.concept;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A statement tree survives the entity codec: tree to bytes to tree keeps every vertex identity,
 * every property including a nested logical expression held by value, and reads back through
 * the same adaptors (IKE-Network/ike-issues#1312).
 *
 * <p>The format is {@link EntityCodec3}'s, entity format 3: what the 64-bit store these tests
 * run on writes, with each nid as its pattern and element sequences.
 */
class AnfTreeCodecTest {

    /** The store's own entity format: what a tree written into a semantic field is encoded in. */
    private static final byte STORE_FORMAT = EntityCodec3.FORMAT;

    @BeforeAll
    static void startStore() {
        AnfTestStore.start();
    }

    @AfterAll
    static void stopStore() {
        AnfTestStore.stop();
    }

    private static DiTreeEntity roundTrip(DiTreeEntity tree, byte format) {
        byte[] bytes = tree.getBytes(format);
        return DiTreeEntity.make(ByteBuf.wrapForReading(bytes), format);
    }

    private static DiTreeEntity statement() {
        AnfExpressionBuilder builder = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        Context context = builder.context();
        builder.conceptReference(context, AnfSemantic.STATEMENT_TYPE, IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        builder.expression(context, AnfSemantic.TOPIC, concept("Systolic blood pressure"));
        builder.participant(context, AnfSemantic.SUBJECT_OF_RECORD, concept("The patient"), concept("Patient role"));
        builder.author(context, concept("Dr Example"), AnfExpressionBuilder.singleConceptExpression(concept("Attending")));
        Performance performance = (Performance) builder.circumstance();
        builder.measure(performance, AnfSemantic.RESULT, new BigDecimal("120.5"), new BigDecimal("120.5"), true, false,
                new BigDecimal("0.1"), concept("mmHg"));
        builder.provisionalConceptReference(performance, AnfSemantic.BODY_SITE,
                new AnfProvisional.Candidate("Left antecubital fossa", "the left elbow crease", "PENDING", List.of("a", "b")));
        builder.provisionalConceptReference(performance, AnfSemantic.LATERALITY,
                new AnfProvisional.Clarification("laterality", "Which arm?"));
        return builder.asDiTree();
    }

    private static void assertSameStatement(DiTreeEntity tree, DiTreeEntity decoded) {
        assertEquals(tree.vertexMap().size(), decoded.vertexMap().size());
        for (int i = 0; i < tree.vertexMap().size(); i++) {
            EntityVertex before = tree.vertex(i);
            EntityVertex after = decoded.vertex(i);
            assertEquals(before.asUuid(), after.asUuid(), "vertex identity " + i);
            assertEquals(before.getMeaningNid(), after.getMeaningNid(), "vertex meaning " + i);
        }
        Root original = AnfExpression.from(tree).root();
        Root root = AnfExpression.from(decoded).root();
        assertEquals(AnfSemantic.PERFORMANCE, root.kindSemantic());
        Context readContext = root.context().orElseThrow();
        Attribute.Expression readTopic = readContext.topic().orElseThrow();
        assertEquals(original.context().orElseThrow().topic().orElseThrow().vertexUUID(), readTopic.vertexUUID());
        assertEquals(concept("Systolic blood pressure").nid(), readTopic.singleConcept().orElseThrow().nid(),
                "the nested logical expression is written as a tree property and read back whole");
        assertEquals(concept("Patient role").nid(), readContext.subjectOfRecord().orElseThrow().role().orElseThrow().nid());
        assertTrue(readContext.authors().get(0).role().isPresent(), "an author's role expression survives");

        Performance readPerformance = (Performance) root.circumstance().orElseThrow();
        Attribute.Measure readResult = readPerformance.result().orElseThrow();
        assertEquals(new BigDecimal("120.5"), readResult.lowerBound().orElseThrow(), "a decimal bound is exact");
        assertTrue(readResult.includeLowerBound());
        assertFalse(readResult.includeUpperBound());
        assertEquals(new BigDecimal("0.1"), readResult.resolution().orElseThrow());
        assertEquals(concept("mmHg").nid(), readResult.unit().orElseThrow().nid());
        AnfProvisional.Candidate candidate = assertInstanceOf(AnfProvisional.Candidate.class,
                readPerformance.bodySite().orElseThrow().provisional().orElseThrow());
        assertEquals(List.of("a", "b"), candidate.nearestMatches());
        assertEquals("the left elbow crease", candidate.text());
        AnfProvisional.Clarification clarification = assertInstanceOf(AnfProvisional.Clarification.class,
                readPerformance.laterality().orElseThrow().provisional().orElseThrow());
        assertEquals("Which arm?", clarification.question());
    }

    @Test
    void aStatementTreeRoundTripsThroughTheStoreFormat() {
        DiTreeEntity tree = statement();
        assertSameStatement(tree, roundTrip(tree, STORE_FORMAT));
    }

    @Test
    void aDecodedTreeIsEditableAndWritesAgain() {
        AnfExpressionBuilder builder = new AnfExpressionBuilder(IkeConcept.NARRATIVE_CIRCUMSTANCE);
        Narrative narrative = (Narrative) builder.circumstance();
        builder.narrativeText(narrative, "First text.");
        DiTreeEntity decoded = roundTrip(builder.asDiTree(), STORE_FORMAT);

        AnfExpressionBuilder again = new AnfExpressionBuilder(decoded);
        Narrative readNarrative = (Narrative) again.root().circumstance().orElseThrow();
        Attribute.NarrativeText text = readNarrative.text().orElseThrow();
        again.updateText(text, "Second text.");
        DiTreeEntity rewritten = roundTrip(again.asDiTree(), STORE_FORMAT);
        Narrative rewrittenNarrative = (Narrative) AnfExpression.from(rewritten).root().circumstance().orElseThrow();
        assertEquals("Second text.", rewrittenNarrative.text().orElseThrow().text());
        assertEquals(text.vertexUUID(), rewrittenNarrative.text().orElseThrow().vertexUUID());
        Narrative untouched = (Narrative) AnfExpression.from(decoded).root().circumstance().orElseThrow();
        assertEquals("First text.", untouched.text().orElseThrow().text(),
                "editing through a builder leaves the decoded tree as it was");
    }
}
