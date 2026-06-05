package tools.deemwar.mochallama.cli;

import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "sessions", mixinStandardHelpOptions = true,
        description = "List saved chat sessions you can resume with `chat --resume <id>`.")
final class SessionsCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        SessionStore store = new SessionStore();
        List<ChatSession> sessions = store.list();

        if (sessions.isEmpty()) {
            System.out.println("No sessions yet. Start one with: mochallama chat");
            return 0;
        }

        System.out.printf("%-10s  %-20s  %-5s  %s%n", "ID", "MODEL", "TURNS", "UPDATED");
        System.out.printf("%-10s  %-20s  %-5s  %s%n", "--", "-----", "-----", "-------");
        for (ChatSession s : sessions) {
            // A stored model can be a long HF id or an absolute .gguf path; keep
            // the columns aligned by truncating it to the field width.
            System.out.printf("%-10s  %-20s  %-5d  %s%n",
                    s.id, truncate(s.model, 20), s.userTurns(), s.updatedAt);
        }

        System.out.println();
        System.out.println("Resume one with: mochallama chat --resume <id>");
        System.out.printf("Sessions dir: %s%n", ModelRegistry.CACHE_DIR.resolve("sessions"));
        return 0;
    }

    /** Clip {@code s} to {@code max} chars, ending with an ellipsis when shortened. */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }
}
