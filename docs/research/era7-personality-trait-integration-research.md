# Era 7 — Personality Trait Integration Research

## Thesis

Characters feel more real when their cognitive defaults reflect the interaction between HOW they think (Jungian cognitive style) and WHAT personality traits they exhibit (PID-5 pathological dimensions). Era 7 integrates these two independent personality channels in the CDE, using the Big Five as a validated mediating bridge.

## Two Personality Channels

The neocortex cognitive pipeline has two independent personality representation channels:

**Channel 1: Disposition (Jungian + consensus axes).** The biography pipeline (`extract_personality.py`) assesses characters across 13 personality frameworks (MBTI, OCEAN, Belbin, DISC, SDI, Enneagram, etc.) and derives consensus DispositionAxes (socialOrient, ruleFollowing, riskAppetite, autonomy, conflictMode) plus Jungian function weights (Ti, Te, Ni, Ne, Si, Se, Fi, Fe). These feed the CDE's cognitive style derivations — how the character processes information.

**Channel 2: AMPD/PID-5 facets.** The same pipeline independently scores 13 PID-5 facets (0-100 scale) from biographical evidence. These measure pathological personality traits — maladaptive behavioral tendencies. The 13 facets are drawn from the DSM-5 Alternative Model for Personality Disorders (Krueger et al. 2012).

These channels are assessed independently by the LLM — the prompt explicitly instructs "assess independently, do not let one framework's result bias another."

## The Integration Question

Can we systematically connect PID-5 trait scores to Jungian-derived cognitive defaults to produce richer character behavior?

### What the peer-reviewed literature supports

**Direct evidence (Tier 1):**

1. **PID-5 is a valid dimensional personality measure.** Krueger, R.F. et al. (2012). Initial construction of a maladaptive personality trait model and inventory for DSM-5. *Psychological Medicine*, 42(9), 1879-1890. The foundational paper establishing PID-5's 5 domains and 25 facets.

2. **PID-5 domains are maladaptive variants of the Big Five.** Gore, W.L. & Widiger, T.A. (2013). The DSM-5 dimensional trait model and five-factor models of general personality. *Journal of Abnormal Psychology*, 122, 816-821. Convergent validity: Negative Affectivity↔Neuroticism (r=0.70), Detachment↔low Extraversion (r=-0.57), Antagonism↔low Agreeableness (r=-0.49), Disinhibition↔low Conscientiousness (r=-0.56). Psychoticism↔Openness is weak (r=0.10).

3. **MBTI dimensions map to FFM.** McCrae, R.R. & Costa, P.T. (1989). Reinterpreting the Myers-Briggs Type Indicator from the perspective of the five-factor model of personality. *Journal of Personality*, 57(1), 17-40. E/I→Extraversion, S/N→Openness, T/F→Agreeableness (inverse), J/P→Conscientiousness.

4. **Specific facet-cognition interactions:**
   - Impulsivity degrades working memory. Heydarloo et al. (2024) *Memory*; Kofler et al. (2024) *Frontiers in Psychiatry*. Consistent across ADHD, BPD, and non-clinical populations.
   - Callousness reduces empathy/relationship processing. Blair, R.J.R. (2005, 2008, 2013) *Nature Reviews Neuroscience*. Amygdala-mediated, meta-analyzed.
   - Anxiousness creates attentional bias toward threat. Bar-Haim, Y. et al. (2007) *Psychological Bulletin*, 133(1), 1-24. 172-study meta-analysis, d=0.45.

5. **Jungian types relate to DSM personality pathology.** Ekstrom, S. Jung's typology and DSM-III personality disorders. *Journal of Analytical Psychology*. Maps Jung's eight function-attitudes to personality disorder features (e.g., extreme Extraverted Feeling → histrionic traits, extreme Introverted Thinking → schizoid traits).

### What the literature does NOT support

**No direct peer-reviewed mapping exists from PID-5 facets to Jungian cognitive functions.** The two systems operate in separate research paradigms:

