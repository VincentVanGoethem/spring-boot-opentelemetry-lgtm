package com.example.orderservice;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import static org.springframework.web.client.ApiVersionInserter.usePathSegment;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import io.micrometer.core.annotation.Timed;

// This class contains no OpenTelemetry code and the module has no OpenTelemetry dependency.
// The javaagent spans the edges of this service (HTTP in, RestClient out, JDBC) on its own, and the
// business-level spans in between are declared in ../otel-agent.properties under
// otel.instrumentation.methods.include — so the method structure below is what shapes the trace.
//
// That also means: renaming or inlining a method here silently drops its span. The
// otel.instrumentation.methods.include entry is the contract; keep the two in step.
@Service
@Transactional
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

    // @Timed comes from Micrometer (spring-boot-starter-actuator), not OpenTelemetry: it records a
    // latency histogram which the agent's Micrometer bridge exports over OTLP. It goes through
    // Spring AOP, so this method has to stay public — the agent's method instrumentation does not.
    @Timed(value = "order.create.time", description = "Time to create an order", histogram = true)
    public Order createOrder(List<Order.OrderLine> lines) {
        log.info("Creating order with {} line(s)", lines.size());
        lines.forEach(l -> l.validate(products));

        var orderItems = getOrderItems(lines);
        var order = orderRepository.save(new Order(orderItems));

        processItems(order.items());

        log.info("Order {} created with {} item(s)", order.id(), order.items().size());
        log.debug("Order details: {}", order);
        return order;
    }

    // Private, and called from within the same bean. Spring AOP could not intercept either, but the
    // agent weaves the bytecode directly, so both still produce spans.
    private void processItems(List<Order.OrderItem> items) {
        log.info("Processing {} item(s)", items.size());
        items.forEach(this::processItem);
    }

    // Detail that used to sit on the span as an attribute now lives in the log line. The agent
    // stamps trace_id/span_id into every record, so Grafana still ties this back to the span.
    private void processItem(Order.OrderItem item) {
        log.info("Processing item {} (quantity {})", item.sku(), item.quantity());
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
