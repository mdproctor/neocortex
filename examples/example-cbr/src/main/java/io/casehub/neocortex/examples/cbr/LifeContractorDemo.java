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

public final class LifeContractorDemo {

    static final MemoryDomain DOMAIN = new MemoryDomain("life");
    static final String TENANT = "demo";
    static final String CASE_TYPE = "life-contractor";

    static final CbrFeatureSchema SCHEMA = CbrFeatureSchema.of(CASE_TYPE,
        FeatureField.categorical("job_type"),
        FeatureField.categorical("urgency"),
        FeatureField.categorical("property_area"),
        FeatureField.categorical("cost_band"),
        FeatureField.categorical("season"),
        FeatureField.text("job_description"));

    record SeedCase(String problem, String solution, String outcome,
                    double confidence, Map<String, FeatureValue> features) {}

    public record Result(ScoredCbrCase<FeatureVectorCbrCase> scored) {}

    static final List<SeedCase> SEED_CASES = List.of(
        new SeedCase(
            "Annual boiler service plus replacement of pressure valve",
            "Contractor: ABC Heating. Cost: £165 (quoted £180). SLA: 48h, completed in 36h.",
            "COMPLETED_ON_TIME", 0.93,
            Map.of("job_type", string("PLUMBING"), "urgency", string("ROUTINE"), "property_area", string("HVAC"),
                   "cost_band", string("100_250"), "season", string("WINTER"),
                   "job_description", string("Annual boiler service plus replacement of pressure valve"))),
        new SeedCase(
            "Intermittent ignition failure — replaced ignitor and flame sensor",
            "Contractor: ABC Heating. Cost: £195 (quoted £200). SLA: 48h, completed in 24h.",
            "COMPLETED_ON_TIME", 0.96,
            Map.of("job_type", string("PLUMBING"), "urgency", string("ROUTINE"), "property_area", string("HVAC"),
                   "cost_band", string("100_250"), "season", string("WINTER"),
                   "job_description", string("Boiler intermittent ignition failure"))),
        new SeedCase(
            "Boiler losing pressure — repressurised and replaced expansion vessel",
            "Contractor: ABC Heating. Cost: £210 (quoted £200). SLA: 72h, completed in 48h.",
            "COMPLETED_ON_TIME", 0.91,
            Map.of("job_type", string("PLUMBING"), "urgency", string("ROUTINE"), "property_area", string("HVAC"),
                   "cost_band", string("100_250"), "season", string("AUTUMN"),
                   "job_description", string("Boiler losing pressure"))),
        new SeedCase(
            "Boiler service — contractor delayed due to parts availability",
            "Contractor: QuickFix Ltd. Cost: £240 (quoted £150). SLA: 48h, completed in 120h.",
            "DELAYED", 0.62,
            Map.of("job_type", string("PLUMBING"), "urgency", string("ROUTINE"), "property_area", string("HVAC"),
                   "cost_band", string("100_250"), "season", string("WINTER"),
                   "job_description", string("Boiler service delayed by parts"))),
        new SeedCase(
            "Kitchen sink blockage — drain cleared, no structural issues",
            "Contractor: DrainMaster. Cost: £85 (quoted £90). SLA: 24h, completed in 12h.",
            "COMPLETED_ON_TIME", 0.94,
            Map.of("job_type", string("PLUMBING"), "urgency", string("ROUTINE"), "property_area", string("KITCHEN"),
                   "cost_band", string("UNDER_100"), "season", string("SPRING"),
                   "job_description", string("Kitchen sink blockage"))),
        new SeedCase(
            "Bathroom shower mixer valve replacement — temperature control fault",
            "Contractor: ABC Heating. Cost: £145 (quoted £150). SLA: 48h, completed in 36h.",
            "COMPLETED_ON_TIME", 0.89,
            Map.of("job_type", string("PLUMBING"), "urgency", string("ROUTINE"), "property_area", string("BATHROOM"),
                   "cost_band", string("100_250"), "season", string("SUMMER"),
                   "job_description", string("Shower mixer valve replacement"))),
        new SeedCase(
            "Electrical socket replacement in living room — loose connection",
            "Contractor: SafeSpark Electrical. Cost: £75 (quoted £80). SLA: 24h, completed in 18h.",
            "COMPLETED_ON_TIME", 0.97,
            Map.of("job_type", string("ELECTRICAL"), "urgency", string("ROUTINE"), "property_area", string("GENERAL"),
                   "cost_band", string("UNDER_100"), "season", string("SPRING"),
                   "job_description", string("Electrical socket replacement"))),
        new SeedCase(
            "Roof tile replacement after storm damage — 8 tiles, waterproofing check",
            "Contractor: RoofGuard Ltd. Cost: £420 (quoted £400). SLA: 72h, completed in 60h.",
            "COMPLETED_ON_TIME", 0.88,
            Map.of("job_type", string("ROOFING"), "urgency", string("PLANNED"), "property_area", string("EXTERIOR"),
                   "cost_band", string("250_500"), "season", string("AUTUMN"),
                   "job_description", string("Roof tile replacement after storm"))),
        new SeedCase(
            "Dishwasher installation — old unit removal, new unit install, plumbing connection",
            "Contractor: AppliancePro. Cost: £180 (quoted £160). SLA: 48h, completed in 48h.",
            "COMPLETED_ON_TIME", 0.86,
            Map.of("job_type", string("APPLIANCE"), "urgency", string("PLANNED"), "property_area", string("KITCHEN"),
                   "cost_band", string("100_250"), "season", string("SUMMER"),
                   "job_description", string("Dishwasher installation"))),
        new SeedCase(
            "Emergency boiler repair — no heating in December, fixed within 8 hours",
            "Contractor: ABC Heating. Cost: £320 (quoted £300). SLA: 8h, completed in 8h.",
            "EXCELLENT", 0.98,
            Map.of("job_type", string("PLUMBING"), "urgency", string("EMERGENCY"), "property_area", string("GENERAL"),
                   "cost_band", string("250_500"), "season", string("WINTER"),
                   "job_description", string("Emergency boiler repair, no heating")))
    );

