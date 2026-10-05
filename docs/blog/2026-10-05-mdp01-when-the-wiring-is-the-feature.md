---
layout: post
title: "When the Wiring Is the Feature"
date: 2026-10-05
entry_type: note
subtype: diary
projects: [casehubio/neocortex]
tags: [cdi, quarkus, knowledge-pipeline, config-mapping, metrics]
series: issue-418-knowledge-pipeline-phase-2
---

# When the Wiring Is the Feature

The knowledge pipeline existed as working Java classes — orchestrator, cache stores, entity resolution, research sessions — but none of them could participate in a Quarkus deployment. Every dependency was constructor-wired by hand. The module compiled, tested, and did its job in isolation. It just couldn't be used.

That gap between "works in tests" and "works in production" is where CDI wiring lives. And it's deceptively mechanical until you discover the places where it isn't.

The straightforward part: annotating stores with `@ApplicationScoped` and `@Inject`. `SqliteSpatialCacheStore`, `QueryCacheStore`, `EntityMetadataStore`, `DedupIndexStore` — all take a `HikariDataSource`, already produced by a `@DefaultBean` method. `EntityPromoter` and `ResearchOrchestrator` take other CDI beans as constructor parameters. `InMemorySpatialCacheStore` gets `@Alternative @Priority(2)` for test displacement. Mechanical, satisfying, no surprises.

The interesting part was the classes that need config values CDI can't inject into constructor parameters. `KnowledgePipelineOrchestrator` needs an `int geohashPrecision`. `CacheEvictionScheduler` needs two `Duration` values. `ResearchSessionStore` needs its own SQLite database with different Flyway migrations. These get produced from `KnowledgePipelineDefaultBeans` — the producer method reads `KnowledgePipelineConfig`, constructs the object, and hands it to the container.

The `Instance<LocationPlatform>` pattern for multi-provider discovery was the design highlight. The orchestrator doesn't know how many location providers exist at compile time — CDI discovers them at runtime. `providers.stream().toList()` gives you whatever's on the classpath. Zero providers? Empty list, empty search results, no error. Three providers? All three are queried, errors isolated per-provider. The producer method is the translation layer between CDI's `Instance<T>` and the orchestrator's plain `List<T>`.

Then the audit surfaced real problems. `KnowledgePipelineMetrics` had been defined — Micrometer counters for cache hits, provider fetch timing, eviction counts — but nothing in the module referenced it. Every metric was silently dropped. I wired it through with `Instance<MeterRegistry>` graceful degradation: if Micrometer is on the classpath, real counters. If not, `Metrics.globalRegistry` provides a no-op. Setter injection kept the existing constructors stable — tests pass `null`, CDI calls `setMetrics()`.

The config collision was the most interesting find. Two nested config groups — pipeline SQLite and research SQLite — shared the same `SqliteConfig` interface. Both had `@WithDefault("knowledge-pipeline.db")` on the `path()` method. SmallRye Config resolves defaults from the declaring interface, not the usage context. Both databases silently pointed at the same file. No startup error. No warning. Just data corruption at runtime. The fix was a separate `ResearchSqliteConfig` interface with its own default — verbose but unambiguous.

The config mapping collision led to a second discovery: you can't mix `@ConfigMapping` and `@ConfigProperty` on the same prefix. SmallRye validates exhaustively — any key under a `@ConfigMapping` prefix that isn't in the interface hierarchy gets rejected at startup with "does not map to any root." The error message doesn't mention `@ConfigProperty` at all. Both mechanisms are legitimate; using them together on the same prefix is not.

Three garden entries came out of this session. The config default collision is the one I'd want someone else to find before I did.