- PID-5: Empirical factor analysis, dimensional severity ratings, clinical populations
- Jungian typology: Qualitative clinical observation, structural archetypes, developmental theory

Researchers have not published a unified model connecting these systems. The reasons include:
- Methodological divide between empirical psychometrics and analytical psychology
- Jungian cognitive functions lack standardised peer-reviewed psychometric validation at the function level (Ti vs Te, Ni vs Ne)
- The 8-function model's evidence base is weaker than the dimension-level (E/I, T/F, S/N, J/P) evidence

### Where we extend beyond literature (Tier 2)

**The Big Five bridge.** Both PID-5 and MBTI have validated mappings to the FFM. We use the FFM as a statistical intermediary to infer how PID-5 facet elevations interact with Jungian-derived cognitive dimensions. This transitive reasoning is grounded in two peer-reviewed links but the composition itself has not been empirically tested as a unified model.

**Function-level extensions.** The MBTI→FFM mapping (McCrae & Costa 1989) operates at the dimension level (E/I, S/N, T/F, J/P), not the function level (Ti vs Te, Ni vs Ne). Our modulations that target specific function combinations (e.g., "Withdrawal reduces connective bias derived from sensing/intuition balance") extend beyond what the dimension-level bridge strictly validates.

**The reasoning chains we use:**

| Modulation | Chain | Evidence gap |
|---|---|---|
| Emotional Lability → amplified affect sensitivity | High lability = high Neuroticism (FFM) = amplified emotional reactivity | Neuroticism↔Emotional Lability is peer-reviewed (r=0.70). Neuroticism→affect sensitivity is a reasonable extension but not directly tested as a CDE modulation. |
| Risk Taking → reduced fear weight | High risk = Disinhibition domain → reduced threat sensitivity | Risk Taking is a PID-5 facet. Its relationship to appraisal fear weighting is inferred from the construct definition, not from a specific study of fear appraisal weights. |
| Impulsivity → amplified urgency | Disinhibition domain → immediate threat/opportunity weighting | Impulsivity→urgency is face-valid and consistent with the construct, but no study directly tests this in an appraisal weight context. |
| Anxiousness → narrowed associative reach | Negative Affectivity → attentional narrowing | Bar-Haim (2007) shows attentional bias in anxiety. The extension to "associative reach" (a cognitive exploration parameter) is our interpretation, not their finding. |
| Withdrawal → reduced connective bias | Detachment → low Extraversion → fewer social connections | The connection is plausible through the FFM bridge. That it specifically affects mental graph connectivity is our architectural decision. |

## Why This Is Appropriate for Our Use Case

This synthesis is explicitly NOT a clinical diagnostic tool. It is a character simulation architecture where the validation criterion is behavioral coherence — do characters feel real and internally consistent?

The Google AI synthesis we consulted framed it well: "Use the unified model as a heuristic tool for self-reflection, depth-oriented coaching, or creative writing, where nuance and narrative coherence matter most. Avoid using it for clinical diagnosis or formal psychological assessment."

Our use case — cognitive agent characters in a social simulation — falls squarely in the "narrative coherence" category. The eval framework (personality differentiation scores, behavioral coherence judges, drive expression ratings) provides empirical validation of whether the integration produces better characters, independent of whether the individual modulations are clinically validated.

## Design Principles

1. **Transparency over confidence.** Every modulation documents its evidence tier. Tier 1 cites a paper. Tier 2 documents the reasoning chain and explicitly marks the evidence gap.

2. **Complementary, not competing.** PID-5 facets modulate Jungian-derived values. They do not replace them. The Jungian channel measures cognitive style (HOW). PID-5 measures trait pathology (WHAT). Both are needed.

3. **Conservative modulation weights.** Tier 2 modulations start with modest effect sizes (0.2 weight) and are tuned via eval results. A facet score of 0.5 (population mean) produces zero modulation.

4. **Eval-validated.** The test is not "is this clinically correct?" but "does this produce more coherent, differentiated characters?" The eval framework answers that question empirically.

## What Changed from Prior Eras

