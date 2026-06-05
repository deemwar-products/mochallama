package tools.deemwar.mochallama.cli;

import tools.deemwar.mochallama.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A persisted chat conversation — the on-disk shape of a resumable session.
 *
 * <p>Jackson-serializable: public fields + a no-arg constructor. The stored
 * {@link #model} is the ORIGINAL ref string the user typed (e.g. {@code
 * "qwen2.5-1.5b"}, an HF id, or a path) — NOT the resolved GGUF path — so that
 * {@code --resume} restores exactly the model the session ran with.
 *
 * <p>Timestamps are ISO-8601 strings ({@link Instant#toString()}).
 */
final class ChatSession {

    /** 8-hex-char id (see {@link SessionStore#newId()}). */
    public String id;

    /** Original model ref string the session was started with. */
    public String model;

    /** ISO-8601 instant the session was first created. */
    public String createdAt;

    /** ISO-8601 instant of the last update (refreshed on every save). */
    public String updatedAt;

    /** The conversation, oldest turn first. */
    public List<Turn> messages = new ArrayList<>();

    /** Jackson needs a no-arg constructor. */
    ChatSession() {}

    /** One stored message: a role ("user"/"assistant"/"system"/"tool") + content. */
    static final class Turn {
        public String role;
        public String content;

        /** Jackson needs a no-arg constructor. */
        Turn() {}

        Turn(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /** Append a turn from a raw role + content pair. */
    void add(String role, String content) {
        messages.add(new Turn(role, content));
    }

    /** Replace the stored turns with the live core history (used before save). */
    void setFromMessages(List<Message> history) {
        List<Turn> turns = new ArrayList<>(history.size());
        for (Message m : history) {
            turns.add(new Turn(m.role(), m.content()));
        }
        this.messages = turns;
    }

    /** Convert the stored turns into core {@link Message}s to seed the engine. */
    List<Message> toMessages() {
        List<Message> out = new ArrayList<>(messages.size());
        for (Turn t : messages) {
            out.add(new Message(t.role, t.content));
        }
        return out;
    }

    /** Number of user turns — what a human means by "how many turns". */
    int userTurns() {
        int n = 0;
        for (Turn t : messages) {
            if ("user".equals(t.role)) {
                n++;
            }
        }
        return n;
    }
}
