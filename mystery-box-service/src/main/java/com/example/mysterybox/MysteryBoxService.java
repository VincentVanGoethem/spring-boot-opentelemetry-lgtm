package com.example.mysterybox;

import io.micrometer.core.annotation.Timed;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Optional;

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

    @Timed("mystery.box.generate")
    MysteryBox generateMysteryBox() {
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

    Optional<MysteryBox> fetchMysteryBox(Long id) {
        log.debug("Fetching mystery box {}", id);
        return repository.findById(id);
    }
}
