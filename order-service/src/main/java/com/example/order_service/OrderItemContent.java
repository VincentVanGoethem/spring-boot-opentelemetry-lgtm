package com.example.order_service;

import org.springframework.data.relational.core.mapping.Table;

@Table("order_item_contents")
record OrderItemContent(String name, String description) {}
