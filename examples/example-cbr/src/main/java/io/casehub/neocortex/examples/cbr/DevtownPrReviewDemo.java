package io.casehub.neocortex.examples.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import static io.casehub.neocortex.memory.cbr.FeatureValue.*;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.inmem.InMemoryCbrCaseMemoryStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DevtownPrReviewDemo {

    static final MemoryDomain DOMAIN = new MemoryDomain("devtown");
    static final String TENANT = "demo";
    static final String CASE_TYPE = "devtown-pr-review";

    static final CbrFeatureSchema SCHEMA = CbrFeatureSchema.of(CASE_TYPE,
        FeatureField.categorical("language"),
        FeatureField.categorical("change_type"),
        FeatureField.numeric("files_changed", 1, 1000),
        FeatureField.numeric("lines_changed", 1, 50000),
        FeatureField.text("pr_description"));

    record SeedCase(String problem, String solution, String outcome,
                    double confidence, Map<String, FeatureValue> features) {}

    public record Result(ScoredCbrCase<FeatureVectorCbrCase> scored) {}

    static final List<SeedCase> SEED_CASES = List.of(
        new SeedCase(
            "Extract persistence layer from service classes",
            "Reviewers: 3. Review duration: 3 days. Finding: missing transaction boundaries at new layer edge. Reviewer comments: 'Need @Transactional on new DAO methods'.",
            "CHANGES_REQUESTED", 0.82,
            Map.of("language", string("JAVA"), "change_type", string("REFACTOR"),
                   "files_changed", number(52), "lines_changed", number(3100),
                   "pr_description", string("Extract persistence layer from service classes"))),
        new SeedCase(
            "Separate domain model from REST DTOs",
            "Reviewers: 2. Review duration: 1.5 days. Finding: clean separation, minor naming issues. Reviewer comments: 'Consider renaming UserDto to UserResponse'.",
            "APPROVED", 0.91,
            Map.of("language", string("JAVA"), "change_type", string("REFACTOR"),
                   "files_changed", number(38), "lines_changed", number(2200),
                   "pr_description", string("Separate domain model from REST DTOs"))),
        new SeedCase(
            "Extract auth middleware into dedicated module",
            "Reviewers: 2. Review duration: 2.5 days. Finding: circular dependency introduced between modules. Reviewer comments: 'AuthModule depends on UserModule which depends on AuthModule'.",
            "CHANGES_REQUESTED", 0.79,
            Map.of("language", string("JAVA"), "change_type", string("REFACTOR"),
                   "files_changed", number(41), "lines_changed", number(2900),
                   "pr_description", string("Extract auth middleware into dedicated module"))),
        new SeedCase(
            "Consolidate 3 payment services into unified payment gateway",
            "Reviewers: 3. Review duration: 4 days. Finding: integration test coverage gaps. Reviewer comments: 'Missing tests for refund flow'.",
            "APPROVED", 0.87,
            Map.of("language", string("JAVA"), "change_type", string("REFACTOR"),
                   "files_changed", number(60), "lines_changed", number(4100),
                   "pr_description", string("Consolidate 3 payment services into unified payment gateway"))),
        new SeedCase(
            "Migrate repository layer to coroutines",
            "Reviewers: 2. Review duration: 2 days. Finding: blocking calls remaining in suspend functions. Reviewer comments: 'UserRepository.findById still uses blocking JDBC'.",
            "CHANGES_REQUESTED", 0.76,
            Map.of("language", string("KOTLIN"), "change_type", string("REFACTOR"),
                   "files_changed", number(35), "lines_changed", number(1800),
                   "pr_description", string("Migrate repository layer to coroutines"))),
        new SeedCase(
            "Add user profile management API — create, read, update endpoints with validation",
            "Reviewers: 2. Review duration: 2 days. Finding: comprehensive tests, good validation. Reviewer comments: 'Nice coverage of edge cases'.",
            "APPROVED", 0.93,
            Map.of("language", string("TYPESCRIPT"), "change_type", string("FEATURE"),
                   "files_changed", number(12), "lines_changed", number(850),
                   "pr_description", string("Add user profile management API"))),
        new SeedCase(
            "Fix race condition in session token refresh logic",
            "Reviewers: 1. Review duration: 1 day. Finding: correct fix, added test. Reviewer comments: 'Good catch, test verifies the fix'.",
            "APPROVED", 0.88,
            Map.of("language", string("TYPESCRIPT"), "change_type", string("BUGFIX"),
                   "files_changed", number(3), "lines_changed", number(45),
                   "pr_description", string("Fix race condition in session token refresh"))),
        new SeedCase(
            "Fix null pointer in payment processing when user has no default card",
            "Reviewers: 1. Review duration: 1.5 days. Finding: fix works but needs defensive check earlier. Reviewer comments: 'Should validate card existence before entering payment flow'.",
            "CHANGES_REQUESTED", 0.71,
            Map.of("language", string("JAVA"), "change_type", string("BUGFIX"),
                   "files_changed", number(2), "lines_changed", number(30),
                   "pr_description", string("Fix null pointer when user has no default card"))),
        new SeedCase(
            "Add integration tests for notification service — email, SMS, push",
            "Reviewers: 1. Review duration: 1 day. Finding: good coverage, uses test doubles correctly. Reviewer comments: 'Appreciated the use of WireMock'.",
            "APPROVED", 0.90,
            Map.of("language", string("PYTHON"), "change_type", string("TEST"),
                   "files_changed", number(8), "lines_changed", number(620),
                   "pr_description", string("Add integration tests for notification service"))),
        new SeedCase(
            "Update API documentation — OpenAPI specs for payment and user endpoints",
            "Reviewers: 1. Review duration: 0.5 days. Finding: clear and accurate. Reviewer comments: 'Matches implementation'.",
            "APPROVED", 0.85,
            Map.of("language", string("TYPESCRIPT"), "change_type", string("DOCS"),
                   "files_changed", number(5), "lines_changed", number(310),
                   "pr_description", string("Update API documentation for payment and user endpoints")))
    );

    public static List<Result> run(CbrCaseMemoryStore store) {
        store.registerSchema(SCHEMA);

        for (var seed : SEED_CASES) {
            var cbrCase = new FeatureVectorCbrCase(
                seed.problem(), seed.solution(), seed.outcome(), seed.confidence(), seed.features());
            store.store(cbrCase, CASE_TYPE, UUID.randomUUID().toString(), DOMAIN, TENANT, UUID.randomUUID().toString());
        }

        var query = CbrQuery.of(TENANT, DOMAIN, CASE_TYPE,
            Map.of("change_type", string("REFACTOR")), 10);

        return store.retrieveSimilar(query, FeatureVectorCbrCase.class).stream()
            .map(Result::new)
            .toList();
    }

    static void printResults(List<Result> results) {
        System.out.println("DevTown PR Review — CBR Results");
        System.out.println("=================================");
        System.out.printf("Query: change_type=REFACTOR — Java, 45 files, 2800 lines%n%n");
        System.out.printf("%d similar past reviews found (of %d in case base)%n%n",
            results.size(), SEED_CASES.size());

        for (int i = 0; i < results.size(); i++) {
            var c = results.get(i).scored().cbrCase();
            var lang = c.features().get("language");
            var files = c.features().get("files_changed");
            var lines = c.features().get("lines_changed");
            System.out.printf("  #%d [%.2f] %s — %s refactor, %s files, %s lines%n", i + 1,
                results.get(i).scored().score(), c.outcome(), lang, files, lines);
            System.out.printf("            \"%s\"%n", c.problem());
            System.out.printf("            %s%n%n", truncate(c.solution(), 80));
        }

        long approvedCount = results.stream()
            .filter(r -> "APPROVED".equals(r.scored().cbrCase().outcome()))
            .count();
        int approvalRate = results.isEmpty() ? 0 : (int) (approvedCount * 100 / results.size());

        var durations = results.stream()
            .map(r -> r.scored().cbrCase().solution())
            .filter(s -> s.contains("Review duration:"))
            .map(s -> s.substring(s.indexOf("Review duration:") + 17))
            .map(s -> s.substring(0, s.indexOf(" days")))
            .mapToDouble(Double::parseDouble)
            .average();

        System.out.printf("Summary: %d%% first-pass approval rate for similar refactors.%n", approvalRate);
        System.out.printf("         Recommended reviewers: 2-3. Average review time: %.1f days.%n",
            durations.orElse(0.0));
        System.out.printf("         Top risk: transaction/dependency boundaries at extraction points.%n");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public static void main(String[] args) {
        var store = new InMemoryCbrCaseMemoryStore();
        printResults(run(store));
    }
}
