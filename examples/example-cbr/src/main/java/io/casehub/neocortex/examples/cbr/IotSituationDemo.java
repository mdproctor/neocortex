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

public final class IotSituationDemo {

    static final MemoryDomain DOMAIN = new MemoryDomain("iot");
    static final String TENANT = "demo";
    static final String CASE_TYPE = "iot-situation";

    static final CbrFeatureSchema SCHEMA = CbrFeatureSchema.of(CASE_TYPE,
        FeatureField.categorical("situation_type"),
        FeatureField.categorical("device_class"),
        FeatureField.categorical("room_type"),
        FeatureField.categorical("time_of_day"),
        FeatureField.categorical("severity"),
        FeatureField.text("situation_description"));

    record SeedCase(String problem, String solution, String outcome,
                    double confidence, Map<String, FeatureValue> features) {}

    public record Result(ScoredCbrCase<FeatureVectorCbrCase> scored) {}

    static final List<SeedCase> SEED_CASES = List.of(
        new SeedCase(
            "Kitchen temperature rose 8°C in 15 minutes — oven preheating for dinner",
            "Resolution: false positive, operator dismissed within 2 minutes. Suppression rule: if oven active, downgrade to LOW.",
            "OPERATOR_DISMISSED", 0.91,
            Map.of("situation_type", string("TEMPERATURE_ANOMALY"), "device_class", string("THERMOSTAT"),
                   "room_type", string("KITCHEN"), "time_of_day", string("EVENING"), "severity", string("MEDIUM"),
                   "situation_description", string("Temperature spike during oven preheating"))),
        new SeedCase(
            "Rapid temperature increase during cooking — opened window resolved",
            "Resolution: false positive, auto-resolved after window opened. Duration: 18 minutes. No action needed.",
            "OPERATOR_DISMISSED", 0.87,
            Map.of("situation_type", string("TEMPERATURE_ANOMALY"), "device_class", string("THERMOSTAT"),
                   "room_type", string("KITCHEN"), "time_of_day", string("EVENING"), "severity", string("MEDIUM"),
                   "situation_description", string("Temperature increase during cooking"))),
        new SeedCase(
            "Temperature spike coincided with dishwasher steam cycle",
            "Resolution: false positive, operator added suppression rule. Future dishwasher steam cycles auto-downgraded.",
            "OPERATOR_DISMISSED", 0.89,
            Map.of("situation_type", string("TEMPERATURE_ANOMALY"), "device_class", string("THERMOSTAT"),
                   "room_type", string("KITCHEN"), "time_of_day", string("AFTERNOON"), "severity", string("LOW"),
                   "situation_description", string("Temperature spike from dishwasher steam"))),
        new SeedCase(
            "Kitchen temperature dropped 5°C overnight — boiler pilot light out",
            "Resolution: work item created, contractor dispatched, resolved in 4 hours. Root cause: boiler pilot light extinguished.",
            "WORK_ITEM_CREATED", 0.94,
            Map.of("situation_type", string("TEMPERATURE_ANOMALY"), "device_class", string("THERMOSTAT"),
                   "room_type", string("KITCHEN"), "time_of_day", string("MORNING"), "severity", string("HIGH"),
                   "situation_description", string("Temperature drop, boiler failure"))),
        new SeedCase(
            "Sustained high temperature, no cooking activity — extractor fan failure",
            "Resolution: escalated to maintenance, fan motor replaced. Duration: 2 days.",
            "ESCALATED", 0.88,
            Map.of("situation_type", string("TEMPERATURE_ANOMALY"), "device_class", string("THERMOSTAT"),
                   "room_type", string("KITCHEN"), "time_of_day", string("NIGHT"), "severity", string("HIGH"),
                   "situation_description", string("Extractor fan failure"))),
        new SeedCase(
            "Motion detected in garage at 2 AM — raccoon triggered camera",
            "Resolution: false positive, operator reviewed footage and dismissed. Added wildlife suppression zone.",
            "OPERATOR_DISMISSED", 0.79,
            Map.of("situation_type", string("MOTION_UNEXPECTED"), "device_class", string("CAMERA"),
                   "room_type", string("GARAGE"), "time_of_day", string("NIGHT"), "severity", string("MEDIUM"),
                   "situation_description", string("Motion detected, wildlife false positive"))),
        new SeedCase(
            "Water leak detected in bathroom — pipe joint failure under sink",
            "Resolution: work item created, plumber dispatched, pipe joint replaced. Duration: 6 hours.",
            "WORK_ITEM_CREATED", 0.96,
            Map.of("situation_type", string("WATER_LEAK"), "device_class", string("SENSOR"),
                   "room_type", string("BATHROOM"), "time_of_day", string("AFTERNOON"), "severity", string("CRITICAL"),
                   "situation_description", string("Pipe joint failure, water leak"))),
        new SeedCase(
            "Smoke detected in kitchen during cooking — burned toast",
            "Resolution: false positive, operator opened window and dismissed. No fire.",
            "OPERATOR_DISMISSED", 0.83,
            Map.of("situation_type", string("SMOKE_DETECTED"), "device_class", string("ALARM"),
                   "room_type", string("KITCHEN"), "time_of_day", string("MORNING"), "severity", string("HIGH"),
                   "situation_description", string("Smoke from burned toast"))),
        new SeedCase(
            "Unexpected motion in living room while away — cleaner arrived early",
            "Resolution: false positive, operator confirmed via camera feed. Updated cleaner schedule in system.",
            "OPERATOR_DISMISSED", 0.76,
            Map.of("situation_type", string("MOTION_UNEXPECTED"), "device_class", string("CAMERA"),
                   "room_type", string("LIVING"), "time_of_day", string("MORNING"), "severity", string("MEDIUM"),
                   "situation_description", string("Unexpected motion, cleaner arrived early"))),
        new SeedCase(
            "Power outage detected — entire property offline for 2 hours",
            "Resolution: escalated to utility company, grid fault. Restored after 2 hours.",
            "ESCALATED", 0.92,
            Map.of("situation_type", string("POWER_OUTAGE"), "device_class", string("METER"),
                   "room_type", string("GENERAL"), "time_of_day", string("AFTERNOON"), "severity", string("CRITICAL"),
                   "situation_description", string("Grid fault, 2-hour outage")))
    );

