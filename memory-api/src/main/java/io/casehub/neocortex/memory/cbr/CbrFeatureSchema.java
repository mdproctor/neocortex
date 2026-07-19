package io.casehub.neocortex.memory.cbr;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record CbrFeatureSchema(String caseType, List<FeatureField> fields, Double learningRate) {
    public CbrFeatureSchema {
        Objects.requireNonNull(caseType, "caseType required");
        if (caseType.isBlank()) {throw new IllegalArgumentException("caseType must not be blank");}
        Objects.requireNonNull(fields, "fields required");
        if (learningRate != null && (learningRate < 0.0 || learningRate > 1.0)) {
            throw new IllegalArgumentException("learningRate must be in [0,1], got: " + learningRate);
        }
        fields = List.copyOf(fields);
        Set<String> names = new HashSet<>();
        for (FeatureField f : fields) {
            if (!names.add(f.name())) {throw new IllegalArgumentException("Duplicate field name: '" + f.name() + "'");}
        }
    }

    public CbrFeatureSchema(String caseType, List<FeatureField> fields) {
        this(caseType, fields, null);
    }

    public static CbrFeatureSchema of(String caseType, FeatureField... fields) {
        return new CbrFeatureSchema(caseType, List.of(fields), null);
    }
}
