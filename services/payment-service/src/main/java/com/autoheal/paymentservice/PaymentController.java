package com.autoheal.paymentservice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Random;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final Random random = new Random();

    @GetMapping
    public List<String> getPayments() throws InterruptedException {
        long delay = 200 + random.nextInt(300);
        Thread.sleep(delay);
        return List.of("payment_1", "payment_2");
    }
}
