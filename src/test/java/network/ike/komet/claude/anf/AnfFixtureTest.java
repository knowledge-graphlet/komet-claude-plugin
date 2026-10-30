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

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Expansion;
import network.ike.komet.claude.complexconcept.model.ComplexConceptBuilder;
import network.ike.komet.claude.koncept.ComponentText;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ANF and complex concept test fixtures are well formed (IKE-Network/ike-issues#1312): every
 * {@code [anf]} block parses through {@link AnfAdoc} with each identifier resolved, builds a
 * well-formed statement tree through {@link AnfProjection} and reads back unchanged, and each
 * fixture's complex concept builds from its own blocks. The fixtures' concepts are minted in an
 * ephemeral store under the SNOMED UUIDs of the SCTIDs the fixtures list, so the list is proven
 * to be exactly what the blocks use.
 */
class AnfFixtureTest {

    private static final List<String> FIXTURES = List.of("blood-pressure.adoc", "perrla.adoc");

    /** Fixed type-5 namespace for the concepts minted for candidate members, which have no identifier. */
    private static final UUID CANDIDATE_NAMESPACE = UUID.fromString("9a4e2c71-3d5b-5f8a-b6c1-7e0d2f4a8b93");

    private static final Pattern BLOCK_START = Pattern.compile("^\\[anf#([\\w-]+),type=\\w+]$");
    private static final Pattern SECTION_ID = Pattern.compile("^\\[#([\\w-]+)]$");
    private static final Pattern CONCEPT_ROW = Pattern.compile("^\\| (\\S+) \\| (.+)$");
    private static final Pattern GROUNDED = Pattern.compile("k:\\w*\\[([^\\]]*)] @id=(\\S+)");
    private static final Pattern XREF = Pattern.compile("<<([\\w-]+)>>");

    /** The fixtures' concepts, nid-keyed: the grounded value each resolves to. */
    private static final Map<Long, AnfValue.Grounded> GROUNDED_BY_NID = new ConcurrentHashMap<>();

    @BeforeAll
    static void startStore() {
        AnfTestStore.start();
        for (String fixture : FIXTURES) {
            load(fixture).concepts().forEach(AnfFixtureTest::mint);
        }
    }

    @AfterAll
    static void stopStore() {
        AnfTestStore.stop();
    }

    // ── The fixture documents ─────────────────────────────────────────────────

    /**
     * A fixture document.
     *
     * @param name       its file name
     * @param text       its text
     * @param blocks     each {@code [anf]} block's text by its id, in document order
     * @param sectionIds the ids of its sections
     * @param concepts   the concepts it lists a test knowledge base must carry, label by identifier
     */
    private record Fixture(String name, String text, Map<String, String> blocks, Set<String> sectionIds,
                           Map<String, String> concepts) {

        AnfStatement statement(String id) {
            String block = blocks.get(id);
            assertNotNull(block, name + " has no block " + id);
            return AnfAdoc.parse(block, AnfFixtureTest::resolve);
        }

        List<AnfStatement> statements(String idPrefix) {
            List<AnfStatement> statements = new ArrayList<>();
            blocks.keySet().stream().filter(id -> id.startsWith(idPrefix)).forEach(id -> statements.add(statement(id)));
            return statements;
        }
    }

    private static Fixture load(String name) {
        String text;
        try (InputStream in = AnfFixtureTest.class.getResourceAsStream("fixtures/" + name)) {
            assertNotNull(in, "fixture " + name);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> blocks = new LinkedHashMap<>();
        Set<String> sectionIds = new LinkedHashSet<>();
        Map<String, String> concepts = new LinkedHashMap<>();
        String blockId = null;
        StringBuilder block = new StringBuilder();
        int delimiters = 0;
        String section = null;
        for (String line : text.split("\n", -1)) {
            Matcher start = BLOCK_START.matcher(line);
            Matcher sectionId = SECTION_ID.matcher(line);
            if (start.matches()) {
                blockId = start.group(1);
                block.setLength(0);
                delimiters = 0;
            } else if (sectionId.matches()) {
                section = sectionId.group(1);
                sectionIds.add(section);
            }
            if (blockId != null) {
                block.append(line).append('\n');
                if (line.equals("--") && ++delimiters == 2) {
                    blocks.put(blockId, block.toString());
                    blockId = null;
                }
                continue;
            }
            Matcher row = CONCEPT_ROW.matcher(line);
            if ("concepts".equals(section) && row.matches() && !row.group(1).equals("Identifier")) {
                concepts.put(row.group(1), row.group(2).strip());
            }
        }
        return new Fixture(name, text, blocks, sectionIds, concepts);
    }

    // ── The ephemeral store's concepts ────────────────────────────────────────

    /** An SCTID's concept is the one the SNOMED type-5 UUID names; any other identifier is a public id. */
    private static PublicId publicIdOf(String identifier) {
        if (identifier.chars().allMatch(Character::isDigit)) {
            return PublicIds.of(UuidUtil.fromSNOMED(identifier));
        }
        return PublicIds.of(identifier.split(","));
    }

    private static void mint(String identifier, String label) {
        EntityProxy.Concept concept = EntityProxy.Concept.make(label, publicIdOf(identifier));
        GROUNDED_BY_NID.put(concept.nid(), new AnfValue.Grounded(concept.nid(),
                ComponentText.publicIdKey(PrimitiveData.publicId(concept.nid())), identifier, label));
    }

    private static Optional<AnfValue.Grounded> resolve(String identifier) {
        PublicId publicId = publicIdOf(identifier);
        if (!PrimitiveData.get().hasPublicId(publicId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(GROUNDED_BY_NID.get(PrimitiveData.nid(publicId)));
    }

    private static AnfProjection.Grounding grounding() {
        return concept -> GROUNDED_BY_NID.get(concept.nid());
    }

    /** A candidate member has no concept yet; the test mints one by its label to build the expansion. */
    private static ConceptFacade candidateConcept(AnfValue.Candidate candidate) {
        return EntityProxy.Concept.make(candidate.provisionalLabel(),
                UuidT5Generator.get(CANDIDATE_NAMESPACE, "fixture-candidate:" + candidate.provisionalLabel()));
    }

    private static ConceptFacade topicConcept(AnfStatement statement) {
        return switch (statement.topic()) {
            case AnfValue.Grounded grounded -> AnfProjection.concept(grounded);
            case AnfValue.Candidate candidate -> candidateConcept(candidate);
            case AnfValue.Clarify clarify -> throw new AssertionError("A topic to clarify: " + clarify.question());
        };
    }

    private static AnfStatement.Result result(AnfStatement statement) {
        return assertInstanceOf(Circumstance.Performance.class, statement.circumstance()).result();
    }

    /** Every value a statement holds, a result's measure semantic included. */
    private static List<AnfValue> values(AnfStatement statement) {
        List<AnfValue> values = new ArrayList<>(present(statement.topic(), statement.subjectOfInformation()));
        List<AnfStatement.Result> results = new ArrayList<>();
        switch (statement.circumstance()) {
            case Circumstance.Performance p -> {
                values.addAll(present(p.status(), p.healthRisk(), p.bodySite(), p.method(), p.laterality()));
                values.addAll(p.purpose());
                results.addAll(present(p.timing(), p.result(), p.normalRange()));
            }
            case Circumstance.Request r -> {
                values.addAll(present(r.priority(), r.method()));
                values.addAll(r.purpose());
                values.addAll(r.conditionalTrigger());
                results.addAll(present(r.timing(), r.requestedResult()));
                if (r.repetition() != null) {
                    Circumstance.Repetition rep = r.repetition();
                    results.addAll(present(rep.periodStart(), rep.periodDuration(), rep.eventSeparation(),
                            rep.eventDuration(), rep.eventFrequency()));
                }
            }
            case Circumstance.Narrative n -> {
                values.addAll(n.purpose());
                results.addAll(present(n.timing()));
            }
        }
        results.forEach(r -> values.add(r.measureSemantic()));
        return values;
    }

    @SafeVarargs
    private static <T> List<T> present(T... items) {
        return Arrays.stream(items).filter(Objects::nonNull).toList();
    }

    // ── Every block ───────────────────────────────────────────────────────────

    @Test
    void everyBlockParsesWithEveryIdentifierResolved() {
        for (String name : FIXTURES) {
            Fixture fixture = load(name);
            assertFalse(fixture.blocks().isEmpty(), name + " has blocks");
            for (String id : fixture.blocks().keySet()) {
                AnfStatement statement = fixture.statement(id);
                assertNotNull(statement, name + " " + id + " parses");
                assertNotNull(statement.topic(), name + " " + id + " has a topic");
                for (AnfValue value : values(statement)) {
                    assertFalse(value instanceof AnfValue.Clarify, name + " " + id + " resolves every value: " + value);
                }
            }
        }
    }

    @Test
    void everyStatementBuildsAWellFormedTreeAndReadsBackUnchanged() {
        Set<String> beyondTheRecord = Set.of("a subject of record", "a statement time");
        for (String name : FIXTURES) {
            Fixture fixture = load(name);
            for (String id : fixture.blocks().keySet()) {
                AnfStatement statement = fixture.statement(id);
                DiTreeEntity tree = AnfProjection.toTree(statement);
                assertEquals(List.of(), AnfExpressionBuilder.wellFormedness(tree), name + " " + id);
                List<String> missing = new AnfExpressionBuilder(tree).completeness();
                assertTrue(beyondTheRecord.containsAll(missing),
                        name + " " + id + " lacks only what the record does not carry: " + missing);
                assertEquals(statement, AnfProjection.fromTree(tree, grounding()), name + " " + id + " through the tree");
                assertEquals(statement, AnfAdoc.parse(AnfAdoc.toAdoc(statement), AnfFixtureTest::resolve),
                        name + " " + id + " through the codec");
            }
        }
    }

    @Test
    void theConceptListIsExactlyWhatTheBlocksUseUnderTheSameLabels() {
        for (String name : FIXTURES) {
            Fixture fixture = load(name);
            Map<String, String> used = new LinkedHashMap<>();
            for (String block : fixture.blocks().values()) {
                Matcher grounded = GROUNDED.matcher(block);
                while (grounded.find()) {
                    used.put(grounded.group(2), grounded.group(1));
                }
            }
            assertEquals(fixture.concepts().keySet(), used.keySet(), name + " lists every concept its blocks use, and no other");
            used.forEach((identifier, label) -> assertEquals(fixture.concepts().get(identifier), label,
                    name + " labels " + identifier + " alike in its blocks and its list"));
        }
    }

    @Test
    void presenceIsNeverOpenEnded() {
        for (String name : FIXTURES) {
            Fixture fixture = load(name);
            for (String id : fixture.blocks().keySet()) {
                for (AnfStatement.Result result : present(result(fixture, id))) {
                    boolean presence = result.lowerBound() != null && result.lowerBound() == 1.0
                            && result.upperBound() == null;
                    assertFalse(presence, name + " " + id + " writes presence as [1,1], never [1,inf)");
                }
            }
        }
    }

    private static AnfStatement.Result result(Fixture fixture, String id) {
        return fixture.statement(id).circumstance() instanceof Circumstance.Performance p ? p.result() : null;
    }

    @Test
    void everyCrossReferenceNamesABlockOrASection() {
        for (String name : FIXTURES) {
            Fixture fixture = load(name);
            Matcher xref = XREF.matcher(fixture.text());
            while (xref.find()) {
                String target = xref.group(1);
                assertTrue(fixture.blocks().containsKey(target) || fixture.sectionIds().contains(target),
                        name + " cross-references " + target);
            }
        }
    }

    // ── The complex concepts ──────────────────────────────────────────────────

    @Test
    void sittingSystolicBloodPressureIsAFocusedExpansionOfTheFixturesStatements() {
        Fixture fixture = load("blood-pressure.adoc");
        AnfStatement input = fixture.statement("ssbp-input");
        AnfStatement focus = fixture.statement("ssbp-focus");
        List<AnfStatement> prerequisites = fixture.statements("ssbp-prerequisite-");
        assertEquals(3, prerequisites.size());

        ComplexConceptBuilder builder = new ComplexConceptBuilder();
        Expansion.Focused focused = builder.focusedExpansion(topicConcept(focus));
        for (AnfStatement prerequisite : prerequisites) {
            builder.prerequisite(focused, AnfProjection.toTree(prerequisite), IkeConcept.PRECONDITION_ASSOCIATION);
        }
        assertEquals(List.of(), ComplexConceptBuilder.wellFormedness(builder.asDiTree()));
        assertEquals(List.of("a definition"), builder.completeness(), "the source gives no definition");
        assertEquals(3, ((Expansion.Focused) builder.build().root().expansion().orElseThrow()).prerequisites().size());

        assertEquals(result(input), result(focus), "the focus carries the provided value");
        AnfStatement systolic = fixture.statement("bp-3-systolic-bp");
        assertEquals(systolic.topic(), focus.topic(), "the lifted systolic statement is the focus's phenomenon");
        assertEquals(result(fixture.statement("bp-5-sitting-position")),
                result(fixture.statement("ssbp-prerequisite-seated-duration")),
                "the lifted sitting statement carries the seated duration prerequisite's value");
    }

    @Test
    void perrlaIsAUniformAndExpansionOfTheFixturesMembers() {
        Fixture fixture = load("perrla.adoc");
        AnfStatement input = fixture.statement("perrla-cc-input");
        List<AnfStatement> members = fixture.statements("perrla-cc-member-");
        assertEquals(4, members.size());

        AnfValue.Grounded presence = assertInstanceOf(AnfValue.Grounded.class, result(input).measureSemantic());
        ComplexConceptBuilder builder = new ComplexConceptBuilder();
        Expansion.Uniform uniform = builder.uniformExpansion(IkeConcept.AND_CONJUNCTION, AnfProjection.concept(presence));
        for (AnfStatement member : members) {
            builder.member(uniform, topicConcept(member));
            assertEquals(result(input), result(member), "an AND expansion distributes the provided value");
        }
        assertEquals(List.of(), ComplexConceptBuilder.wellFormedness(builder.asDiTree()));
        assertEquals(List.of("a definition"), builder.completeness(), "the source gives no definition");
        assertEquals(4, ((Expansion.Uniform) builder.build().root().expansion().orElseThrow()).members().size());
    }
}
