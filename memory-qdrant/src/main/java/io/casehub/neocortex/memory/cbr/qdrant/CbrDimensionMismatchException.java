package io.casehub.neocortex.memory.cbr.qdrant;

public class CbrDimensionMismatchException extends RuntimeException {
    private final String collection;
    private final int existingDimension;
    private final int requestedDimension;

    public CbrDimensionMismatchException(String collection, int existingDimension, int requestedDimension) {
        super("Dimension mismatch in collection " + collection + ": existing=" + existingDimension
            + ", requested=" + requestedDimension
            + ". Set casehub.memory.cbr.qdrant.allow-dimension-migration=true to allow destructive recreation.");
        this.collection = collection;
        this.existingDimension = existingDimension;
        this.requestedDimension = requestedDimension;
    }

    public String collection() { return collection; }
    public int existingDimension() { return existingDimension; }
    public int requestedDimension() { return requestedDimension; }
}
