package network.ike.komet.claude.stamp;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * What the structured blocks of one document make known to each other, one context per surface.
 * The document knows no kind of block: it makes the context, hands it to every kind through
 * {@link StructuredBlockKind.Context}, and starts a fresh one when it draws the document again.
 * Each kind keeps its own state here under a {@link Key} it declares, so a decision tree can read
 * the model a graphlet block defined above it, and a block kind in another plugin can read the
 * state of the kinds it builds on, without the document naming either.
 */
public final class BlockContext {

    /**
     * The name of one kind of state in a context, with how a context makes it on first use.
     * Keys are compared by identity: a kind declares its key once, as a constant.
     *
     * @param <T> the state's type
     */
    public static final class Key<T> {

        private final String name;
        private final Function<BlockContext, T> initial;

        private Key(String name, Function<BlockContext, T> initial) {
            this.name = Objects.requireNonNull(name);
            this.initial = Objects.requireNonNull(initial);
        }

        /**
         * A key whose state a context makes from itself, so the state can reach the context it lives in.
         *
         * @param name    the key's name, for messages
         * @param initial makes the state the first time a context is asked for it
         * @param <T>     the state's type
         * @return the key
         */
        public static <T> Key<T> of(String name, Function<BlockContext, T> initial) {
            return new Key<>(name, initial);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final Map<Key<?>, Object> state = new HashMap<>();

    /**
     * The state of a key in this context, made on first use.
     *
     * @param key the key
     * @param <T> the state's type
     * @return the state
     */
    public <T> T get(Key<T> key) {
        Object present;
        synchronized (this) {
            present = state.get(key);
        }
        if (present != null) {
            @SuppressWarnings("unchecked")
            T known = (T) present;
            return known;
        }
        // Made outside the lock: a key's initial may itself read the context.
        T made = key.initial.apply(this);
        synchronized (this) {
            Object raced = state.putIfAbsent(key, made);
            @SuppressWarnings("unchecked")
            T kept = raced == null ? made : (T) raced;
            return kept;
        }
    }

    /**
     * Puts a key's state into this context when it has none, for state made before its context.
     *
     * @param key   the key
     * @param value the state
     * @param <T>   the state's type
     * @return the state the context keeps: {@code value}, or the state it already had
     */
    public synchronized <T> T putIfAbsent(Key<T> key, T value) {
        Object present = state.putIfAbsent(key, Objects.requireNonNull(value));
        @SuppressWarnings("unchecked")
        T kept = present == null ? value : (T) present;
        return kept;
    }
}
