---
title: Quarkus Application Lifecycle
domain: tech
tags: quarkus, lifecycle, startup
---

Quarkus divides application execution into two distinct phases: build time and runtime. Build time encompasses compilation, dependency analysis, bytecode transformation, and configuration resolution. Runtime covers the period from JVM startup to shutdown. Understanding this separation is critical for optimal performance and correct behavior.

During the build phase, Quarkus performs aggressive optimization. Extensions scan the classpath, generate proxies, resolve configuration properties, and prepare metadata. Code that executes at build time cannot access runtime configuration or depend on runtime services. The build phase produces an optimized application model that dramatically reduces startup overhead.

The runtime phase begins when the JVM starts the application. Quarkus fires lifecycle events that applications can observe. StartupEvent signals that the application context is fully initialized and ready to accept requests. ShutdownEvent fires when the application begins graceful termination, allowing cleanup of resources like database connections or external service clients.

The @Scheduled annotation enables declarative cron-style task execution. Scheduled methods run on managed threads with automatic context propagation. They fire after StartupEvent and cease before ShutdownEvent, ensuring clean lifecycle boundaries.
