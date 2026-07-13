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

public final class ClinicalAdverseEventDemo {

    static final MemoryDomain DOMAIN = new MemoryDomain("clinical");
    static final String TENANT = "demo";
    static final String CASE_TYPE = "clinical-adverse-event";

    static final CbrFeatureSchema SCHEMA = CbrFeatureSchema.of(CASE_TYPE,
        FeatureField.categorical("adverse_event_type"),
        FeatureField.categorical("trial_arm"),
        FeatureField.numeric("severity_grade", 1, 5),
        FeatureField.numeric("time_to_onset_days", 0, 365),
        FeatureField.text("event_description"));

    record SeedCase(String problem, String solution, String outcome,
                    double confidence, Map<String, FeatureValue> features) {}

    public record Result(ScoredCbrCase<FeatureVectorCbrCase> scored) {}

    static final List<SeedCase> SEED_CASES = List.of(
        new SeedCase(
            "Grade 3 hepatotoxicity in treatment arm patient, day 18 — ALT 5.2x ULN, bilirubin normal",
            "Causality assessment: Probable. Action: dose reduction + liver function monitoring weekly. Resolution: ALT normalized by day 42.",
            "SAFETY_PROTOCOL", 0.89,
            Map.of("adverse_event_type", string("Hepatotoxicity"), "trial_arm", string("TREATMENT"),
                   "severity_grade", number(3), "time_to_onset_days", number(18),
                   "event_description", string("Grade 3 hepatotoxicity, day 18, no concurrent hepatotoxic medications"))),
        new SeedCase(
            "Grade 3 hepatotoxicity in treatment arm patient, day 24 — ALT 4.8x ULN, patient on concurrent statin",
            "Causality assessment: Possible. Action: treatment hold pending liver panel. Statin discontinued. Resolution: ALT normalized by day 60.",
            "SAFETY_PROTOCOL", 0.78,
            Map.of("adverse_event_type", string("Hepatotoxicity"), "trial_arm", string("TREATMENT"),
                   "severity_grade", number(3), "time_to_onset_days", number(24),
                   "event_description", string("Grade 3 hepatotoxicity, day 24, concurrent statin use"))),
        new SeedCase(
            "Grade 2 hepatotoxicity progressed to grade 3 in treatment arm, day 10 to day 14 — rapid progression",
            "Causality assessment: Probable. Action: dose modification protocol activated, daily monitoring. Resolution: stabilized at grade 2 by day 21.",
            "SAFETY_PROTOCOL", 0.91,
            Map.of("adverse_event_type", string("Hepatotoxicity"), "trial_arm", string("TREATMENT"),
                   "severity_grade", number(3), "time_to_onset_days", number(14),
                   "event_description", string("Rapid progression from grade 2 (day 10) to grade 3 (day 14)"))),
        new SeedCase(
            "Grade 1 hepatotoxicity in control arm, day 30 — transient, self-limiting",
            "Causality assessment: Possible. Action: monitoring only, no intervention. Resolution: ALT normalized spontaneously by day 45.",
            "MONITORING", 0.65,
            Map.of("adverse_event_type", string("Hepatotoxicity"), "trial_arm", string("CONTROL"),
                   "severity_grade", number(1), "time_to_onset_days", number(30),
                   "event_description", string("Grade 1 transient hepatotoxicity"))),
        new SeedCase(
            "Grade 4 neutropenia in treatment arm, day 7 — absolute neutrophil count 0.3 × 10^9/L, no fever",
            "Causality assessment: Certain. Action: G-CSF initiated, treatment paused. Resolution: ANC recovered by day 21, treatment resumed at reduced dose.",
            "SAFETY_PROTOCOL", 0.96,
            Map.of("adverse_event_type", string("Neutropenia"), "trial_arm", string("TREATMENT"),
                   "severity_grade", number(4), "time_to_onset_days", number(7),
                   "event_description", string("Grade 4 neutropenia, day 7, G-CSF initiated"))),
        new SeedCase(
            "Grade 3 neutropenia in control arm, day 14 — concurrent infection, unrelated to study drug",
            "Causality assessment: Unrelated. Action: infection treated with antibiotics, no protocol modification. Resolution: ANC normalized by day 28.",
            "CLEARED", 0.82,
            Map.of("adverse_event_type", string("Neutropenia"), "trial_arm", string("CONTROL"),
                   "severity_grade", number(3), "time_to_onset_days", number(14),
                   "event_description", string("Grade 3 neutropenia, concurrent infection in control arm"))),
        new SeedCase(
            "Grade 2 nephrotoxicity in treatment arm, day 60 — creatinine 1.8x baseline, reversible",
            "Causality assessment: Probable. Action: hydration protocol, dose reduction. Resolution: creatinine returned to baseline by day 90.",
            "SAFETY_PROTOCOL", 0.84,
            Map.of("adverse_event_type", string("Nephrotoxicity"), "trial_arm", string("TREATMENT"),
                   "severity_grade", number(2), "time_to_onset_days", number(60),
                   "event_description", string("Grade 2 nephrotoxicity, day 60, reversible with hydration"))),
        new SeedCase(
            "Grade 1 nephrotoxicity in treatment arm, day 45 — creatinine 1.2x baseline, no intervention",
            "Causality assessment: Possible. Action: monitoring only. Resolution: stable, no progression through day 180.",
            "MONITORING", 0.71,
            Map.of("adverse_event_type", string("Nephrotoxicity"), "trial_arm", string("TREATMENT"),
                   "severity_grade", number(1), "time_to_onset_days", number(45),
                   "event_description", string("Grade 1 nephrotoxicity, stable"))),
        new SeedCase(
            "Grade 3 nephrotoxicity in control arm, day 120 — pre-existing renal impairment, unrelated to study drug",
            "Causality assessment: Unrelated. Action: patient withdrawn from study. Resolution: managed by nephrology, no protocol changes.",
            "CLEARED", 0.68,
            Map.of("adverse_event_type", string("Nephrotoxicity"), "trial_arm", string("CONTROL"),
                   "severity_grade", number(3), "time_to_onset_days", number(120),
                   "event_description", string("Grade 3 nephrotoxicity, pre-existing condition, control arm"))),
        new SeedCase(
            "Grade 2 neutropenia in treatment arm, day 21 — mild, no clinical sequelae",
            "Causality assessment: Probable. Action: continued monitoring, no dose modification. Resolution: ANC normalized by day 35.",
            "MONITORING", 0.79,
            Map.of("adverse_event_type", string("Neutropenia"), "trial_arm", string("TREATMENT"),
                   "severity_grade", number(2), "time_to_onset_days", number(21),
                   "event_description", string("Grade 2 neutropenia, mild, no intervention needed")))
    );