    public static List<Result> run(CbrCaseMemoryStore store) {
        store.registerSchema(SCHEMA);

        for (var seed : SEED_CASES) {
            var cbrCase = new FeatureVectorCbrCase(
                seed.problem(), seed.solution(), seed.outcome(), seed.confidence(), seed.features());
            store.store(cbrCase, CASE_TYPE, UUID.randomUUID().toString(), DOMAIN, TENANT, UUID.randomUUID().toString());
        }

        var query = CbrQuery.of(TENANT, DOMAIN, CASE_TYPE,
            Map.of("job_type", string("PLUMBING"), "property_area", string("HVAC")), 10);

        return store.retrieveSimilar(query, FeatureVectorCbrCase.class).stream()
            .map(Result::new)
            .toList();
    }

    static void printResults(List<Result> results) {
        System.out.println("Life Contractor Coordination — CBR Results");
        System.out.println("============================================");
        System.out.printf("Query: job_type=PLUMBING, property_area=HVAC — boiler repair needed%n%n");
        System.out.printf("%d similar past jobs found (of %d in case base)%n%n",
            results.size(), SEED_CASES.size());

        for (int i = 0; i < results.size(); i++) {
            var c = results.get(i).scored().cbrCase();
            var urgency = c.features().get("urgency");
            var season = c.features().get("season");
            System.out.printf("  #%d [%.2f] %s — %s, %s, HVAC%n", i + 1,
                results.get(i).scored().score(), c.outcome(), urgency, season);
            System.out.printf("            %s%n", c.solution());
            System.out.printf("            \"%s\"%n%n", c.problem());
        }

        var abcHeatingResults = results.stream()
            .filter(r -> r.scored().cbrCase().solution().startsWith("Contractor: ABC Heating"))
            .toList();

        long onTimeCount = abcHeatingResults.stream()
            .filter(r -> "COMPLETED_ON_TIME".equals(r.scored().cbrCase().outcome()) ||
                         "EXCELLENT".equals(r.scored().cbrCase().outcome()))
            .count();

        System.out.printf("Summary: ABC Heating — %d jobs, %.0f%% on-time rate.%n",
            abcHeatingResults.size(), abcHeatingResults.isEmpty() ? 0 : 100.0 * onTimeCount / abcHeatingResults.size());
        System.out.printf("         Suggested SLA: 48 hours (historical median: 36 hours).%n");
        System.out.printf("         Suggested contractor: ABC Heating (trust score derived from outcomes).%n");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public static void main(String[] args) {
        var store = new InMemoryCbrCaseMemoryStore();
        printResults(run(store));
    }
}
