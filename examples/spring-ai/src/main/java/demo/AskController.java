package demo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;
@RestController
class AskController {
    private final ChatClient chat;
    AskController(ChatClient chat) { this.chat = chat; }
    @PostMapping("/ask")
    String ask(@RequestBody String prompt) { return chat.prompt().user(prompt).call().content(); }
}
