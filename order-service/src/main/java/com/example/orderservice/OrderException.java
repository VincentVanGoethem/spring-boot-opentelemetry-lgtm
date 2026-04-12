package com.example.orderservice;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.BAD_REQUEST)
class OrderException extends RuntimeException {

    private OrderException(String message) {
        super(message);
    }

    static class MysteryBoxQuantityExceededException extends OrderException {
        MysteryBoxQuantityExceededException() {
            super("Mystery box quantity must not exceed 1");
        }
    }

    static class UnknownSkuException extends OrderException {
        UnknownSkuException(String sku) {
            super("Unknown SKU: " + sku);
        }
    }

    static class InsufficientStockException extends OrderException {
        InsufficientStockException(String sku) {
            super("Insufficient stock for SKU: " + sku);
        }
    }

}