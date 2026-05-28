package tools.deemwar.mochallama.cli;

import tools.deemwar.mochallama.LlamaException;
import tools.deemwar.mochallama.ModelInfo;
import tools.deemwar.mochallama.panama.ChatEngine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "chat", mixinStandardHelpOptions = true,
        description = "Load a model and chat interactively (one turn per line; /exit or EOF quits).")
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

    @Override
    public Integer call() throws Exception {
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

        try (ChatEngine eng = engine;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            System.out.println("Ready. Type a message, /exit to quit.\n");
            while (true) {
                System.out.print("you> ");
                System.out.flush();
                String line = in.readLine();
                if (line == null || line.strip().equals("/exit")) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }

                long startNanos = System.nanoTime();
                String reply = eng.chat(line, maxTokens, temperature);
                double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

                System.out.println("bot> " + reply);
                int wordCount = reply.isBlank() ? 0 : reply.strip().split("\\s+").length;
                System.out.printf("     (%.1fs, ~%.1f words/s)%n%n",
                        seconds, seconds > 0 ? wordCount / seconds : 0.0);
            }
        }
        System.out.println("bye.");
        return 0;
    }
}
