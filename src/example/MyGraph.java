package example;

import java.util.List;
import java.util.Map;
import java.util.Set;

import graph.Graph;
import graph.State;
import graph.Graph.GraphBuilder;

/**
 * Usage example for {@link Graph}: a small "fetch, parse, summarize" pipeline
 * with a retry loop.
 *
 * <pre>
 *                  +-------+
 *                  v       | (retry)
 * START -> fetch --+-------+
 *            |
 *            +--> parse -> summarize -> END   (payload received)
 *            |
 *            +--> fail -> END                 (too many attempts)
 * </pre>
 *
 * <p>
 * The example shows:
 * </p>
 * <ul>
 * <li>nodes and conditional edges written as plain methods, wired with method
 * references;</li>
 * <li>a simple edge ({@code parse -> summarize}) and a conditional edge
 * ({@code fetch -> parse | fetch | fail});</li>
 * <li>a loop with an exit (the retry on {@code fetch}), protected by the
 * recursion limit;</li>
 * <li>nodes that return a "delta" instead of mutating the state;</li>
 * <li>constants for node names and state keys;</li>
 * <li>synchronous and asynchronous execution.</li>
 * </ul>
 *
 * <p>
 * The "fetch" is simulated and fully deterministic: it succeeds on the attempt
 * number stored in {@link #SUCCEED_ON_ATTEMPT}, so the result does not depend
 * on
 * timing or randomness.
 * </p>
 */
public class MyGraph {

    // Node names
    private static final String FETCH = "fetch";
    private static final String PARSE = "parse";
    private static final String SUMMARIZE = "summarize";
    private static final String FAIL = "fail";

    // State keys
    private static final String ATTEMPTS = "attempts";
    private static final String MAX_ATTEMPTS = "maxAttempts";
    private static final String SUCCEED_ON_ATTEMPT = "succeedOnAttempt";
    private static final String PAYLOAD = "payload";
    private static final String WORDS = "words";
    private static final String SUMMARY = "summary";
    private static final String STATUS = "status";

    // ------------------------------------------------------------------
    // Nodes
    // ------------------------------------------------------------------

    /**
     * Simulates a fetch that can fail. It counts the attempts and, once the
     * simulated success attempt is reached, stores the payload.
     */
    public Map<String, Object> fetchNode(State state) {
        int attempts = (int) state.getOrDefault(ATTEMPTS, 0) + 1;
        int succeedOn = (int) state.getOrDefault(SUCCEED_ON_ATTEMPT, 1);

        System.out.println("[fetch]     attempt " + attempts);
        if (attempts >= succeedOn)
            return Map.of(ATTEMPTS, attempts, PAYLOAD, "graphs make workflows explicit and easy to test");
        return Map.of(ATTEMPTS, attempts);
    }

    /** Splits the payload into words. The list is immutable. */
    public Map<String, Object> parseNode(State state) {
        String payload = state.getWithCast(PAYLOAD, String.class);

        System.out.println("[parse]     " + payload);
        return Map.of(WORDS, List.of(payload.trim().split("\\s+")));
    }

    /** Builds a short summary from the parsed words. */
    public Map<String, Object> summarizeNode(State state) {
        List<?> words = state.getWithCast(WORDS, List.class);
        String summary = words.size() + " words, starting with \"" + words.get(0) + "\"";

        System.out.println("[summarize] " + summary);
        return Map.of(SUMMARY, summary, STATUS, "ok");
    }

    /** Terminal node used when the fetch keeps failing. */
    public Map<String, Object> failNode(State state) {
        int attempts = (int) state.get(ATTEMPTS);

        System.out.println("[fail]      giving up after " + attempts + " attempts");
        return Map.of(STATUS, "failed after " + attempts + " attempts");
    }

    // ------------------------------------------------------------------
    // Conditional edges
    // ------------------------------------------------------------------

    /**
     * Decides what happens after a fetch: parse the payload if it arrived, retry
     * if attempts are left, otherwise give up.
     */
    public String afterFetch(State state) {
        if (state.containsKey(PAYLOAD))
            return PARSE;

        int attempts = (int) state.get(ATTEMPTS);
        int maxAttempts = (int) state.getOrDefault(MAX_ATTEMPTS, 3);

        return attempts >= maxAttempts ? FAIL : FETCH;
    }

    // ------------------------------------------------------------------
    // Graph definition
    // ------------------------------------------------------------------

    /** Wires nodes and edges together and returns the validated graph. */
    public Graph build() {
        GraphBuilder g = Graph.builder();

        g.addNode(FETCH, this::fetchNode);
        g.addNode(PARSE, this::parseNode);
        g.addNode(SUMMARIZE, this::summarizeNode);
        g.addNode(FAIL, this::failNode);

        g.addEdge(Graph.START_NODE, FETCH);
        g.addConditionalEdge(FETCH, this::afterFetch, Set.of(FETCH, PARSE, FAIL));
        g.addEdge(PARSE, SUMMARIZE);
        g.addEdge(SUMMARIZE, Graph.END_NODE);
        g.addEdge(FAIL, Graph.END_NODE);

        return g.build();
    }

    // ------------------------------------------------------------------
    // Demo
    // ------------------------------------------------------------------

    private static State newInput(int succeedOnAttempt, int maxAttempts) {
        State input = new State();

        input.put(SUCCEED_ON_ATTEMPT, succeedOnAttempt);
        input.put(MAX_ATTEMPTS, maxAttempts);
        return input;
    }

    private static void printResult(State state) {
        System.out.println("  status   = " + state.get(STATUS));
        System.out.println("  attempts = " + state.get(ATTEMPTS));
        System.out.println("  summary  = " + state.getOrDefault(SUMMARY, "-"));
    }

    public static void main(String[] args) {
        Graph graph = new MyGraph().build();

        System.out.println("=== Run 1: succeeds on the 3rd attempt (max 5) ===");
        State ok = newInput(3, 5);
        graph.run(ok);
        printResult(ok);

        System.out.println();
        System.out.println("=== Run 2: would succeed on the 10th attempt, but max is 2 ===");
        State ko = newInput(10, 2);
        graph.run(ko, 20);
        printResult(ko);

        System.out.println();
        System.out.println("=== Run 3: asynchronous (the input state is copied) ===");
        State input = newInput(2, 5);
        State result = graph.runAsync(input, 20).join();
        printResult(result);
        System.out.println("  input state untouched = " + !input.containsKey(STATUS));
    }

}
