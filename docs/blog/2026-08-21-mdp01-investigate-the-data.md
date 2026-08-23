---
layout: post
title: "Investigate the Data Before the Architecture"
date: 2026-08-21
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [ml, strategy-classifier, labelling, feature-engineering, data-quality, upgrade-timing]
series: issue-202-retrain-strategy-classifier
---

Continues from [The Signal Your Model Can't See](2026-08-20-mdp03-the-signal-your-model-cant-see.md).

The previous session's conclusion was wrong. I'd spent days optimising architecture — dual encoders, attention heads, hierarchical classification — and concluded the bottleneck was temporal window length. Strategy divergence happens at minute 5-7, data covers 3-5 minutes, nothing the model can do. Sounded right. Felt right. Was wrong.

I started this session by implementing the hierarchical classification head from the plan. Auxiliary coarse loss, three strategy groups, shared encoder. Swept the loss weight from 0.3 to 1.0. Every configuration landed within 2pp of the baseline 53.6%. The hierarchy wasn't fighting the right confusion pairs — I'd grouped BIO_TIMING with BANSHEE_HARASS, but the actual confusion was MECH_PUSH with BIO_TIMING. The architecture wasn't the problem.

So I stopped building and started investigating. Four parallel probes: confusion matrix, source composition, labelling rules audit, feature vector audit.

The confusion matrix was the first result back. MECH_PUSH and BIO_TIMING were misclassified as each other in 20.9% of all test samples. BIO_TIMING acted as an attractor class — 995 predictions against 706 actual samples. MACRO_ECONOMY had 80.6% precision but 24.2% recall. When the model predicted MACRO_ECONOMY it was almost always right, but it almost never predicted it.

The source composition probe found something I hadn't checked: the Spawning Tool adapter was producing **zero player features**. Line 216: `own_feat = np.zeros_like(opp_feat)`. The replay parser returns both players' data. The adapter just wasn't extracting it. 27% of training data had an entire feature block blanked out. Nobody noticed because the availability gating silently downweighted the missing modality.

The labelling audit found the root cause of the MACRO_ECONOMY anomaly. The Terran labelling function checked for CC-first at rule position #2 — before any strategy-specific rule. Any Terran who built a second Command Center before their first Factory got labelled MACRO_ECONOMY regardless of what followed. CC-first is the most common competitive Terran opening. A CC-first into Banshees, mech, or bio all got the same label. The strategy rules never got a chance to fire.

The feature audit found the missing discriminator. The feature vector tracked 55 buildings, 51 units, 13 economy stats per player. Zero upgrades. No Stimpack, no Siege Mode research, no Banshee Cloak. In competitive SC2, the single strongest signal distinguishing bio from mech is which upgrade starts at minute 2.5 — Stimpack means bio, anything else means mech or tech. The SC2EGSet tracker events have `UpgradeComplete` events with the upgrade name. The extractor just skipped them.

Three root causes, none architectural:
1. A labelling bug poisoning 11% of training labels
2. A data adapter silently zeroing 27% of player features
3. The most discriminative feature in the domain not extracted

I fixed all three. Moved MACRO_ECONOMY to fallback position — it now only fires when no other strategy rule matches. Extracted both players' build orders from Spawning Tool replays. Added 15 upgrade features to the feature vector (Stimpack, ShieldWall, BansheeCloak, BlinkTech, and 11 others). Downloaded 5 SC2EGSet tournament ZIPs from Zenodo to re-ingest with the fixes.

The result: 52.8% to 55.3%. BIO_TIMING improved from 60.9% to 69.3% — the Stimpack feature is doing exactly what I expected. MECH_PUSH improved from 49.6% to 53.6%. Not 65% yet, but the direction is right, and the path is clear: download the remaining 65 tournament ZIPs to increase upgrade-enriched data coverage, and extract upgrade columns from the MSC sparse matrices where they exist but aren't used.

The lesson I keep relearning: when a model plateaus, the instinct is to change the model. Add attention. Add hierarchy. Make the architecture smarter. But a smarter architecture can't learn from wrong labels, can't use features that aren't there, and can't compensate for 27% of its training data having a blank feature block. Investigate the data first. The confusion matrix took ten lines of code and immediately pointed at the feature gap. The labelling audit found a bug that no architecture change could ever work around.
