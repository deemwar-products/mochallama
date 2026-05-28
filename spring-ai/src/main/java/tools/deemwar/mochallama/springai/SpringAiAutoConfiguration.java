package tools.deemwar.mochallama.springai;

import tools.deemwar.mochallama.MochallamaClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Spring AI adapter.
 *
 * <p>Builds a {@link ChatModel} (and convenience {@link ChatClient}) backed by
 * the framework-free {@link MochallamaClient} — but only when Spring AI is
 * actually present on the consumer's classpath and a {@code MochallamaClient}
 * bean exists. Because Spring AI is a compile-only dependency here, the consumer
 * supplies its own version and this module never pins one.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.ai.chat.model.ChatModel")
public class SpringAiAutoConfiguration {

    @Bean
    @ConditionalOnBean(MochallamaClient.class)
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel mochallamaChatModel(MochallamaClient client) {
        return new MochallamaChatModel(client);
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient mochallamaChatClient(ChatModel mochallamaChatModel) {
        return ChatClient.builder(mochallamaChatModel).build();
    }
}
