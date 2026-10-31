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
package network.ike.komet.claude.calculator;

import java.util.Optional;
import org.eclipse.collections.api.factory.primitive.LongObjectMaps;
import org.eclipse.collections.api.map.primitive.ImmutableLongObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;

/**
 * The bindings of one use of a calculator, by input quantity: at most one {@link Binding.Observed}
 * or inline {@link Binding.Supplied} per input, and beside it at most one assumption, which an
 * evaluation prefers when present and reports as such.
 */
public final class Bindings {

    private final ImmutableLongObjectMap<Binding> bound;
    private final ImmutableLongObjectMap<Binding.Supplied> assumptions;

    private Bindings(ImmutableLongObjectMap<Binding> bound, ImmutableLongObjectMap<Binding.Supplied> assumptions) {
        this.bound = bound;
        this.assumptions = assumptions;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Bindings none() {
        return new Builder().build();
    }

    /** The binding the block states for an input, before any assumption; empty when none is stated. */
    public Optional<Binding> stated(long inputQuantity) {
        return Optional.ofNullable(bound.get(inputQuantity));
    }

    /** The assumption the block states for an input, when it states one. */
    public Optional<Binding.Supplied> assumption(long inputQuantity) {
        return Optional.ofNullable(assumptions.get(inputQuantity));
    }

    /** Whether any input carries an assumption. */
    public boolean anyAssumption() {
        return assumptions.notEmpty();
    }

    /** These bindings with an assumption set, replaced or added, for one input. */
    public Bindings withAssumption(long inputQuantity, Binding.Supplied assumption) {
        MutableLongObjectMap<Binding.Supplied> copy = LongObjectMaps.mutable.ofAll(assumptions);
        copy.put(inputQuantity, assumption);
        return new Bindings(bound, copy.toImmutable());
    }

    /** These bindings with an input's assumption removed. */
    public Bindings withoutAssumption(long inputQuantity) {
        MutableLongObjectMap<Binding.Supplied> copy = LongObjectMaps.mutable.ofAll(assumptions);
        copy.remove(inputQuantity);
        return new Bindings(bound, copy.toImmutable());
    }

    public static final class Builder {
        private final MutableLongObjectMap<Binding> bound = LongObjectMaps.mutable.empty();
        private final MutableLongObjectMap<Binding.Supplied> assumptions = LongObjectMaps.mutable.empty();

        private Builder() {
        }

        public Builder bind(long inputQuantity, Binding binding) {
            if (binding instanceof Binding.Supplied supplied && supplied.assumption()) {
                assumptions.put(inputQuantity, supplied);
            } else {
                bound.put(inputQuantity, binding);
            }
            return this;
        }

        public Builder assume(long inputQuantity, Binding.Supplied assumption) {
            assumptions.put(inputQuantity, assumption);
            return this;
        }

        public Bindings build() {
            return new Bindings(bound.toImmutable(), assumptions.toImmutable());
        }
    }
}