    public static List<Result> run(CbrCaseMemoryStore store) {
        store.registerSchema(SCHEMA);

        for (var seed : SEED_CASES) {
            var cbrCase = new FeatureVectorCbrCase(
                seed.problem(), seed.solution(), seed.outcome(), seed.confidence(), seed.features());
            store.store(cbrCase, CASE_TYPE, UUID.randomUUID().toString(), DOMAIN, TENANT, UUID.randomUUID().toString());
        }

        var query = CbrQuery.of(TENANT, DOMAIN, CASE_TYPE,
            Map.of("adverse_event_type", string("Hepatotoxicity"), "trial_arm", string("TREATMENT")), 10);

        return store.retrieveSimilar(query, FeatureVectorCbrCase.class).stream()
            .map(Result::new)
            .toList();
    }

    static void printResults(List<Result> results) {
        System.out.println("Clinical Adverse Event — CBR Results");
        System.out.println("=====================================");
        System.out.printf("Query: adverse_event_type=Hepatotoxicity, trial_arm=TREATMENT%n%n");
        System.out.printf("%d similar adverse events found (of %d in case base)%n%n",
            results.size(), SEED_CASES.size());

        for (int i = 0; i < results.size(); i++) {
            var c = results.get(i).scored().cbrCase();
            var grade = c.features().get("severity_grade");
            var day = c.features().get("time_to_onset_days");
            System.out.printf("  #%d [%.2f] %s — Grade %s, day %s%n", i + 1,
                results.get(i).scored().score(), c.outcome(), grade, day);
            System.out.printf("            %s%n", truncate(c.solution(), 90));
            var desc = c.features().get("event_description");
            System.out.printf("            Note: %s%n%n", desc);
        }

        long protocolCount = results.stream()
            .filter(r -> "SAFETY_PROTOCOL".equals(r.scored().cbrCase().outcome()))
            .count();
        var onsetDays = results.stream()
            .map(r -> ((int) ((FeatureValue.NumberVal) r.scored().cbrCase().features().get("time_to_onset_days")).value()))
            .sorted()
            .toList();
        int medianOnset = onsetDays.isEmpty() ? 0 : onsetDays.get(onsetDays.size() / 2);

        System.out.printf("Summary: %d%% safety protocol trigger rate for similar events.%n",
            results.isEmpty() ? 0 : protocolCount * 100 / results.size());
        System.out.printf("         Median onset: day %d.%n", medianOnset);

        boolean statinFound = results.stream()
            .anyMatch(r -> r.scored().cbrCase().features().get("event_description").toString()
                .contains("statin"));
        if (statinFound) {
            System.out.printf("         ⚠ 1 case involved concurrent statin — assess concomitant medications.%n");
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public static void main(String[] args) {
        var store = new InMemoryCbrCaseMemoryStore();
        printResults(run(store));
    }
}
