package com.example.mysterybox;

import io.micrometer.core.annotation.Timed;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Optional;

// Emits spans + metrics (latency, count, errors) for every method in this service
// Propagates trace context to logs & downstream calls
// Note: methods must be public — Spring AOP proxies only intercept public calls.
// @Timed on private methods is silently ignored for the same reason.
@Observed(name = "mystery.box.service")
@Service
class MysteryBoxService {

    private static final Logger log = LoggerFactory.getLogger(MysteryBoxService.class);

    private final ChatClient chatClient;
    private final MysteryBoxRepository repository;

    MysteryBoxService(ChatClient.Builder chatClientBuilder, MysteryBoxRepository repository) {
        this.chatClient = chatClientBuilder.build();
        this.repository = repository;
    }

    // Adds detailed latency metrics (p95, p99) for this method on top of the service-wide @Observed
    // For full control, use the fluent builder: Timer.builder(...).register(meterRegistry)
    @Timed(value = "mystery.boxes.generation.time", histogram = true)
    public MysteryBox generateMysteryBox() {
        log.info("Generating mystery box via AI");
        var mysteryBox = this.chatClient.prompt()
                .system("The id value should be null.")
                .user("Generate a random mystery box with 3–5 fun, whimsical items from an ancient curiosity shop")
                .call()
                .entity(MysteryBox.class);
        var saved = repository.save(mysteryBox);
        log.info("Mystery box {} generated with {} item(s)", saved.id(), saved.contents().size());
        return saved;
    }

    public Optional<MysteryBox> fetchMysteryBox(Long id) {
        log.debug("Fetching mystery box {}", id);
        return repository.findById(id);
    }
}
