package demo;
import tools.deemwar.mochallama.panama.ChatEngine;
import java.nio.file.Path;
public class Main {
    public static void main(String[] args) throws Exception {
        Path gguf = Path.of(System.getProperty("user.home"), ".chatbot_models", "qwen2.5-1.5b-instruct-q4_k_m.gguf");
        try (ChatEngine engine = ChatEngine.load(gguf)) {
            System.out.println(engine.chat("Say hello to mochallama in 5 words.", 32, 0.2));
        }
    }
}
