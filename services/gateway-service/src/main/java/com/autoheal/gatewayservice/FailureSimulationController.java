package com.autoheal.gatewayservice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/simulate")
public class FailureSimulationController {

    private final List<byte[]> memoryLeakList = new ArrayList<>();

    @GetMapping("/cpu")
    public String simulateCpuOverload() {
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 60000) {
                Math.pow(Math.random(), Math.random());
            }
        }).start();
        return "CPU overload started for 60 seconds.";
    }

    @GetMapping("/memory")
    public String simulateMemoryLeak() {
        byte[] b = new byte[50 * 1024 * 1024]; // 50MB leak
        memoryLeakList.add(b);
        return "Allocated 50MB of memory. Total leaked: " + (memoryLeakList.size() * 50) + "MB";
    }

    @GetMapping("/latency")
    public String simulateLatency() throws InterruptedException {
        Thread.sleep(5000);
        return "Artificial latency of 5s introduced.";
    }

    @GetMapping("/crash")
    public void simulateCrash() {
        System.exit(1);
    }
}
