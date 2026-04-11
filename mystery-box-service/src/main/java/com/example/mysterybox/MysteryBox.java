package com.example.mysterybox;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

@Table("mystery_boxes")
public record MysteryBox(
        @Id @Nullable Long id,
        @MappedCollection(idColumn = "mystery_box_id", keyColumn = "mystery_box_key") List<Item> contents) {

    @Table("mystery_box_items")
    record Item(String name, String description) { }
}