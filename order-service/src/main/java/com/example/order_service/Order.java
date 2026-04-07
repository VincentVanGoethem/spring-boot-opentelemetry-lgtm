package com.example.order_service;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

@Table("orders")
record Order(@Id @Nullable Long id, Instant createdAt,
             @MappedCollection(idColumn = "order_id", keyColumn = "order_key") List<OrderItem> items) {

    Order(List<OrderItem> items) {
        this(null, Instant.now(), items);
    }

    Order withId(Long id) {
        return new Order(id, this.createdAt, this.items);
    }

    Order withItems(List<OrderItem> items) {
        return new Order(this.id, this.createdAt, items);
    }
}
