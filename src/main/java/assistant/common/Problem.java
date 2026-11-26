package assistant.common;

public final class Problem extends RuntimeException {
    private final int status;
    private final Object current;

    private Problem(int status, String message, Object current) {
        super(message);
        this.status = status;
        this.current = current;
    }

    public int status() { return status; }
    public Object current() { return current; }
    public static Problem invalid(String message) { return new Problem(400, message, null); }
    public static Problem missing(String message) { return new Problem(404, message, null); }
    public static Problem conflict(String message) { return conflict(message, null); }
    public static Problem conflict(String message, Object current) { return new Problem(409, message, current); }
    public static Problem rateLimited(String message) { return new Problem(429, message, null); }
    public static Problem unavailable(String message) { return new Problem(503, message, null); }
}
