# Query Expander Default Bean — Design Spec

**Issue:** casehubio/neocortex#112
**Date:** 2026-07-06

## Problem

`LlmQueryExpander` is annotated with `@IfBuildProperty(name = "casehub.rag.expansion.mode", stringValue = "llm", enableIfMissing = true)`. This registers it by default in any Quarkus application where `rag-expansion` is on the classpath. Its constructor requires `ChatModel`. Quarkus Arc validates injection points of all registered beans — including beans not selected due to a higher-priority `@Alternative`. In a consumer `@QuarkusTest` where no `ChatModel` bean exists, the build fails with an unsatisfied dependency even when `InMemoryQueryExpander` (`@Alternative @Priority(1)`) would be selected.

## Root Cause

Two independent consumer decisions are conflated:

1. **"I want query expansion in my pipeline"** — `casehub.rag.expansion.enabled=true` (activates the decorator)
2. **"I want THIS expansion strategy"** — `casehub.rag.expansion.mode=llm|step-back|template` (selects the implementation)

Decision 1 implicitly drags in `ChatModel` via `LlmQueryExpander`'s `enableIfMissing = true`. A consumer cannot activate the expansion decorator without accepting a heavy transitive dependency they did not explicitly request.

## Design

### SPI Classification

`QueryExpander` is an operational SPI — skipping expansion means normal retrieval without enhancement. The system functions correctly. Per the platform's SPI default rules, operational SPIs get a no-op `@DefaultBean`.

### Changes

**1. Remove `enableIfMissing = true` from `LlmQueryExpander`**

```java
// Before
@IfBuildProperty(name = "casehub.rag.expansion.mode", stringValue = "llm", enableIfMissing = true)

// After
@IfBuildProperty(name = "casehub.rag.expansion.mode", stringValue = "llm")
```

`LlmQueryExpander` is the only expander with `enableIfMissing = true` — the other two (`TemplateQueryExpander`, `StepBackQueryExpander`) already require explicit mode selection. This change makes all three consistent.

**2. Remove `@WithDefault("llm")` from `ExpansionConfig.mode()`**

```java
// Before
@WithDefault("llm")
String mode();

// After
Optional<String> mode();
```

`@WithDefault("llm")` is a vestige of the old design where LLM was the implicit default. With the new explicit-selection model, the "no mode set" state must be representable in the config. Changing to `Optional<String>` eliminates a semantic inconsistency: without this change, `config.mode()` returns `"llm"` at runtime even when the actual behavior is no-op pass-through.

This also provides defense in depth against a potential `@IfBuildProperty` / `@WithDefault` interaction. SmallRye Config registers `@ConfigMapping` `@WithDefault` values in the `DefaultValuesConfigSource`. If `@IfBuildProperty` sees these defaults during build-time evaluation, the property `casehub.rag.expansion.mode` would resolve to `"llm"` even when unset — matching `@IfBuildProperty(stringValue = "llm")` and registering `LlmQueryExpander` regardless of removing `enableIfMissing`. Removing the `@WithDefault` eliminates this interaction entirely.

`ExpansionConfig.mode()` has zero runtime callers — no code reads the mode value; selection is entirely via `@IfBuildProperty` annotations. The change is safe.

**3. Add `NoOpQueryExpander` in `rag-expansion`**

```java
@DefaultBean
@ApplicationScoped
public class NoOpQueryExpander implements QueryExpander {
    @Override
    public List<RetrievalQuery> expand(RetrievalQuery query) {
        return List.of(query);
    }
}
```

- `@DefaultBean` — displaced by any `@ApplicationScoped` implementation (when mode is set) or `@Alternative` (InMemoryQueryExpander in tests)
- Returns the query unchanged — pass-through
- Located in `rag-expansion/` (CDI wiring module), not `rag-api/` — only needed when the decorator is active
- Follows `NoOpCbrCaseMemoryStore` pattern in `memory/`

**4. Add startup misconfiguration warning**

```java
@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.expansion.enabled", stringValue = "true")
public class ExpansionConfigValidator {

    private static final Logger LOG = Logger.getLogger(ExpansionConfigValidator.class.getName());

    @Inject
    ExpansionConfig config;

    void onStartup(@Observes StartupEvent event) {
        if (config.mode().isEmpty()) {
            LOG.warning("Query expansion is enabled but no mode is set"
                + " — queries will pass through unchanged."
                + " Set casehub.rag.expansion.mode to llm, template, or step-back.");
        }
    }
}
```

