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
package network.ike.komet.claude.ui;

import java.lang.ref.WeakReference;
import network.ike.komet.document.BlockContext;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.anf.element.ElementLevel;
import network.ike.komet.claude.calculator.Calculator;
import network.ike.komet.claude.complexconcept.ComplexConceptText;
import network.ike.komet.claude.decision.ExternalQuantities;
import network.ike.komet.claude.decision.GraphletModel;
import network.ike.komet.claude.decision.StatementMeasures;
import network.ike.komet.claude.decision.ViewIdentities;

/**
 * What the blocks of one document know of each other ({@code dev-calculator-blocks}): the
 * calculators its {@code [calculator]} blocks define, by the calculator's concept, and the
 * quantities its {@code [calculate]} blocks have computed, by the calculator's concept and its
 * output's topic, which a decision tree reads as a parameter through {@link #externals()}. A block
 * that publishes a value notifies the blocks that asked to be told, so a tree rendered above the
 * calculator it reads redraws when the calculator lands. One context per surface; the references
 * point from a reader to a quantity, never from a calculator to its readers.
 */
public final class ProfileBlocks {

    /** Where a document's {@link BlockContext} keeps the profile blocks' state. */
    private static final BlockContext.Key<ProfileBlocks> KEY = BlockContext.Key.of("profile-blocks", ProfileBlocks::new);

    private final BlockContext shared;

    /** The profile blocks' state in a fresh context of its own, for a surface or test that makes one. */
    public ProfileBlocks() {
        this.shared = new BlockContext();
        shared.putIfAbsent(KEY, this);
    }

    private ProfileBlocks(BlockContext shared) {
        this.shared = shared;
    }

    /**
     * The profile blocks' state in a document's context, made on first use.
     *
     * @param shared the document's context
     * @return the state
     */
    public static ProfileBlocks of(BlockContext shared) {
        return shared.get(KEY);
    }

    /** @return the document's context this state lives in, to hand to a block kind */
    public BlockContext shared() {
        return shared;
    }


        private final Map<Long, Calculator> calculators = new HashMap<>();
    private final Map<String, AnfStatement> statements = new HashMap<>();
    private final Map<Long, ComplexConceptText.Spec> complexConcepts = new HashMap<>();
    private final Map<Long, Supplier<Node>> complexConceptCards = new HashMap<>();
    /** By name: a fresh editable card over the statement an anf block of this document names, for a peek from a tree. */
    private final Map<String, Supplier<Node>> statementCards = new HashMap<>();
    private final Map<Long, ExternalQuantities.Value> values = new HashMap<>();
    private final List<WeakReference<Runnable>> listeners = new ArrayList<>();
    private Shared identities;
    private final ElementSpans elementSpans = new ElementSpans();
    private final List<WeakReference<Consumer<ElementLevel>>> levelListeners = new ArrayList<>();
    private ElementLevel level = ElementLevel.ANF;

    /** Where the document's clinical elements rest in its narrative, read before each turn renders. */
    public ElementSpans elementSpans() {
        return elementSpans;
    }

    /** How much of the two-step lift the document shows: one level for the whole surface. */
    public ElementLevel elementLevel() {
        return level;
    }

    /** Sets the level every element block of the document shows, and tells the blocks that listen. */
    public void setElementLevel(ElementLevel newLevel) {
        if (newLevel == null || newLevel == level) {
            return;
        }
        level = newLevel;
        List<Consumer<ElementLevel>> live = new ArrayList<>();
        Iterator<WeakReference<Consumer<ElementLevel>>> it = levelListeners.iterator();
        while (it.hasNext()) {
            Consumer<ElementLevel> listener = it.next().get();
            if (listener == null) {
                it.remove();
            } else {
                live.add(listener);
            }
        }
        live.forEach(listener -> listener.accept(newLevel));
    }

    /** Asks to be told when the level changes; held weakly, so the listener lives as long as its block holds it. */
    public void listenLevel(Consumer<ElementLevel> listener) {
        levelListeners.add(new WeakReference<>(listener));
    }

    /**
     * The identities every block of the document resolves through: the view's, and beneath them
     * one numbering of the symbols the store lacks, shared across blocks so a calculator, a
     * calculation and a tree that name the same symbol mean the same local concept, and
     * synchronized so a sweep on another thread may read through them.
     */
    public synchronized Identities identities(ViewCalculator view) {
        if (identities == null) {
            identities = new Shared(new BlockIdentities(new ViewIdentities(view)));
        }
        return identities;
    }

    /** A thread-safe view of one set of block identities. */
    private static final class Shared implements Identities {
        private final BlockIdentities inner;

        Shared(BlockIdentities inner) {
            this.inner = inner;
        }

        @Override
        public synchronized OptionalLong nid(String identifier) {
            return inner.nid(identifier);
        }

        @Override
        public synchronized Optional<String> identifier(long nid) {
            return inner.identifier(nid);
        }

        @Override
        public synchronized String label(long nid) {
            return inner.label(nid);
        }