| Era | Personality representation | Resolution |
|---|---|---|
| Era 1-3 | Jungian functions + OCEAN | Categorical (3-4 levels) |
| Era 4-5 | + DispositionAxes consensus | Categorical (3-5 values per axis) |
| Era 6 | + AMPD/PID-5 facets (expression styles, search budget, recurring thoughts) | Continuous [0,1] for 5 methods, categorical for 6 |
| **Era 7** | **Bridge fix + full facet integration + Jungian×PID-5 modulation** | **Continuous for all methods, dual-channel interaction** |

## Implementation Status (2026-11-13)

All design goals achieved. Implementation complete across three repos (eidos, neocortex, examples).

### Pipeline unification
- `AmpdFacet` record added to `AgentDisposition` (eidos-api). Backward-compatible 10-arg constructor preserved.
- `EidosCognitiveProfileBridge.toDescriptorView()` maps `AmpdFacet` → `PersonalityFacet` with title-case normalization. Origin tagged as `"eidos"`.
- Pipeline path: Engram YAML → Eidos `AgentDisposition.ampdFacets` → Bridge → `DescriptorView.personalityFacets` → CDE.

### CDE modulations implemented
- **Category A (6 methods):** deriveMoodBaseline, deriveCuriosity, deriveCbrStrategy, deriveSocialCognition, deriveHabituationConfig, deriveRegulationDefaults — all upgraded to accept and apply PID-5 facet modulations on top of axes-derived values.
- **Tier 1 (3 modulations):** Impulsivity→WM (deriveCapacity), Callousness→relationship bias (deriveExtractionBias), Anxiousness→fear (deriveAppraisalWeights).
- **Tier 2 (5 modulations):** Emotional Lability→affect sensitivity, Risk Taking→fear (inverse), Impulsivity→urgency, Anxiousness→associative reach, Withdrawal→connective bias.
- All modulations use `(facetScore - 0.5) * weight` delta pattern with weight=0.2 (modest initial effect).
- Empty facets = zero modulation = backward-compatible behavior.

### Derivation pipeline observability (#960)
- `DerivationObserver` interface in cognitive-observability-api — consistent with existing `CompactionObserver`, `MemoryRecallObserver` patterns.
- `DerivationTrace` journal accumulator captures: PipelineStart (full descriptor context), StageComputation (base values → facet effects → final values per method), DataFlow (cross-stage value propagation), PipelineComplete.
- `eventRef` on every entry for script-position correlation (tick/prompt correlation).
- Stage filter predicate for constraining capture scope.
- `derive(DescriptorView, String eventRef, DerivationObserver observer)` overload threads observer through all methods.

### Test coverage
- 23 PID-5 facet modulation tests (direction assertions per facet per method)
- 7 derivation trace tests (journal capture, query, filtering, cross-stage flow)
- 512 eidos API tests, 447 cognitive-index tests, 7 bridge tests, 712 wacky-manor fast tests — all pass with 0 regressions.

### Remaining work
- neocortex#962: Instrument remaining 4 CDE methods with journal (searchBudget, pool, recurringThought, expressionStyles)
- neocortex#963: Bridge should fire onFacetMapped events
- examples#207: Add ampdFacets to wacky-manor character engrams
- neocortex#964: YAML round-trip test for ampdFacets deserialization
- neocortex#961: Runtime cognitive pipeline journal (CAPS, regulation, consolidation)

## Open Questions

1. **Should `derivePersonality` (the foundation derivation) get PID-5 modulation?** Currently excluded to avoid cascading effects. May revisit after eval validation of downstream modulations.

2. **Are 13 facets sufficient?** The full PID-5 has 25 facets. The biography pipeline scores 13. Missing facets include Anhedonia, Depressivity, Restricted Affectivity, Separation Insecurity, Submissiveness, and the Psychoticism facets. Some of these (Anhedonia, Depressivity) could add richness.

3. **Should Tier 2 modulations be promoted to Tier 1?** If eval results show significant coherence improvement from a Tier 2 modulation, and subsequent literature search finds supporting evidence, the modulation can be reclassified.
