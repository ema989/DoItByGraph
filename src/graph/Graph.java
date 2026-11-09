package graph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * A minimal, LangGraph-like state graph.
 *
 * <p>
 * A graph is made of <em>nodes</em> and <em>edges</em>. Each node is a function
 * that receives the current {@link State} and returns a map of updates (a
 * "delta") that is merged into the state. Edges define which node runs next:
 * </p>
 * <ul>
 * <li>a <b>simple edge</b> always leads to the same node;</li>
 * <li>a <b>conditional edge</b> calls a function that inspects the state and
 * returns the name of the next node, chosen among a set of declared
 * destinations.</li>
 * </ul>
 *
 * <p>
 * Execution starts from {@link #START_NODE} and ends when {@link #END_NODE} is
 * reached. Both are reserved names and are always present.
 * </p>
 *
 * <h2>Building a graph</h2>
 * 
 * <pre>{@code
 * Graph g = Graph.builder()
 *         .addNode("first", state -> Map.of("x", 1))
 *         .addNode("second", state -> Map.of())
 *         .addEdge(Graph.START_NODE, "first")
 *         .addEdge("first", "second")
 *         .addConditionalEdge("second",
 *                 state -> state.get("x") != null ? Graph.END_NODE : "first",
 *                 Set.of("first", Graph.END_NODE))
 *         .build();
 * State result = g.run(new State());
 * }</pre>
 *
 * <p>
 * {@link GraphBuilder#build()} validates the structure: {@code START_NODE} must
 * have an outgoing edge, every node must have an outgoing edge, every node must
 * be reachable from {@code START_NODE}, and every node must have at least one
 * path leading to {@code END_NODE}.
 * </p>
 *
 * <h2>Loops</h2>
 * <p>
 * Cycles are allowed, including going back to {@code START_NODE} through a
 * conditional edge, as long as at least one path leaves the cycle. The
 * validation is static: a conditional edge counts as able to reach every node
 * in its destination set. It cannot detect a condition function that, at
 * runtime, never chooses the exit. That case is stopped by the recursion limit
 * (see {@link #run(State, int)}). Avoiding endless or abusive loops is
 * responsibility of the graph author.
 * </p>
 *
 * <h2>State handling</h2>
 * <p>
 * A node can update the state in two ways: by returning a map of updates, or by
 * mutating the {@link State} it receives. Both are allowed. If both are used on
 * the same key, the returned map wins, because it is applied after the node
 * finishes.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * A built {@code Graph} is immutable and can be shared between threads.
 * {@link State} is <b>not</b> thread-safe, so each run should use its own
 * state. Note that {@link State#copy()} is shallow: mutable values are shared
 * between copies.
 * </p>
 */
public final class Graph {

    /** Reserved name of the entry node. Every graph starts from it. */
    public static final String START_NODE = "START_NODE";
    /** Reserved name of the exit node. Reaching it ends the execution. */
    public static final String END_NODE = "END_NODE";
    private static final int DEFAULT_RECURSION_LIMIT = 100;

    private final Map<String, Function<State, Map<String, Object>>> nodeMap;
    private final Map<String, String> edgeMap;
    private final Map<String, Function<State, String>> condEdgeMap;
    private final Map<String, Set<String>> pathMap;

    private Graph(
            Map<String, Function<State, Map<String, Object>>> nodeMap,
            Map<String, String> edgeMap,
            Map<String, Function<State, String>> condEdgeMap,
            Map<String, Set<String>> pathMap) {
        this.nodeMap = nodeMap;
        this.edgeMap = edgeMap;
        this.condEdgeMap = condEdgeMap;
        this.pathMap = pathMap;
    }

    /**
     * Runs the graph with the default recursion limit (100).
     *
     * @param initialState the starting state; if {@code null}, an empty state is
     *                     used. The given instance is modified in place.
     * @return the final state (the same instance as {@code initialState}, when
     *         not {@code null})
     * @throws NodeExecutionException if a node or a conditional edge fails, or
     *                                if the recursion limit is exceeded
     * @see #run(State, int)
     */
    public State run(State initialState) {
        return run(initialState, DEFAULT_RECURSION_LIMIT);
    }

    /**
     * Runs the graph synchronously, starting from {@link #START_NODE} until
     * {@link #END_NODE} is reached.
     *
     * <p>
     * After each node, the map it returns (if not {@code null}) is merged into
     * the state, then the next node is resolved. The state passed in is
     * <b>modified in place</b>; use {@link State#copy()} first if the original
     * must be preserved.
     * </p>
     *
     * <p>
     * {@code recursionLimit} is the maximum number of node executions allowed in
     * a single run. It protects against loops whose exit condition is never
     * met.
     * </p>
     *
     * @param initialState   the starting state; if {@code null}, an empty state is
     *                       used
     * @param recursionLimit maximum number of node executions, must be greater
     *                       than zero
     * @return the final state
     * @throws IllegalArgumentException if {@code recursionLimit} is zero or
     *                                  negative
     * @throws NodeExecutionException   if a node or a conditional edge throws
     *                                  (the original exception is the cause), if
     *                                  a conditional edge returns {@code null} or
     *                                  a node not declared in its destination
     *                                  set, or if the recursion limit is exceeded
     */
    public State run(State initialState, int recursionLimit) {
        State state;
        String currNode;
        int loops = 0;

        if (recursionLimit <= 0)
            throw new IllegalArgumentException("recursionLimit can't be zero or negative.");

        state = initialState != null ? initialState : new State();
        currNode = resolveNext(START_NODE, state);
        while (!currNode.equals(END_NODE)) {
            Map<String, Object> out;

            if (++loops > recursionLimit)
                throw new NodeExecutionException("Max recursions exceeded.");

            out = execute(currNode, state);
            if (out != null)
                state.put(out);
            currNode = resolveNext(currNode, state);
        }
        return state;
    }

    /**
     * Runs a node, wrapping any runtime exception together with the node name.
     */
    /**
     * Runs a node, wrapping any runtime exception together with the node name.
     */
    private Map<String, Object> execute(String nodeName, State state) {
        try {
            return nodeMap.get(nodeName).apply(state);
        } catch (RuntimeException e) {
            throw new NodeExecutionException("Node \"" + nodeName + "\" threw an exception: " + e.getMessage(), e);
        }
    }

    /**
     * Determines the node to run after {@code currNode}, following its simple
     * edge or evaluating its conditional edge.
     */
    private String resolveNext(String currNode, State state) {
        String nextNode = edgeMap.get(currNode);
        Function<State, String> cond;

        if (nextNode != null)
            return nextNode;

        cond = condEdgeMap.get(currNode);
        if (cond == null)
            throw new NodeExecutionException(
                    "Graph inconsistency error: node \"" + currNode + "\" has no outgoing edges.");

        try {
            nextNode = cond.apply(state);
        } catch (RuntimeException e) {
            throw new NodeExecutionException(
                    "Conditional edge from node \"" + currNode + "\" threw an exception: " + e.getMessage(), e);
        }

        if (nextNode == null)
            throw new NodeExecutionException(
                    "Conditional edge from node \"" + currNode + "\" returned null.");
        if (!nodeMap.containsKey(nextNode))
            throw new NodeExecutionException("toNode from conditional edge not declared: \"" + nextNode + "\".");
        if (!pathMap.get(currNode).contains(nextNode))
            throw new NodeExecutionException(
                    "The destination node \"" + nextNode
                            + "\" is not present in the `toNode` list declared in the conditional edge.");

        return nextNode;
    }

    /**
     * Runs the graph asynchronously on the common
     * {@link java.util.concurrent.ForkJoinPool}.
     *
     * <p>
     * Unlike {@link #run(State, int)}, the given state is <b>copied</b> (with
     * {@link State#copy()}) at call time, so the caller's instance is not
     * modified and later changes made by the caller do not affect the run. The
     * copy is shallow: mutable values are still shared.
     * </p>
     *
     * @param initialState   the starting state; if {@code null}, an empty state is
     *                       used
     * @param recursionLimit maximum number of node executions, must be greater
     *                       than zero
     * @return a future completed with the final state. Failures (including an
     *         invalid {@code recursionLimit}) complete the future exceptionally
     *         with a {@link java.util.concurrent.CompletionException} wrapping
     *         the cause.
     */
    public CompletableFuture<State> runAsync(State initialState, int recursionLimit) {
        State copy = initialState != null ? initialState.copy() : new State();
        return CompletableFuture.supplyAsync(() -> run(copy, recursionLimit));
    }

    /**
     * Runs the graph asynchronously on the given executor.
     *
     * @param initialState   the starting state; if {@code null}, an empty state is
     *                       used
     * @param recursionLimit maximum number of node executions, must be greater
     *                       than zero
     * @param executor       the executor that runs the graph
     * @return a future completed with the final state
     * @see #runAsync(State, int)
     */
    public CompletableFuture<State> runAsync(State initialState, int recursionLimit, Executor executor) {
        State copy = initialState != null ? initialState.copy() : new State();
        return CompletableFuture.supplyAsync(() -> run(copy, recursionLimit), executor);
    }

    /**
     * Creates a new {@link GraphBuilder}.
     *
     * @return an empty builder
     */
    public static GraphBuilder builder() {
        return new GraphBuilder();
    }

    /**
     * Builder for {@link Graph}. Nodes must be declared before the edges that
     * reference them. Every method validates its arguments immediately and throws
     * {@link GraphStateException} on inconsistencies; global checks are performed
     * by {@link #build()}.
     *
     * <p>
     * A builder is not thread-safe.
     * </p>
     */
    public static class GraphBuilder {

        private Map<String, Function<State, Map<String, Object>>> nodeMap = new HashMap<>();
        private Map<String, String> edgeMap = new HashMap<>();
        private Map<String, Function<State, String>> condEdgeMap = new HashMap<>();
        private Map<String, Set<String>> pathMap = new HashMap<>();

        /**
         * Creates a builder that already contains the reserved {@link Graph#START_NODE}
         * and {@link Graph#END_NODE} nodes.
         */
        public GraphBuilder() {
            nodeMap.put(START_NODE, state -> Map.of());
            nodeMap.put(END_NODE, state -> Map.of());
        }

        /**
         * Adds a node.
         *
         * @param name the unique node name; {@code START_NODE} and {@code END_NODE}
         *             are reserved
         * @param node the function executed when the node runs. It receives the
         *             current state and returns the updates to merge into it, or
         *             {@code null}/an empty map for no updates.
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws GraphStateException  if the name is reserved or already used
         */
        public GraphBuilder addNode(String name, Function<State, Map<String, Object>> node) {
            Objects.requireNonNull(name, "name can't be null.");
            Objects.requireNonNull(node, "node can't be null");

            if (name.equals(START_NODE))
                throw new GraphStateException("The name \"START_NODE\" is reserved.");
            if (name.equals(END_NODE))
                throw new GraphStateException("The name \"END_NODE\" is reserved.");
            if (nodeMap.containsKey(name))
                throw new GraphStateException("A node named \"" + name + "\" is already present.");

            nodeMap.put(name, node);

            return this;
        }

        /**
         * Adds a simple edge, always followed after {@code fromNode} has run.
         *
         * <p>
         * A simple edge cannot target {@code START_NODE}, because it would create
         * a cycle with no way out. To go back to the start, use a conditional
         * edge.
         * </p>
         *
         * @param fromNode the source node, already declared; it cannot be
         *                 {@code END_NODE}
         * @param toNode   the destination node, already declared; it cannot be
         *                 {@code START_NODE}
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws GraphStateException  if a node is not declared, if the
         *                              endpoints are not allowed, or if
         *                              {@code fromNode} already has an outgoing
         *                              edge (simple or conditional)
         */
        public GraphBuilder addEdge(String fromNode, String toNode) {
            Objects.requireNonNull(fromNode, "fromNode can't be null.");
            Objects.requireNonNull(toNode, "toNode can't be null.");

            boolean fromCtrl;
            boolean toCtrl;

            fromCtrl = nodeMap.containsKey(fromNode);
            toCtrl = nodeMap.containsKey(toNode);

            if (toNode.equals(START_NODE))
                throw new GraphStateException("\"START_NODE\" can't be a to node.");
            if (fromNode.equals(END_NODE))
                throw new GraphStateException("\"END_NODE\" can't be a from node.");
            if (!fromCtrl)
                throw new GraphStateException("fromNode not declared.");
            if (!toCtrl)
                throw new GraphStateException("toNode not declared.");
            if (edgeMap.containsKey(fromNode))
                throw new GraphStateException("Edge from \"" + fromNode + "\" already exists.");
            if (condEdgeMap.containsKey(fromNode))
                throw new GraphStateException(
                        "Edge from \"" + fromNode + "\" already declared as conditional edge.");

            edgeMap.put(fromNode, toNode);

            return this;
        }

        /**
         * Adds a conditional edge. After {@code fromNode} has run,
         * {@code conditionalEdge} is evaluated on the current state and must
         * return the name of the next node, which must belong to {@code toNodes}.
         *
         * <p>
         * {@code toNodes} may contain {@code START_NODE} (to restart the graph),
         * {@code END_NODE}, or the node itself. The set is copied, so later
         * changes to the caller's set have no effect. Returning a node outside
         * {@code toNodes}, or {@code null}, fails at runtime with a
         * {@link NodeExecutionException}.
         * </p>
         *
         * @param fromNode        the source node, already declared; it cannot be
         *                        {@code END_NODE}
         * @param conditionalEdge function that selects the next node
         * @param toNodes         the possible destinations, non-empty and all
         *                        already declared
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws GraphStateException  if {@code toNodes} is empty or contains
         *                              undeclared nodes, if {@code fromNode} is
         *                              not declared, or if it already has an
         *                              outgoing edge (simple or conditional)
         */
        public GraphBuilder addConditionalEdge(String fromNode, Function<State, String> conditionalEdge,
                Set<String> toNodes) {
            Objects.requireNonNull(fromNode, "fromNode can't be null.");
            Objects.requireNonNull(conditionalEdge, "conditionalEdge can't be null.");
            Objects.requireNonNull(toNodes, "toNodes can't be null.");

            if (fromNode.equals(END_NODE))
                throw new GraphStateException("\"END_NODE\" can't be a from node.");
            if (toNodes.isEmpty())
                throw new GraphStateException("toNodes must have at least one output node.");
            if (!toNodes.stream().allMatch(nodeMap::containsKey))
                throw new GraphStateException("toNode contains undeclared nodes.");
            if (!nodeMap.containsKey(fromNode))
                throw new GraphStateException("fromNode not declared.");
            if (condEdgeMap.containsKey(fromNode))
                throw new GraphStateException("Edge from \"" + fromNode + "\" already exists.");
            if (edgeMap.containsKey(fromNode))
                throw new GraphStateException("Edge from \"" + fromNode + "\" already declared as simple edge.");

            condEdgeMap.put(fromNode, conditionalEdge);
            pathMap.put(fromNode, Set.copyOf(toNodes));

            return this;
        }

        /**
         * Validates the structure and builds an immutable {@link Graph}.
         *
         * <p>
         * Checks performed, in order: {@code START_NODE} has an outgoing edge;
         * every node has an outgoing edge; every node is reachable from
         * {@code START_NODE}; every node has a path to {@code END_NODE}. Loops are
         * allowed as long as they have a way out.
         * </p>
         *
         * @return the built graph
         * @throws GraphStateException if any check fails
         */
        public Graph build() {
            checkStart();
            checkOutputs();
            checkPaths();
            checkExits();
            return new Graph(Map.copyOf(nodeMap), Map.copyOf(edgeMap), Map.copyOf(condEdgeMap), Map.copyOf(pathMap));
        }

        /** Verifies that {@code START_NODE} has an outgoing edge. */
        private void checkStart() {
            boolean checkA = true;
            boolean checkB = true;

            if (!edgeMap.containsKey(START_NODE) || edgeMap.get(START_NODE) == null)
                checkA = false;
            if (!condEdgeMap.containsKey(START_NODE) || !pathMap.containsKey(START_NODE))
                checkB = false;
            if (!checkA && !checkB)
                throw new GraphStateException("The START_NODE node must have at least one outgoing edge.");
        }

        /** Verifies that every node, except {@code END_NODE}, has an outgoing edge. */
        private void checkOutputs() {
            Map<String, Boolean> ctrlOutputs = new HashMap<>();
            List<String> nodeWithoutOutputs;
            StringBuilder builder;

            nodeMap.keySet().stream()
                    .filter(key -> !key.equals(END_NODE))
                    .forEach(fKey -> ctrlOutputs.put(fKey, false));
            edgeMap.entrySet().stream()
                    .filter(entry -> entry.getValue() != null)
                    .map(fEntry -> fEntry.getKey())
                    .forEach(key -> ctrlOutputs.put(key, true));
            pathMap.entrySet().stream()
                    .filter(entry -> !entry.getValue().isEmpty())
                    .map(fEntry -> fEntry.getKey())
                    .forEach(key -> ctrlOutputs.put(key, true));
            nodeWithoutOutputs = ctrlOutputs.entrySet().stream()
                    .filter(entry -> !entry.getValue())
                    .map(fEntry -> fEntry.getKey())
                    .toList();

            if (nodeWithoutOutputs.isEmpty())
                return;

            builder = new StringBuilder();
            builder.append("[ ");
            nodeWithoutOutputs.forEach(node -> builder.append(node + " "));
            builder.append("]");
            throw new GraphStateException("There are nodes with no outgoing edges " + builder.toString());
        }

        /** Verifies that every node is reachable from {@code START_NODE}. */
        private void checkPaths() {
            Set<String> visited = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(START_NODE);
            while (!stack.isEmpty()) {
                String n = stack.pop();
                if (!visited.add(n))
                    continue;
                String next = edgeMap.get(n);
                if (next != null)
                    stack.push(next);
                pathMap.getOrDefault(n, Set.of()).forEach(stack::push);
            }
            Set<String> unreachable = new TreeSet<>(nodeMap.keySet());
            unreachable.removeAll(visited);
            if (!unreachable.isEmpty())
                throw new GraphStateException("Unreachable nodes: " + unreachable);
        }

        /**
         * Verifies that every node has at least one path to {@code END_NODE},
         * using a backward visit from {@code END_NODE}. Loops are accepted when at
         * least one edge leads out of them.
         */
        private void checkExits() {
            Map<String, Set<String>> reverse = new HashMap<>();
            edgeMap.forEach((from, to) -> reverse.computeIfAbsent(to, k -> new HashSet<>()).add(from));
            pathMap.forEach(
                    (from, tos) -> tos.forEach(to -> reverse.computeIfAbsent(to, k -> new HashSet<>()).add(from)));

            Set<String> canExit = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(END_NODE);
            while (!stack.isEmpty()) {
                String n = stack.pop();
                if (!canExit.add(n))
                    continue;
                reverse.getOrDefault(n, Set.of()).forEach(stack::push);
            }

            Set<String> trapped = new TreeSet<>(nodeMap.keySet());
            trapped.removeAll(canExit);
            if (!trapped.isEmpty())
                throw new GraphStateException("Nodes with no path to END_NODE: " + trapped);
        }

    }

    /**
     * Thrown when the graph structure is invalid. It is raised while the graph is
     * being built (by {@link GraphBuilder} methods and
     * {@link GraphBuilder#build()}).
     */
    public static class GraphStateException extends RuntimeException {

        /** Creates an exception with no message. */
        public GraphStateException() {
            super();
        }

        /**
         * Creates an exception with a message.
         *
         * @param message the detail message
         */
        public GraphStateException(String message) {
            super(message);
        }

        /**
         * Creates an exception with a message and a cause.
         *
         * @param message   the detail message
         * @param throwable the cause
         */
        public GraphStateException(String message, Throwable throwable) {
            super(message, throwable);
        }

    }

    /**
     * Thrown when a failure happens while the graph is running: a node or a
     * conditional edge throws, a conditional edge returns an invalid destination,
     * or the recursion limit is exceeded. When caused by user code, the original
     * exception is available through {@link #getCause()}.
     */
    public static class NodeExecutionException extends RuntimeException {

        /** Creates an exception with no message. */
        public NodeExecutionException() {
            super();
        }

        /**
         * Creates an exception with a message.
         *
         * @param message the detail message
         */
        public NodeExecutionException(String message) {
            super(message);
        }

        /**
         * Creates an exception with a message and a cause.
         *
         * @param message   the detail message
         * @param throwable the cause
         */
        public NodeExecutionException(String message, Throwable throwable) {
            super(message, throwable);
        }

    }
}