        @Override
        public synchronized OptionalLong local(String identifier, String label) {
            return inner.local(identifier, label);
        }

        @Override
        public synchronized boolean isLocal(long nid) {
            return inner.isLocal(nid);
        }
    }

    /** A calculator block has rendered: the method is known by its concept. */
    public void defineCalculator(long conceptNid, Calculator calculator) {
        if (conceptNid == 0 || calculator == null) {
            return;
        }
        calculators.put(conceptNid, calculator);
        notifyListeners();
    }

        /** An anf block has rendered under a name: the statement is readable by it. */
    public synchronized void defineStatement(String name, AnfStatement statement) {
        if (name == null || name.isBlank() || statement == null) {
            return;
        }
        if (!statement.equals(statements.put(name, statement))) {
            notifyListeners();
        }
    }

    /**
     * A complex-concept block has rendered: the concept, local to the document or stored, is known
     * by its spec, and by the card that shows and edits it, which a peek from another block raises.
     */
    public synchronized void defineComplexConcept(long conceptNid, ComplexConceptText.Spec spec, Supplier<Node> card) {
        complexConcepts.put(conceptNid, spec);
        if (card != null) {
            complexConceptCards.put(conceptNid, card);
        }
        notifyListeners();
    }

    /** A fresh card over the complex concept a block of this document specifies under a nid, when one does. */
    public synchronized Optional<Supplier<Node>> complexConceptCard(long conceptNid) {
        return Optional.ofNullable(complexConceptCards.get(conceptNid));
    }

    /** The complex concept a block of this document specifies under a nid, when one does. */
    public synchronized Optional<ComplexConceptText.Spec> complexConcept(long conceptNid) {
        return Optional.ofNullable(complexConcepts.get(conceptNid));
    }

    /** By root identifier: the body of each decision-tree block, which an outcome of another tree may fold in. */
    private final Map<String, String> trees = new java.util.LinkedHashMap<>();
    /** The document's value trees and effects tables, by identifier. */
    private final Map<String, EvidenceBlock> evidenceBlocks = new java.util.TreeMap<>();
    private int evidenceYear = Integer.MAX_VALUE;
    /** By root identifier: the node that draws the tree, which a link from another tree scrolls to. */
    private final Map<String, Node> treeNodes = new HashMap<>();
    /** By root identifier: the trees that fold that tree in at an outcome. */
    private final Map<String, java.util.Set<String>> foldedBy = new HashMap<>();
    /** By root identifier: the order of the block that holds it, the latest to define it. */
    private final Map<String, Long> treeOrders = new HashMap<>();

    /**
     * A decision-tree block has read: its body is known by its root's identifier, so an outcome
     * of another tree may fold it in, {@code tree=…}, and its node is where a link to it goes.
     * Listeners hear only when the body changed.
     *
     * <p>A conversation restates a tree under the same identifier in every correction, so the
     * block made latest, by {@code order}, holds it; an earlier block that rebuilds does not take
     * it back. Two blocks taking it in turn would each tell the other the body changed, without end.
     */
    public synchronized void defineTree(String identifier, String body, Node node, long order) {
        if (identifier == null || identifier.isBlank() || body == null) {
            return;
        }
        Long holder = treeOrders.get(identifier);
        if (holder != null && holder > order) {
            return;
        }
        treeOrders.put(identifier, order);
        if (node != null) {
            treeNodes.put(identifier, node);
        }
        if (!body.equals(trees.put(identifier, body))) {
            notifyListeners();
        }
    }

    /**
     * A value tree or effects table's text, by the identifier its block gives itself, for the
     * blocks that name it. Listeners hear only when the text changed.
     */
    public synchronized void defineEvidenceBlock(String identifier, String info, String body) {
        EvidenceBlock block = new EvidenceBlock(info, body);
        if (!block.equals(evidenceBlocks.put(identifier, block))) {
            notifyListeners();
        }
    }

    /** The year a document's evidence is read as of, by default the present. */
    public synchronized int evidenceYear() {
        return evidenceYear;
    }

    /** Reads the document's evidence as of a year; listeners hear when it moved. */
    public synchronized void setEvidenceYear(int year) {
        if (year != evidenceYear) {
            evidenceYear = year;
            notifyListeners();
        }
    }

    /** What the evidence blocks and statements of the document last said, and the year they are read as of. */
    public synchronized String evidenceState() {
        return evidenceYear + evidenceBlocks.toString() + new java.util.TreeMap<>(statements);
    }

    /** The text of the value tree or effects table with an identifier, when its block has been read. */
    public synchronized Optional<EvidenceBlock> evidenceBlock(String identifier) {
        return Optional.ofNullable(evidenceBlocks.get(identifier));
    }

    /**
     * A benefit-risk block as written.
     *
     * @param info its info string
     * @param body its content
     */
    public record EvidenceBlock(String info, String body) {
    }

    /** Every tree block of the document, by its root's identifier. */
    public synchronized Map<String, String> trees() {
        return Map.copyOf(trees);
    }

