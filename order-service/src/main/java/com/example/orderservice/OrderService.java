package com.example.orderservice;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import static org.springframework.web.client.ApiVersionInserter.usePathSegment;
import org.springframework.web.client.RestClient;

import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

// @Observed(name = "order.service")
@Service
class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final ObservationRegistry observationRegistry;
    private final OrderRepository orderRepository;
    private final RestClient mysteryBoxClient;
    private final List<Product> products;
    private final Tracer tracer;

    OrderService(OrderRepository orderRepository, RestClient.Builder restClientBuilder, OrderProperties orderProperties, OpenTelemetry openTelemetry, io.micrometer.observation.ObservationRegistry observationRegistry) {
        this.orderRepository = orderRepository;
        this.mysteryBoxClient = restClientBuilder
                .baseUrl(orderProperties.mysteryBoxApiUrl())
                .apiVersionInserter(usePathSegment(1)).build();
        this.products = orderProperties.products();
        this.tracer = openTelemetry.getTracer(OrderService.class.getName());
        this.observationRegistry = observationRegistry;
    }

    Order createOrder(List<Order.OrderLine> lines) {
        log.info("Creating order with {} line(s)", lines.size());
        lines.forEach(l -> l.validate(products));

        var orderItems = getOrderItems(lines);
        var order = orderRepository.save(new Order(orderItems));

        Observation.createNotStarted("order.service", observationRegistry).observe(() -> {
            for (var item : order.items()) {
                processItem(item);
            }
        });

        // Span orderSpan = tracer.spanBuilder("order")
        //         .setParent(Context.current())
        //         .setSpanKind(SpanKind.INTERNAL)
        //         .startSpan();
        // orderSpan.setAttribute("order.id", order.id());
        // orderSpan.setAttribute("order.items.quantity", order.items().size());
        // orderSpan.end();
        // try (Scope scope = orderSpan.makeCurrent()) {
        //     // Add spans for each order item
        // for (var item : order.items()) {
        //     processItem(item);
        // }
        //         Span orderItemSpan = tracer.spanBuilder("order.item")
        //                 .setSpanKind(SpanKind.INTERNAL)
        //                 .startSpan();
        //         orderItemSpan.setAttribute("order.item.sku", item.sku());
        //         orderItemSpan.setAttribute("order.item.quantity", item.quantity());
        //         orderItemSpan.end();
        //     }
        // }
        log.info("Order {} created", order.id());
        log.debug("Order details: {}", order);

        return order;
    }

    void processItem(Order.OrderItem item) {

        Observation.createNotStarted("order.item.process", observationRegistry)
                .highCardinalityKeyValue("sku", item.sku())
                .highCardinalityKeyValue("quantity", item.quantity() + "")
                .observe(() -> {
                    log.info("Processing item {}", item.sku());
                });
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
