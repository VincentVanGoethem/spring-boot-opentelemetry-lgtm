package com.example.mysterybox;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/{version}/mysteryboxes")
public class MysteryBoxController {

    private final MysteryBoxService mysteryBoxService;

    MysteryBoxController(MysteryBoxService mysteryBoxService) {
        this.mysteryBoxService = mysteryBoxService;
    }

    @PostMapping
    public ResponseEntity<MysteryBox> generateMysteryBox(@PathVariable String version) throws Exception {
        var mysteryBox = mysteryBoxService.generateMysteryBox();
        var location = URI.create("/api/%s/mysteryboxes/%s".formatted(version, mysteryBox.id()));
        return ResponseEntity.created(location).body(mysteryBox);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MysteryBox> fetchMysteryBox(@PathVariable Long id) throws Exception {
        var mysteryBox = mysteryBoxService.fetchMysteryBox(id);
        return mysteryBox.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}