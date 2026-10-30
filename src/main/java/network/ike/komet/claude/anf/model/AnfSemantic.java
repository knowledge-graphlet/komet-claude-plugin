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
package network.ike.komet.claude.anf.model;

import dev.ikm.tinkar.terms.ConceptFacade;
import network.ike.foundation.ike.bindings.IkeConcept;

import java.util.EnumMap;
import java.util.Map;

/**
 * Connects each vertex meaning of an ANF statement tree to the shape of the vertex that carries
 * it, mirroring {@code dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalAxiomSemantic} and the
 * complex clause plugin's {@code ClauseSemantic} (IKE-Network/ike-issues#1312). The meanings are
 * the starter set's: the ANF statement set of {@code ike-terms} declares the statement, its
 * context and every attribute, and the circumstance kinds are the ones its ELM node kind set
 * declares. Each constant's {@link #nid} resolves from its meaning at class load, so a Tinkar
 * store must be running before this enum is referenced, the contract the kernel's enum carries.
 *
 * <p>The enum also answers the one question structural editing asks, {@link #admits}: does this
 * vertex admit a child of that meaning, and how many. Every rule the
 * {@link AnfExpressionBuilder} enforces about where a vertex may sit reads from here, so the
 * editor's admission answers and the builder's refusals cannot disagree.
 */
public enum AnfSemantic {

    // ── The statement ─────────────────────────────────────────────────────────
    /** The root: the statement, carrying its kind as a property. */
    STATEMENT(IkeConcept.ANF_STATEMENT, Shape.ROOT),

    // ── The context and its envelope attributes ───────────────────────────────
    CONTEXT(IkeConcept.ANF_CONTEXT, Shape.CLAUSE),
    SUBJECT_OF_RECORD(IkeConcept.ANF_SUBJECT_OF_RECORD, Shape.PARTICIPANT),
    SUBJECT_OF_INFORMATION(IkeConcept.ANF_SUBJECT_OF_INFORMATION, Shape.EXPRESSION),
    TOPIC(IkeConcept.ANF_TOPIC, Shape.EXPRESSION),
    STATEMENT_TYPE(IkeConcept.ANF_STATEMENT_TYPE, Shape.CONCEPT_REFERENCE),
    AUTHOR(IkeConcept.ANF_AUTHOR, Shape.AUTHOR),
    STATEMENT_TIME(IkeConcept.ANF_STATEMENT_TIME, Shape.MEASURE),

    // ── The circumstance kinds, reused from the ELM node kind set ─────────────
    PERFORMANCE(IkeConcept.PERFORMANCE_CIRCUMSTANCE, Shape.CIRCUMSTANCE),
    REQUEST(IkeConcept.REQUEST_CIRCUMSTANCE, Shape.CIRCUMSTANCE),
    NARRATIVE(IkeConcept.NARRATIVE_CIRCUMSTANCE, Shape.CIRCUMSTANCE),

    // ── Attributes any circumstance carries ───────────────────────────────────
    TIMING(IkeConcept.ANF_TIMING, Shape.MEASURE),
    PURPOSE(IkeConcept.ANF_PURPOSE, Shape.EXPRESSION),

    // ── Performance attributes ────────────────────────────────────────────────
    RESULT(IkeConcept.ANF_RESULT, Shape.MEASURE),
    DISPOSITION(IkeConcept.ANF_DISPOSITION, Shape.CONCEPT_REFERENCE),
    HEALTH_RISK(IkeConcept.ANF_HEALTH_RISK, Shape.CONCEPT_REFERENCE),
    NORMAL_RANGE(IkeConcept.ANF_NORMAL_RANGE, Shape.MEASURE),
    METHOD(IkeConcept.ANF_METHOD, Shape.CONCEPT_REFERENCE),
    DEVICE(IkeConcept.ANF_DEVICE, Shape.CONCEPT_REFERENCE),
    BODY_SITE(IkeConcept.ANF_BODY_SITE, Shape.CONCEPT_REFERENCE),
    LATERALITY(IkeConcept.ANF_LATERALITY, Shape.CONCEPT_REFERENCE),
    APPROACH(IkeConcept.ANF_APPROACH, Shape.CONCEPT_REFERENCE),
    SPECIMEN_TYPE(IkeConcept.ANF_SPECIMEN_TYPE, Shape.CONCEPT_REFERENCE),
    SPECIMEN_SOURCE_SITE(IkeConcept.ANF_SPECIMEN_SOURCE_SITE, Shape.CONCEPT_REFERENCE),
    PARTICIPANT(IkeConcept.ANF_PARTICIPANT, Shape.PARTICIPANT),

