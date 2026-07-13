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

public final class AmlInvestigationDemo {

    static final MemoryDomain DOMAIN = new MemoryDomain("aml");
    static final String TENANT = "demo";
    static final String CASE_TYPE = "aml-investigation";

    static final CbrFeatureSchema SCHEMA = CbrFeatureSchema.of(CASE_TYPE,
        FeatureField.categorical("transaction_pattern"),
        FeatureField.categorical("entity_risk_tier"),
        FeatureField.categorical("jurisdiction"),
        FeatureField.categorical("amount_range"),
        FeatureField.numeric("prior_sars_on_entity", 0, 100),
        FeatureField.text("investigation_narrative"));

    record SeedCase(String problem, String solution, String outcome,
                    double confidence, Map<String, FeatureValue> features) {}

    public record Result(ScoredCbrCase<FeatureVectorCbrCase> scored) {}

    static final List<SeedCase> SEED_CASES = List.of(
        new SeedCase(
            "Multiple cash deposits under $10,000 across 3 branches within 48 hours, beneficial owner linked to shell company in Cyprus",
            "Evidence path: bank statement analysis → beneficial ownership → SAR. Duration: 8 days.",
            "SAR_FILED", 0.92,
            Map.of("transaction_pattern", string("STRUCTURING"), "entity_risk_tier", string("HIGH"),
                   "jurisdiction", string("CY"), "amount_range", string("50K-100K"),
                   "prior_sars_on_entity", number(2),
                   "investigation_narrative", string("Multiple cash deposits under $10,000 across 3 branches within 48 hours"))),
        new SeedCase(
            "Series of $9,000 cash deposits at different branches over two weeks, HIGH risk entity, Cyprus shell company",
            "Evidence path: transaction pattern → KYC gaps → SAR. Duration: 14 days.",
            "SAR_FILED", 0.88,
            Map.of("transaction_pattern", string("STRUCTURING"), "entity_risk_tier", string("HIGH"),
                   "jurisdiction", string("CY"), "amount_range", string("50K-100K"),
                   "prior_sars_on_entity", number(0),
                   "investigation_narrative", string("Series of $9,000 cash deposits at different branches"))),
        new SeedCase(
            "Cash deposits just below reporting threshold from PEP-linked entity, funds moved to UK account",
            "Evidence path: transaction analysis → enhanced due diligence → SAR. Duration: 12 days.",
            "SAR_FILED", 0.85,
            Map.of("transaction_pattern", string("STRUCTURING"), "entity_risk_tier", string("PEP"),
                   "jurisdiction", string("GB"), "amount_range", string("100K-500K"),
                   "prior_sars_on_entity", number(1),
                   "investigation_narrative", string("Cash deposits from PEP-linked entity, funds transferred to UK"))),
        new SeedCase(
            "Regular cash deposits under $10,000 from seasonal business — ice cream van operator with documented revenue",
            "Evidence path: bank statement → business verification → cleared. Duration: 6 days.",
            "CLEARED", 0.95,
            Map.of("transaction_pattern", string("STRUCTURING"), "entity_risk_tier", string("LOW"),
                   "jurisdiction", string("US"), "amount_range", string("10K-50K"),
                   "prior_sars_on_entity", number(0),
                   "investigation_narrative", string("Regular cash deposits from seasonal business"))),
        new SeedCase(
            "Funds moved through 5 intermediary accounts across 3 jurisdictions before reaching final beneficiary",
            "Evidence path: network analysis → cross-border trace → SAR. Duration: 21 days.",
            "SAR_FILED", 0.91,
            Map.of("transaction_pattern", string("LAYERING"), "entity_risk_tier", string("HIGH"),
                   "jurisdiction", string("MT"), "amount_range", string("500K+"),
                   "prior_sars_on_entity", number(3),
                   "investigation_narrative", string("Funds layered through 5 intermediary accounts across 3 jurisdictions"))),
        new SeedCase(
            "Multiple small wire transfers to same beneficiary from different source accounts, Malta corridor",
            "Evidence path: network mapping → KYC review → monitoring. Duration: 10 days.",
            "ESCALATED", 0.72,
            Map.of("transaction_pattern", string("LAYERING"), "entity_risk_tier", string("MEDIUM"),
                   "jurisdiction", string("MT"), "amount_range", string("100K-500K"),
                   "prior_sars_on_entity", number(0),
                   "investigation_narrative", string("Small wire transfers from multiple source accounts to single beneficiary"))),
        new SeedCase(
            "10 individuals each depositing $9,500 at different branches on the same day for the same entity",
            "Evidence path: coordinated deposit analysis → smurfing pattern confirmed → SAR. Duration: 5 days.",
            "SAR_FILED", 0.96,
            Map.of("transaction_pattern", string("SMURFING"), "entity_risk_tier", string("HIGH"),
                   "jurisdiction", string("US"), "amount_range", string("50K-100K"),
                   "prior_sars_on_entity", number(0),
                   "investigation_narrative", string("Coordinated deposits by 10 individuals at different branches"))),
        new SeedCase(
            "Wire transfer sent and returned between two related entities in different jurisdictions, no economic purpose",
            "Evidence path: transaction trace → related party analysis → SAR. Duration: 9 days.",
            "SAR_FILED", 0.89,
            Map.of("transaction_pattern", string("ROUND_TRIP"), "entity_risk_tier", string("MEDIUM"),
                   "jurisdiction", string("LU"), "amount_range", string("100K-500K"),
                   "prior_sars_on_entity", number(1),
                   "investigation_narrative", string("Wire transfer round-trip between related entities"))),
        new SeedCase(
            "Large cash deposit from business with documented high cash turnover — cleared after enhanced due diligence",
            "Evidence path: business verification → site visit → cleared. Duration: 4 days.",
            "CLEARED", 0.97,
            Map.of("transaction_pattern", string("LAYERING"), "entity_risk_tier", string("LOW"),
                   "jurisdiction", string("DE"), "amount_range", string("50K-100K"),
                   "prior_sars_on_entity", number(0),
                   "investigation_narrative", string("Large cash deposit from legitimate high-turnover business"))),
        new SeedCase(
            "Series of just-below-threshold international wires from PEP entity, rapid velocity over 72 hours",
            "Evidence path: velocity analysis → PEP screening → SAR. Duration: 7 days.",
            "SAR_FILED", 0.90,
            Map.of("transaction_pattern", string("SMURFING"), "entity_risk_tier", string("PEP"),
                   "jurisdiction", string("CY"), "amount_range", string("100K-500K"),
                   "prior_sars_on_entity", number(4),
                   "investigation_narrative", string("Below-threshold international wires from PEP entity")))
    );

