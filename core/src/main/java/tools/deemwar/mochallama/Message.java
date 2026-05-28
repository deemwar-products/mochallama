package tools.deemwar.mochallama;

import java.util.Objects;

/**
 * A single chat message: a {@code role} ("system", "user", "assistant" or
 * "tool") and its textual {@code content}.
 */
public final class Message {

    private final String role;
    private final String content;

    public Message(String role, String content) {
        this.role = Objects.requireNonNull(role, "role");
        this.content = content == null ? "" : content;
    }

    public static Message system(String content)    { return new Message("system", content); }
    public static Message user(String content)       { return new Message("user", content); }
    public static Message assistant(String content)  { return new Message("assistant", content); }
    public static Message tool(String content)       { return new Message("tool", content); }

    public String role()    { return role; }
    public String content() { return content; }

    @Override
    public String toString() {
        return "Message{role='" + role + "', content='" + content + "'}";
    }
}
