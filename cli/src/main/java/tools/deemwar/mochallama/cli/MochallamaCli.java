package tools.deemwar.mochallama.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "mochallama",
        mixinStandardHelpOptions = true,
        version = "mochallama-cli 0.1.0",
        description = "Pick a llama.cpp model and chat with it from the terminal.",
        subcommands = {ModelsCommand.class, ChatCommand.class})
public final class MochallamaCli implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new MochallamaCli()).execute(args));
    }
}
