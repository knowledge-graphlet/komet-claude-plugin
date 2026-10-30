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
package network.ike.komet.claude.complexconcept.model;

import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.terms.ConceptFacade;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.EnumMap;
import java.util.Map;

/**
 * The vertex meanings of a complex concept specification's tree, each keyed to its starter-set
 * concept and to the shape of the vertex that carries it (IKE-Network/ike-issues#1312;
 * ike-lab-documents topics {@code anf-complex-concept-architecture} and
 * {@code dev-ditree-editor-skin-family}). The analogue of {@code AnfSemantic} for the ANF
 * statement's adaptor layer and of {@code LogicalAxiomSemantic} for EL++.
 *
 * <p>The enum also answers the one question structural editing asks, {@link #admits}: does this
 * vertex take a child of that meaning, and how many. The root takes a definition and one
 * expansion, uniform or focused; a uniform expansion takes members; a focused expansion takes
 * prerequisites; everything else is a leaf.
 */
public enum ComplexConceptSemantic {

    /** The root: the specification, whose children are the definition and the expansion. */
    SPECIFICATION(IkeConcept.COMPLEX_CONCEPT_SPECIFICATION, Shape.ROOT),
    /** The description logic definition, an EL++ expression held by value. */
    DEFINITION(IkeConcept.COMPLEX_CONCEPT_DEFINITION, Shape.EXPRESSION),
    /** A uniform expansion: conjunction, measurement domain, unsafe distribution policy, members. */
    UNIFORM_EXPANSION(IkeConcept.UNIFORM_EXPANSION, Shape.EXPANSION),
    /** A focused expansion: the focus phenomenon and the prerequisites. */
    FOCUSED_EXPANSION(IkeConcept.FOCUSED_EXPANSION, Shape.EXPANSION),
    /** A member of a uniform expansion: a reference to its phenomenon. */
    MEMBER(IkeConcept.EXPANSION_MEMBER, Shape.CONCEPT_REFERENCE),
    /** A prerequisite of a focused expansion: an ANF statement held by value, and its association. */
    PREREQUISITE(IkeConcept.EXPANSION_PREREQUISITE, Shape.STATEMENT);

    /** The shape of the vertex a meaning is carried by: which properties it holds. */
    public enum Shape {
        /** The specification root. */
        ROOT,
        /** A nested EL++ expression held by value as a tree property. */
        EXPRESSION,
        /** An expansion, a clause with properties of its own whose children are its constituents. */
        EXPANSION,
        /** A concept reference. */
        CONCEPT_REFERENCE,
        /** A nested ANF statement held by value as a tree property, with an association. */
        STATEMENT
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

    ComplexConceptSemantic(ConceptFacade meaning, Shape shape) {
        this.meaning = meaning;
        this.nid = meaning.nid();
        this.shape = shape;
    }

    /**
     * Resolves the meaning for a vertex meaning nid.
     *
     * @param meaningNid the vertex's {@code meaningNid}
     * @return the matching meaning
     * @throws IllegalStateException if the nid is not a complex concept specification meaning: an
     *                               unknown meaning is refused, never mapped to a fallback
     */
    public static ComplexConceptSemantic get(long meaningNid) {
        for (ComplexConceptSemantic semantic : values()) {
            if (semantic.nid == meaningNid) {
                return semantic;
            }
        }
        throw new IllegalStateException("Not a vertex meaning of a complex concept specification: "
                + ComponentText.name(Calculators.View.Default(), meaningNid));
    }

    /**
     * @param meaningNid a vertex's {@code meaningNid}
     * @return whether it is a complex concept specification meaning
     */
    public static boolean covers(long meaningNid) {
        for (ComplexConceptSemantic semantic : values()) {
            if (semantic.nid == meaningNid) {
                return true;
            }
        }
        return false;
    }

    /** @return whether this meaning is an expansion, uniform or focused */
    public boolean isExpansion() {
        return shape == Shape.EXPANSION;
    }

    /**
     * How many children of a meaning this vertex admits: the admission table of the complex
     * concept specification tree, one exhaustive switch over the parent meaning.
     *
     * @param child the child's meaning
     * @return the cardinality, {@link Cardinality#NONE} when the child may not sit here
     */
    public Cardinality admits(ComplexConceptSemantic child) {
        Cardinality cardinality = ADMISSION.get(this).get(child);
        return cardinality == null ? Cardinality.NONE : cardinality;
    }

    private static final Map<ComplexConceptSemantic, Map<ComplexConceptSemantic, Cardinality>> ADMISSION = admissionTable();

    private static Map<ComplexConceptSemantic, Map<ComplexConceptSemantic, Cardinality>> admissionTable() {
        Map<ComplexConceptSemantic, Map<ComplexConceptSemantic, Cardinality>> table = new EnumMap<>(ComplexConceptSemantic.class);
        for (ComplexConceptSemantic parent : values()) {
            table.put(parent, admitted(parent));
        }
        return table;
    }

    /** The children a meaning admits: exhaustive over the parent, no default branch. */
    private static Map<ComplexConceptSemantic, Cardinality> admitted(ComplexConceptSemantic parent) {
        Map<ComplexConceptSemantic, Cardinality> admitted = new EnumMap<>(ComplexConceptSemantic.class);
        switch (parent) {
            case SPECIFICATION -> {
                admitted.put(DEFINITION, Cardinality.ONE);
                // Exactly one expansion across the two kinds: the builder and wellFormedness hold that line.
                admitted.put(UNIFORM_EXPANSION, Cardinality.ONE);
                admitted.put(FOCUSED_EXPANSION, Cardinality.ONE);
            }
            case UNIFORM_EXPANSION -> admitted.put(MEMBER, Cardinality.MANY);
            case FOCUSED_EXPANSION -> admitted.put(PREREQUISITE, Cardinality.MANY);
            case DEFINITION, MEMBER, PREREQUISITE -> {
                // leaves
            }
        }
        return admitted;
    }

    /** @return the meaning's description, for refusals and rows */
    public String describe() {
        return meaning.description();
    }
}
