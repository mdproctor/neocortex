---
title: Reactive Streams with Mutiny
domain: tech
tags: reactive, mutiny, vertx
---

Mutiny is a reactive programming library designed for the Quarkus stack, providing two core types: Uni for single-value streams and Multi for multi-value streams. Unlike traditional blocking I/O where threads wait for results, reactive streams propagate values asynchronously through callback chains. This model eliminates thread blocking, enabling high concurrency with minimal thread overhead.

The Vert.x event loop is central to Quarkus's reactive architecture. Event loop threads handle I/O events, timers, and asynchronous callbacks without blocking. Application code running on the event loop must never block — no JDBC calls, no Thread.sleep, no blocking file I/O. Blocking the event loop starves other tasks and degrades throughput.

The blocking-to-reactive bridge pattern solves a common problem: integrating blocking APIs into reactive applications. The pattern executes blocking code on a worker thread pool, then wraps the result in a Uni. The Uni emits on the event loop after the blocking operation completes. This preserves the reactive contract while safely using blocking libraries.

Operators like map, flatMap, and onFailure transform and compose reactive streams. Error handling uses onFailure recovery chains rather than try-catch blocks. Subscriptions trigger execution — Mutiny streams are lazy and do nothing until subscribed.
