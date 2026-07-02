# DevTown — CBR for PR Review

## Why CBR

DevTown reviews pull requests. When a new PR arrives, CBR finds similar past PRs
and surfaces what happened: how many reviewers were needed, what common findings
came up, how long review took. This turns institutional review knowledge into
actionable suggestions — not just who should review, but what to watch for.

## CBR Paradigm

**Textual + Feature-Vector CBR.** Structured features (language, change type, scale)
filter the candidate set. The PR description provides a dense text signal for
semantic similarity within that filtered set.

## Feature Schema

```java
CbrFeatureSchema SCHEMA = CbrFeatureSchema.of("devtown-pr-review",
    FeatureField.categorical("language"),       // JAVA, KOTLIN, TYPESCRIPT, PYTHON
    FeatureField.categorical("change_type"),    // FEATURE, BUGFIX, REFACTOR, DOCS, TEST
    FeatureField.numeric("files_changed", 1, 1000),
    FeatureField.numeric("lines_changed", 1, 50000),
    FeatureField.text("pr_description"));
```

### Why these fields

- **language** — review patterns differ by language (Java PRs need different eyes than TypeScript)
- **change_type** — a refactor has different risk profile than a feature; past outcomes for the same type are most relevant
- **files_changed / lines_changed** — scale affects review strategy (small fix vs. large refactor)
- **pr_description** — semantic match catches domain similarity that categorical fields miss

## Retain — Storing PR Review Outcomes

When a PR review completes, build a case from the outcome:

```java
@ApplicationScoped
public class PrReviewOutcomeObserver {

    @Inject CbrCaseMemoryStore cbrStore;

    void onReviewComplete(@Observes PrReviewCompleteEvent event) {
        var cbrCase = new FeatureVectorCbrCase(
            event.prDescription(),                          // problem
            formatSolution(event),                          // solution summary
            event.outcome().name(),                         // APPROVED, CHANGES_REQUESTED, etc.
            event.reviewerConfidence(),                     // nullable
            Map.of(
                "language", event.primaryLanguage(),
                "change_type", event.changeType(),
                "files_changed", event.filesChanged(),
                "lines_changed", event.linesChanged(),
                "pr_description", event.prDescription()));

        cbrStore.store(cbrCase, "devtown-pr-review",
            event.repoId(), PR_REVIEW_DOMAIN, event.tenantId(), event.prId());
    }

    private String formatSolution(PrReviewCompleteEvent e) {
        return "Reviewed by %d reviewers. Findings: %s. Duration: %s"
            .formatted(e.reviewerCount(), e.topFindings(), e.reviewDuration());
    }
}
```

## Retrieve — Finding Similar Past PRs

At PR assignment time, query for similar past reviews:

```java
@ApplicationScoped
public class PrReviewAssistant {

    @Inject CbrCaseMemoryStore cbrStore;

    public List<FeatureVectorCbrCase> findSimilarReviews(PullRequest pr) {
        var query = CbrQuery.of(
            pr.tenantId(),
            PR_REVIEW_DOMAIN,
            "devtown-pr-review",
            Map.of(
                "language", pr.primaryLanguage(),
                "change_type", pr.changeType()),
            5);

        return cbrStore.retrieveSimilar(query, FeatureVectorCbrCase.class);
    }
}
```

Results tell you: "5 similar past Java refactors — 4 required 2+ reviewers, common
finding was missing transaction boundaries, average review time was 2 days."

## Mock → Production

| Phase | Backend | What works |
|-------|---------|-----------|
| **Now** | `memory-cbr-inmem` | Schema validation, categorical exact match, store/retrieve round-trip. No persistence. |
| **Production** | `memory-qdrant` | Payload filters on language/change_type + range on files/lines + dense vector on pr_description. Persistent. Scalable. |

Switching backends requires no code changes — CDI selects the highest-priority
`CbrCaseMemoryStore` implementation on the classpath.

## Maven Dependencies

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-api</artifactId>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-cbr-inmem</artifactId>
    <scope>test</scope>
</dependency>
```