    // ── Request attributes ────────────────────────────────────────────────────
    PRIORITY(IkeConcept.ANF_PRIORITY, Shape.CONCEPT_REFERENCE),
    REQUESTED_RESULT(IkeConcept.ANF_REQUESTED_RESULT, Shape.MEASURE),
    REPETITION(IkeConcept.ANF_REPETITION, Shape.CLAUSE),
    PERIOD_START(IkeConcept.ANF_PERIOD_START, Shape.MEASURE),
    PERIOD_DURATION(IkeConcept.ANF_PERIOD_DURATION, Shape.MEASURE),
    EVENT_SEPARATION(IkeConcept.ANF_EVENT_SEPARATION, Shape.MEASURE),
    EVENT_DURATION(IkeConcept.ANF_EVENT_DURATION, Shape.MEASURE),
    EVENT_FREQUENCY(IkeConcept.ANF_EVENT_FREQUENCY, Shape.MEASURE),
    CONDITIONAL_TRIGGER(IkeConcept.ANF_CONDITIONAL_TRIGGER, Shape.CONCEPT_REFERENCE),
    LOCATION(IkeConcept.ANF_LOCATION, Shape.CONCEPT_REFERENCE),

    // ── Narrative ─────────────────────────────────────────────────────────────
    NARRATIVE_TEXT(IkeConcept.ANF_NARRATIVE_TEXT, Shape.TEXT),

    // ── Associated statements ─────────────────────────────────────────────────
    ASSOCIATED_STATEMENT(IkeConcept.ANF_ASSOCIATED_STATEMENT, Shape.ASSOCIATION);

    /**
     * The shape of the vertex a meaning is carried by: which properties it holds and which
     * {@link AnfAdaptor} adapts it.
     */
    public enum Shape {
        /** The statement root, with its kind as a property. */
        ROOT,
        /** A clause whose children are attributes: the context, a repetition. */
        CLAUSE,
        /** A circumstance, one of three kinds, whose children are that kind's attributes. */
        CIRCUMSTANCE,
        /** A concept reference chip. */
        CONCEPT_REFERENCE,
        /** A measure: bounds, open flags, resolution and unit as properties. */
        MEASURE,
        /** A nested logical expression held by value as a tree property. */
        EXPRESSION,
        /** A reference to a participant with its role as a concept property. */
        PARTICIPANT,
        /** A reference to a participant with its role as a nested expression. */
        AUTHOR,
        /** A text literal. */
        TEXT,
        /** A reference to another statement with the association type as a property. */
        ASSOCIATION
    }

    /** How many children of one meaning a vertex admits. */
    public enum Cardinality {
        /** None: the child meaning may not sit under this vertex. */
        NONE,
        /** At most one. */
        ONE,
        /** Any number. */
        MANY
    }

    /** The meaning concept stored as the vertex's {@code meaningNid}. */
    public final ConceptFacade meaning;
    /** The meaning concept's nid, resolved at class load. */
    public final long nid;
    /** The shape of the vertex that carries this meaning. */
    public final Shape shape;

    AnfSemantic(ConceptFacade meaning, Shape shape) {
        this.meaning = meaning;
        this.nid = meaning.nid();
        this.shape = shape;
    }

