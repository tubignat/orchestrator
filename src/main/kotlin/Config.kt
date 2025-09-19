package com.example.orchestrator

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class Config(
    val appsDirectory: String = System.getenv("APPS_DIR") ?: "apps",
    val logsDirectory: String = System.getenv("LOGS_DIR") ?: "logs",
    val stopProcessTimeoutSeconds: Int = System.getenv("STOP_PROCESS_TIMEOUT_SECONDS")?.toIntOrNull() ?: 20,
    val domain: String = System.getenv("DOMAIN") ?: "localhost",
    val maxRevivalsInOneMinute: Int = System.getenv("MAX_REVIVALS_IN_ONE_MINUTE")?.toIntOrNull() ?: 10,
    val cpuLimitPerApp: Double = System.getenv("CPU_LIMIT_PER_APP")?.toDouble() ?: 5.0,
    val memLimitPerAppInMB: Int = System.getenv("MEM_LIMIT_PER_APP_IN_MB")?.toIntOrNull() ?: 50,
) {

    private val logger = LoggerFactory.getLogger(Config::class.java)

    init {
        logger.info(this.toString())
    }

    override fun toString(): String =
        "Config(appsDirectory = $appsDirectory, logsDirectory = $logsDirectory, stopProcessTimeoutSeconds = $stopProcessTimeoutSeconds, " +
                "domain = $domain, maxRevivalsInOneMinute = $maxRevivalsInOneMinute, cpuLimitPerApp = $cpuLimitPerApp, memLimitPerAppInMB = $memLimitPerAppInMB)"
}
