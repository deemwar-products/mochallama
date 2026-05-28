package tools.deemwar.mochallama;

import  tools.deemwar.mochallama.Llama3.Options;

import java.io.IOException;
import java.nio.file.Paths;

import static tools.deemwar.mochallama.Llama3.selectSampler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class ChatBot {
    private Llama model;
    private Sampler sampler;
    private Options options;

    /**
     * Loads the model with the specified parameters.
     *
     * @param modelPath the path to the model file
     * @param maxTokens the maximum number of tokens
     * @param seed the seed for random number generation
     * @param stream specifies if the response should be streamed
     * @throws IOException if an I/O error occurs
     */

    private static final String MODEL_BASE_URL = "https://huggingface.co/";



    private Path downloadModelIfNotExists(String modelName,String fileName) throws IOException {
        Path cacheDir = Paths.get(System.getProperty("user.home"), ".chatbot_models");
        Files.createDirectories(cacheDir);  // Ensure the cache directory exists

//        String fileName = modelName + ".gguf";
        Path modelPath = cacheDir.resolve(fileName);

        if (Files.exists(modelPath)) {
            System.out.println("Model already downloaded.");
            return modelPath;
        }
        //https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf?download=true

        String modelUrl = MODEL_BASE_URL + modelName + "/resolve/main/" + fileName +"?download=true";
        URL url = new URL(modelUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        System.out.println("Downloading model...");
        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, modelPath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }

        System.out.println("Model downloaded to " + modelPath);
        return modelPath;
    }


//    private Llama model;
//    private Sampler sampler;
//    private Options options;
    private Llama.State state;
    private List<Integer> conversationTokens;
    private int startPosition;
    private ChatFormat chatFormat;





    public void loadModel(String modelName,String filename, int maxTokens, int seed, boolean stream) throws IOException {
//        public void loadModel(String modelPath, int maxTokens, long seed, boolean stream) throws IOException {
            // Set default or null values for parameters not provided explicitly
            float defaultTemperature = 0.1f; // default temperature
            float defaultTopp = 0.95f; // default top-p value
            String defaultPrompt = null; // no default prompt
            String defaultSystemPrompt = null; // no default system prompt
            boolean defaultInteractive = true; // default to non-interactive mode
            boolean defaultEcho = false; // default echo setting
        Path modelPath = downloadModelIfNotExists(modelName,filename);
            // Create a new Options object with a mix of provided and default values
            this.options = new Options(
                    modelPath,     // Convert string path to Path object
                    defaultPrompt,
                    defaultSystemPrompt,
                    defaultInteractive,
                    defaultTemperature,
                    defaultTopp,
                    seed,
                    maxTokens,
                    stream,
                    defaultEcho
            );

            // Assuming ModelLoader.loadModel and other subsequent method calls are implemented elsewhere
            this.model = ModelLoader.loadModel(options.modelPath(), options.maxTokens());
            this.sampler = selectSampler(model.configuration().vocabularySize, options.temperature(), options.topp(), options.seed());


//        this.model = model;
//        this.sampler = sampler;
//        this.options = options;
        this.chatFormat = new ChatFormat(model.tokenizer());
        this.conversationTokens = new ArrayList<>();
        if (options.systemPrompt() != null) {
            this.conversationTokens.addAll(chatFormat.encodeMessage(new ChatFormat.Message(ChatFormat.Role.SYSTEM, options.systemPrompt())));
        }
        this.startPosition = 0;
        this.state = null;

          }

    /**
     * Generates a response to the given question using the loaded model.
     *
     * @param question the user's question
     * @return the model's response
     */
    public String chat(String question) {
        Llama.State state = model.createNewState();
        ChatFormat chatFormat = new ChatFormat(model.tokenizer());

        List<Integer> promptTokens = new ArrayList<>();
        promptTokens.add(chatFormat.beginOfText);
        promptTokens.addAll(chatFormat.encodeMessage(new ChatFormat.Message(ChatFormat.Role.USER, question)));
        promptTokens.addAll(chatFormat.encodeHeader(new ChatFormat.Message(ChatFormat.Role.ASSISTANT, "")));

        Set<Integer> stopTokens = chatFormat.getStopTokens();
        List<Integer> responseTokens = Llama.generateTokens(model, state, 0, promptTokens, stopTokens, options.maxTokens(), sampler, options.echo(), token -> {
            if (options.stream() && !model.tokenizer().isSpecialToken(token)) {
                System.out.print(model.tokenizer().decode(List.of(token)));
            }
        });

        if (!responseTokens.isEmpty() && stopTokens.contains(responseTokens.get(responseTokens.size() - 1))) {
            responseTokens.remove(responseTokens.size() - 1);
        }

        return options.stream() ? "" : model.tokenizer().decode(responseTokens);
    }


    public String chatNew(String userText) {
        if (this.state == null) {
            this.state = model.createNewState();
        }

        this.conversationTokens.addAll(chatFormat.encodeMessage(new ChatFormat.Message(ChatFormat.Role.USER, userText)));
        this.conversationTokens.addAll(chatFormat.encodeHeader(new ChatFormat.Message(ChatFormat.Role.ASSISTANT, "")));

        Set<Integer> stopTokens = chatFormat.getStopTokens();
        List<Integer> responseTokens = Llama.generateTokens(model, state, startPosition, conversationTokens.subList(startPosition, conversationTokens.size()), stopTokens, options.maxTokens(), sampler, options.echo(), token -> {
            if (options.stream() && !model.tokenizer().isSpecialToken(token)) {
                System.out.print(model.tokenizer().decode(List.of(token)));
            }
        });

        // Update conversation history
        this.conversationTokens.addAll(responseTokens);
        this.startPosition = conversationTokens.size();

        // Process response for display
        Integer stopToken = null;
        if (!responseTokens.isEmpty() && stopTokens.contains(responseTokens.get(responseTokens.size() - 1))) {
            stopToken = responseTokens.get(responseTokens.size() - 1);
            responseTokens.remove(responseTokens.size() - 1);
        }

        return options.stream() ? "" : model.tokenizer().decode(responseTokens);
    }
}
