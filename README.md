# DoItByGraph

A minimal, dependency-free state graph engine for Java, inspired by [LangGraph](https://github.com/langchain-ai/langgraph).

You describe a workflow as a set of **nodes** (plain Java functions) connected by **edges** (fixed or conditional). The engine runs the nodes one after the other, passing a shared **state** between them, until the graph reaches its end. Loops, branching and retry patterns are supported out of the box, and the structure of the graph is validated when you build it, so many mistakes are caught before anything runs.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-17%2B-blue)

---

## Table of contents

- [Features](#features)
- [Requirements](#requirements)
- [Getting started](#getting-started)
- [Core concepts](#core-concepts)
- [Usage guide](#usage-guide)
  - [Building a graph](#building-a-graph)
  - [Conditional edges and branching](#conditional-edges-and-branching)
  - [Loops](#loops)
  - [Working with the state](#working-with-the-state)
  - [Running a graph](#running-a-graph)
  - [Asynchronous execution](#asynchronous-execution)
  - [Error handling](#error-handling)
- [Build-time validation](#build-time-validation)
- [Thread safety](#thread-safety)
- [API overview](#api-overview)
- [Design notes and limitations](#design-notes-and-limitations)
- [Comparison with LangGraph](#comparison-with-langgraph)
- [Project structure](#project-structure)
- [Contributing](#contributing)
- [License](#license)

---

## Features

- **Tiny and self-contained**: three classes, only the JDK, no external dependencies.
- **Simple and conditional edges**: fixed transitions, or a function that inspects the state and picks the next node among a declared set.
- **Loops allowed**: cycles are supported as long as there is a way out, including going back to `START_NODE` through a conditional edge.
- **Recursion limit**: a configurable cap on node executions protects against loops that never terminate.
- **Fail-fast validation**: unreachable nodes, nodes without outgoing edges, and nodes with no path to `END_NODE` are rejected by `build()`.
- **Immutable graphs**: a built `Graph` can be shared safely between threads.
- **Sync and async execution**: `run(...)` and `runAsync(...)` (with an optional custom `Executor`).
- **Clear errors**: build-time problems and runtime problems use different exception types, and runtime failures carry the name of the node and the original cause.

## Requirements

- **Java 17 or newer** (the code uses `Stream.toList()`, `Set.copyOf` and `Map.copyOf`).
- No third-party libraries.

## Getting started

Clone the repository and copy the `graph` package into your project, or compile it directly:

```bash
git clone https://github.com/ema989/DoItByGraph.git
cd DoItByGraph

# compile (adjust the path to match your layout)
javac -d out src/graph/*.java
```

Then add the compiled classes (or the sources) to your project's classpath.

### Your first graph

This graph increments a counter until it reaches 3, then ends:

```java
import graph.Graph;
import graph.State;

import java.util.Map;
import java.util.Set;

public class Example {

    public static void main(String[] args) {
        Graph graph = Graph.builder()
                .addNode("increment",
                        state -> Map.of("count", (int) state.getOrDefault("count", 0) + 1))
                .addEdge(Graph.START_NODE, "increment")
                .addConditionalEdge("increment",
                        state -> (int) state.get("count") >= 3 ? Graph.END_NODE : "increment",
                        Set.of("increment", Graph.END_NODE))
                .build();

        State result = graph.run(new State());
        System.out.println(result.get("count")); // 3
    }
}
```

## Core concepts

| Concept | Description |
| --- | --- |
| **State** | A mutable key/value container (`Map<String, Object>` underneath) shared by all nodes during a run. |
| **Node** | A `Function<State, Map<String, Object>>`. It receives the state and returns a map of updates that is merged into the state. It may return `null` or an empty map for "no updates". |
| **Simple edge** | A fixed transition: after node `A`, always run node `B`. |
| **Conditional edge** | A `Function<State, String>` that returns the name of the next node. The possible destinations must be declared in advance. |
| `START_NODE` | Reserved entry point. Every graph starts from it. |
| `END_NODE` | Reserved exit point. Reaching it ends the execution. |
| **Recursion limit** | The maximum number of node executions allowed in a single run (default: 100). |

Every node except `END_NODE` must have exactly one outgoing edge, either simple or conditional.

## Usage guide

### Building a graph

Graphs are created with `Graph.builder()`. Nodes must be declared **before** the edges that reference them.

```java
Graph graph = Graph.builder()
        .addNode("first",  state -> Map.of("greeting", "hello"))
        .addNode("second", state -> {
            System.out.println(state.get("greeting"));
            return Map.of();
        })
        .addEdge(Graph.START_NODE, "first")
        .addEdge("first", "second")
        .addEdge("second", Graph.END_NODE)
        .build();
```

Rules enforced while building:

- `START_NODE` and `END_NODE` are reserved names and cannot be redeclared.
- `START_NODE` cannot be the destination of a **simple** edge (it would be a cycle with no way out). Use a conditional edge to go back to the start.
- `END_NODE` cannot be the source of an edge.
- A node can have only one outgoing edge, simple or conditional, not both.

### Conditional edges and branching

A conditional edge takes the current state and returns the name of the next node. The set of allowed destinations is declared up front:

```java
private static final String APPROVED = "approved";
private static final String REJECTED = "rejected";

Graph graph = Graph.builder()
        .addNode("review",   state -> Map.of("score", 7))
        .addNode(APPROVED,   state -> Map.of("result", "ok"))
        .addNode(REJECTED,   state -> Map.of("result", "ko"))
        .addEdge(Graph.START_NODE, "review")
        .addConditionalEdge("review",
                state -> (int) state.get("score") >= 5 ? APPROVED : REJECTED,
                Set.of(APPROVED, REJECTED))
        .addEdge(APPROVED, Graph.END_NODE)
        .addEdge(REJECTED, Graph.END_NODE)
        .build();
```

If the function returns `null`, or a node that is not in the declared set, the run fails with a `NodeExecutionException`. Destinations can include `END_NODE`, `START_NODE`, or the source node itself.

> Tip: define node names as constants. Strings repeated between the condition function and the builder are an easy source of typos.

### Loops

Cycles are allowed as long as at least one path leads out of them. A typical "retry until done" pattern:

```java
Graph graph = Graph.builder()
        .addNode("attempt", state -> {
            int tries = (int) state.getOrDefault("tries", 0) + 1;
            boolean ok = callSomething();               // your logic
            return Map.of("tries", tries, "ok", ok);
        })
        .addEdge(Graph.START_NODE, "attempt")
        .addConditionalEdge("attempt",
                state -> (boolean) state.get("ok") || (int) state.get("tries") >= 5
                        ? Graph.END_NODE
                        : "attempt",
                Set.of("attempt", Graph.END_NODE))
        .build();
```

A loop made only of simple edges (for example `a -> b -> a`) is rejected by `build()`, because no runtime choice could ever leave it.

**Important:** the validation is *static*. A conditional edge counts as able to reach every node in its destination set, so the engine cannot know whether your condition will actually choose the exit at runtime. If it never does, the **recursion limit** stops the run with a `NodeExecutionException`. Choose a limit that fits the maximum number of iterations that makes sense for your loops, and make sure your exit conditions are correct.

### Working with the state

`State` offers a small, map-like API:

```java
State state = new State();
state.put("name", "Ada");                       // single value
state.put(Map.of("a", 1, "b", 2));              // merge several values

Object raw     = state.get("name");
String name    = state.getWithCast("name", String.class);   // typed read
Object missing = state.getOrDefault("x", 42);
boolean has    = state.containsKey("name");
state.remove("name");
```

`getWithCast` returns `null` for absent keys. If the stored value has a different type, it throws a `ClassCastException` whose message includes the key, the actual type and the expected type.

**Two ways to update the state.** A node can either return a map of updates (the recommended "delta" style), or mutate the `State` it receives directly. Both are allowed. If both touch the same key, the returned map wins, because it is applied after the node finishes.

**Copying.** `state.copy()` creates a *shallow* copy: keys are copied, values are shared. Adding or removing keys in the copy does not affect the original, but mutating a shared object (for example adding an element to a list) is visible from both. For real isolation, either store immutable values (`record`, `String`, `List.copyOf(...)`, ...) or provide your own copier:

```java
State isolated = state.copy(v -> v instanceof List<?> l ? new ArrayList<>(l) : v);
```

### Running a graph

```java
State result = graph.run(initialState);          // default recursion limit (100)
State result = graph.run(initialState, 20);      // custom recursion limit
```

- If `initialState` is `null`, an empty state is created.
- `run` **modifies the given state in place** and returns it. Call `state.copy()` first if you need to keep the original.
- The recursion limit must be greater than zero, otherwise an `IllegalArgumentException` is thrown.

### Asynchronous execution

```java
CompletableFuture<State> future = graph.runAsync(initialState, 100);
CompletableFuture<State> future2 = graph.runAsync(initialState, 100, myExecutor);

State result = future.join();
```

Unlike `run`, `runAsync` works on a **copy** of the initial state (taken when you call the method), so the caller's instance is never modified. Failures complete the future exceptionally with a `CompletionException` wrapping the real cause. Without an executor, the common `ForkJoinPool` is used.

### Error handling

There are two exception types, both unchecked:

| Exception | When |
| --- | --- |
| `Graph.GraphStateException` | The graph definition is invalid. Thrown by the builder methods and by `build()`. |
| `Graph.NodeExecutionException` | Something fails while running: a node or a conditional function throws, a conditional edge returns `null` or an undeclared destination, or the recursion limit is exceeded. |

When user code throws, the engine wraps the exception and adds the node name, keeping the original as the cause:

```java
try {
    graph.run(new State());
} catch (Graph.NodeExecutionException e) {
    System.err.println(e.getMessage());   // Node "attempt" threw an exception: ...
    Throwable cause = e.getCause();       // the original exception
}
```

## Build-time validation

`build()` runs these checks, in order, and throws a `GraphStateException` on the first failure:

| Check | Rejects |
| --- | --- |
| Start | A graph where `START_NODE` has no outgoing edge. |
| Outputs | Nodes (other than `END_NODE`) without any outgoing edge. |
| Reachability | Nodes that cannot be reached from `START_NODE`. |
| Exits | Nodes with no path to `END_NODE` (for example a closed `a -> b -> a` cycle). |

The builder methods also check their arguments immediately: unknown or reserved node names, duplicate nodes, duplicate outgoing edges, empty destination sets, and `null` arguments (`NullPointerException`).

## Thread safety

- A built `Graph` is **immutable** and can be shared and run concurrently from multiple threads.
- `State` is **not** thread-safe. Use one state per run.
- `GraphBuilder` is not thread-safe.
- Your node functions are responsible for their own thread safety if they touch shared resources.

## API overview

**`Graph`**

| Member | Description |
| --- | --- |
| `Graph.START_NODE`, `Graph.END_NODE` | Reserved node names. |
| `Graph.builder()` | Creates a `GraphBuilder`. |
| `run(State)` / `run(State, int)` | Runs synchronously, modifying the given state. |
| `runAsync(State, int)` / `runAsync(State, int, Executor)` | Runs asynchronously on a copy of the state. |

**`Graph.GraphBuilder`**

| Method | Description |
| --- | --- |
| `addNode(String, Function<State, Map<String, Object>>)` | Declares a node. |
| `addEdge(String, String)` | Adds a simple edge. |
| `addConditionalEdge(String, Function<State, String>, Set<String>)` | Adds a conditional edge with its allowed destinations. |
| `build()` | Validates and builds the immutable graph. |

**`State`**

| Method | Description |
| --- | --- |
| `put(String, Object)` / `put(Map<String, Object>)` | Stores one value, or merges a map. |
| `get`, `getWithCast`, `getOrDefault`, `containsKey` | Reads. |
| `remove(String)` | Removes a key. |
| `copy()` / `copy(UnaryOperator<Object>)` | Shallow copy, or copy with a custom value copier. |

Full details are in the Javadoc of each class.

## Design notes and limitations

- **Static validation only.** The engine verifies the structure of the graph, not the behavior of your condition functions. It cannot detect a condition that never picks the exit; the recursion limit is the safety net.
- **Shallow state copies.** See [Working with the state](#working-with-the-state).
- **One outgoing edge per node.** Fan-out (running several nodes in parallel from one node) is not supported.
- **Overwrite semantics.** Each update replaces the previous value for a key. There are no per-key reducers (such as "append to list").
- **No persistence.** There is no checkpointing, resuming or human-in-the-loop interruption.
- **Strings as node identifiers.** Node names are plain strings; use constants to avoid typos.

## Comparison with LangGraph

| Feature | LangGraph | This library |
| --- | --- | --- |
| Nodes as functions over a shared state | Yes | Yes |
| Simple and conditional edges | Yes | Yes |
| Cycles / loops | Yes | Yes |
| Recursion limit | Yes | Yes |
| Compile-time structure validation | Yes | Yes (`build()`) |
| Per-key state reducers | Yes | No |
| Parallel branches (fan-out / fan-in) | Yes | No |
| Checkpointing and persistence | Yes | No |
| Streaming of intermediate steps | Yes | No |
| Interrupts / human-in-the-loop | Yes | No |

The goal of this project is to stay small and easy to read, not to replace LangGraph.

## Project structure

```text
.
├── src/
│   └── example
│       └── MyGraph.java    # usage example
│   └── graph/
│       ├── Graph.java      # graph, builder, validation, exceptions
│       └── State.java      # shared key/value state
├── LICENSE
└── README.md
```

## Contributing

Contributions are welcome.

1. Fork the repository and create a branch for your change.
2. Keep the code dependency-free and consistent with the existing style.
3. Document public methods with Javadoc.
4. Add tests for new behavior or bug fixes.
5. Open a pull request describing what changed and why.

Bug reports and feature ideas can be filed as GitHub issues.

## License

This project is released under the [MIT License](LICENSE).

```text
MIT License

Copyright (c) 2026 Emanuele Ciro Muto

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
