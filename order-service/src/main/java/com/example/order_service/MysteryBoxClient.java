package com.example.order_service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class MysteryBoxClient {

    private final RestClient restClient;

    public MysteryBoxClient(@Value("${mystery-box-service.url}") String baseUrl, RestClient.Builder builder) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    record MysteryBoxResponse(String sku, String name, double cost, List<OrderItemContent> contents) {}

    public OrderItem fetchItem() {
        MysteryBoxResponse response = restClient.get()
                .uri("/")
                .retrieve()
                .body(MysteryBoxResponse.class);
        return new OrderItem(response.sku(), response.name(), response.cost(), response.contents());
    }
}
