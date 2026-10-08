package io.casehub.neocortex.knowledge.normalization;

import io.casehub.neocortex.knowledge.ExpandedTerm;
import io.casehub.neocortex.knowledge.TermNormalizer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import net.sf.extjwnl.data.POS;
import net.sf.extjwnl.dictionary.Dictionary;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class StemmingTermNormalizer implements TermNormalizer {

    private static final Logger LOG = Logger.getLogger(StemmingTermNormalizer.class.getName());

    private Dictionary dictionary;

    @PostConstruct
    void init() {
        try {
            dictionary = Dictionary.getDefaultResourceInstance();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load WordNet dictionary for stemming", e);
        }
    }

    @Override
    public ExpandedTerm normalize(String term, String domain) {
        if (dictionary == null || term == null || term.isBlank()) {
            String safe = term == null ? "" : term;
            return new ExpandedTerm(safe, Set.of(safe));
        }

        try {
            String lookupTerm = term.trim().toLowerCase();
            for (POS pos : List.of(POS.VERB, POS.NOUN, POS.ADJECTIVE, POS.ADVERB)) {
                List<String> baseForms = dictionary.getMorphologicalProcessor()
                                                   .lookupAllBaseForms(pos, lookupTerm);
                if (baseForms != null && !baseForms.isEmpty()) {
                    String baseForm = baseForms.get(0);
                    if (!baseForm.equals(lookupTerm)) {
                        return new ExpandedTerm(baseForm, Set.of(baseForm, lookupTerm));
                    }
                }
            }
            return ExpandedTerm.passthrough(term);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Stemming failed for: " + term, e);
            return ExpandedTerm.passthrough(term);
        }
    }
}
