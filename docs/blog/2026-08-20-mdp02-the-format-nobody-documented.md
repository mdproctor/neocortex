---
layout: post
title: "The Format Nobody Documented"
date: 2026-08-20
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [ml, msc, reverse-engineering, data-pipeline, normalization, batchnorm]
series: issue-202-retrain-strategy-classifier
---

Continues from [The Data You Don't Have](2026-08-20-mdp01-the-data-you-dont-have.md).

The MSC download was blocked by Google Drive rate-limiting. gdown threw `FileURLRetrievalError` and the FAQ suggested changing permissions — useless for a public file. I tried the direct download URL with `&confirm=t` appended, which bypasses the virus-scan confirmation page entirely. 1.3GB downloaded in two minutes via curl. The trick isn't documented anywhere in gdown or Google's API — the `confirm=t` parameter exists only in the hidden form fields of the scan warning page.

The MSC GlobalFeatureVector turned out to be a bigger puzzle than the download. The dataset provides per-timestep sparse matrices (scipy CSC, ~1121 features) with no documentation of the feature layout. The `game_state.py` in the MSC repo defines a `to_vector()` method that produces a different layout than what's in the actual `.npz` files — 15 features are missing from the code (reward, action index, and 13 score_cumulative fields that precede the documented scalars).

The directory naming is the real trap. `Terran_vs_Zerg/Zerg/` looks like the Zerg player's data. It's actually the Terran player's. The stat encoding explains why: the Zerg stat file encodes 119 unit types covering all three races (because Zerg can observe any opponent), while Terran and Protoss stats encode only their own race (56 and 41 types respectively). A Terran player's units appear correctly in the Zerg-encoded "friendly" section. The "enemy" section, encoded with the opponent's smaller stat, silently drops unit types it can't represent. Every cross-race matchup through a non-Zerg stat has an empty enemy section.

We extracted ~7,500 labelled replays from 10,000 MSC files (75% label rate), plus 2,443 from four Spawning Tool tournament packs (IEM Katowice 2024, EWC 2024/2025, ESL Spring 2024). The tail classes that were unusable before — MUTA_HARASS at 33 samples, HYDRA_PUSH at 28 — jumped to 321 and 376 across all sources.

Training on SC2EGSet alone hit 75% top-1 for vs_zerg and 77.7% for vs_protoss. Both pass the 65% acceptance threshold. vs_terran stuck at 53.7% — MECH_PUSH and BIO_TIMING look too similar in early game for the CNN to distinguish.

The multi-source mixing broke everything. Spawning Tool samples have build-order-derived opponent features but zero player features. MSC has the opposite — player features but no opponent data. When these mix with SC2EGSet's full-feature samples in the same batch, BatchNorm's running statistics get contaminated by the zero-padded blocks. The result is either NaN loss or majority-class collapse — 99.8% accuracy on ROACH_RUSH, 0% on everything else.

I built the normalization infrastructure: per-feature standardization from SC2EGSet stats, binary availability flags (has_player, has_opponent), and random modality dropout that zeros feature blocks during training to teach the model to handle partial observations. The NaN is gone. The accuracy isn't back — BatchNorm fundamentally can't maintain stable statistics when 39% of samples have zero-valued feature blocks. The fix is either LayerNorm (normalizes per-sample instead of per-batch) or separate feature branches that keep each block's statistics independent.

The session went from "we have no data" to "we have 20,000+ samples we can't use together." The infrastructure for multi-source training exists. The architectural change to make it work is the next step — but two of three matchups already pass on SC2EGSet alone.
