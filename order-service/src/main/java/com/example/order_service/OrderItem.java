package com.example.order_service;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.lang.Nullable;

import java.util.List;

@Table("order_items")
record OrderItem(@Id @Nullable Long id, String sku, String name, double cost,
                 @MappedCollection(idColumn = "order_item_id", keyColumn = "order_item_key") List<OrderItemContent> contents) {

    OrderItem(String sku, String name, double cost, List<OrderItemContent> contents) {
        this(null, sku, name, cost, contents);
    }

    OrderItem withId(Long id) {
        return new OrderItem(id, this.sku, this.name, this.cost, this.contents);
    }

    OrderItem withContents(List<OrderItemContent> contents) {
        return new OrderItem(this.id, this.sku, this.name, this.cost, contents);
    }
}