    /**
     * Resolves the meaning for a vertex meaning nid.
     *
     * @param meaningNid the vertex's {@code meaningNid}
     * @return the matching meaning
     * @throws IllegalStateException if the nid is not an ANF statement meaning: an unknown
     *                               meaning is refused, never mapped to a fallback
     */
    public static AnfSemantic get(long meaningNid) {
        for (AnfSemantic semantic : values()) {
            if (semantic.nid == meaningNid) {
                return semantic;
            }
        }
        throw new IllegalStateException("Not a vertex meaning of an ANF statement: " + meaningDescription(meaningNid));
    }

    /**
     * Resolves the meaning for a meaning concept.
     *
     * @param meaning the meaning concept
     * @return the matching meaning
     */
    public static AnfSemantic get(ConceptFacade meaning) {
        return get(meaning.nid());
    }

    /**
     * Whether a nid is an ANF statement meaning.
     *
     * @param meaningNid a vertex meaning nid
     * @return true when {@link #get(long)} answers for it
     */
    public static boolean covers(long meaningNid) {
        for (AnfSemantic semantic : values()) {
            if (semantic.nid == meaningNid) {
                return true;
            }
        }
        return false;
    }

    /**
     * The circumstance kind a statement kind concept names.
     *
     * @param kind a circumstance kind concept: Performance, Request or Narrative circumstance
     * @return the matching circumstance meaning
     * @throws AnfRefusal if the concept is not a circumstance kind
     */
    public static AnfSemantic circumstanceKind(ConceptFacade kind) {
        if (kind == null) {
            throw new AnfRefusal("A statement kind is required: Performance, Request or Narrative circumstance");
        }
        for (AnfSemantic semantic : values()) {
            if (semantic.shape == Shape.CIRCUMSTANCE && semantic.nid == kind.nid()) {
                return semantic;
            }
        }
        throw new AnfRefusal("Not a circumstance kind: " + kind.description()
                + "; a statement kind is Performance, Request or Narrative circumstance");
    }

    /** @return true for the three circumstance kinds. */
    public boolean isCircumstance() {
        return shape == Shape.CIRCUMSTANCE;
    }

    /**
     * How many children of a meaning this vertex admits: the admission table of the ANF
     * statement tree, one exhaustive switch over the parent meaning. A leaf admits nothing.
     *
     * @param child the child meaning
     * @return the cardinality, {@link Cardinality#NONE} when the child may not sit here
     */
    public Cardinality admits(AnfSemantic child) {
        Map<AnfSemantic, Cardinality> admitted = ADMISSION.get(this);
        Cardinality cardinality = admitted == null ? null : admitted.get(child);
        return cardinality == null ? Cardinality.NONE : cardinality;
    }

    private static final Map<AnfSemantic, Map<AnfSemantic, Cardinality>> ADMISSION = admissionTable();

    private static Map<AnfSemantic, Map<AnfSemantic, Cardinality>> admissionTable() {
        Map<AnfSemantic, Map<AnfSemantic, Cardinality>> table = new EnumMap<>(AnfSemantic.class);
        for (AnfSemantic parent : values()) {
            table.put(parent, admitted(parent));
        }
        return table;
    }

