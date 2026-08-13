package com.example.orderservice;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import static org.springframework.web.client.ApiVersionInserter.usePathSegment;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import io.micrometer.core.annotation.Timed;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

// Class-level @Observed instruments every public method:
// emits spans + timer metrics (count, latency, errors) and propagates trace context to logs & downstream calls.
// Note: methods must be public — Spring AOP proxies only intercept public calls.
// @Timed on private methods (e.g. createMysteryBox) is silently ignored for the same reason.
@Observed(name = "order.service")
@Service
@Transactional
class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final ObservationRegistry observationRegistry;
    private final OrderRepository orderRepository;
    private final RestClient mysteryBoxClient;
    private final List<Product> products;

    OrderService(OrderRepository orderRepository, RestClient.Builder restClientBuilder,
                 OrderProperties orderProperties, ObservationRegistry observationRegistry) {
        this.orderRepository = orderRepository;
        this.mysteryBoxClient = restClientBuilder
                .baseUrl(orderProperties.mysteryBoxApiUrl())
                .apiVersionInserter(usePathSegment(1)).build();
        this.products = orderProperties.products();
        this.observationRegistry = observationRegistry;
    }

    // Records a dedicated latency histogram for order creation; complements the class-level @Observed
    // timer by allowing independent percentile charts for this critical path.
    @Timed(value = "order.create.time", description = "Time to create an order", histogram = true)
    public Order createOrder(List<Order.OrderLine> lines) {
        log.info("Creating order with {} line(s)", lines.size());
        lines.forEach(l -> l.validate(products));

        var orderItems = getOrderItems(lines);
        var order = orderRepository.save(new Order(orderItems));

        // Programmatic Observation API: use when annotations aren't enough — e.g. wrapping
        // a code block (not a method) or adding dynamic tags from local variables.
        // Produces the same span + metrics as @Observed, just built manually.
        Observation.createNotStarted("order.items.processing", observationRegistry)
                .lowCardinalityKeyValue("itemCount", String.valueOf(order.items().size()))
                .observe(() -> order.items().forEach(this::processItem));

        log.info("Order {} created", order.id());
        log.debug("Order details: {}", order);
        return order;
    }

    private void processItem(Order.OrderItem item) {
        // lowCardinality tags → become metric labels (few distinct values, safe to chart).
        // highCardinality tags → only attached to the trace span (many distinct values, would explode metrics).
        Observation.createNotStarted("order.item.process", observationRegistry)
                .lowCardinalityKeyValue("sku", item.sku())
                .highCardinalityKeyValue("quantity", String.valueOf(item.quantity()))
                .observe(() -> log.info("Processing item {}", item.sku()));
    }

    public List<Order> getOrders() {
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