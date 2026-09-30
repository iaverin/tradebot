package hzpro.com.tradingdesk.controller;

import hzpro.com.tradingdesk.controller.dto.HealthCheckResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    @GetMapping("/health")
    public HealthCheckResponse health() {
        return new HealthCheckResponse("UP");
    }
}
