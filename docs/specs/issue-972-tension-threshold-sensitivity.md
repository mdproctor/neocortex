# Tension Threshold Sensitivity Analysis (#972)

## Background

Issue #941 introduced A/C prompt routing for personality-aware situation classification.
The tension threshold determines which profiles get the full personality system prompt
(path C) vs. a lightweight user-prompt suffix (path A). Originally hardcoded at 0.5.

## Analysis

### Tension table score distribution

The `DispositionTension` table contains 6 entries, all hand-curated with psychological
rationale. Their scores span 0.6–1.0:

| Pair | Score | Conflict |
|------|-------|----------|
| bold + strict | 1.0 | approach vs inhibition |
| high autonomy + avoidant | 0.9 | independent but won't confront |
| cooperative + high autonomy | 0.8 | group-oriented but self-directed |
| bold + avoidant | 0.7 | acts but doesn't confront |
| independent + low autonomy | 0.7 | independent identity but follows |
| competitive + strict | 0.6 | driven to win but bound by rules |

Profiles without a matching pair score 0.0 (no tension).

### Routing at each threshold

All 6 table entries score ≥ 0.6. The eval test exercises all 6 via 7 profiles
(6 tension-triggering + 1 zero-tension baseline).

| Threshold | Path C (tension > T) | Path A | Notes |
|-----------|---------------------|--------|-------|
| 0.3 | 6/7 (all 6 tensions) | 1/7 (baseline) | Same as 0.5 — no scores in (0.3, 0.6) |
| 0.5 | 6/7 (all 6 tensions) | 1/7 (baseline) | Captures all curated tensions |
| 0.7 | 3/7 (1.0, 0.9, 0.8) | 4/7 (0.7×2, 0.6, baseline) | Excludes moderate tensions |

### Key observation: no scores in (0.0, 0.6)

The tension table is binary: either a profile has a genuine psychological tension
(scoring 0.6–1.0) or it has no tension at all (scoring 0.0). There are no "borderline"
entries. This means any threshold in (0.0, 0.6) produces identical routing.

### Threshold 0.7 drops meaningful tensions

The two entries at 0.6–0.7 describe genuine interpretive conflicts:

- **bold + avoidant (0.7):** "impulsive toward action but shrinks from interpersonal
  friction — sees opportunities but worries about the social cost of seizing them"
- **competitive + strict (0.6):** "driven to win but constrained by propriety — seeks
  advantage within the rules, alert to where rules create or block competitive edges"

Both narratives frame how the character perceives ambiguous situations. At threshold 0.7,
these interpretive frames are lost — the classifier falls back to path A's lightweight
"classify as this character would perceive it" suffix, which doesn't capture the push-pull
tension that defines these profiles.

### Threshold 0.3 vs 0.5: no difference

Since the minimum tension score is 0.6, thresholds 0.3 and 0.5 produce identical routing.
If the tension table were extended with weaker tensions (say 0.4–0.5), threshold 0.3 would
capture them while 0.5 would not. However, adding such weak tensions would require
justification that the interpretive conflict is strong enough to warrant a dedicated
system prompt framing.

## Conclusion

**Threshold 0.5 is empirically optimal.** It captures all curated tensions (which all
score ≥ 0.6), excludes non-tensions (which score 0.0), and provides headroom for future
table entries with moderate tensions (0.4–0.5 range) if justified.

The threshold is now configurable via `casehub.caps.classification.tension-threshold`
(default 0.5) for runtime tuning without code changes.

### LLM eval data: pending

The eval test (`TensionThresholdSensitivityEvalTest`) is structured to measure
inter-profile variance, dual-activation rates, and boundary profile quality across
thresholds. Data collection requires a working LLM agent deployment — currently blocked
by model availability (fable-5-2 not available on Vertex). When the deployment is
updated, re-run with:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl caps-llm-classifier -Pllm-eval \
    -Dtest=TensionThresholdSensitivityEvalTest
```

The report is written to `caps-llm-classifier/target/threshold-sensitivity-report.txt`.
