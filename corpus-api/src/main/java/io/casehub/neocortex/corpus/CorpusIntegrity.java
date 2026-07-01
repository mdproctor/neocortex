package io.casehub.neocortex.corpus;

public interface CorpusIntegrity {
    IntegrityReport check();
    IntegrityReport checkAndRecover();
    IntegrityReport fullHashVerification();
}
