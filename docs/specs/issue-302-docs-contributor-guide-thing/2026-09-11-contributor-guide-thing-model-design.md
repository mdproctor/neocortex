# Contributor Guide — Thing Model Internals

**Date:** 2026-09-11
**Issue:** #302
**Status:** Draft

---

## 1. Scope

Add a `### Knowledge Model Internals` section to `docs/guides/contributor-guide.md` inside the existing `## Internal Architecture` section. This covers 7 topics from #302 — all oriented at platform builders who need to extend the knowledge model, not app builders who consume it (the consumer guide already covers usage).

Placement: after the existing `### CDI Decorator Priority Chain Summary` section (end of Internal Architecture), before `## Dependencies`.

---

## 2. Content Plan

### 2.1 Thing / MindMapNode Hierarchy

**What to cover:**
- Why Thing is a separate `thing-api` module with zero deps — consumers access semantic knowledge without pulling in MindMap cognitive machinery
- Dependency direction: `thing-api` ← `mindmap-api` ← `mindmap-intelligence` ← `cognitive-index`
- Content vs Cognition split: Thing = what something IS (id, name, type, properties, traits, `is()`/`as()`); MindMapNode extends with what the agent BELIEVES (confidence, PAD, temporal bounds, provenance, visibility)
- Read-only projection principle — Thing is a snapshot; mutations go through MindMapStore
- `type()` default method on MindMapNode delegates to `subgraphType()` — the invariant lives in one place, no implementor divergence

**Length:** ~150 words + the hierarchy diagram from the #285 spec.

### 2.2 Adding subgraphType() to New Store Implementations

**What to cover:**
- The contract: every MindMapNode must resolve its subgraph type at construction time
- Two patterns with code examples:
  - **SQLite (JOIN pattern):** `getNode()` query includes `JOIN mindmap_subgraph sg ON n.subgraph_id = sg.subgraph_id`, `toNode()` reads `rs.getString("sg_type")` into `SqliteNode`. Cost is negligible — `subgraph_id` is the PK of `mindmap_subgraph`, so each resolution is a single B-tree lookup.
  - **InMemory (cache lookup pattern):** `addNode()` resolves type at creation: `subgraphs.get(input.subgraphId()).type()`, passes to `StoredNode` constructor. Stored as a field.
- The key rule: never defer type resolution to a property or lazy lookup — it must be available on every `MindMapNode` returned by the store
- Reference: `MindMapStoreContractTest` validates `subgraphType()` is present and correct

**Length:** ~200 words + 2 short code snippets (SQL query, in-memory lookup).

### 2.3 TypeRegistry Internals

**What to cover:**
- CDI wiring: `@ApplicationScoped`, `Instance<MindMapStore>` for graceful degradation (returns `BootstrappedTenant.EMPTY` when store is absent)
- Lazy bootstrap: `ConcurrentHashMap.computeIfAbsent` per tenant — no eager `@PostConstruct` scan. First access for a tenant triggers TYPE_SYSTEM subgraph creation + core type node population
- Race condition handling: `createTypeSystemSubgraph` catches `IllegalStateException` on unique constraint violation (concurrent JVM instances) and re-queries to find the winner's subgraph
- Core type registration: `CORE_TYPES` map links `SubgraphTypes.PERSON → Personable.class`, etc. Types without Java interfaces (concept, general, research-area) get nodes but no `java-class` property
- Schema derivation from Java interfaces: `deriveSchemaFromInterface()` reflects declared methods → `SchemaField` records. Return type mapping: `String`/`Optional<String>` → `"string"`, `int`/`Integer`/`long`/`Long`/`double`/`Double` → `"number"`, `boolean`/`Boolean` → `"boolean"`
- Dynamic type registration: `registerType()` creates a node in TYPE_SYSTEM, optional `subtype-of` edge to parent. Normalized with `strip().toLowerCase()`
- Cache structure: `BootstrappedTenant` holds `typeSystemSubgraphId` + `ConcurrentHashMap<String, String>` of type name → node ID. `resolveTypeNode()` does a store `getNode()` — cache maps names to IDs, not to nodes (nodes may change)

