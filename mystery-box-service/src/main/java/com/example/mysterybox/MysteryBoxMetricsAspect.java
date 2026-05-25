package com.example.mysterybox;

import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

// Separating observability into an aspect keeps the service free of metrics code
// Useful when metrics depend on the result (e.g. box contents) — something annotations can't easily express
@Aspect
@Component
class MysteryBoxMetricsAspect {

    private final MeterRegistry meterRegistry;

    MysteryBoxMetricsAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @AfterReturning(
            pointcut = "execution(* com.example.mysterybox.MysteryBoxService.generateMysteryBox(..))",
            returning = "mysteryBox"
    )
    void onGenerated(MysteryBox mysteryBox) {
        meterRegistry.counter("mystery.box.generated.count", "status", "success").increment();
        // Distribution summary: tracks min/max/avg/percentiles of a value (not time)
        meterRegistry.summary("mystery.box.items.count").record(mysteryBox.contents().size());
    }

    @AfterThrowing(
            pointcut = "execution(* com.example.mysterybox.MysteryBoxService.generateMysteryBox(..))",
            throwing = "ex"
    )
    void onGenerationError(Exception ex) {
        meterRegistry.counter("mystery.boxes.generated", "status", "error", "reason", ex.getClass().getSimpleName()).increment();
    }
}
