package com.ecommerce.cache.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 主页控制器
 * 提供前端页面路由
 */
@Controller
public class HomeController {

    /**
     * 首页重定向到高级组件控制面板
     */
    @GetMapping("/")
    public String home() {
        return "redirect:/advanced-components.html";
    }

    /**
     * 高级组件控制面板页面
     */
    @GetMapping("/advanced-components")
    public String advancedComponents() {
        return "forward:/advanced-components.html";
    }
}