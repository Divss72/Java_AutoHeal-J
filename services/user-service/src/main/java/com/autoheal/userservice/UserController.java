package com.autoheal.userservice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Random;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final Random random = new Random();

    @GetMapping
    public List<String> getUsers() throws InterruptedException {
        // Simulate workload with delay
        long delay = 50 + random.nextInt(150);
        Thread.sleep(delay);
        return List.of("user_1", "user_2");
    }
}