    public static List<Result> run(CbrCaseMemoryStore store) {
        store.registerSchema(SCHEMA);

        for (var seed : SEED_CASES) {
            var cbrCase = new FeatureVectorCbrCase(
                seed.problem(), seed.solution(), seed.outcome(), seed.confidence(), seed.features());
            store.store(cbrCase, CASE_TYPE, UUID.randomUUID().toString(), DOMAIN, TENANT, UUID.randomUUID().toString());
        }

        var query = CbrQuery.of(TENANT, DOMAIN, CASE_TYPE,
            Map.of("transaction_pattern", string("STRUCTURING")), 10);

        return store.retrieveSimilar(query, FeatureVectorCbrCase.class).stream()
            .map(Result::new)
            .toList();
    }

    static void printResults(List<Result> results) {
        System.out.println("AML Investigation — CBR Results");
        System.out.println("================================");
        System.out.printf("Query: transaction_pattern=STRUCTURING — new entity alert%n%n");
        System.out.printf("%d similar investigations found (of %d in case base)%n%n",
            results.size(), SEED_CASES.size());

        for (int i = 0; i < results.size(); i++) {
            var c = results.get(i).scored().cbrCase();
            System.out.printf("  #%d [%.2f] %s — %s%n", i + 1,
                results.get(i).scored().score(), c.outcome(), truncate(c.problem(), 70));
            System.out.printf("            %s%n%n", c.solution());
        }

        long sarCount = results.stream()
            .filter(r -> "SAR_FILED".equals(r.scored().cbrCase().outcome()))
            .count();
        System.out.printf("Summary: %d%% SAR filing rate for STRUCTURING cases.%n",
            results.isEmpty() ? 0 : sarCount * 100 / results.size());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public static void main(String[] args) {
        var store = new InMemoryCbrCaseMemoryStore();
        printResults(run(store));
    }
}