**Length:** ~300 words. No code snippets — the concepts are more important than line-level detail.

### 2.4 Writing TraitRules — Programmatic vs Declarative

**What to cover:**

**Programmatic rules:**
- Implement `TraitRule` interface: `traitName()` + `matches(MindMapNode, List<MindMapEdge>)`
- Register as `@ApplicationScoped` CDI bean — `TraitApplicationDecorator` discovers via `Instance<TraitRule>`
- Example: `PersonableTraitRule` — checks for person-related properties (birthday, role, email, phone) OR person-related edges (parent-of, child-of, works-at). Returns `"Personable"` as trait name
- Naming convention: trait name = PascalCase Java interface simple name

**Declarative rules:**
- `DeclarativeTraitRule` record in `mindmap-api`: wraps a `RuleCondition` tree
- `RuleCondition` sealed interface with 11 variants: `HasProperty`, `PropertyEquals`, `PropertyIn`, `NotHasProperty`, `HasEdgeType`, `HasEdgeTypes`, `HasAnyEdge`, `InSubgraphType`, `AnyOf`, `AllOf`, `Not`
- Declared in YAML cognitive profiles, deserialized by `DeclarativeTraitRuleDeserializer`
- Loaded by `DeclarativeRuleRegistry` — global rules from `rules/*.yaml` + per-agent overrides from cognitive profiles (name-based override merge: local rule with same name suppresses global)

**How traits get applied:**
- `TraitApplicationDecorator` (@Decorator @Priority(70)) intercepts `addNode`, `updateNode`, `addEdge`, `removeEdge`
- Evaluates all rules (programmatic + declarative) against the affected node
- Adds/removes traits via `NodeUpdate` — additive trait mutations
- `ThreadLocal` reentrancy guard prevents infinite recursion (trait update triggers updateNode which would re-evaluate)
- Per-principal rule resolution: when `PrincipalId` is available, resolves per-agent declarative rules; otherwise falls back to all rules

**Length:** ~350 words + short example of a programmatic rule.

### 2.5 ThingProxyHandler — Adding New Return Type Coercions

**What to cover:**
- Location: `thing-api/src/main/java/.../thing/ThingProxyHandler.java` — package-private, 67 lines
- Mechanism: JDK `Proxy.newProxyInstance` with `InvocationHandler`. Maps method names to `thing.property(methodName)` calls
- Current coercion table: `String`, `Optional<String>`, `Integer`/`int`, `Long`/`long`, `Double`/`double`, `Boolean`/`boolean`
- Special methods: `toString` → `"InterfaceName[nodeName]"`, `hashCode` → `hash(id, traitInterface)`, `equals` → same node ID + same trait interface
- **How to add a new coercion:**
  1. Add case to `coerce()` method (parsing from String)
  2. Add primitive default to `primitiveDefault()` if it's a primitive type
  3. Update `TypeRegistry.mapReturnType()` in `mindmap-intelligence` to map the new Java return type to a schema type string
  4. Add test in thing-api's `ThingTest`
- Constraint: thing-api has zero deps — coercions cannot depend on external libraries

**Length:** ~200 words + the coercion table.

### 2.6 Creating New Trait Interfaces

**What to cover:**
- Placement: platform-provided traits in `mindmap-intelligence` (`Personable`, `Projectlike`, `Organisational`, `Eventlike`). Consumer-defined traits in their own module — `as()` works with ANY interface
- Naming convention: PascalCase (matching Java interface simple name). `is("Personable")` is case-sensitive
- Interface structure: methods return `Optional<String>` (preferred), `String`, or primitive wrappers. Method names must match property keys in the node
- Checklist for a new platform trait:
  1. Create interface in `mindmap-intelligence` (e.g., `Eventlike.java`)
  2. Create `TraitRule` implementation (e.g., `EventlikeTraitRule.java`) — `@ApplicationScoped`
  3. Add mapping to `TypeRegistry.CORE_TYPES` if this trait corresponds to a core type
  4. Add tests in `StandardTraitRulesTest`

