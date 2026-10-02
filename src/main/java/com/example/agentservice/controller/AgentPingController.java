package com.example.agentservice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 供服务发现链路验证使用，不依赖数据库或模型服务。 */
@RestController
public class AgentPingController {

    @GetMapping("/internal/ping")
    public String ping() {
        return "pong";
    }
}