    /** The children a meaning admits: exhaustive over the parent, no default branch. */
    private static Map<AnfSemantic, Cardinality> admitted(AnfSemantic parent) {
        Map<AnfSemantic, Cardinality> admitted = new EnumMap<>(AnfSemantic.class);
        switch (parent) {
            case STATEMENT -> {
                admitted.put(CONTEXT, Cardinality.ONE);
                // Exactly one circumstance across the three kinds: the builder holds that line.
                admitted.put(PERFORMANCE, Cardinality.ONE);
                admitted.put(REQUEST, Cardinality.ONE);
                admitted.put(NARRATIVE, Cardinality.ONE);
                admitted.put(ASSOCIATED_STATEMENT, Cardinality.MANY);
            }
            case CONTEXT -> {
                admitted.put(SUBJECT_OF_RECORD, Cardinality.ONE);
                admitted.put(SUBJECT_OF_INFORMATION, Cardinality.ONE);
                admitted.put(TOPIC, Cardinality.ONE);
                admitted.put(STATEMENT_TYPE, Cardinality.ONE);
                admitted.put(AUTHOR, Cardinality.MANY);
                admitted.put(STATEMENT_TIME, Cardinality.ONE);
            }
            case PERFORMANCE -> {
                admitted.put(TIMING, Cardinality.ONE);
                admitted.put(PURPOSE, Cardinality.MANY);
                admitted.put(RESULT, Cardinality.ONE);
                admitted.put(DISPOSITION, Cardinality.ONE);
                admitted.put(HEALTH_RISK, Cardinality.ONE);
                admitted.put(NORMAL_RANGE, Cardinality.ONE);
                admitted.put(METHOD, Cardinality.ONE);
                admitted.put(DEVICE, Cardinality.ONE);
                admitted.put(BODY_SITE, Cardinality.ONE);
                admitted.put(LATERALITY, Cardinality.ONE);
                admitted.put(APPROACH, Cardinality.ONE);
                admitted.put(SPECIMEN_TYPE, Cardinality.ONE);
                admitted.put(SPECIMEN_SOURCE_SITE, Cardinality.ONE);
                admitted.put(PARTICIPANT, Cardinality.MANY);
            }
            case REQUEST -> {
                admitted.put(TIMING, Cardinality.ONE);
                admitted.put(PURPOSE, Cardinality.MANY);
                admitted.put(PRIORITY, Cardinality.ONE);
                admitted.put(REQUESTED_RESULT, Cardinality.ONE);
                admitted.put(DISPOSITION, Cardinality.ONE);
                admitted.put(REPETITION, Cardinality.ONE);
                admitted.put(CONDITIONAL_TRIGGER, Cardinality.MANY);
                admitted.put(PARTICIPANT, Cardinality.MANY);
                admitted.put(DEVICE, Cardinality.ONE);
                admitted.put(METHOD, Cardinality.ONE);
                admitted.put(LOCATION, Cardinality.ONE);
                admitted.put(SPECIMEN_TYPE, Cardinality.ONE);
                admitted.put(SPECIMEN_SOURCE_SITE, Cardinality.ONE);
            }
            case NARRATIVE -> {
                admitted.put(TIMING, Cardinality.ONE);
                admitted.put(PURPOSE, Cardinality.MANY);
                admitted.put(NARRATIVE_TEXT, Cardinality.ONE);
            }
            case REPETITION -> {
                admitted.put(PERIOD_START, Cardinality.ONE);
                admitted.put(PERIOD_DURATION, Cardinality.ONE);
                admitted.put(EVENT_SEPARATION, Cardinality.ONE);
                admitted.put(EVENT_DURATION, Cardinality.ONE);
                admitted.put(EVENT_FREQUENCY, Cardinality.ONE);
            }
            case SUBJECT_OF_RECORD, SUBJECT_OF_INFORMATION, TOPIC, STATEMENT_TYPE, AUTHOR, STATEMENT_TIME,
                 TIMING, PURPOSE, RESULT, DISPOSITION, HEALTH_RISK, NORMAL_RANGE, METHOD, DEVICE, BODY_SITE,
                 LATERALITY, APPROACH, SPECIMEN_TYPE, SPECIMEN_SOURCE_SITE, PARTICIPANT, PRIORITY,
                 REQUESTED_RESULT, PERIOD_START, PERIOD_DURATION, EVENT_SEPARATION, EVENT_DURATION,
                 EVENT_FREQUENCY, CONDITIONAL_TRIGGER, LOCATION, NARRATIVE_TEXT, ASSOCIATED_STATEMENT -> {
                // leaves
            }
        }
        return admitted;
    }

    /** The meaning's name for a message, by its concept's description, never its nid. */
    public String describe() {
        return meaning.description();
    }

    private static String meaningDescription(long meaningNid) {
        for (IkeConcept concept : IkeConcept.values()) {
            if (concept.nid() == meaningNid) {
                return concept.description();
            }
        }
        return "a concept outside the IKE starter set";
    }
}
