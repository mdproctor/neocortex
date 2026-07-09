package io.casehub.neocortex.memory.cbr.qdrant;

public class CbrSparseVectorMigrationException extends RuntimeException {
    private final String collection;

    public CbrSparseVectorMigrationException(String collection) {
        super("Collection " + collection + " is missing required sparse vector definitions. "
            + "Enabling SPLADE/BM25 requires destructive collection recreation (all data lost). "
            + "Set casehub.memory.cbr.qdrant.allow-sparse-vector-migration=true to allow this, "
            + "then run reconciliation per tenant to recover data.");
        this.collection = collection;
    }

    public String collection() { return collection; }
}
