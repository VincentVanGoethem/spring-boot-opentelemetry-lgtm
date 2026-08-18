package com.example.mysterybox;

import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

// No OpenTelemetry code and no OpenTelemetry dependency. The javaagent spans HTTP in and JDBC, and
// — because Spring AI 2.x calls OpenAI through the official com.openai:openai-java SDK — the chat
// call below too, as a proper `chat <model>` GenAI span with token counts. The prompts and
// completions are attached to it as log records; see the GenAI section of ../otel-agent.properties.
// The business-level spans are declared there as well, under otel.instrumentation.methods.include.
@Service
class MysteryBoxService {

    private static final Logger log = LoggerFactory.getLogger(MysteryBoxService.class);

    private static final String SYSTEM_PROMPT = "The id value should be null.";
    private static final String USER_PROMPT =
            "Generate a random mystery box with 3–5 fun, whimsical items from an ancient curiosity shop";

    private final ChatClient chatClient;
    private final MysteryBoxRepository repository;
    private final String model;

    MysteryBoxService(ChatClient.Builder chatClientBuilder, MysteryBoxRepository repository,
                      @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.chatClient = chatClientBuilder.build();
        this.repository = repository;
        this.model = model;
    }

    // @Timed is Micrometer, not OpenTelemetry — it needs Spring AOP, hence public.
    @Timed(value = "mystery.boxes.generation.time", histogram = true)
    public MysteryBox generateMysteryBox() {
        log.info("Generating mystery box via {}", model);

        // The agent records this call: the prompts below and the completion are captured for you,
        // so there is no reason to log them by hand.
        var mysteryBox = this.chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(USER_PROMPT)
                .call()
                .entity(MysteryBox.class);

        var saved = repository.save(mysteryBox);
        log.info("Mystery box {} generated with {} item(s)", saved.id(), saved.contents().size());
        return saved;
    }

    public Optional<MysteryBox> fetchMysteryBox(Long id) {
        log.info("Fetching mystery box {}", id);
        return repository.findById(id);
    }
}
