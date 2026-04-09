package com.autoheal.orderservice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Random;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final Random random = new Random();

    @GetMapping
    public List<String> getOrders() throws InterruptedException {
        long delay = 100 + random.nextInt(200);
        Thread.sleep(delay);
        return List.of("order_1", "order_2");
    }
}
