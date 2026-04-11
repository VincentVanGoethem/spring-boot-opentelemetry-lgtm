package com.example.orderservice;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "order")
record OrderProperties(String mysteryBoxApiUrl, List<Product> products) { }