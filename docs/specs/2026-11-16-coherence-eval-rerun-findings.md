# Coherence Eval Re-run Findings

**Date:** 2026-11-16
**Parent epic:** #841

---

## Re-run #1014 — Post #1005/#1009/#1000

**Related fixes:** #1005 (SHAME→AFFILIATION secondary boost), #1009 (Cross-layer tanh in DriveComposer), #1000 (AVOIDANCE/ANTAGONISM channel weights), #1010 (seedUserProfile eval harness)

### Summary

Re-ran `shameRegulationChain` and `curiosityExplorationChain` after the three drive-layer fixes. Both scenarios show clear improvement. The shame scenario's `full-trace-consistency` jumps from 2.8→4.2 — the primary signal that #1009 (cross-layer tanh) and #1005 (SHAME→AFFILIATION boost) resolved the drive coherence problems. User profile seeding via #1010's `seedUserProfile()` gives AFFILIATION a real baseline signal.

### Shame Regulation Chain — Mean 4.53 (was 4.025)

| Transition | Score (now) | Score (#1003) | Delta | Notes |
|---|---|---|---|---|
| observation-to-appraisal | 4.6 | 4.7 | -0.1 | SHAME(0.88), FEAR(0.93), REPROACH(0.90). AVOIDANCE(0.95). Minor: guilt absent as named emotion despite being most natural response |
| appraisal-to-regulation | 4.7 | 4.8 | -0.1 | ATTENTIONAL_DEPLOYMENT correct at intensity 0.93. Peak 0.93→0.60. Effectiveness 0.35. Regulation narrative quality high |
| regulation-to-prompt | 4.6 | 3.8 | **+0.8** | AFFILIATION=1.0 (was 0.1), AUTONOMY=0.8 (was 1.0). Drive profile now matches emotional context |
| full-trace-consistency | 4.2 | 2.8 | **+1.4** | No section contradicts another. Consistent emotional tone across all prompt sections |
| **Mean** | **4.53** | **4.025** | **+0.50** | |

**What changed — drive profile:**
- **AFFILIATION 0.1→1.0:** Two factors: (1) seeded UserProfile gives AffiliationDrive a baseline signal — "close-friend" profile with stale interaction triggers "1 of 1 relationships neglected", (2) #1005 SHAME→AFFILIATION secondary boost compounds on the non-zero baseline. Judge noted: "Affiliation at 1.0 is exactly right for a betrayal-of-closest-friend scenario."
- **AUTONOMY 1.0→0.8:** #1009's cross-layer tanh prevents mood+personality+appraisal overflow. 0.8 is psychologically defensible as reactive escape/control drive (consistent with AVOIDANCE tendency 0.95). Judge noted the justification text "elevated by emotional response" is thin — the narrative shows constriction and loss of agency, which could suppress or inflate autonomy reactively.
- **COMPETENCE 0.6:** Judge flagged this as insufficiently grounded — shame is a moral/relational failure, not a skill failure, so competence activation needs better drive-specific justification. The generic "elevated by emotional response" annotation doesn't distinguish drive-emotion pathways.

**Cross-slot note (regulation transitions):** The regulation-specific transitions (observation-to-appraisal, appraisal-to-regulation) scored 4.6/4.7 — stable from #1003's 4.7/4.8. These are within LLM eval variance. Regulation strategy selection and narrative rendering are slot 196's scope (#595/#821). The improvements here are entirely in drive-layer transitions (regulation-to-prompt, full-trace-consistency) which are this slot's scope.

### Curiosity Exploration Chain — Mean 4.30 (was 4.025)

| Transition | Score (now) | Score (#1003) | Delta | Notes |
|---|---|---|---|---|
| observation-to-appraisal | 4.6 | 4.3 | +0.3 | HOPE(0.72), JOY(0.30). ATTENDING(0.92), APPROACH(0.38). Excellent Loewenstein gap-proximity narrative |
| appraisal-to-drives | 4.3 | 4.3 | 0.0 | Curiosity sole activated drive. Missing: channel/intensity detail in output |
| drives-to-channel-weights | 3.8 | 3.2 | **+0.6** | Attentional(0.297) elevated over Somatic(0.186). ActionTendency(0.309) still dominant |
| trace-to-prompt | 4.5 | 4.3 | +0.2 | Narrative preserved faithfully. SEARCH_BUDGET slightly undersells investigation urgency |
| **Mean** | **4.30** | **4.025** | **+0.28** | |

**What changed — channel weights:**
- `drives-to-channel-weights` 3.2→3.8 — #1000's AVOIDANCE/ANTAGONISM channel weight mappings give the weighter more signal. Attentional (0.297) vs ActionTendency (0.309) gap narrowed from the #1003 baseline. The judge noted the modulation is "visible but not sharply attentional-dominant." Competence no longer co-activates alongside curiosity (was co-activated in #1003), which reduces the ActionTendency pull — but the single-drive curiosity profile still doesn't produce a clear attentional spike.

**Remaining observation:** The SEARCH_BUDGET section renders "doesn't demand deep investigation" despite ATTENDING at 0.92 and HOPE at 0.72. The search budget prompt renderer may under-weight the appraisal intensity signal when translating to prose.

### Full Suite Run — All 5 Scenarios

Second run of all 5 scenarios to establish complete floor analysis:

| Scenario | Transition | Score | Status |
|---|---|---|---|
| Curiosity | observation-to-appraisal | 4.3 | At risk |
| Curiosity | appraisal-to-drives | **3.3** | FAIL |
| Curiosity | drives-to-channel-weights | **3.8** | FAIL |
| Curiosity | trace-to-prompt | 4.5 | OK |
| Curiosity | **Mean** | **3.97** | **FAIL** |
| Shame | observation-to-appraisal | 4.6 | OK |
| Shame | appraisal-to-regulation | 4.6 | OK |
| Shame | regulation-to-prompt | 4.5 | OK |
| Shame | full-trace-consistency | 4.3 | At risk |
| Shame | **Mean** | **4.50** | Pass |
| Security | observation-to-appraisal | 4.3 | At risk |
| Security | appraisal-to-channel-weights | **3.8** | FAIL |
| Security | channel-weights-to-prompt | 4.3 | At risk |
| Security | **Mean** | **4.13** | Pass |
| Trajectory | 10-tick coherence | **3.8** | FAIL |
| CAPS | graduated-nodes-to-caps-input | 4.6 | OK |
| CAPS | caps-input-to-settling | **2.8** | FAIL |
| CAPS | settling-to-behavioral-prompt | **3.8** | FAIL |
| CAPS | full-consolidation-coherence | **3.2** | FAIL |
| CAPS | **Mean** | **3.60** | **FAIL** |

**Summary:** 8 transitions below 4.0, 4 transitions in the 4.0-4.3 risk zone. 3 of 5 scenarios fail the gate.

### Root Cause Analysis — 6 Structural Problems

| # | Problem | Affected transitions | Root cause |
|---|---|---|---|
| 1 | Channel weight distribution too flat | drives-to-channel-weights (3.8×3) | Additive modulation + normalization compresses polarization |
| 2 | Drive output is a black box | appraisal-to-drives (3.3/4.3) | `activatedDriveCategories()` = `Set<String>`, no sub-threshold trace |
| 3 | SearchBudget prose ignores appraisal | trace-to-prompt, channel-weights-to-prompt | Renderer has no access to arousal/intensity |
| 4 | Drive annotations lack provenance | full-trace-consistency (4.2-4.3) | DriveComposer discards modulation source info |
| 5 | LLM appraisal under-specifies OCC | observation-to-appraisal (4.3) | Missing guilt, interest, distress; empty targets |
| 6 | CAPS settling diverges on social-threat | caps-input-to-settling (2.8) | Oscillation between withdrawal and reactive aggression |
| 7 | Drive activation has no temporal memory | trajectory (3.8) | Per-tick evaluation with no smoothing |

### Issues Filed

| Issue | Title | Scale | Complexity | Target transitions |
|---|---|---|---|---|
| #1020 | Channel weight polarization | M | Med | drives-to-channel-weights, appraisal-to-channel-weights |
| #1021 | Drive activation trace enrichment | M | Med | appraisal-to-drives |
| #1022 | SearchBudget intensity coupling | S | Med | trace-to-prompt, channel-weights-to-prompt |
| #1023 | Drive annotation provenance | S | Med | full-trace-consistency |
| #1024 | LLM appraisal vocabulary | S | Low | observation-to-appraisal |
| #1025 | AppraisalDriveModulator secondary boosts | S | Low | appraisal-to-drives |
| #1026 | Drive temporal smoothing | S | Med | trajectory |
| #1027 | CAPS settling convergence | M | High | caps-input-to-settling, full-consolidation-coherence |

**Recommended execution order:**
1. #1024 (LLM vocabulary) — upstream, improves all downstream transitions
2. #1020 (channel polarization) — fixes 3 deterministic 3.8s
3. #1021 (drive trace enrichment) — foundational API change, unblocks #1022 and #1023
4. #1027 (CAPS settling) — fixes the worst scenario (mean 3.60)
5. #1025 (secondary boost mappings) — quick win alongside #1024
6. #1026 (drive temporal smoothing) — trajectory-specific
7. #1023 (drive provenance) — builds on #1021
8. #1022 (SearchBudget coupling) — builds on #1021

---

## Run #1003 — Post #983/#984/#985/#989/#990 (historical baseline)

**Related fixes:** #983 (SHAME affiliation suppression), #984 (Drive saturation clamping), #985 (Curiosity attentional channel weight), #989 (Tension threshold eval), #990 (LlmSituationClassifier silent degradation)

### Summary

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

## Post-Fix Note: Double-Tanh Compression

After #1009, the drive modulation pipeline has two tanh layers:
1. `AppraisalDriveModulator.modulate()` (line 37) — compresses per-emotion delta accumulation (prevents FEAR+SHAME stacking)
2. `DriveComposer.compose()` (line 46) — compresses cross-source total (prevents mood+personality+appraisal overflow)

Appraisal contributions pass through both: `tanh(moodDelta + personalityDelta + tanh(rawAppraisalDelta))`. This makes appraisal slightly more conservative than mood/personality modulation. If appraisal needs stronger influence in future tuning, the BOOST/SECONDARY_BOOST constants in AppraisalDriveModulator can be increased — the double-tanh provides a safety margin rather than a hard ceiling.

## Issues Filed

- **#1009** — DriveComposer cross-layer AUTONOMY saturation (S/Med) — ✅ resolved, AUTONOMY 1.0→0.8
- **#1010** — Eval test seeding for AFFILIATION drive testing (XS/Low) — ✅ resolved, AFFILIATION 0.1→1.0

## Recommendations

1. ~~**Cross-layer tanh** (#1009)~~ — ✅ Done. Double-tanh prevents AUTONOMY saturation.

2. ~~**Eval test seeding** (#1010)~~ — ✅ Done. Shame scenario seeds a UserProfile for "close-friend".

3. **Channel weight gate** — drives-to-channel-weights improved from 3.2→3.8 but ActionTendency (0.309) still dominates Attentional (0.297). Consider whether the attentional channel should receive a stronger bias when curiosity is the sole active drive.

4. **Drive annotation quality** — The DrivePromptSection's generic "elevated by emotional response" annotation is flagged in both scenarios. Drive-specific justification text (e.g. "SHAME→AFFILIATION: relationship threat", "low control→AUTONOMY: compensatory") would improve full-trace coherence scores further.

5. **Search budget intensity coupling** — SEARCH_BUDGET under-signals investigation urgency when ATTENDING intensity is high (0.92). The renderer's prose mapping may need to weight appraisal intensity more directly.
