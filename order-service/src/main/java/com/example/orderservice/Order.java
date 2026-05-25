package com.example.orderservice;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Table("orders")
public record Order(@Id @Nullable Long id, Instant createdAt,
             @MappedCollection(idColumn = "order_id", keyColumn = "order_key") List<OrderItem> items) {

    Order(List<OrderItem> items) {
        this(null, Instant.now(), items);
    }

    Order withId(Long id) {
        return new Order(id, createdAt, items);
    }

    double value(List<Product> products) {
        return items.stream().mapToDouble(item -> item.value(products)).sum();
    }

    @Table("order_items")
    public record OrderItem(@Id @Nullable Long id, String sku, int quantity,
                     @MappedCollection(idColumn = "order_item_id", keyColumn = "order_item_key") List<OrderItemContent> contents) {

        OrderItem(String sku, int quantity, List<OrderItemContent> contents) {
            this(null, sku, quantity, contents);
        }

        OrderItem withId(Long id) {
            return new OrderItem(id, sku, quantity, contents);
        }

        double value(List<Product> products) {
            return products.stream().filter(p -> p.sku().equals(sku))
                    .mapToDouble(p -> p.cost() * quantity)
                    .findFirst().orElse(0.0);
        }

        @Table("order_item_contents")
        record OrderItemContent(String name, String description) {}
    }

    record OrderLine(String sku, int quantity) {

        void validate(List<Product> products) {
            if (Product.MYSTERY_BOX_SKU.equals(sku) && quantity > 1) {
                throw new OrderException.MysteryBoxQuantityExceededException();
            }
            var product = products.stream().filter(p -> p.sku().equals(sku)).findFirst()
                    .orElseThrow(() -> new OrderException.UnknownSkuException(sku));

            if (product.stock() < quantity) {
                throw new OrderException.InsufficientStockException(sku);
            }
        }
    }
}
