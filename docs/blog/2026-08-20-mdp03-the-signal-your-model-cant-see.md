---
layout: post
title: "The Signal Your Model Can't See"
date: 2026-08-20
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [ml, dual-encoder, modality-dropout, multi-source, classification, confusion-matrix]
series: issue-202-retrain-strategy-classifier
---

Continues from [The Format Nobody Documented](2026-08-20-mdp02-the-format-nobody-documented.md).

The previous session ended with a clear next step: replace BatchNorm with LayerNorm. Two lines. That part was easy. What followed was not.

LayerNorm stabilised training — no more NaN, no more majority-class collapse. All three matchups train cleanly on the merged data. vs_zerg and vs_protoss cleared 65% (66.5% and 69.9%). vs_terran sat at 51.4%, refusing to budge. The confusion matrix showed why: 44% of MECH_PUSH samples were misclassified as BIO_TIMING. Every class collapsed into BIO_TIMING like a gravitational attractor — 38% of BANSHEE_HARASS, 39% of MACRO_ECONOMY, 35% of RUSH.

I broke the confusion matrix down by data source, and the picture changed completely.

SC2EGSet — both player and opponent features available — scored 54.3%. SpawningTool — opponent features only — scored 58.2%. MSC — player features only — scored **42.3%**. MECH_PUSH on SpawningTool: 67.6%. MECH_PUSH on MSC: 10.1%.

The labels describe the opponent's strategy. The opponent's features directly encode what we're trying to classify — their buildings, their units, their economy. Player features encode your response to the opponent, which is a noisy proxy at best. MSC has only player features. It's trying to infer "the Terran went mech" from "the Zerg built roaches." A third of the test set is dragging overall accuracy because the signal isn't there.

The single Conv stack was processing all 239 features as a flat vector. It had no way to learn that opponent features are the primary signal and player features are a fallback. I split the model into two separate Conv encoders — one for each feature block — with a learned sigmoid gate that weights how much to trust each stream based on availability flags. When the opponent is visible, lean on the opponent encoder. When you only have player data, fall back to the player encoder at lower confidence.

The gating needed careful handling. Simple additive fusion — `x = player + opponent` when both available, `x = player` when only player — creates a 2x magnitude discontinuity. Samples with both modalities activate the downstream layers at double scale. A learned gate with sigmoid activation lets the model discover the right weighting, which turned out to be asymmetric: opponent features get heavier weight when available.

The other surprise was modality dropout. The default 20% drop rate meant the model saw 80% complete samples during training, but only ~40% at test time. Bumping dropout to 40% — matching the actual data distribution — improved all three matchups. Label smoothing at 0.1 compounded with this, telling the model not to overfit to the noisier player-only labels.

After all the architectural changes — dual-encoder, learned gating, feature projection from 119 raw features to 64 learned dimensions, residual attention with 4 heads, label smoothing, calibrated dropout — the results:

| Matchup | Start | End |
|---------|-------|-----|
| vs_terran | 51.4% | 52.8% |
| vs_zerg | 66.5% | 70.2% |
| vs_protoss | 69.9% | 74.0% |

vs_zerg and vs_protoss improved substantially. vs_terran barely moved. The per-class distribution is more balanced now — MECH_PUSH went from 42.4% to 54.8%, but BIO_TIMING dropped from 74.7% to 58.9%. The model is less biased toward BIO_TIMING but the overall ceiling hasn't lifted.

The implication is uncomfortable: the problem isn't the architecture. The problem is that Terran strategy divergence — mech vs. bio vs. banshee — happens at minute 5-7, and our temporal window only covers 5 minutes. SpawningTool data maxes at 3.5 minutes. SC2EGSet median is 3 minutes. The model can't see the divergence point because the data doesn't extend far enough. Extending the window requires re-extracting from raw replays, which is the next major effort.
