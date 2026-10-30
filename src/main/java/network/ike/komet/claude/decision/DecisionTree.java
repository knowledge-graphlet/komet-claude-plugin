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
package network.ike.komet.claude.decision;

import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfiledTree;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfiledTreeFactory;
import dev.ikm.tinkar.entity.graph.adaptor.profile.VertexKinds;
import dev.ikm.tinkar.terms.EntityFacade;
import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionElement.Branch;
import network.ike.komet.claude.decision.DecisionElement.Chance;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.Decision;
import network.ike.komet.claude.decision.DecisionElement.Event;
import network.ike.komet.claude.decision.DecisionElement.HealthCost;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Node;
import network.ike.komet.claude.decision.DecisionElement.Outcome;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import network.ike.komet.claude.decision.DecisionElement.Probability;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.LongLists;
import org.eclipse.collections.api.factory.primitive.LongObjectMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.list.primitive.ImmutableLongList;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * The decision-tree profile over a stored tree ({@code dev-decision-tree-blocks}): the typed view
 * a {@code Decision tree pattern} semantic's one {@code DITREE} field is read through, the way
 * {@code LogicalExpression} reads a stated definition. The root is a {@link Decision}; branches
 * are vertices; every number is a measure vertex under the node that carries it, its bounds and
 * semantic under the kernel's interval-role keys. {@link #check()} says whether the tree is well
 * formed under the profile, and {@link Rollback} evaluates it under a {@link ValuationCoordinate}.
 *
 * <p>{@link #builder(DecisionTerms)} writes such a tree, for the block parser and for tests; the
 * stored tree is the truth, and this class only reads it.
 */
public final class DecisionTree extends ProfiledTree<DecisionElement> {

    private final DecisionTerms terms;

    /**
     * Reads a stored tree through the profile.
     *
     * @param source the stored tree
     * @param terms  the profile's concepts as nids
     */
    public DecisionTree(DiTreeEntity source, DecisionTerms terms) {
        super(source, kinds(terms));
        this.terms = terms;
    }

    /** The factory that attaches this profile to a tree's cached adaptor hook. */
    public static ProfiledTreeFactory<DecisionTree> factory(DecisionTerms terms) {
        return new ProfiledTreeFactory<>(tree -> new DecisionTree(tree, terms));
    }

    private static VertexKinds<DecisionElement> kinds(DecisionTerms terms) {
        return VertexKinds.<DecisionElement>builder()
                .kind(terms.decisionNode(), (tree, index) -> new Decision((DecisionTree) tree, index))
                .kind(terms.alternativeNode(), (tree, index) -> new Alternative((DecisionTree) tree, index))
                .kind(terms.chanceNode(), (tree, index) -> new Chance((DecisionTree) tree, index))
                .kind(terms.eventNode(), (tree, index) -> new Event((DecisionTree) tree, index))
                .kind(terms.outcomeNode(), (tree, index) -> new Outcome((DecisionTree) tree, index))
                .kind(terms.probabilityMeasure(), (tree, index) -> new Probability((DecisionTree) tree, index))
                .kind(terms.moneyCostMeasure(), (tree, index) -> new MoneyCost((DecisionTree) tree, index))
                .kind(terms.healthCostMeasure(), (tree, index) -> new HealthCost((DecisionTree) tree, index))
                .kind(terms.parameterMeasure(), (tree, index) -> new Parameter((DecisionTree) tree, index))
                .build();
    }

    public DecisionTerms terms() {
        return terms;
    }

    /** The decision at the root, when the root is one. */
    public Optional<Decision> decision() {
        return root().filter(Decision.class::isInstance).map(Decision.class::cast);
    }

    /** The measure a measure vertex carries, read from the kernel's interval-role keys. */
    public Measure measure(int vertexIndex) {
        OptionalDouble lower = numberProperty(vertexIndex, terms.intervalLowerBound());
        OptionalDouble upper = numberProperty(vertexIndex, terms.intervalUpperBound());
        double lo = lower.orElse(upper.orElse(Double.NaN));
        double hi = upper.orElse(lower.orElse(Double.NaN));
        Bounds bounds = new Bounds(lo, hi,
                flagProperty(vertexIndex, terms.lowerBoundOpen(), false),
                flagProperty(vertexIndex, terms.upperBoundOpen(), false));
        long semantic = conceptProperty(vertexIndex, terms.unitOfMeasure()).orElse(0L);
        return new Measure(bounds, semantic,
                conceptProperty(vertexIndex, terms.measureBasis()),
                conceptProperty(vertexIndex, terms.derivedFrom()),
                componentsProperty(vertexIndex, terms.operands()));
    }

    /** A property that names components: one nid, or several. */
    public ImmutableLongList componentsProperty(int vertexIndex, long propertyKeyNid) {
        Object value = vertex(vertexIndex).propertyFast(propertyKeyNid);
        MutableLongList result = LongLists.mutable.empty();
        switch (value) {
            case null -> { }
            case Long nid -> result.add(nid);
            case Integer nid -> result.add(nid);
            case EntityFacade facade -> result.add(facade.nid());
            case long[] nids -> result.addAll(nids);
            case ImmutableLongList nids -> result.addAll(nids);
            case Iterable<?> items -> items.forEach(item -> {
                switch (item) {
                    case Long nid -> result.add(nid);
                    case Integer nid -> result.add(nid);
                    case EntityFacade facade -> result.add(facade.nid());
                    default -> throw new IllegalStateException("vertex " + vertexIndex + " names a non-component: " + item);
                }
            });
            default -> throw new IllegalStateException("vertex " + vertexIndex + " property " + propertyKeyNid
                    + " names no component: " + value);
        }
        return result.toImmutable();
    }

    // ── Well-formedness ──────────────────────────────────────────────────────

    @Override
    public ImmutableList<ProfileFinding> check() {
        MutableList<ProfileFinding> findings = Lists.mutable.empty();
        checkProfiled(findings);
        Optional<DecisionElement> root = root();
        if (root.isEmpty() || !(root.get() instanceof Decision)) {
            findings.add(ProfileFinding.ofTree("the root is not a decision"));
        }
        for (DecisionElement element : nodes()) {
            switch (element) {
                case Decision decision -> {
                    if (decision.alternatives().isEmpty()) {
                        findings.add(new ProfileFinding(decision.vertexIndex(), "a decision has no alternative"));
                    }
                    if (decision.vertexIndex() == rootIndex()) {
                        childrenOnly(findings, decision.vertexIndex(), "a decision", Alternative.class, Parameter.class);
                    } else {
                        childrenOnly(findings, decision.vertexIndex(), "a decision", Alternative.class);
                    }
                }
                case Branch branch -> {
                    ImmutableList<Node> structural = children(branch.vertexIndex(), Node.class);
                    if (structural.size() > 1) {
                        findings.add(new ProfileFinding(branch.vertexIndex(),
                                "a branch leads to more than one node"));
                    }
                    structural.forEach(next -> {
                        if (next instanceof Branch) {
                            findings.add(new ProfileFinding(next.vertexIndex(),
                                    "a branch follows a branch directly"));
                        }
                    });
                    if (branch instanceof Event event) {
                        checkEvent(findings, event);
                    } else {
                        childrenOnly(findings, branch.vertexIndex(), "an alternative", Node.class, Cost.class);
                    }
                }
                case Chance chance -> checkChance(findings, chance);
                case Outcome outcome -> {
                    childrenOnly(findings, outcome.vertexIndex(), "an outcome", Cost.class);
                    checkCosts(findings, outcome.costs());
                }
                case Cost cost -> checkMeasure(findings, cost.vertexIndex(), cost.measure());
                case Parameter parameter -> {
                    checkMeasure(findings, parameter.vertexIndex(), parameter.measure());
                    if (parameter.quantity().isEmpty()) {
                        findings.add(new ProfileFinding(parameter.vertexIndex(), "a parameter names no quantity"));
                    }
                    if (parentIndex(parameter.vertexIndex()).orElse(-1) != rootIndex()) {
                        findings.add(new ProfileFinding(parameter.vertexIndex(), "a parameter sits under the root decision"));
                    }
                }
                case Probability probability -> {
                    checkMeasure(findings, probability.vertexIndex(), probability.measure());
                    Measure measure = probability.measure();
                    if (measure.semanticNid() != terms.shareAtNode()) {
                        findings.add(new ProfileFinding(probability.vertexIndex(),
                                "a probability is not on the Share at node semantic"));
                    } else if (measure.stated() && (measure.bounds().lower() < -Bounds.EPSILON || measure.bounds().upper() > 1 + Bounds.EPSILON)) {
                        findings.add(new ProfileFinding(probability.vertexIndex(),
                                "a probability lies outside zero and one: " + measure.bounds()));
                    }
                }
            }
        }
        return findings.toImmutable();
    }

    private void checkEvent(MutableList<ProfileFinding> findings, Event event) {
        childrenOnly(findings, event.vertexIndex(), "an event", Node.class, Cost.class, Probability.class);
        if (children(event.vertexIndex(), Probability.class).size() > 1) {
            findings.add(new ProfileFinding(event.vertexIndex(), "an event carries more than one probability"));
        }
        if (event.residual() && event.probability().isPresent()) {
            findings.add(new ProfileFinding(event.vertexIndex(),
                    "a residual event carries a probability, which it derives instead"));
        }
        if (!event.residual() && event.probability().isEmpty()) {
            findings.add(new ProfileFinding(event.vertexIndex(), "an event carries no probability and is not residual"));
        }
        checkCosts(findings, event.costs());
    }

    private void checkChance(MutableList<ProfileFinding> findings, Chance chance) {
        childrenOnly(findings, chance.vertexIndex(), "a chance", Event.class);
        ImmutableList<Event> events = chance.events();
        if (events.isEmpty()) {
            findings.add(new ProfileFinding(chance.vertexIndex(), "a chance has no event"));
            return;
        }
        long residuals = events.count(Event::residual);
        if (residuals > 1) {
            findings.add(new ProfileFinding(chance.vertexIndex(), "a chance has more than one residual event"));
        }
        if (residuals == 0) {
            double lowerSum = 0;
            double upperSum = 0;
            for (Event event : events) {
                if (event.probability().isEmpty()) {
                    return;
                }
                Bounds bounds = event.probability().get().measure().bounds();
                lowerSum += bounds.lower();
                upperSum += bounds.upper();
            }
            if (lowerSum > 1 + Bounds.EPSILON || upperSum < 1 - Bounds.EPSILON) {
                findings.add(new ProfileFinding(chance.vertexIndex(),
                        "the events do not close: their probabilities sum to [" + lowerSum + ", " + upperSum
                                + "], and no event is residual"));
            }
        }
    }

    private void checkCosts(MutableList<ProfileFinding> findings, ImmutableList<Cost> costs) {
        for (Cost cost : costs) {
            Measure measure = cost.measure();
            if (measure.semanticNid() == terms.shareAtNode()) {
                findings.add(new ProfileFinding(cost.vertexIndex(), "a cost is on the Share at node semantic"));
            }
        }
    }

    private void checkMeasure(MutableList<ProfileFinding> findings, int vertexIndex, Measure measure) {
        if (!measure.stated() && !measure.derived()) {
            findings.add(new ProfileFinding(vertexIndex, "a measure has no bounds and no derivation"));
        }
        if (measure.derived()) {
            long from = measure.derivedFrom().getAsLong();
            if (terms.isDerivationKind(from)) {
                if (measure.operands().isEmpty()) {
                    findings.add(new ProfileFinding(vertexIndex, "a derived measure names no operands"));
                }
            } else if (!measure.operands().isEmpty()) {
                findings.add(new ProfileFinding(vertexIndex, "a derivation names no kind the evaluator can run"));
            } else if (!hasParameterFor(from)) {
                findings.add(new ProfileFinding(vertexIndex, "a measure reads a quantity the tree has no parameter for"));
            }
        }
        if (measure.semanticNid() == 0) {
            findings.add(new ProfileFinding(vertexIndex, "a measure has no measure semantic"));
        }
        if (measure.basisNid().isPresent() && measure.basisNid().getAsLong() == terms.derivedBasis()
                && measure.derivedFrom().isEmpty()) {
            findings.add(new ProfileFinding(vertexIndex, "a derived measure names nothing it is derived from"));
        }
    }

    /** Whether the tree states or derives a parameter for a quantity. */
    private boolean hasParameterFor(long quantity) {
        return nodesOfType(Parameter.class).anySatisfy(parameter ->
                parameter.quantity().isPresent() && parameter.quantity().getAsLong() == quantity);
    }

    @SafeVarargs
    private void childrenOnly(MutableList<ProfileFinding> findings, int vertexIndex, String what,
                              Class<? extends DecisionElement>... allowed) {
        for (DecisionElement child : children(vertexIndex)) {
            boolean ok = false;
            for (Class<? extends DecisionElement> kind : allowed) {
                if (kind.isInstance(child)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                findings.add(new ProfileFinding(child.vertexIndex(), what + " does not carry a "
                        + child.getClass().getSimpleName().toLowerCase()));
            }
        }
    }

    // ── Writing a tree ───────────────────────────────────────────────────────

    /** A builder that writes a decision tree as the profile reads it. */
    public static Builder builder(DecisionTerms terms) {
        return new Builder(terms);
    }

    /**
     * Writes a decision tree vertex by vertex. Each node method adds a child of the given kind and
     * returns its handle; the measure methods add a measure to the node that carries it and
     * return the node, so costs chain, while {@link Handle#measure} returns the measure's own
     * handle for a basis or a derivation. A vertex keeps the identity it is given, so a block
     * parsed and written again holds the same vertices. {@link #build()} makes the stored tree,
     * and {@link #tree()} reads it back through the profile.
     */
    public static final class Builder {
        private final DecisionTerms terms;
        private final DiTreeEntity.Builder tree = DiTreeEntity.builder();
        private final MutableList<Handle> handles = Lists.mutable.empty();

        private Builder(DecisionTerms terms) {
            this.terms = terms;
        }

        /** The root decision, with a fresh vertex identity. */
        public Handle decision(long conceptNid) {
            return decision(conceptNid, UUID.randomUUID());
        }

        /** The root decision, with the vertex identity given. */
        public Handle decision(long conceptNid, UUID vertexId) {
            Handle root = new Handle(terms.decisionNode(), conceptNid, vertexId);
            tree.setRoot(root.vertex);
            return root;
        }

        /** The stored tree, with every property committed to its vertex. */
        public DiTreeEntity build() {
            handles.forEach(Handle::commit);
            return tree.build();
        }

        /** The stored tree read through the profile. */
        public DecisionTree tree() {
            return new DecisionTree(build(), terms);
        }

        /** One vertex under construction. */
        public final class Handle {
            private final EntityVertex vertex;
            private final MutableLongObjectMap<Object> properties = LongObjectMaps.mutable.empty();
            private final MutableLongList statements = LongLists.mutable.empty();

            private Handle(long meaningNid, long conceptNid, UUID vertexId) {
                this.vertex = EntityVertex.make(vertexId, meaningNid);
                if (conceptNid != 0) {
                    properties.put(terms.conceptReference(), conceptNid);
                }
                handles.add(this);
            }

            private Handle child(long meaningNid, long conceptNid, UUID vertexId) {
                Handle child = new Handle(meaningNid, conceptNid, vertexId);
                tree.addEdge(child.vertex, vertex);
                return child;
            }

            private void commit() {
                if (statements.size() == 1) {
                    properties.put(terms.statement(), statements.get(0));
                } else if (statements.size() > 1) {
                    properties.put(terms.statement(), statements.toArray());
                }
                vertex.setProperties(properties);
            }

            public int vertexIndex() {
                return vertex.vertexIndex();
            }

            public UUID vertexId() {
                return new UUID(vertex.mostSignificantBits(), vertex.leastSignificantBits());
            }

            public Handle alternative(long conceptNid) {
                return alternative(conceptNid, UUID.randomUUID());
            }

            public Handle alternative(long conceptNid, UUID vertexId) {
                return child(terms.alternativeNode(), conceptNid, vertexId);
            }

            public Handle chance(long conceptNid) {
                return chance(conceptNid, UUID.randomUUID());
            }

            public Handle chance(long conceptNid, UUID vertexId) {
                return child(terms.chanceNode(), conceptNid, vertexId);
            }

            public Handle event(long conceptNid) {
                return event(conceptNid, UUID.randomUUID());
            }

            public Handle event(long conceptNid, UUID vertexId) {
                return child(terms.eventNode(), conceptNid, vertexId);
            }

            /** An event that is the remainder of its chance's partition. */
            public Handle residualEvent(long conceptNid) {
                return residualEvent(conceptNid, UUID.randomUUID());
            }

            public Handle residualEvent(long conceptNid, UUID vertexId) {
                Handle event = child(terms.eventNode(), conceptNid, vertexId);
                event.properties.put(terms.residual(), Boolean.TRUE);
                return event;
            }

            public Handle decision(long conceptNid) {
                return decision(conceptNid, UUID.randomUUID());
            }

            public Handle decision(long conceptNid, UUID vertexId) {
                return child(terms.decisionNode(), conceptNid, vertexId);
            }

            public Handle outcome(long conceptNid) {
                return outcome(conceptNid, UUID.randomUUID());
            }

            public Handle outcome(long conceptNid, UUID vertexId) {
                return child(terms.outcomeNode(), conceptNid, vertexId);
            }

            /** A probability on the Share at node semantic; returns this node. */
            public Handle probability(Bounds bounds) {
                measure(terms.probabilityMeasure(), bounds, terms.shareAtNode(), UUID.randomUUID());
                return this;
            }

            /** A money cost; returns this node. */
            public Handle money(Bounds bounds, long moneySemanticNid) {
                measure(terms.moneyCostMeasure(), bounds, moneySemanticNid, UUID.randomUUID());
                return this;
            }

            /** A health cost; returns this node. */
            public Handle health(Bounds bounds, long healthSemanticNid) {
                measure(terms.healthCostMeasure(), bounds, healthSemanticNid, UUID.randomUUID());
                return this;
            }

            /** A measure vertex of a kind under this node, returning the measure's own handle. */
            public Handle measure(long measureKindNid, Bounds bounds, long semanticNid, UUID vertexId) {
                Handle measure = child(measureKindNid, 0, vertexId);
                measure.properties.put(terms.intervalLowerBound(), bounds.lower());
                measure.properties.put(terms.intervalUpperBound(), bounds.upper());
                if (bounds.lowerOpen()) {
                    measure.properties.put(terms.lowerBoundOpen(), Boolean.TRUE);
                }
                if (bounds.upperOpen()) {
                    measure.properties.put(terms.upperBoundOpen(), Boolean.TRUE);
                }
                measure.properties.put(terms.unitOfMeasure(), semanticNid);
                return measure;
            }

            /** A parameter of the method under this root: a measure named by its quantity. */
            public Handle parameter(long quantityNid, Bounds bounds, long semanticNid, UUID vertexId) {
                Handle parameter = measure(terms.parameterMeasure(), bounds, semanticNid, vertexId);
                parameter.properties.put(terms.conceptReference(), quantityNid);
                return parameter;
            }

            /** A derived parameter: its quantity, the kind that computes it and the quantities it reads. */
            public Handle derivedParameter(long quantityNid, long kindNid, long[] operandQuantities, long semanticNid,
                                           UUID vertexId) {
                Handle parameter = derived(terms.parameterMeasure(), kindNid, operandQuantities, semanticNid, vertexId);
                parameter.properties.put(terms.conceptReference(), quantityNid);
                return parameter;
            }

            /** A derived measure of a kind under this node: no bounds of its own, a kind and its operands. */
            public Handle derived(long measureKindNid, long kindNid, long[] operandQuantities, long semanticNid,
                                  UUID vertexId) {
                Handle measure = child(measureKindNid, 0, vertexId);
                measure.properties.put(terms.unitOfMeasure(), semanticNid);
                measure.properties.put(terms.derivedFrom(), kindNid);
                measure.properties.put(terms.operands(), operandQuantities.clone());
                measure.properties.put(terms.measureBasis(), terms.derivedBasis());
                return measure;
            }

            /** The basis kind a measure's value rests on. */
            public Handle basis(long basisKindNid) {
                properties.put(terms.measureBasis(), basisKindNid);
                return this;
            }

            /** The source a cited measure's value is taken from. */
            public Handle basisSource(long sourceNid) {
                properties.put(terms.basisSource(), sourceNid);
                return this;
            }

            /** The definition a derived measure's value is computed by. */
            public Handle derivedFrom(long definitionNid) {
                properties.put(terms.derivedFrom(), definitionNid);
                return this;
            }

            /** An ANF statement the node is or holds; several accumulate. */
            public Handle statement(long statementNid) {
                statements.add(statementNid);
                return this;
            }

            public Handle inputKind(long inputKindNid) {
                properties.put(terms.inputKind(), inputKindNid);
                return this;
            }

            public Handle treeReference(long decisionConceptNid) {
                properties.put(terms.treeReference(), decisionConceptNid);
                return this;
            }
        }
    }
}
