package graph;

import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import java.util.HashMap;

/**
 * Mutable key/value container shared by the nodes of a {@link Graph} during a
 * run.
 *
 * <p>
 * Nodes can read values from it and either return a map of updates (merged with
 * {@link #put(Map)} by the graph) or modify it directly.
 * </p>
 *
 * <p>
 * This class is <b>not thread-safe</b>. Use one instance per run. Values are
 * stored as they are, with no defensive copies: prefer immutable values
 * (records, {@code String}, {@code List.copyOf(...)}, {@code Map.copyOf(...)})
 * to avoid surprises when a state is copied.
 * </p>
 */
public class State {

    private Map<String, Object> stateMap = new HashMap<>();

    /**
     * Checks whether a key is present, even if mapped to {@code null}.
     *
     * @param key the key to look for
     * @return {@code true} if the key is present
     */
    public boolean containsKey(Object key) {
        return stateMap.containsKey(key);
    }

    /**
     * Returns the value for a key, or a default if the key is absent.
     *
     * @param key          the key
     * @param defaultValue the value returned when the key is not present
     * @return the stored value, or {@code defaultValue}
     */
    public Object getOrDefault(Object obj1, Object obj2) {
        return stateMap.getOrDefault(obj1, obj2);
    }

    /**
     * Associates a value with a key.
     *
     * @param key   the key
     * @param value the value, may be {@code null}
     * @return the previous value for the key, or {@code null} if there was none
     */
    public Object put(String key, Object value) {
        return stateMap.put(key, value);
    }

    /**
     * Copies all the entries of the given map into this state, overwriting
     * existing keys. This is how the graph merges the updates returned by a node.
     *
     * @param map the entries to add
     * @throws NullPointerException if {@code map} is {@code null}
     */
    public void put(Map<String, Object> map) {
        Objects.requireNonNull(map, "map can't be null");
        stateMap.putAll(map);
    }

    /**
     * Returns the value for a key.
     *
     * @param key the key
     * @return the stored value, or {@code null} if the key is absent or mapped to
     *         {@code null}
     */
    public Object get(String key) {
        return stateMap.get(key);
    }

    /**
     * Returns the value for a key, cast to the requested type.
     *
     * @param <T>  the expected type
     * @param key  the key
     * @param type the expected class
     * @return the value cast to {@code type}, or {@code null} if the key is absent
     *         or mapped to {@code null}
     * @throws ClassCastException if the value is not an instance of {@code type};
     *                            the message reports the key, the actual type and
     *                            the expected type
     */

    public <T> T getWithCast(String key, Class<T> type) {
        Object result = stateMap.get(key);

        try {
            return result == null ? null : type.cast(result);
        } catch (ClassCastException e) {
            ClassCastException e1 = new ClassCastException("Cast error: the value with key \"" + key
                    + "\" is of type \"" + result.getClass().getName() + "\", expected \"" + type.getName()
                    + "\".");
            e1.initCause(e);
            throw e1;
        }
    }

    /**
     * Removes a key.
     *
     * @param key the key to remove
     * @return the removed value, or {@code null} if the key was not present
     */
    public Object remove(String key) {
        return stateMap.remove(key);
    }

    /**
     * Creates a <b>shallow</b> copy of this state: the keys are copied, the values
     * are shared. Adding or removing keys in the copy does not affect the
     * original, but mutating a shared value (for example adding an element to a
     * list) is visible from both. To isolate mutable values, use
     * {@link #copy(UnaryOperator)}.
     *
     * @return a new state with the same entries
     */
    public State copy() {
        return copy(UnaryOperator.identity());
    }

    /**
     * Creates a copy of this state, passing every non-{@code null} value through
     * {@code valueCopier}. This allows callers that need real isolation to
     * duplicate mutable values, for example:
     *
     * <pre>{@code
     * state.copy(v -> v instanceof List<?> l ? new ArrayList<>(l) : v);
     * }</pre>
     *
     * @param valueCopier function that returns the value to store in the copy; it
     *                    is never called with {@code null}
     * @return a new state with the copied values
     * @throws NullPointerException if {@code valueCopier} is {@code null}
     */
    public State copy(UnaryOperator<Object> valueCopier) {
        State s = new State();
        stateMap.forEach((k, v) -> s.stateMap.put(k, v == null ? null : valueCopier.apply(v)));
        return s;
    }

}
