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
        return 0;
    }
}
