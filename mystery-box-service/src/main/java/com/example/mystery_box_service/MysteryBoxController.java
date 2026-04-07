package com.example.mystery_box_service;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MysteryBoxController {

    private final ChatClient chat;

    MysteryBoxController(ChatClient.Builder chatClientBuilder) {
        this.chat = chatClientBuilder.build();
    }

    // Generated mystery box items
    record GeneratedItems(List<Item> contents) {

    }

    record Item(String name, String description) {

    }

    @RequestMapping(value = "/", method = {RequestMethod.GET, RequestMethod.POST})
    public Map<String, Object> mysteryBox() {
        GeneratedItems generated = this.chat.prompt()
                .user("""
					Generate a random mystery box: 3–5 fun items.
					Reply with JSON only in this form: {"contents":[{"name":"...","description":"..."}]}
					""")
                .call()
                .entity(GeneratedItems.class);

        return Map.of(
                "sku", "MYSTERY-BOX",
                "name", "Mystery box",
                "cost", 9.99,
                "contents", generated.contents());
    }

}