**Length:** ~150 words.

### 2.7 Flyway Migration Patterns — V4 as Reference

**What to cover:**
- Reference: `mindmap-sqlite/.../migration/V4__subgraph_type_string.sql`
- What it does: lowercase-converts `SubgraphType` enum values in `mindmap_subgraph`, adds `(tenant_id, type)` unique index
- Pattern: data conversion (3 lines total — UPDATE + CREATE INDEX) with idempotent index creation (`IF NOT EXISTS`)
- Why the unique constraint: prevents type-name duplicates per tenant, enables TypeRegistry's race-condition recovery (`createTypeSystemSubgraph` catches constraint violation, re-queries)
- File location convention: `<module>/src/main/resources/db/<module-slug>/migration/V<N>__<description>.sql`

**Length:** ~100 words + the 3-line SQL.

---

## 3. Cross-References

The consumer guide (`docs/guides/consumer-guide.md`) already covers:
- Thing interface usage, `is()`/`as()` with code examples
- TypeRegistry API (public methods)
- SubgraphTypes constants table
- MindMapNode cognitive extension fields
- Subject bridge
- Knowledge lifecycle phases

The contributor guide section should reference the consumer guide for usage patterns rather than duplicating them. The focus is **how things work internally** and **how to extend them**.

---

## 4. What's NOT Covered

- General Flyway migration guide (version allocation, naming, cross-module coordination) — separate concern, separate issue
- MindMap SPI contract (covered by `MindMapStoreContractTest` — 72 tests)
- Consolidation pipeline internals (ConsolidationScheduler, MergeDetectionPhase, etc.) — #295 territory
- ConversationBridge internals — covered by its own section when added
- Cognitive index layer (TemporalIndex, CognitiveProfile, PerspectivalResolver) — separate contributor guide section

---

## 5. Estimated Size

~1,450 words total across 7 subsections, plus code snippets and tables. Comparable to the existing CBR internals coverage in the contributor guide.

---

## References

- `docs/guides/contributor-guide.md` — target document, current state
- `docs/guides/consumer-guide.md` — recently updated (#301, 46d1b36) — Thing model usage coverage
- `docs/specs/issue-285-knowledge-repr-model/2026-09-09-knowledge-repr-model-design.md` — authoritative design
- `thing-api/src/main/java/.../thing/ThingProxyHandler.java` — JDK Proxy handler (67 lines)
- `mindmap-intelligence/src/main/java/.../intelligence/TypeRegistry.java` — CDI bean (240 lines)
- `mindmap-api/src/main/java/.../mindmap/TraitRule.java` — SPI (8 lines)
- `mindmap-api/src/main/java/.../mindmap/DeclarativeTraitRule.java` — declarative variant (35 lines)
- `mindmap-api/src/main/java/.../mindmap/RuleCondition.java` — sealed condition hierarchy (94 lines)
- `mindmap/src/main/java/.../runtime/TraitApplicationDecorator.java` — fires rules on mutation (168 lines)
- `mindmap-intelligence/src/main/java/.../intelligence/PersonableTraitRule.java` — reference programmatic rule
- `mindmap-intelligence/src/main/java/.../intelligence/Personable.java` — reference trait interface
- `mindmap-sqlite/.../migration/V4__subgraph_type_string.sql` — reference Flyway migration
- `mindmap-inmem/src/main/java/.../inmem/InMemoryMindMapStore.java:95-113` — cache lookup pattern
- `mindmap-sqlite/src/main/java/.../sqlite/SqliteMindMapStore.java:275-284` — JOIN pattern