    public static List<Result> run(CbrCaseMemoryStore store) {
        store.registerSchema(SCHEMA);

        for (var seed : SEED_CASES) {
            var cbrCase = new FeatureVectorCbrCase(
                seed.problem(), seed.solution(), seed.outcome(), seed.confidence(), seed.features());
            store.store(cbrCase, CASE_TYPE, UUID.randomUUID().toString(), DOMAIN, TENANT, UUID.randomUUID().toString());
        }

        var query = CbrQuery.of(TENANT, DOMAIN, CASE_TYPE,
            Map.of("situation_type", string("TEMPERATURE_ANOMALY"), "room_type", string("KITCHEN")), 10);

        return store.retrieveSimilar(query, FeatureVectorCbrCase.class).stream()
            .map(Result::new)
            .toList();
    }

    static void printResults(List<Result> results) {
        System.out.println("IoT Situation Handling — CBR Results");
        System.out.println("======================================");
        System.out.printf("Query: situation_type=TEMPERATURE_ANOMALY, room_type=KITCHEN%n%n");
        System.out.printf("%d similar past situations found (of %d in case base)%n%n",
            results.size(), SEED_CASES.size());

        for (int i = 0; i < results.size(); i++) {
            var c = results.get(i).scored().cbrCase();
            var timeOfDay = c.features().get("time_of_day");
            var severity = c.features().get("severity");
            System.out.printf("  #%d [%.2f] %s — %s, %s%n", i + 1,
                results.get(i).scored().score(), c.outcome(), timeOfDay, severity);
            System.out.printf("            \"%s\"%n", c.problem());
            System.out.printf("            %s%n%n", truncate(c.solution(), 80));
        }

        long dismissedCount = results.stream()
            .filter(r -> "OPERATOR_DISMISSED".equals(r.scored().cbrCase().outcome()))
            .count();
        long genuineCount = results.stream()
            .filter(r -> "WORK_ITEM_CREATED".equals(r.scored().cbrCase().outcome()) ||
                         "ESCALATED".equals(r.scored().cbrCase().outcome()))
            .count();

        int falsePositiveRate = results.isEmpty() ? 0 : (int) (dismissedCount * 100 / results.size());
        int genuineRate = results.isEmpty() ? 0 : (int) (genuineCount * 100 / results.size());

        System.out.printf("Summary: %d%% false positive rate for kitchen temperature anomalies (cooking-related).%n",
            falsePositiveRate);
        System.out.printf("         %d%% genuine — boiler/ventilation issues requiring work items.%n", genuineRate);
        System.out.printf("         Suggestion: if oven/hob active, auto-downgrade to LOW severity.%n");
        System.out.printf("         If no cooking appliance active, maintain MEDIUM and alert operator.%n");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public static void main(String[] args) {
        var store = new InMemoryCbrCaseMemoryStore();
        printResults(run(store));
    }
}
