package tools.deemwar.mochallama.cli;

import tools.deemwar.mochallama.GenerationOptions;
import tools.deemwar.mochallama.LlamaException;
import tools.deemwar.mochallama.Message;
import tools.deemwar.mochallama.ChatResult;
import tools.deemwar.mochallama.ModelInfo;
import tools.deemwar.mochallama.panama.ChatEngine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "chat", mixinStandardHelpOptions = true,
        description = "Load a model and chat interactively — a real multi-turn conversation. "
                + "Sessions persist to disk; resume them with --resume. "
                + "(In-chat: /exit or EOF quits, /reset clears history, /help lists commands.)")
final class ChatCommand implements Callable<Integer> {

    @Option(names = {"-m", "--model"}, defaultValue = "qwen2.5-1.5b",
            description = "Built-in profile name, a Hugging Face id (org/repo), or a path "
                    + "to a local .gguf (default: ${DEFAULT-VALUE}). Only tool-capable "
                    + "models are accepted.")
    String model;

    @Option(names = "--max-tokens", defaultValue = "256",
            description = "Max tokens to generate per turn (default: ${DEFAULT-VALUE}).")
    int maxTokens;

    @Option(names = "--temperature", defaultValue = "0.7",
            description = "Sampling temperature (default: ${DEFAULT-VALUE}).")
    double temperature;

    @Option(names = "--resume",
            description = "Resume an existing session by id (see `mochallama sessions`). "
                    + "The session's stored model is restored.")
    String resume;

    @Option(names = "--no-save", negatable = false,
            description = "Ephemeral chat: do not persist the conversation to disk.")
    boolean noSave;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        SessionStore store = new SessionStore();

        // Resolve the session FIRST: a resumed session decides the model.
        ChatSession session;
        List<Message> history = new ArrayList<>();
        if (resume != null) {
            Optional<ChatSession> existing = store.load(resume);
            if (existing.isEmpty()) {
                System.err.println("No session '" + resume + "'. List sessions with: mochallama sessions");
                return 3;
            }
            session = existing.get();
            // The session's model wins over an EXPLICIT -m. Since --model has a
            // default value, picocli always populates it, so a plain null check
            // would fire on every resume; ask the parse result whether the user
            // actually typed -m.
            boolean modelExplicit = spec.commandLine().getParseResult()
                    .hasMatchedOption("--model");
            if (modelExplicit && !model.equals(session.model)) {
                System.out.println("Note: session model '" + session.model
                        + "' overrides --model '" + model + "'.");
            }
            model = session.model;
            history.addAll(session.toMessages());
        } else {
            session = new ChatSession();
            session.id = store.newId();
            session.model = model;
            session.createdAt = Instant.now().toString();
        }

        Path gguf = ModelRegistry.resolve(model);

        // Gate on tool capability before loading — mochallama only runs
        // tool-capable models. inspect() is the cheap pre-flight; load() enforces
        // the same gate authoritatively (throws MODEL_NOT_TOOL_CAPABLE).
        ModelInfo info = ChatEngine.inspect(gguf);
        if (info.ok() && !info.toolCapable()) {
            System.err.println("Refusing to load '" + model + "': its chat template does not "
                    + "support tool calling. mochallama only runs tool-capable models.");
            return 2;
        }

        System.out.println("Loading model: " + gguf);

        ChatEngine engine;
        try {
            engine = ChatEngine.load(gguf);
        } catch (LlamaException e) {
            if ("MODEL_NOT_TOOL_CAPABLE".equals(e.code())) {
                System.err.println("Refusing to load '" + model + "': its chat template does not "
                        + "support tool calling. mochallama only runs tool-capable models.");
                return 2;
            }
            throw e;
        }

        // Build the generation options once — they don't change between turns.
        GenerationOptions opts = GenerationOptions.builder()
                .maxTokens(maxTokens)
                .temperature(temperature)
                .build();

        try (ChatEngine eng = engine;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            System.out.println("session " + session.id);
            if (!noSave) {
                System.out.println("resume later with: mochallama chat --resume " + session.id);
            }
            if (resume != null) {
                System.out.println("Resuming session " + session.id
                        + " (" + session.userTurns() + " turns)");
                if (noSave) {
                    System.out.println("(--no-save: new turns in this resumed session "
                            + "will NOT be persisted)");
                }
            }
            System.out.println("Ready. Type a message, /help for commands, /exit to quit.\n");

            while (true) {
                System.out.print("you> ");
                System.out.flush();
                String line = in.readLine();
                if (line == null || line.strip().equals("/exit")) {
                    break;
                }

                String cmd = line.strip();
                if (cmd.equals("/help")) {
                    System.out.println("Commands:");
                    System.out.println("  /exit   quit");
                    System.out.println("  /reset  clear the conversation history (keeps the session id)");
                    System.out.println("  /help   show this help");
                    continue;
                }
                if (cmd.equals("/reset")) {
                    history.clear();
                    if (!noSave) {
                        session.setFromMessages(history);
                        session.updatedAt = Instant.now().toString();
                        store.save(session);
                    }
                    System.out.println("(history cleared)");
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }

                history.add(Message.user(line));

                long startNanos = System.nanoTime();
                ChatResult r = eng.chat(history, List.of(), opts);
                double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

                String reply = r.text();
                history.add(Message.assistant(reply));

                System.out.println("bot> " + reply);
                int wordCount = reply.isBlank() ? 0 : reply.strip().split("\\s+").length;
                System.out.printf("     (%.1fs, ~%.1f words/s)%n%n",
                        seconds, seconds > 0 ? wordCount / seconds : 0.0);

                if (!noSave) {
                    session.setFromMessages(history);
                    session.updatedAt = Instant.now().toString();
                    store.save(session);
                }
            }
        }
        System.out.println("bye.");
        return 0;
    }
}
