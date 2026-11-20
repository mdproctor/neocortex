---
layout: post
title: "The Facets That Move Everything"
date: 2026-11-20
entry_type: note
subtype: diary
projects: [casehubio/neocortex]
tags: [cognitive-defaults, pid5-facets, test-coverage, caps, displacement, derivation-engine]
---

# The Facets That Move Everything

I ran a test coverage sweep across the cognitive pipeline today — six issues, five areas that had been flagged during code review as untested paths. The kind of work that feels mechanical until it isn't.

The interesting finding came from #1079, the field classification test. The issue asked for a test that enumerates every `CognitiveDefaults` component and asserts whether it uses the resting view (without situational personality displacement) or the effective view (with displacement). I expected a clean partition: facet-derived fields like `appraisalWeights` on one side, axis-derived fields like `cbrStrategy` on the other.

The partition is not clean. When I wrote the test with `cbrStrategy` classified as displacement-independent, it failed. `deriveCbrStrategy` takes PID-5 facets as a parameter and modulates both `minSimilarity` (via IMPULSIVITY) and `decayDays` (via RISK_TAKING). Same story for `deriveMoodBaseline`, `deriveCuriosity`, `deriveSocialCognition`, `deriveGraphStructure`, `deriveExtractionBias`. Almost every derivation path in `CognitiveDerivationEngine` accepts facets and uses them.

The truly displacement-independent fields turned out to be just three: `personality` (derived from disposition profile terms only), `personalityDominance` (same), and `temporalFocus` (derived from goals alone). Everything else moves when facets move. That means situational personality displacement — the transient facet shifts from stress, flow, or social mode engagement — ripples through CBR retrieval parameters, graph structure inference style, extraction bias sensitivity, mood baseline, curiosity weights, and social cognition defaults. Not just the obvious emotion-adjacent fields.

This matters because `EffectiveCognitiveDefaults` maintains two parallel views: `restingFor()` returns a `LearningProfile` for CBR similarity scoring (the agent's settled personality), while `forAgent()` returns `CognitiveDefaults` for the prompt pipeline (the agent's current personality including transient states). If I'd gotten the classification wrong — treating cbrStrategy as resting when it's actually effective — the CBR retrieval engine would use situationally-distorted parameters for similarity matching. A stressed agent would have different retrieval thresholds than a calm one, which is intentional for the prompt pipeline but would corrupt the CBR's notion of "how similar is this case to past cases."

The other finding worth noting: CAPS trajectory divergence doesn't hold. Issue #1171 asked for tests verifying that contrasting personalities produce different settling trajectories — different iteration counts, convergence types, or saturation ratios. The open and cautious agents get radically different input activations (approach/exploratory vs threat/avoidance nodes via `DriveCompetitionDetector.DRIVE_CAPS_MAPPINGS`), and they do converge to different attractor patterns (the existing `behavioralAttractorsDiverge` test passes). But both converge in exactly 10 iterations with identical convergence type and zero saturation. The topology settles too quickly for the personality-dependent connection weights to create measurably different convergence paths. Different destination, same speed. If trajectory divergence is wanted as a test signal, the topology would need deeper feedback loops or the test would need to seed more extreme activation patterns.

The practical lesson: test coverage sweeps are worth doing even when every individual issue looks trivial. The issue titles read like checkboxes — "add missing tests for `evictIdle`", "cover negative facet clamping", "test the MindMapNode overload." But the act of writing the tests forces you to understand what the code actually does with its inputs. The field classification test taught me more about how `CognitiveDerivationEngine` propagates personality state than reading the derivation code had. I'd read the code; I hadn't traced the full dependency graph until I had to classify every field as resting-or-effective and was wrong about most of them.
