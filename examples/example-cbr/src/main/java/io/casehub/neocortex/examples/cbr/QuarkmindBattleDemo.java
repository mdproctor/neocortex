package io.casehub.neocortex.examples.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import static io.casehub.neocortex.memory.cbr.FeatureValue.*;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.inmem.InMemoryCbrCaseMemoryStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QuarkmindBattleDemo {

    static final MemoryDomain DOMAIN = new MemoryDomain("quarkmind");
    static final String TENANT = "demo";
    static final String CASE_TYPE = "quarkmind-battle";

    static final CbrFeatureSchema SCHEMA = CbrFeatureSchema.of(CASE_TYPE,
        FeatureField.categorical("opponent_race"),
        FeatureField.categorical("detected_build"),
        FeatureField.numeric("army_size_ratio", 0.0, 3.0),
        FeatureField.numeric("resource_advantage", -5000, 5000));

    record SeedCase(String problem, String solution, String outcome,
                    double confidence, Map<String, FeatureValue> features,
                    List<PlanTrace> planTrace) {}

    public record Result(ScoredCbrCase<PlanCbrCase> scored) {}

    static final List<SeedCase> SEED_CASES = List.of(
        // Game 1: WIN vs ZERG/ROACH_RUSH with scout
        new SeedCase(
            "Zerg opponent detected Roach Rush at 5:30, army ratio 0.75, resources -150",
            "Scout early → bunker wall → counter-push with marine/medivac → victory",
            "WIN", 0.92,
            Map.of("opponent_race", string("ZERG"), "detected_build", string("ROACH_RUSH"),
                   "army_size_ratio", number(0.75), "resource_advantage", number(-150)),
            List.of(
                new PlanTrace("scout", "reconnaissance", "overlord-scout", "SUCCESS", 1, Map.of()),
                new PlanTrace("assess-threat", "threat-analysis", "zerg-analyzer", "SUCCESS", 2, Map.of()),
                new PlanTrace("bunker-up", "static-defence", "bunker-wall", "SUCCESS", 3, Map.of()),
                new PlanTrace("counter-push", "offensive", "marine-medivac", "SUCCESS", 4, Map.of())
            )),

        // Game 2: WIN vs ZERG/ROACH_RUSH with scout
        new SeedCase(
            "Zerg opponent detected Roach Rush at 5:00, army ratio 0.85, resources -300",
            "Scout with reaper → bunker → hellion harass → expand → bio counter → victory",
            "WIN", 0.88,
            Map.of("opponent_race", string("ZERG"), "detected_build", string("ROACH_RUSH"),
                   "army_size_ratio", number(0.85), "resource_advantage", number(-300)),
            List.of(
                new PlanTrace("scout", "reconnaissance", "reaper-scout", "SUCCESS", 1, Map.of()),
                new PlanTrace("bunker-up", "static-defence", "bunker-wall", "SUCCESS", 2, Map.of()),
                new PlanTrace("early-pressure", "offensive", "hellion-harass", "SUCCESS", 3, Map.of()),
                new PlanTrace("expand", "economy", "natural-expand", "SUCCESS", 4, Map.of()),
                new PlanTrace("counter-push", "offensive", "bio-push", "SUCCESS", 5, Map.of())
            )),

        // Game 3: WIN vs ZERG/ROACH_RUSH with scout
        new SeedCase(
            "Zerg opponent detected Roach Rush at 6:00, army ratio 0.70, resources -100",
            "Scout with scan → bunker defence → siege tank push → victory",
            "WIN", 0.90,
            Map.of("opponent_race", string("ZERG"), "detected_build", string("ROACH_RUSH"),
                   "army_size_ratio", number(0.70), "resource_advantage", number(-100)),
            List.of(
                new PlanTrace("scout", "reconnaissance", "scan-sweep", "SUCCESS", 1, Map.of()),
                new PlanTrace("bunker-up", "static-defence", "bunker-wall", "SUCCESS", 2, Map.of()),
                new PlanTrace("counter-push", "offensive", "siege-tank-push", "SUCCESS", 3, Map.of())
            )),

        // Game 4: WIN vs ZERG/ROACH_RUSH with scout (one failure step)
        new SeedCase(
            "Zerg opponent detected Roach Rush at 5:45, army ratio 0.90, resources +100",
            "Scout → assess threat → failed marine pressure → bunker recovery → bio push → victory",
            "WIN", 0.85,
            Map.of("opponent_race", string("ZERG"), "detected_build", string("ROACH_RUSH"),
                   "army_size_ratio", number(0.90), "resource_advantage", number(100)),
            List.of(
                new PlanTrace("scout", "reconnaissance", "overlord-scout", "SUCCESS", 1, Map.of()),
                new PlanTrace("assess-threat", "threat-analysis", "zerg-analyzer", "SUCCESS", 2, Map.of()),
                new PlanTrace("early-pressure", "offensive", "marine-pressure", "FAILURE", 3, Map.of()),
                new PlanTrace("bunker-up", "static-defence", "bunker-wall", "SUCCESS", 4, Map.of()),
                new PlanTrace("counter-push", "offensive", "bio-push", "SUCCESS", 5, Map.of())
            )),

        // Game 5: LOSS vs ZERG/ROACH_RUSH — NO SCOUT, opened with economy
        new SeedCase(
            "Zerg opponent detected Roach Rush at 6:30 (late), army ratio 0.65, resources -500",
            "Expanded early without scouting → macro-up → failed counter-push → defeat",
            "LOSS", 0.45,
            Map.of("opponent_race", string("ZERG"), "detected_build", string("ROACH_RUSH"),
                   "army_size_ratio", number(0.65), "resource_advantage", number(-500)),
            List.of(
                new PlanTrace("expand", "economy", "natural-expand", "SUCCESS", 1, Map.of()),
                new PlanTrace("macro-up", "economy", "double-refinery", "SUCCESS", 2, Map.of()),
                new PlanTrace("counter-push", "offensive", "marine-push", "FAILURE", 3, Map.of())
            )),

        // Game 6: WIN vs PROTOSS/ZEALOT_RUSH
        new SeedCase(
            "Protoss opponent detected Zealot Rush at 4:30, army ratio 0.80, resources -200",
            "Early bunker → wall-off → marauder defence → victory",
            "WIN", 0.89,
            Map.of("opponent_race", string("PROTOSS"), "detected_build", string("ZEALOT_RUSH"),
                   "army_size_ratio", number(0.80), "resource_advantage", number(-200)),
            List.of(
                new PlanTrace("scout", "reconnaissance", "marine-scout", "SUCCESS", 1, Map.of()),
                new PlanTrace("bunker-up", "static-defence", "wall-bunker", "SUCCESS", 2, Map.of()),
                new PlanTrace("counter-push", "offensive", "marauder-push", "SUCCESS", 3, Map.of())
            )),

        // Game 7: WIN vs TERRAN/MARINE_PUSH
        new SeedCase(
            "Terran opponent detected Marine Push at 5:15, army ratio 0.95, resources 50",
            "Siege tank defence → counter with bio → victory",
            "WIN", 0.87,
            Map.of("opponent_race", string("TERRAN"), "detected_build", string("MARINE_PUSH"),
                   "army_size_ratio", number(0.95), "resource_advantage", number(50)),
            List.of(
                new PlanTrace("scout", "reconnaissance", "reaper-scout", "SUCCESS", 1, Map.of()),
                new PlanTrace("bunker-up", "static-defence", "siege-line", "SUCCESS", 2, Map.of()),
                new PlanTrace("counter-push", "offensive", "bio-counter", "SUCCESS", 3, Map.of()),
                new PlanTrace("expand", "economy", "third-base", "SUCCESS", 4, Map.of())
            )),

        // Game 8: WIN vs ZERG/MACRO
        new SeedCase(
            "Zerg opponent detected Macro play at 7:00, army ratio 1.10, resources 400",
            "Match opponent macro → expand → multi-pronged attacks → victory",
            "WIN", 0.91,
            Map.of("opponent_race", string("ZERG"), "detected_build", string("MACRO"),
                   "army_size_ratio", number(1.10), "resource_advantage", number(400)),
            List.of(
                new PlanTrace("scout", "reconnaissance", "overlord-scout", "SUCCESS", 1, Map.of()),
                new PlanTrace("expand", "economy", "fast-expand", "SUCCESS", 2, Map.of()),
                new PlanTrace("macro-up", "economy", "three-base", "SUCCESS", 3, Map.of()),
                new PlanTrace("counter-push", "offensive", "multi-prong", "SUCCESS", 4, Map.of())
            )),

        // Game 9: LOSS vs PROTOSS/UNKNOWN
        new SeedCase(
            "Protoss opponent build unknown, army ratio 0.60, resources -600",
            "Failed to scout → blind defence → overwhelmed → defeat",
            "LOSS", 0.40,
            Map.of("opponent_race", string("PROTOSS"), "detected_build", string("UNKNOWN"),
                   "army_size_ratio", number(0.60), "resource_advantage", number(-600)),
            List.of(
                new PlanTrace("bunker-up", "static-defence", "blind-wall", "SUCCESS", 1, Map.of()),
                new PlanTrace("macro-up", "economy", "greedy-expand", "FAILURE", 2, Map.of()),
                new PlanTrace("counter-push", "offensive", "desperate-push", "FAILURE", 3, Map.of())
            )),

        // Game 10: WIN vs TERRAN/MACRO
        new SeedCase(
            "Terran opponent detected Macro play at 8:00, army ratio 1.05, resources 300",
            "Scout → match macro → superior positioning → victory",
            "WIN", 0.88,
            Map.of("opponent_race", string("TERRAN"), "detected_build", string("MACRO"),
                   "army_size_ratio", number(1.05), "resource_advantage", number(300)),
            List.of(
                new PlanTrace("scout", "reconnaissance", "scan-sweep", "SUCCESS", 1, Map.of()),
                new PlanTrace("expand", "economy", "dual-expand", "SUCCESS", 2, Map.of()),
                new PlanTrace("macro-up", "economy", "four-base", "SUCCESS", 3, Map.of()),
                new PlanTrace("early-pressure", "offensive", "drop-harass", "SUCCESS", 4, Map.of()),
                new PlanTrace("counter-push", "offensive", "max-out-push", "SUCCESS", 5, Map.of())
            ))
    );

    public static List<Result> run(CbrCaseMemoryStore store) {
        store.registerSchema(SCHEMA);

        for (var seed : SEED_CASES) {
            var cbrCase = new PlanCbrCase(
                seed.problem(), seed.solution(), seed.outcome(), seed.confidence(),
                seed.features(), seed.planTrace());
            store.store(cbrCase, CASE_TYPE, UUID.randomUUID().toString(),
                DOMAIN, TENANT, UUID.randomUUID().toString());
        }

        var query = CbrQuery.of(TENANT, DOMAIN, CASE_TYPE,
            Map.of("opponent_race", string("ZERG"), "detected_build", string("ROACH_RUSH")), 10);

        return store.retrieveSimilar(query, PlanCbrCase.class).stream()
            .map(Result::new)
            .toList();
    }

    static void printResults(List<Result> results) {
        System.out.println("QuarkMind Battle — CBR Results (Plan-Based)");
        System.out.println("=============================================");
        System.out.printf("Query: opponent_race=ZERG, detected_build=ROACH_RUSH — army 0.8, resources -200%n%n");
        System.out.printf("%d similar past games found (of %d in case base)%n%n",
            results.size(), SEED_CASES.size());

        // Print individual results with plan traces
        for (int i = 0; i < results.size(); i++) {
            var c = results.get(i).scored().cbrCase();
            var features = c.features();
            System.out.printf("  #%d [%.2f] %s — vs %s, %s, army %.2f, resources %d%n",
                i + 1, results.get(i).scored().score(), c.outcome(),
                features.get("opponent_race"), features.get("detected_build"),
                features.get("army_size_ratio"),
                ((int) ((FeatureValue.NumberVal) features.get("resource_advantage")).value()));
            System.out.println("     Plan trace:");
            for (var trace : c.planTrace()) {
                System.out.printf("       %d. %-15s → %-17s → %-17s → %s (pri %d)%n",
                    trace.priority(), trace.bindingName(), trace.capabilityName(),
                    trace.workerName(), trace.stepOutcome(), trace.priority());
            }
            System.out.println();
        }

        // Plan analysis
        System.out.println("Plan analysis:");

        long wins = results.stream().filter(r -> "WIN".equals(r.scored().cbrCase().outcome())).count();
        long losses = results.stream().filter(r -> "LOSS".equals(r.scored().cbrCase().outcome())).count();
        System.out.printf("  Win rate: %d%% (%d/%d). Opening binding breakdown:%n",
            results.isEmpty() ? 0 : wins * 100 / results.size(), wins, results.size());

        // Count binding appearances in winning vs losing games
        Map<String, long[]> bindingStats = new LinkedHashMap<>();
        for (var result : results) {
            var c = result.scored().cbrCase();
            boolean isWin = "WIN".equals(c.outcome());
            for (var trace : c.planTrace()) {
                bindingStats.computeIfAbsent(trace.bindingName(), k -> new long[2]);
                bindingStats.get(trace.bindingName())[isWin ? 0 : 1]++;
            }
        }

        // Print binding stats
        bindingStats.forEach((binding, counts) -> {
            long winsWithBinding = counts[0];
            long lossesWithBinding = counts[1];
            long total = winsWithBinding + lossesWithBinding;
            System.out.printf("    %-15s %d/%d games, %d/%d wins",
                binding + ":", total, results.size(), winsWithBinding,
                winsWithBinding + lossesWithBinding);
            if (total == wins && lossesWithBinding == 0) {
                System.out.print(" — present in every winning plan");
            } else if (lossesWithBinding > 0 && winsWithBinding == 0) {
                System.out.print(" — only in losses");
            } else if (total == results.size() && winsWithBinding == wins) {
                System.out.print(" — universal but with mixed outcomes");
            }
            System.out.println();
        });

        // Identify losses that skipped scouting
        long lossesWithoutScout = results.stream()
            .filter(r -> "LOSS".equals(r.scored().cbrCase().outcome()))
            .filter(r -> r.scored().cbrCase().planTrace().stream()
                .noneMatch(t -> "scout".equals(t.bindingName())))
            .count();
        if (lossesWithoutScout > 0) {
            System.out.printf("%n  The %d loss skipped scouting entirely and opened with economy (expand → macro-up).%n",
                lossesWithoutScout);
        }

        // Suggest opening
        System.out.printf("%n  Suggested opening: scout (pri 1) → bunker-up (pri 2) → counter-push (pri 3).%n");
    }

    public static void main(String[] args) {
        var store = new InMemoryCbrCaseMemoryStore();
        printResults(run(store));
    }
}
