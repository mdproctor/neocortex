# Unify Prompt Assembly — Neocortex Portion

**Issue:** casehubio/neocortex#1105
**Linked:** casehubio/eidos#240 (prerequisite), casehubio/agency#490 (downstream consumer)
**Date:** 2026-11-18

## Problem

Three parallel prompt assembly abstractions exist across eidos, neocortex, and agency:

| Concept | eidos-api | cognition-api | speech-api |
|---|---|---|---|
| Prompt output | `AssembledPrompt(system, user)` | _(none)_ | `AssembledPrompt(system, user, model)` |
| Section SPI | `PromptContributor → List<PromptBlock>` | `CognitionPromptRenderer → String` | `PromptSection → String` |
| Ordering | `PromptTier` (4 tiers) + tag | `BlockTag` (6 tiers, 24 entries) | _(none)_ |
| Context | `AgentPromptContext` | `CognitionRenderContext` | `PromptContext` |

`CognitionAvatarAdapter` in agency-core bridges all three via a decorator pattern that wraps a `SpeechPromptAssembler` with cognitive renderers while taking eidos `AgentDescriptor` for lifecycle. This is fragile coupling — the speech-api `PromptSection`/`PromptContext` are dead in production.

## Design

The unified design adopts eidos-api as the prompt representation home. This spec covers only the neocortex changes (step 2 of the migration order: eidos → neocortex → agency).

### 1. New `CognitiveLifecycle` interface in cognition-api

```java
package io.casehub.neocortex.cognition.prompt;

import io.casehub.eidos.api.PromptBlock;
import io.casehub.eidos.api.PromptTier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public interface CognitiveLifecycle {

    void initialize(String agentId, String tenantId);

    void tick(String agentId, String tenantId, Set<String> activeSubjects);

    @Nullable String evaluateProactive(String agentId, String tenantId,
                                        String channelContext);

    void recordInteraction(String agentId, String tenantId,
                           @Nullable String subjectId,
                           String userMessage, String response);

    List<CognitionPromptRenderer> promptSections();

    default List<PromptBlock> contributeBlocks(CognitionRenderContext context) {
        var blocks = new ArrayList<PromptBlock>();
        for (var renderer : promptSections()) {
            var content = renderer.render(context);
            if (content != null && !content.isBlank()) {
                var tag = renderer.blockTag();
                var tagName = tag != null ? tag.name() : renderer.getClass().getSimpleName();
                blocks.add(new PromptBlock(PromptTier.COGNITIVE, tagName, 0f, content));
            }
        }
        return List.copyOf(blocks);
    }
}
```

**Contract:**
- The 4 lifecycle methods (`initialize`, `tick`, `evaluateProactive`, `recordInteraction`) match `AvatarCognition` exactly, minus the speech-specific `wrapAssembler()`.
- `promptSections()` returns the internal cognitive renderers for callers that need fine-grained access.
- `contributeBlocks()` is the bridge: iterates renderers, calls `render(context)`, wraps non-null results into `PromptBlock(COGNITIVE, tagName, 0f, content)`. Default implementation — CognitionCore can override if it needs custom salience computation later.
- Zero speech types. Zero agency dependencies.

### 2. CognitionCore implements CognitiveLifecycle

```java
public class CognitionCore implements CognitiveLifecycle, CognitionStateProvider {
    // ... existing code unchanged
}
```

All 5 required methods already exist on CognitionCore:
- `initialize()` — currently called indirectly via `tick()` with empty subjects
- `tick()` — CognitionCore's tick takes `(agentId, tenantId, descriptor, subjectResolver)` which is richer; the `CognitiveLifecycle.tick(agentId, tenantId, activeSubjects)` signature requires a thin adapter (the descriptor lookup moves into CognitionCore, which already holds `lastDescriptor`)
- `evaluateProactive()` — delegates to `InnerLifeOrchestrator` via `ProactiveSpeechSupport`
- `recordInteraction()` — already public on CognitionCore
- `promptSections()` — already public, returns `List<CognitionPromptRenderer>`

**Tick signature adaptation:** CognitionCore's current `tick()` takes an `AgentDescriptor` and a `SubjectResolver`. The `CognitiveLifecycle` interface omits the descriptor because the lifecycle owner should resolve it internally (CognitionCore already caches `lastDescriptor`; the caller passes it via `setDescriptor()` or similar before ticking). Two options:

