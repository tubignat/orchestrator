package com.example.orchestrator

import org.springframework.stereotype.Component

@Component
class Config(
    val appsDirectory: String = "apps",
    val logsDirectory: String = "logs",
    val stopProcessTimeoutSeconds: Int = 15,
    val domain: String = "localhost",
    val maxRevivalsInOneMinute: Int = 10
)
