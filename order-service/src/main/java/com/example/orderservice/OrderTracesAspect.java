package com.example.orderservice;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
class OrderTracesAspect {

    private final Tracer tracer;

    OrderTracesAspect(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(OrderTracesAspect.class.getName());
    }

    @AfterReturning(
            pointcut = "execution(* com.example.orderservice.OrderService.createOrder(..))",
            returning = "order"
    )
    void onOrderCreated(Order order) {
        for (var item : order.items()) {
            Span lineSpan = tracer.spanBuilder("order.line")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
            lineSpan.setAttribute("sku", item.sku());
            lineSpan.setAttribute("quantity", item.quantity());
            lineSpan.end();
        }
    }
}
