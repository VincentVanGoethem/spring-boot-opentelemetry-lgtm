package com.example.orderservice;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.stream.Collectors;

@Controller
class OrderUiController {

    private final OrderService orderService;
    private final List<Product> products;

    OrderUiController(OrderService orderService, OrderProperties orderProperties) {
        this.orderService = orderService;
        this.products = orderProperties.products();
    }

    @GetMapping("/")
    String index(Model model) {
        model.addAttribute("products", products);
        return "index";
    }

    @PostMapping("/orders")
    String order(@RequestBody List<Order.OrderLine> lines, Model model, HttpServletResponse response) {
        try {
            var order = orderService.createOrder(lines);
            model.addAttribute("orderedItems", order.items());
            model.addAttribute("productNames", products.stream().collect(Collectors.toMap(Product::sku, Product::name)));
            return "fragments/order-result :: success";
        } catch (OrderException e) {
            response.setStatus(422);
            response.setHeader("X-Error", e.getMessage());
            return "fragments/order-result :: error";
        }
    }
}
