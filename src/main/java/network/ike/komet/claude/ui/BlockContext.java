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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.calculator.Calculator;
import network.ike.komet.claude.decision.ExternalQuantities;
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
public final class BlockContext {

        private final Map<Long, Calculator> calculators = new HashMap<>();
    private final Map<String, AnfStatement> statements = new HashMap<>();
    private final Map<Long, ExternalQuantities.Value> values = new HashMap<>();
    private final List<WeakReference<Runnable>> listeners = new ArrayList<>();
    private Shared identities;

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
    public void defineStatement(String name, AnfStatement statement) {
        if (name == null || name.isBlank() || statement == null) {
            return;
        }
        statements.put(name, statement);
        notifyListeners();
    }

    /** The statement an anf block of this document named. */
    public Optional<AnfStatement> statement(String name) {
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
