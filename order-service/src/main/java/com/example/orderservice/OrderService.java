package com.example.orderservice;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.springframework.web.client.ApiVersionInserter.usePathSegment;

@Service
class OrderService {

    private final OrderRepository orderRepository;
    private final RestClient mysteryBoxClient;
    private final List<Product> products;

    OrderService(OrderRepository orderRepository, RestClient.Builder restClientBuilder, OrderProperties orderProperties) {
        this.orderRepository = orderRepository;
        this.mysteryBoxClient = restClientBuilder
                .baseUrl(orderProperties.mysteryBoxApiUrl())
                .apiVersionInserter(usePathSegment(1)).build();
        this.products = orderProperties.products();
    }

    Order createOrder(List<Order.OrderLine> lines) {
        lines.forEach(l -> l.validate(products));
        var order = new Order(getOrderItems(lines));
        return orderRepository.save(order);
    }

    List<Order> getOrders() {
        return orderRepository.findAll();
    }

    private List<Order.OrderItem> getOrderItems(List<Order.OrderLine> lines) {
        return lines.stream().map(line -> Product.MAGIC_BOX_SKU.equals(line.sku())
                ? createMysteryBox().toOrderItem()
                : new Order.OrderItem(line.sku(), line.quantity(), List.of())
        ).toList();
    }

    private MysteryBox createMysteryBox() {
        return mysteryBoxClient.post()
                .uri("/mysteryboxes").apiVersion(1)
                .retrieve()
                .body(MysteryBox.class);
    }

}