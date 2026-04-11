package com.example.orderservice;

record Product(String sku, String name, double cost, int stock) {
    static final String MAGIC_BOX_SKU = "MAGIC_BOX";
}