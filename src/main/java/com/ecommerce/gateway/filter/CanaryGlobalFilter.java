package com.ecommerce.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
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
 * 配置参数通过 application.yml / Nacos 配置中心动态注入
 */
@Component
public class CanaryGlobalFilter implements GlobalFilter, Ordered {
    
    private static final String CANARY_HEADER = "canary";
    private static final String USER_ID_HEADER = "X-User-Id";
    
    @Value("${canary.enabled:false}")
    private boolean canaryEnabled;
    
    @Value("${canary.weight:10}")
    private int canaryWeight;
    
    @Value("${canary.whitelist-users:}")
    private List<String> whitelistUsers;
    
    @Value("${canary.whitelist-ips:10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}")
    private List<String> whitelistIpCidrs;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 灰度开关关闭时直接放行
        if (!canaryEnabled) {
            return chain.filter(exchange);
        }
        
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
        boolean isCanary = ThreadLocalRandom.current().nextInt(100) < canaryWeight;
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
        for (String cidr : whitelistIpCidrs) {
            if (cidr.contains("/")) {
                // 简化 CIDR 匹配：取网络前缀比对
                String prefix = cidr.substring(0, cidr.indexOf('/'));
                String[] parts = prefix.split("\\.");
                if (ip.startsWith(parts[0] + ".")) {
                    return true;
                }
            } else if (ip.startsWith(cidr)) {
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
