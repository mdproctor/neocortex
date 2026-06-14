---
title: CDI Dependency Injection
domain: tech
tags: cdi, quarkus, injection
---

CDI (Contexts and Dependency Injection) provides a powerful mechanism for managing dependencies in Java applications through runtime reflection and proxy generation. In traditional CDI containers, beans are discovered and wired at runtime using dynamic proxies, which introduces overhead and complexity. Quarkus takes a fundamentally different approach through ArC, its build-time oriented CDI implementation.

ArC performs dependency resolution, proxy generation, and bean metadata construction during the build phase rather than at application startup. This build-time optimization eliminates the runtime reflection overhead that plagues traditional CDI containers. The result is faster startup times, reduced memory footprint, and better compatibility with native compilation through GraalVM.

Developers annotate beans with standard CDI annotations like @ApplicationScoped, @Inject, and @Produces. ArC analyzes these annotations during the build, generates optimized proxy classes, and creates a complete dependency graph. At runtime, bean lookup becomes a simple array access rather than a reflective operation. This build-time model also enables dead code elimination, where unused beans and their dependencies never make it into the final application binary.
