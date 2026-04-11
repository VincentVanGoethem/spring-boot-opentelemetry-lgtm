package com.example.orderservice;

import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.List;

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
        meterRegistry.summary("orders.value").record(order.value(products));
        order.items().forEach(item ->
                meterRegistry.counter("orders.items.ordered", "sku", item.sku()).increment(item.quantity()));
    }

    @AfterThrowing(
            pointcut = "execution(* com.example.orderservice.OrderService.createOrder(..))",
            throwing = "ex"
    )
    void onOrderCreationError(Exception ex) {
        meterRegistry.counter("orders.created",
                "status", "error", "reason", ex.getClass().getSimpleName()).increment();
    }
}