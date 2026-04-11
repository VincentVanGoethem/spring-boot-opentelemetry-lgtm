package com.example.orderservice;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping(path = "/api/{version}/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody CreateOrderRequest request, @PathVariable String version) {
        var order = orderService.createOrder(request.lines());
        var location = URI.create("/api/%s/orders/%s".formatted(version, order.id()));
        return ResponseEntity.created(location).body(order);
    }

    @GetMapping
    public ResponseEntity<List<Order>> getOrders() {
        return ResponseEntity.ok(orderService.getOrders());
    }

    public record CreateOrderRequest(List<Order.OrderLine> lines) {}

}