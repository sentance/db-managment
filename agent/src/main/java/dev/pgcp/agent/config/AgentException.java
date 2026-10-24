package dev.pgcp.agent.config;

/** Error with a stable code that maps to an HTTP status in the API layer. */
public class AgentException extends RuntimeException {

    public enum Kind {
        BAD_REQUEST,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT,
        INSUFFICIENT_RESOURCES,
        FAILED
    }

    private final Kind kind;

    public AgentException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AgentException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static AgentException badRequest(String message) {
        return new AgentException(Kind.BAD_REQUEST, message);
    }

    public static AgentException forbidden(String message) {
        return new AgentException(Kind.FORBIDDEN, message);
    }

    public static AgentException notFound(String message) {
        return new AgentException(Kind.NOT_FOUND, message);
    }

    public static AgentException conflict(String message) {
        return new AgentException(Kind.CONFLICT, message);
    }
}
