# Coherence Eval Re-run Findings — #1003

**Date:** 2026-11-16
**Parent epic:** #841
**Related fixes:** #983 (SHAME affiliation suppression), #984 (Drive saturation clamping), #985 (Curiosity attentional channel weight), #989 (Tension threshold eval), #990 (LlmSituationClassifier silent degradation)

## Summary

Ran PipelineCoherenceEvalTest scenarios after #983/#984/#985/#989/#990 fixes. Both scenarios pass the 4.0 coherence gate. Drive modulation issues remain in the shame scenario — AUTONOMY saturates at 1.0 despite #984's tanh fix.

## Results

### Curiosity Exploration Chain

| Transition | Score | Notes |
|---|---|---|
| observation-to-appraisal | 4.3 | HOPE (0.69) dominant, ATTENDING (0.88) strongest tendency. Minor: no anxiety despite uncertainty in observation |
| appraisal-to-drives | 4.3 | Curiosity + Competence activated correctly. Missing: affiliation signal from narrative's social sharing impulse |
| drives-to-channel-weights | 3.2 | Attentional (0.292) second to ActionTendency (0.313). Barely clears 3.0 gate |
| trace-to-prompt | 4.3 | Faithful narrative preservation. SEARCH_BUDGET section under-signals investigation depth |
| **Mean** | **4.025** | **Passed** |

**Finding:** drives-to-channel-weights at 3.2 indicates #985's attentional channel improvement is working but the effect is marginal. ActionTendency dominates because competence is a co-activated drive. The Attentional channel weight (0.292) is elevated over Somatic (0.188) which is the right direction, but the channel weighting doesn't make curiosity's attentional bias dominant when competence is also active.

### Shame Regulation Chain

| Transition | Score | Notes |
|---|---|---|
| observation-to-appraisal | 4.7 | SHAME (0.84), FEAR, REPROACH (0.83) correctly identified. AVOIDANCE (0.88) primary tendency |
| appraisal-to-regulation | 4.8 | ATTENTIONAL_DEPLOYMENT correctly selected at intensity >0.75. 38% effectiveness, intensity 0.84→0.52 |
| regulation-to-prompt | 3.8 | Appraisal narrative faithful, but DrivePromptSection shows AFFILIATION=0.1, AUTONOMY=1.0 |
| full-trace-consistency | 2.8 | Drive section contradicts emotional state. AFFILIATION should be dominant, AUTONOMY should not saturate |
| **Mean** | **4.025** | **Passed** |

**Finding — AUTONOMY saturation (#984 incomplete):**
#984 applied `Math.tanh()` to accumulated appraisal deltas in `AppraisalDriveModulator.modulate()`, reducing FEAR(0.8)+SHAME(0.84) co-occurrence from raw 0.82 to tanh(0.82)≈0.675. This fixes the specific linear-summation bug.

However, `DriveComposer.compose()` still linearly accumulates across three independent modulation sources:
1. **Mood modulation** (line 72-74): `mood.dominance() < 0` adds `|dominance| × moodPleasureModulation` to AUTONOMY. Shame's PAD dominance of -0.50 contributes ~0.15
2. **Mood arousal**: high arousal from shame adds `arousal × moodArousalModulation` (~0.17)
3. **Appraisal modulation**: ~0.675 after tanh

Total before personality: ~1.0. After clamping: 1.0.

The fix addressed one accumulation layer (appraisal) but not the cross-layer total. The dominance-based autonomy boost at `DriveComposer:72-74` is the additional contributor — designed to model compensatory autonomy-seeking when an agent feels low control, but in high-arousal shame scenarios it combines with appraisal to overflow.

**Finding — AFFILIATION suppressed (test setup, not #983):**
AFFILIATION=0.1 is primarily a test setup issue. `AffiliationDrive` returns 0.0 when `UserModelOrchestrator.activeProfiles()` is empty (no seeded user profiles in the eval test). The 0.1 comes from personality modulation (cooperative disposition → small positive SOCIAL_ORIENTATION weight). Net appraisal delta for AFFILIATION is slightly negative (REPROACH maps to AFFILIATION REDUCE=-0.2). #983's fix is not testable through this eval path — would need a test that seeds user profiles.

## TensionThresholdSensitivityEvalTest

**Status:** Passed — 8/8 tests, 0 failures, 1171 seconds (19.5 minutes).

**Results:**
- All tests passed including `interProfileVariance_reportedPerThreshold` which asserts inter-profile cosine distance > 0.05 at each threshold — confirming non-zero, personality-differentiated activation vectors
- `tensionScores_matchExpectedValues` — tension computation correct for all 7 profiles
- `routingCounts_varyAcrossThresholds` — routing counts: 6 at threshold 0.3, 6 at 0.5, 3 at 0.7 (expected)
- `boundaryProfiles_classificationQuality` — dual activation (approach+inhibition) detected for competitive-strict
- `debugSingleClassification` — 131s for a single focused classification, produced valid results

**Note:** Stdout sensitivity report (routing table and stability metrics) was lost due to surefire fork timeout after test completion. All assertion-based validations passed — the data was valid but the detailed report was not captured. The fork timeout is a known issue with `ClaudeAgentClient` session cleanup.

## Issues Filed

- **#1009** — DriveComposer cross-layer AUTONOMY saturation (S/Med)
- **#1010** — Eval test seeding for AFFILIATION drive testing (XS/Low)

## Recommendations

1. **Cross-layer tanh** (#1009) — Apply diminishing returns to the total accumulated intensity in `DriveComposer.compose()` (across mood + personality + appraisal), not just within the appraisal layer. This would prevent AUTONOMY saturation while preserving individual modulation sensitivity.

2. **Eval test seeding** (#1010) — The shame scenario needs user profiles seeded for AFFILIATION to be meaningful. Without them, AFFILIATION will always be near-zero regardless of #983's fix. Create an `EvalMindMapSeeder.seedRelationships()` helper.

3. **Channel weight gate** — drives-to-channel-weights at 3.2 is marginal. Consider whether the 3.0 gate should be raised to 3.5 after the channel weighting architecture stabilises.
