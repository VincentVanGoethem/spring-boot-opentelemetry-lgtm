package com.example.orderservice;
import java.util.List;

public record MysteryBox(Long id, List<Item> contents) {

    record Item(String name, String description) { }

    Order.OrderItem toOrderItem() {
        var orderItemContents = contents.stream()
                .map(c -> new Order.OrderItem.OrderItemContent(c.name(), c.description())).toList();
        return new Order.OrderItem(Product.MYSTERY_BOX_SKU, 1, orderItemContents);
    }
}