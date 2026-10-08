package io.casehub.neocortex.knowledge.normalization;

import io.casehub.neocortex.knowledge.ExpandedTerm;
import io.casehub.neocortex.knowledge.TermNormalizer;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.language.DoubleMetaphone;

import java.util.Set;

@ApplicationScoped
public class PhoneticTermNormalizer implements TermNormalizer {

    private final DoubleMetaphone metaphone = new DoubleMetaphone();

    @Override
    public ExpandedTerm normalize(String term, String domain) {
        if (term == null || term.isBlank()) {
            String safe = term == null ? "" : term;
            return new ExpandedTerm(safe, Set.of(safe));
        }
        String normalized = term.trim().toLowerCase();
        String code = metaphone.doubleMetaphone(normalized);
        if (code == null || code.isEmpty()) {
            return ExpandedTerm.passthrough(term);
        }
        return new ExpandedTerm(normalized, Set.of(normalized, "phonetic:" + code));
    }
}
