package com.example.orderservice;

record Product(String sku, String name, double cost, int stock) {
    static final String MYSTERY_BOX_SKU = "MYSTERY_BOX";
}