A) Add a `setDescriptor(AgentDescriptor)` method to `CognitiveLifecycle` for callers that need to push descriptor updates (agency does this every tick).
B) Make `CognitiveLifecycle.tick()` take the descriptor as a parameter.

Option B is simpler — it matches how `CognitionAvatarAdapter.tick()` already calls `core.tick(agentId, tenantId, descriptor, ...)`:

```java
void tick(String agentId, String tenantId,
          @Nullable AgentDescriptor descriptor, Set<String> activeSubjects);
```

This avoids mutable state management and matches the existing call pattern.

### 3. No changes to CognitionPromptRenderer or BlockTag

`CognitionPromptRenderer` keeps its current contract: `@Nullable String render(CognitionRenderContext context)` + `default BlockTag blockTag()`. BlockTag stays as the internal ordering enum with 24 entries across 6 tiers.

The bridge in `contributeBlocks()` maps:
- `BlockTag.name()` → `PromptBlock.tag` (e.g. "MOOD", "DRIVES", "NARRATIVE")
- `BlockTag.sortOrdinal()` → insertion order (sections are pre-sorted by CognitionCore when `directivePrompts()` is true)
- All cognitive sections → `PromptTier.COGNITIVE`

### 4. No eidos-api changes beyond #240

The existing `PromptTier.COGNITIVE` + tag string is sufficient for all 24 cognitive sub-sections. BlockTag's 6 internal tiers (Identity, State, Knowledge, Strategy, Signals, Stimulus) are an ordering concern within COGNITIVE, not a prompt-tier distinction.

Eidos#240 adds the optional `model` field to `AssembledPrompt` — the only prerequisite.

### 5. DirectiveSection integration

When `config.directivePrompts()` is true, `CognitionCore.promptSections()` wraps each renderer in `DirectiveSection` and sorts by `blockTag().sortOrdinal()`. The `contributeBlocks()` default method works identically on wrapped or unwrapped sections — it calls `render()` and reads `blockTag()` on whatever the final renderer is. The directive text becomes part of the `PromptBlock.content`.

### 6. Salience

`PromptBlock` carries a `float salience` field. Currently all blocks get `0f` (the default). This is a future extension point — when cognitive sections need salience-based pruning at the assembler level, they can:
- Override `contributeBlocks()` in CognitionCore to compute salience per section
- Or add a `default float salience()` method to `CognitionPromptRenderer`

No salience computation in this iteration.

## Scope boundary

**In scope (this issue):**
- New `CognitiveLifecycle` interface in cognition-api
- `CognitionCore implements CognitiveLifecycle`
- Tests for `contributeBlocks()` bridge logic

**Out of scope (downstream issues):**
- Agency adoption of `CognitiveLifecycle` → agency#490
- Deletion of speech-api dead types → agency#490
- Eidos `AssembledPrompt.model` field → eidos#240 (already implemented, branch exists)

## Testing

- Unit test: `CognitiveLifecycleContributeBlocksTest` — verify that `contributeBlocks()` correctly wraps renderer output into `PromptBlock(COGNITIVE, tag, 0f, content)`
- Null handling: renderers returning null are skipped
- Blank handling: renderers returning blank strings are skipped
- Tag resolution: renderers with `blockTag()` get `blockTag().name()` as tag; renderers without get `getClass().getSimpleName()`
- Integration: existing `CognitionCore` tests continue to pass — `implements CognitiveLifecycle` adds no new behaviour to existing methods

## References

- CognitionCore.java:746-828 — promptSections() assembly
- CognitionPromptRenderer.java — current SPI
- BlockTag.java — 24 entries, 6 tiers
- DirectiveSection.java — existing tag-resolution and wrapping pattern
- PromptBlock.java (eidos-api) — target type
- PromptContributor.java (eidos-api) — target SPI pattern
- AvatarCognition.java (agency speech-api) — lifecycle methods to extract
- CognitionAvatarAdapter.java (agency-core) — current fragile bridge
- casehubio/eidos#240 — AssembledPrompt model field (prerequisite)
- casehubio/agency#490 — agency adoption (downstream)