    /** By id: the document's graphlet models, which its decision-tree and tornado blocks render. */
    private final Map<String, GraphletModel> models = new java.util.LinkedHashMap<>();
    /** By model id: the order of the block that holds the model, the latest to define it. */
    private final Map<String, Long> modelOrders = new HashMap<>();

    /**
     * A graphlet-model block has read: the model is known by its id, so the renderings that name
     * it draw it, and write a value into it through its writer. Listeners hear only when the
     * model's text changed. As with trees, the latest block to restate a model under an id holds
     * it.
     */
    public synchronized void defineModel(GraphletModel model, long order) {
        if (model == null || model.id() == null || model.id().isBlank()) {
            return;
        }
        Long holder = modelOrders.get(model.id());
        if (holder != null && holder > order) {
            return;
        }
        modelOrders.put(model.id(), order);
        GraphletModel was = models.put(model.id(), model);
        if (was == null || !was.body().equals(model.body()) || !was.info().equals(model.info())) {
            notifyListeners();
        }
    }

    /** The model a rendering names, when a block of this document defines it. */
    public synchronized Optional<GraphletModel> model(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(models.get(id));
    }

    /** Every model of the document, by id. */
    public synchronized Map<String, GraphletModel> models() {
        return Map.copyOf(models);
    }

    /**
     * The measures the document's statements state, as a model's parameters read them by name:
     * the range a statement's result gives, its scale read on the tree's terms, and its name.
     *
     * @param ids   the identities the model is read under, which give a candidate scale its identity
     * @param terms the profile's terms, for the share at a node
     */
    public StatementMeasures statementMeasures(Identities ids, network.ike.komet.claude.decision.DecisionTerms terms) {
        return name -> statement(name).flatMap(statement -> DocumentStatementMeasures.of(statement, ids, terms));
    }

    /** The node that draws the tree whose root has an identifier, when a block draws it. */
    public synchronized Optional<Node> treeNode(String identifier) {
        return Optional.ofNullable(treeNodes.get(identifier));
    }

    /** A tree folds another in at an outcome: the other may link back. Listeners hear only the first time. */
    public synchronized void noteFoldIn(String folding, String folded) {
        if (folding == null || folded == null) {
            return;
        }
        if (foldedBy.computeIfAbsent(folded, k -> new java.util.LinkedHashSet<>()).add(folding)) {
            notifyListeners();
        }
    }

    /** The trees that fold in the tree whose root has an identifier, by their roots' identifiers. */
    public synchronized java.util.Set<String> foldedBy(String identifier) {
        return java.util.Set.copyOf(foldedBy.getOrDefault(identifier, java.util.Set.of()));
    }

    /** An anf block named its statement: a peek from another block raises the card this supplies, editable. */
    public synchronized void defineStatementCard(String name, Supplier<Node> card) {
        if (name != null && !name.isBlank() && card != null) {
            statementCards.put(name, card);
        }
    }

    /** A fresh card over the statement an anf block of this document named, when one did. */
    public synchronized Optional<Supplier<Node>> statementCard(String name) {
        return Optional.ofNullable(statementCards.get(name));
    }

    /** The statement an anf block of this document named. */
    public synchronized Optional<AnfStatement> statement(String name) {
        return Optional.ofNullable(statements.get(name));
    }

    /** The calculator a concept names, when a block of this document defines it. */
    public Optional<Calculator> calculator(long conceptNid) {
        return Optional.ofNullable(calculators.get(conceptNid));
    }

    /**
     * A calculate block has evaluated: the result is readable under each of the quantities given,
     * the calculator's concept and its output's topic. Publishing the same value again is silent.
     */
    public void publish(long[] quantityNids, ExternalQuantities.Value value) {
        boolean changed = false;
        for (long quantity : quantityNids) {
            if (quantity == 0) {
                continue;
            }
            ExternalQuantities.Value was = value == null ? values.remove(quantity) : values.put(quantity, value);
            changed |= !Objects.equals(was, value);
        }
        if (changed) {
            notifyListeners();
        }
    }

    /** Withdraws a result, when its block is no longer determinable. */
    public void withdraw(long[] quantityNids) {
        publish(quantityNids, null);
    }

    /** The quantities other blocks have computed, as a tree reads them. */
    public ExternalQuantities externals() {
        return quantityNid -> Optional.ofNullable(values.get(quantityNid));
    }

    /** Asks to be told when a calculator is defined or a value lands; held weakly, so a dropped block is forgotten. */
    public void listen(Runnable listener) {
        listeners.add(new WeakReference<>(listener));
    }

    private void notifyListeners() {
        List<Runnable> live = new ArrayList<>();
        Iterator<WeakReference<Runnable>> it = listeners.iterator();
        while (it.hasNext()) {
            Runnable listener = it.next().get();
            if (listener == null) {
                it.remove();
            } else {
                live.add(listener);
            }
        }
        live.forEach(Runnable::run);
    }
}
