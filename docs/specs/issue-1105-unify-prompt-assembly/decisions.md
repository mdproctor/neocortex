# Decisions — #1105 Unify Prompt Assembly

## D1: CognitiveLifecycle SPI placement

**Choice:** CognitionCore implements CognitiveLifecycle directly
**Alternatives:**
- Wrapper/adapter — more indirection for no gain; CognitionCore already has all 5 methods
- Skip CognitiveLifecycle — defers the cross-repo benefit that makes the unification worthwhile
**Rationale:** CognitionCore already implements initialize, tick, evaluateProactive, recordInteraction, and promptSections. Formalising this as an interface is a one-line `implements` clause.
**Trade-offs:** CognitionCore's public API surface grows by one type, but the methods are already public.
**Sources:** CognitionCore.java:746 (promptSections), CognitionCore.java:78 (initialize/tick), AvatarCognition.java (the 4 lifecycle methods)
**Exploration:** quick
**Status:** captured

## D2: Renderer-to-PromptBlock adaptation strategy

**Choice:** Bridge adapter — keep CognitionPromptRenderer returning String, centralise PromptBlock wrapping in a new default method on CognitiveLifecycle
**Alternatives:**
- Change CognitionPromptRenderer to return List<PromptBlock> — touches 24 renderer implementations for no internal benefit; PromptBlock is an eidos concern, not a cognitive one
- CognitionPromptRenderer extends PromptContributor — different context types (CognitionRenderContext vs AgentPromptContext) make subtyping wrong; would couple the cognitive SPI to eidos assembly context
**Rationale:** Cognitive renderers know content and tag. The bridge knows how to wrap that into PromptBlock(COGNITIVE, tag, salience, content). Separation of concerns — renderers stay focused on cognitive state rendering.
**Trade-offs:** Two representations exist (String internally, PromptBlock externally). The bridge is a ~10-line method with no branching.
**Sources:** CognitionPromptRenderer.java, PromptBlock.java, PromptContributor.java, DirectiveSection.java (existing tag-resolution pattern)
**Exploration:** quick
**Status:** captured

## D3: BlockTag-to-PromptTier mapping

**Choice:** All cognitive sections use PromptTier.COGNITIVE with BlockTag.name() as the tag string. No new PromptTier values needed.
**Alternatives:**
- Extend PromptTier with cognitive sub-tiers (COGNITIVE_IDENTITY, COGNITIVE_STATE, etc.) — bleeds cognitive-specific semantics into the shared eidos enum
- Add numeric ordinal to PromptBlock — changes eidos-api for a problem that only exists inside cognitive ordering
**Rationale:** PromptTier distinguishes IDENTITY/COGNITIVE/COMMAND/CONVERSATION — a prompt-structure concern. Cognitive sub-ordering (Identity 100s, State 200s, Knowledge 300s, etc.) is an internal concern handled by BlockTag.sortOrdinal(). The tag string on PromptBlock carries the semantic name for consumers who need it.
**Trade-offs:** External consumers see COGNITIVE blocks in insertion order but can't distinguish State from Knowledge by tier alone — they'd need to inspect the tag. This is acceptable because the ordering within COGNITIVE is a cognitive-pipeline concern, not a prompt-assembly concern.
**Sources:** BlockTag.java (6 tiers, 24 entries), PromptTier.java (4 values), PromptBlock.java (tier + tag)
**Exploration:** quick
**Status:** captured

## D4: CognitiveLifecycle API for PromptBlock output

**Choice:** CognitiveLifecycle exposes both promptSections() (returns List<CognitionPromptRenderer>) and contributeBlocks(CognitionRenderContext) (returns List<PromptBlock>). The latter is a default method that iterates promptSections(), calls render(), and wraps non-null results into PromptBlock.
**Alternatives:**
- Only promptSections() — pushes PromptBlock wrapping into agency's adapter, duplicating the mapping logic outside neocortex
**Rationale:** Neocortex owns the BlockTag-to-tag mapping and the salience computation. Exposing PromptBlock from CognitiveLifecycle means agency can call contributeBlocks() directly without reimplementing the conversion.
**Trade-offs:** CognitiveLifecycle depends on eidos-api's PromptBlock — but cognition-api already depends on eidos-api, so no new dependency edge.
**Sources:** CognitionCore.java:746-828 (promptSections assembly), PromptBlock.cognitive() factory
**Exploration:** quick
**Depends on:** D2 (bridge adapter strategy), D3 (tier mapping)
**Status:** captured