This lives in `rag-expansion/` and is gated by the same `@IfBuildProperty` as the decorators — it only activates when expansion is enabled. The `NoOpQueryExpander` itself stays a pure no-op per the platform's `@DefaultBean` pattern; configuration coherence validation is a separate concern.

Without this, a consumer who sets `expansion.enabled=true` but forgets to set a mode gets silent pass-through — the spec's "forces every consumer to be explicit" claim would be half-enforced.

### CDI Resolution Table

| Context | Mode set? | Beans registered | Selected bean | ChatModel needed? |
|---------|-----------|-----------------|---------------|-------------------|
| Production, no mode | No | NoOp (@DefaultBean) | NoOp | No |
| Production, mode=llm | Yes | NoOp + LlmQueryExpander | LlmQueryExpander | Yes (explicit) |
| Production, mode=template | Yes | NoOp + TemplateQueryExpander | TemplateQueryExpander | No |
| Production, mode=step-back | Yes | NoOp + StepBackQueryExpander | StepBackQueryExpander | Yes (explicit) |
| @QuarkusTest + rag-testing, no mode | No | NoOp + InMemoryQueryExpander (@Alt) | InMemoryQueryExpander | No |
| @QuarkusTest + rag-testing, mode=llm | Yes | NoOp + LlmQueryExpander + InMemoryQueryExpander (@Alt) | InMemoryQueryExpander | Yes* |

*When mode=llm in tests, `LlmQueryExpander` is registered and its `ChatModel` dependency is validated. Consumer explicitly opted into LLM mode — they must provide a `ChatModel` bean (or mock). This is correct: explicit opt-in, explicit dependencies.

### Decorator Fast Path

The decorator's existing single-query fast path handles the no-op case with zero overhead:

```java
if (expanded.size() == 1) {
    return delegate.retrieve(expanded.get(0), corpus, maxResults, filter);
}
```

`NoOpQueryExpander` returns `List.of(query)` (size 1) — the decorator calls the delegate with the original query directly. No RRF fusion, no fan-out, no extra retrieval calls.

### Why Not Template as Default

`TemplateQueryExpander` wraps the query in a fixed string: `"A document that answers '%s' would contain..."`. For dense embeddings this barely shifts the vector. For BM25/sparse it adds high-frequency noise terms (`document`, `answers`, `information`). It is not meaningfully better than no expansion but gives the illusion of doing something. If the default does nothing useful, it should be honest about it.

### Divergence from Issue #112

Issue #112 proposes "just remove `enableIfMissing = true`" and claims the decorator handles the "no expander available" case gracefully. That claim is incorrect — both `QueryExpandingCaseRetriever` and `ReactiveQueryExpandingCaseRetriever` inject `QueryExpander` via constructor. If the decorator is enabled (`expansion.enabled=true`) but no `QueryExpander` bean exists, CDI fails with `UnsatisfiedResolutionException` at build time. The decorator's try/catch handles expansion *runtime failures*, not missing beans.

Removing `enableIfMissing = true` alone is insufficient: when expansion is enabled without a mode, no expander bean is registered, and the decorator's injection point is unsatisfied. `NoOpQueryExpander` fills this gap — it ensures a `QueryExpander` bean always exists when `rag-expansion` is on the classpath, regardless of mode configuration.

## Breaking Change

Consumers relying on the implicit LLM default (expansion enabled, no mode set) will get no-op expansion instead. They must add `casehub.rag.expansion.mode=llm` to their config. This is intentional — the breakage forces every consumer to be explicit about their expansion strategy and its dependencies.

## Files Changed

| File | Change |
|------|--------|
| `rag-expansion/.../LlmQueryExpander.java` | Remove `enableIfMissing = true` |
| `rag-expansion/.../ExpansionConfig.java` | Change `mode()` from `@WithDefault("llm") String` to `Optional<String>` |
| `rag-expansion/.../NoOpQueryExpander.java` | New — `@DefaultBean` pass-through |
| `rag-expansion/.../ExpansionConfigValidator.java` | New — startup warning when expansion enabled without mode |
| `rag-expansion/.../NoOpQueryExpanderTest.java` | New — unit test |
| `rag-expansion/.../ExpansionConfigValidatorTest.java` | New — unit test |

## Out of Scope

- Metrics/observability for expansion operations
- Timeout configuration for LLM expansion calls
