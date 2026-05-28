package tools.deemwar.mochallama.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "models", mixinStandardHelpOptions = true,
        description = "List the built-in model profiles and whether they are cached locally.")
final class ModelsCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.printf("%-14s  %-40s  %-9s  %s%n", "PROFILE", "FILENAME", "SIZE", "CACHED");
        System.out.printf("%-14s  %-40s  %-9s  %s%n", "-------", "--------", "----", "------");
        for (ModelRegistry.Profile p : ModelRegistry.profiles().values()) {
            System.out.printf("%-14s  %-40s  %-9s  %s%n",
                    p.name(), p.filename(), p.sizeHint(),
                    ModelRegistry.isCached(p) ? "yes" : "no");
        }
        System.out.printf("%nCache dir: %s%n", ModelRegistry.CACHE_DIR);
        System.out.println();
        System.out.println("These built-ins are the tool-only lineup shared with the Spring app.");
        System.out.println("Any tool-capable Hugging Face id also works, e.g.:");
        System.out.println("  mochallama chat --model Qwen/Qwen2.5-3B-Instruct-GGUF");
        System.out.println("Non-tool-capable models are refused at load.");
        return 0;
    }
}
