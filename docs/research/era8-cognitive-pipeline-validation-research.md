# Era 8 — Cognitive Pipeline Validation Research

## Thesis

A cognitive agent architecture is only as real as its measurable behavioral differentiation. Era 8 establishes the validation methodology: systematic divergence testing of all 48 pipeline capabilities, proving that personality differences produce observable behavioral differences at every layer of the cognitive stack.

## The Measurement Gap

Eras 1-7 built the cognitive pipeline: CAPS settling (#39), Theory of Mind (#25), emotion regulation (#27), situational personality (#46), PID-5 facet integration, drive composition, search budgets, and 40+ other capabilities. Each was designed to produce personality-dependent output. But only 6 of 48 capabilities had tests proving personality differentiation (see Capability Audit below).

The gap is not in the pipeline's design — it's in the evidence. Without systematic divergence tests, we cannot distinguish between:
- A capability that produces personality-dependent output (working as designed)
- A capability that is correctly wired but produces identical output for all personalities (inert)
- A capability that is not wired at all (dead code)

## Capability Audit

Full audit conducted 2026-11-19. 48 distinct capabilities identified across 6 pipeline layers.

### Coverage before era 8

| Layer | Capabilities | Tested | Coverage |
|-------|-------------|--------|----------|
| Derivation | 15 | 2 | 13% |
| Tick pipeline | 14 | 2 | 14% |
| Prompt rendering | 9 | 1 | 11% |
| Consolidation | 7 | 1 | 14% |
| Multi-agent (ToM) | 4 | 0 | 0% |
| Multi-tick (regulation) | 5 | 0 | 0% |
| **Total** | **48** | **6** | **12.5%** |

### PID-5 facets that modulate the pipeline

| Facet | Capabilities modulated |
|-------|----------------------|
| Anxiousness | mood baseline, curiosity channel (I/D-type), search convergence, threat CPT priors, recurring thought escalation, habituation threshold |
| Risk-Taking | mood pleasure, search laterality, curiosity exploration weight, habituation rate, coping confrontation |
| Emotional Lability | mood arousal, recurring thought sensitivity |
| Withdrawal | search depth, social engagement |
| Callousness | ToM distress-avoidance coupling, expression styles |
| Hostility | coping confrontation, appraisal threat sensitivity |
| Suspiciousness | ToM evidence scaling (attributional bias), trust rate |
| Impulsivity | working memory span, search breadth, coping tendency |
| Intimacy Avoidance | affiliation drive damping |
| Manipulativeness | expression styles, social strategy |

### Disposition axes that modulate the pipeline

| Axis | Capabilities modulated |
|------|----------------------|
| socialOrient | mood arousal, trust rate, conflict interpretation, social centrality |
| ruleFollowing | CBR retrieval mode, repetition tolerance, coping suppression |
| riskAppetite | mood pleasure, habituation rate, coping confrontation vs avoidance |
| autonomy | mood dominance, regulation reappraisal weight, coping autonomy |
| conflictMode | conflict interpretation, regulation weights, coping defaults |

## Test Architecture

### Design principles

1. **Real pipeline, no mocks.** All tests run on `CognitionTestHarness` with `InMemoryCapsEngine`, real `CognitiveDerivationEngine`, real consolidation phases. In-memory stores behave identically to production — they are implementations, not mocks.

2. **Contrastive personality pairs.** Every test uses the same two agents: open/bold (low anxiousness, high risk-taking, cooperative) vs cautious/avoidant (high anxiousness, low risk-taking, independent). These produce maximal divergence across the most pipeline capabilities.

3. **Observable assertions.** Each test asserts a measurable difference — numeric inequality, different enum values, different string content. No "not null" assertions that pass for any value.

4. **Deterministic.** No LLM in the loop. Pipeline output is fully determined by personality inputs + observation text + MindMap state. Tests are repeatable.

### Test structure

```
cognition/src/test/java/.../eval/divergence/
  DivergenceFixtures.java          — shared personality pairs and builder helpers
  DerivationDivergenceTest.java    — 9 tests (derivation-only, no tick)
  AppraisalDivergenceTest.java     — 6 tests (single tick)
  ConsolidationDivergenceTest.java — 5 tests (seeded MindMap + consolidation)
  PromptSectionDivergenceTest.java — 8 tests (tick + render)
  TheoryOfMindDivergenceTest.java  — 4 tests (multi-agent)
  EmotionRegulationDivergenceTest.java — 4 tests (multi-tick)
```

7 test classes. 36 new tests + 5 migrated = 41 total. Covers all 48 capabilities.

### Epic and issues

- Epic: casehubio/neocortex#1175
- DivergenceFixtures + Derivation: #1176
- Appraisal: #1177
- Consolidation: #1178
- Prompt sections: #1179
- Theory of Mind: #1180
- Emotion regulation: #1181
- Cleanup (delete PersonalityDifferentiationTest): #1182

## Scenario Evaluation Strategy

### Two-layer validation

The divergence tests prove the pipeline **produces** differentiated output. Scenario evaluation proves the LLM **uses** that differentiated output to generate distinguishable character behavior.

| Layer | What it proves | Method | Deterministic? |
|-------|---------------|--------|---------------|
| Divergence tests | Pipeline produces different signals | CognitionTestHarness assertions | Yes |
| Scenario evaluation | LLM generates different behavior | Short-run + LLM classifier scoring | No |

### Short-run scenario methodology

From the drive expression eval work (era 4-5): targeted scenarios with 10-20 events, each designed to activate a specific cognitive pathway. Scored by LLM classifier. Golden outputs crafted for underperforming cases.

### Threat scenario design (planned)

The existing scenario repertoire exercises cooperative/competitive dynamics — roughly 40% of the pipeline. Threat pathways (ToM under deception, trust evolution under betrayal, CAPS under high-threat/high-uncertainty) remain unexercised.

Planned short-run scenarios:
- Betrayal discovery (ToM + trust evolution)
- False accusation (suspiciousness evidence weighting + defense)
- Alliance fracture (drive competition: loyalty vs self-preservation)
- Deceptive cooperation (CAPS threat/reward competition)
- Trust erosion over time (Bayesian posterior updating)

## Progress

### Landed

| Issue | Description | Date |
|-------|------------|------|
| #1160 | CognitionTestHarness CAPS settling + behavioral attractor divergence | 2026-11-19 |
| #1163 | EffectiveCognitiveDefaultsTest assertion fix (situational origin format) | 2026-11-19 |

### Blocked

| Issue | Description | Blocker |
|-------|------------|---------|
| #1162 | MutationContext → ScopedValue migration | maven.compiler.release=21, needs 25+ (#1173) |

### Open — divergence test suite

| Issue | Description | Priority | Status |
|-------|------------|----------|--------|
| #1176 | DivergenceFixtures + DerivationDivergenceTest | impl-first | queued |
| #1177 | AppraisalDivergenceTest | P2 | queued |
| #1178 | ConsolidationDivergenceTest | P2 | queued |
| #1179 | PromptSectionDivergenceTest | P3 | queued |
| #1180 | TheoryOfMindDivergenceTest | P1 (highest risk) | queued |
| #1181 | EmotionRegulationDivergenceTest | P1 (highest risk) | queued |
| #1182 | Delete PersonalityDifferentiationTest | cleanup | queued |

### Open — supporting

| Issue | Description | Status |
|-------|------------|--------|
| #1171 | CAPS settling trajectory assertions | queued |
| #1172 | CognitionTestHarness trajectory accessor | queued |
| #1173 | Raise maven.compiler.release to 25+ | queued |
| #1174 | InnerLifeOrchestratorTest NPE | queued |

## Key Finding: The Tenant ID Trap

During #1160 implementation, CAPS initialization in the harness Builder used hardcoded `"test-tenant"` while `consolidate()` runs with the caller's tenant ID (`"test"`). Both agents were silently auto-initialized with identical default axes, making all CAPS settling results identical regardless of personality.

**Fix:** Lazy initialization in `consolidate()` — checks each agent's CAPS state on first call and initializes with their descriptor's disposition axes using the caller's actual tenant ID.

**Lesson:** Cross-component integration tests catch wiring bugs that unit tests cannot. The CAPS engine, the derivation engine, and the consolidation phase each worked correctly in isolation. The failure was in how they were connected — a tenant ID mismatch invisible to any single component's tests.
