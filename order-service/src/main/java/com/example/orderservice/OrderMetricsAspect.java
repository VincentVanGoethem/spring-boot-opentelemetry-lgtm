package com.example.orderservice;

import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.List;

// Aspect-based metrics keep observability code out of the service.
// Best for metrics that depend on the *return value* (e.g. order total, item counts) —
// something annotations like @Counted or @Timed can't easily express.
@Aspect
@Component
class OrderMetricsAspect {

    private final MeterRegistry meterRegistry;
    private final List<Product> products;

    OrderMetricsAspect(MeterRegistry meterRegistry, OrderProperties orderProperties) {
        this.meterRegistry = meterRegistry;
        this.products = orderProperties.products();
    }

    @AfterReturning(
            pointcut = "execution(* com.example.orderservice.OrderService.createOrder(..))",
            returning = "order"
    )
    void onOrderCreated(Order order) {

        meterRegistry.counter("orders.created", "status", "success").increment();
        // Distribution summary: records arbitrary values (not durations) — min/max/avg/percentiles.
        // Use for monetary amounts, payload sizes, queue depths, etc.
        meterRegistry.summary("orders.value").record(order.value(products));

        // Tagged counter: one metric, broken down by SKU → drives "top-selling product" charts.
        // Only safe because SKUs are a bounded set (low cardinality).
        order.items().forEach(item ->
                meterRegistry.counter("orders.items.ordered", "sku", item.sku())
                        .increment(item.quantity()));
    }

    @AfterThrowing(
            pointcut = "execution(* com.example.orderservice.OrderService.createOrder(..))",
            throwing = "ex"
    )
    void onOrderCreationError(Exception ex) {
        meterRegistry.counter("orders.created", "status", "error", "reason",
                ex.getClass().getSimpleName()).increment();
    }
}