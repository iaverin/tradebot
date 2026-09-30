package hzpro.com.tradingdesk.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import hzpro.com.tradingdesk.controller.dto.FetcherStatusResponse;
import hzpro.com.tradingdesk.marketsfetcher.lib.FetcherState;
import hzpro.com.tradingdesk.marketsfetcher.service.fetchers.KalshiFetcher;
import hzpro.com.tradingdesk.marketsfetcher.service.fetchers.PolymarketFetcher;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/dashboard")
@PreAuthorize("isAuthenticated()")
public class DashboardController {

    /**
     * Protected endpoint - requires JWT authentication
     * Returns a greeting with the authenticated user's information
     */
    @GetMapping("/hello")
    public ResponseEntity<Map<String, String>> getHelloWorld(@AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails.getUsername();

        Map<String, String> response = new HashMap<>();
        response.put("message", "Hello World");
        response.put("authenticatedUser", username);
        response.put("status", "authenticated");
        return ResponseEntity.ok(response);
    }


}
