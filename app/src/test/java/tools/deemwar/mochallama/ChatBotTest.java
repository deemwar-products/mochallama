package tools.deemwar.mochallama;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ChatBotTest {
    private ChatBot chatBot;

    @BeforeEach
    void setUp() {
        chatBot = new ChatBot();
    }

    @Test
    void testModelDownloadAndLoad() {
        // Switched from microsoft/Phi-3-mini-4k-instruct-gguf because this project's
        // ModelLoader is Llama-3-specific (parses tokenizer.ggml.merges which Phi-3 lacks).
        // Using mukel's repo because bartowski's modern quants mix in Q6_K tensors which
        // this loader does not support (only Q4_0 and Q8_0).
        String modelName = "mukel/Llama-3.2-1B-Instruct-GGUF";
        int maxTokens = 50;
        int seed = 42;
        boolean stream = false;

        try {
            // Ensure model is loaded
            String filename="Llama-3.2-1B-Instruct-Q4_0.gguf";
            long t0 = System.nanoTime();
            chatBot.loadModel(modelName,filename, maxTokens, seed, stream);
            long t1 = System.nanoTime();
            System.out.println("[BENCH] model load took " + ((t1 - t0) / 1_000_000) + " ms");

            // Verify the model file exists in the cache directory
            Path modelPath = Paths.get(System.getProperty("user.home"), ".chatbot_models", filename);
            assertTrue(Files.exists(modelPath), "Model file should exist after loading");

            // Now run actual inference and measure tokens
            String prompt = "Say hello in one sentence.";
            long g0 = System.nanoTime();
            String response = chatBot.chat(prompt);
            long g1 = System.nanoTime();
            double elapsedSec = (g1 - g0) / 1_000_000_000.0;
            System.out.println("[BENCH] generation wall-clock: " + String.format("%.2f", elapsedSec) + " s");
            System.out.println("[BENCH] prompt: " + prompt);
            System.out.println("[BENCH] response: " + response);
            assertTrue(response != null && !response.isBlank(), "Response should be non-empty");
        } catch (IOException e) {
            fail("Failed to load model due to IOException: " + e.getMessage());
        }
    }
}
