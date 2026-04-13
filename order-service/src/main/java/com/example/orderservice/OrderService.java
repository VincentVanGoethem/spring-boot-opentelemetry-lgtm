package com.example.orderservice;

import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.springframework.web.client.ApiVersionInserter.usePathSegment;

@Observed(name = "order.service")
@Service
class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final RestClient mysteryBoxClient;
    private final List<Product> products;

    OrderService(OrderRepository orderRepository, RestClient.Builder restClientBuilder,
                 OrderProperties orderProperties) {
        this.orderRepository = orderRepository;
        this.mysteryBoxClient = restClientBuilder
                .baseUrl(orderProperties.mysteryBoxApiUrl())
                .apiVersionInserter(usePathSegment(1)).build();
        this.products = orderProperties.products();
    }

    Order createOrder(List<Order.OrderLine> lines) {
        log.info("Creating order with {} line(s)", lines.size());
        lines.forEach(l -> l.validate(products));

        var orderItems = getOrderItems(lines);
        var order = orderRepository.save(new Order(orderItems));
        log.info("Order {} created", order.id());
        log.debug("Order details: {}", order);

        return order;
    }

    List<Order> getOrders() {
        var orders = orderRepository.findAll();
        log.info("Fetching all {} orders", orders.size());
        return orders;
    }

    private List<Order.OrderItem> getOrderItems(List<Order.OrderLine> lines) {
        return lines.stream().map(line -> Product.MYSTERY_BOX_SKU.equals(line.sku())
                ? createMysteryBox().toOrderItem()
                : new Order.OrderItem(line.sku(), line.quantity(), List.of())
        ).toList();
    }

    @Timed
    @Counted
    private MysteryBox createMysteryBox() {
        log.info("Requesting mystery box from mystery-box-service");
        var box = mysteryBoxClient.post()
                .uri("/mysteryboxes").apiVersion(1)
                .retrieve()
                .body(MysteryBox.class);
        log.info("Mystery box {} received with {} item(s)", box.id(), box.contents().size());
        log.debug("Mystery box details: {}", box);
        return box;
    }
}
