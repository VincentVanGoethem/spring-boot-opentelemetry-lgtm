package com.example.order_service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderRepository orderRepository;
    private final MysteryBoxClient mysteryBoxClient;

    public OrderController(OrderRepository orderRepository, MysteryBoxClient mysteryBoxClient) {
        this.orderRepository = orderRepository;
        this.mysteryBoxClient = mysteryBoxClient;
    }

    record CreateOrderRequest(List<String> skus) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Order createOrder(@RequestBody CreateOrderRequest request) {
        List<OrderItem> items = request.skus().stream()
                .map(sku -> sku.equalsIgnoreCase("MYSTERY-BOX")
                        ? mysteryBoxClient.fetchItem()
                        : new OrderItem(sku, sku, 0.0, List.of()))
                .toList();
        var order = new Order(items);
        return orderRepository.save(order);
    }

    @GetMapping
    public List<Order> getOrders() {
        return orderRepository.findAll();
    }
}