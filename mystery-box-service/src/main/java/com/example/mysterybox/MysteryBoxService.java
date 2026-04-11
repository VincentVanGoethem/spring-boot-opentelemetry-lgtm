package com.example.mysterybox;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
class MysteryBoxService {

    private final ChatClient chatClient;
    private final MysteryBoxRepository repository;

    MysteryBoxService(ChatClient.Builder chatClientBuilder, MysteryBoxRepository repository) {
        this.chatClient = chatClientBuilder.build();
        this.repository = repository;
    }

    MysteryBox generateMysteryBox() {
        var mysteryBox = this.chatClient.prompt()
                .system("The id value should be null.")
                .user("Generate a random mystery box with 3–5 fun, whimsical items from an ancient curiosity shop")
                .call()
                .entity(MysteryBox.class);
        return repository.save(mysteryBox);
    }

    Optional<MysteryBox> fetchMysteryBox(Long id) {
        return repository.findById(id);
    }
}