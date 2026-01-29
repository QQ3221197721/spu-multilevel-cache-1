package com.ecommerce.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 灰度路由全局过滤器
 * 支持按比例、按用户白名单、按 Header 进行灰度分流
 */
@Component
public class CanaryGlobalFilter implements GlobalFilter, Ordered {
    
    private static final String CANARY_HEADER = "canary";
    private static final String USER_ID_HEADER = "X-User-Id";
    
    // 灰度比例 10%
    private static final int CANARY_WEIGHT = 10;
    
    // 灰度用户白名单
    private final List<String> whitelistUsers = List.of(
        "test_user_001", 
        "test_user_002",
        "test_user_003"
    );

    // 灰度 IP 前缀
    private final List<String> whitelistIpPrefixes = List.of(
        "10.0.",
        "172.16.",
        "192.168."
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        
        // 1. 检查是否已有 canary header（显式指定）
        String canaryHeader = request.getHeaders().getFirst(CANARY_HEADER);
        if (canaryHeader != null) {
            return chain.filter(exchange);
        }
        
        // 2. 检查用户白名单
        String userId = request.getHeaders().getFirst(USER_ID_HEADER);
        if (userId != null && whitelistUsers.contains(userId)) {
            return addCanaryHeaderAndProceed(exchange, chain);
        }
        
        // 3. 检查 IP 白名单
        String clientIp = getClientIp(request);
        if (clientIp != null && isWhitelistIp(clientIp)) {
            return addCanaryHeaderAndProceed(exchange, chain);
        }
        
        // 4. 按权重随机分配
        boolean isCanary = ThreadLocalRandom.current().nextInt(100) < CANARY_WEIGHT;
        if (isCanary) {
            return addCanaryHeaderAndProceed(exchange, chain);
        }
        
        return chain.filter(exchange);
    }

    private Mono<Void> addCanaryHeaderAndProceed(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest newRequest = exchange.getRequest().mutate()
            .header(CANARY_HEADER, "1")
            .build();
        return chain.filter(exchange.mutate().request(newRequest).build());
    }

    private String getClientIp(ServerHttpRequest request) {
        // 优先从 X-Forwarded-For 获取
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        
        // 其次从 X-Real-IP 获取
        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        
        // 最后从 RemoteAddress 获取
        if (request.getRemoteAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        
        return null;
    }

    private boolean isWhitelistIp(String ip) {
        for (String prefix : whitelistIpPrefixes) {
            if (ip.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return -100; // 高优先级，在其他过滤器之前执行
    }
}
