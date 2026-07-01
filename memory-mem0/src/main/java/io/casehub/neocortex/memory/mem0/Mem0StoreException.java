package io.casehub.neocortex.memory.mem0;

public class Mem0StoreException extends RuntimeException {

    private final int status;

    public Mem0StoreException(String message) {
        super(message);
        this.status = -1;
    }

    public Mem0StoreException(int status, String body) {
        super("Mem0 returned HTTP " + status + ": " + body);
        this.status = status;
    }

    public Mem0StoreException(int status, String body, Throwable cause) {
        super("Mem0 returned HTTP " + status + ": " + body, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
