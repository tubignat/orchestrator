package com.example.orchestrator

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface AppManager {
    fun exists(name: String): Boolean
    fun deploy(name: String, tarGz: ByteArray)
    fun start(name: String)
    fun stop(name: String)
    fun status(name: String): AppStatus
    fun appURL(name: String): String?
    fun logs(name: String, limit: Int): List<String>
}

@Component
class ThreadSafeAppManager(val runner: AppRunner, val config: Config) : AppManager {
    private val logger = LoggerFactory.getLogger(AppManager::class.java)
    private val processes = ConcurrentHashMap<String, AppProcess>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val failures = ConcurrentHashMap<String, Pair<Int, Instant>>()

    private fun <T> lockPerApp(name: String, action: () -> T): T {
        val lock = locks.computeIfAbsent(name) { Any() }
        synchronized(lock) {
            return action()
        }
    }

    init {
        val root = File(config.appsDirectory)
        if (!root.exists()) {
            root.mkdirs()
        }

        root.listFiles { file -> file.isDirectory }?.forEach { dir ->
            try {
                processes[dir.name] = runner.start(dir, ::handleExit)
            } catch (e: Throwable) {
                logger.error("Failed to start app in directory ${dir.name}: ${e.message}", e)
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        logger.info("Shutdown, stop all processes")
        processes.forEach { (name, process) ->
            lockPerApp(name) { runner.stop(process) }
        }
    }

    override fun deploy(name: String, tarGz: ByteArray) = lockPerApp(name) {
        val existing = processes[name]
        if (existing != null) {
            runner.stop(existing)
        }

        val dir = File("${config.appsDirectory}/$name")
        if (dir.exists()) {
            val success = dir.deleteRecursively()
            if (!success) {
                throw Error("Couldn't delete existing app version")
            }
        }

        val success = dir.mkdirs()
        if (!success) {
            throw Error("Couldn't create a directory for the app")
        }

        unpackTarGz(tarGz, dir)
        processes[dir.name] = runner.start(dir, ::handleExit)
    }

    override fun start(name: String) = lockPerApp(name) {
        val dir = File("${config.appsDirectory}/$name")
        if (runner.status(processes[name]) == AppStatus.RUNNING) {
            return@lockPerApp
        }

        processes[dir.name] = runner.start(dir, ::handleExit)
    }

    override fun stop(name: String): Unit = lockPerApp(name) {
        processes[name]?.let {
            runner.stop(it)
        }
    }

    override fun exists(name: String) = File("${config.appsDirectory}/$name").exists()

    override fun status(name: String): AppStatus = runner.status(processes[name])

    override fun appURL(name: String): String? {
        val process = processes[name] ?: return null
        if (runner.status(process) != AppStatus.RUNNING) return null

        return "http://127.0.0.1:${process.port}"
    }

    override fun logs(name: String, limit: Int): List<String> {
        val logFile = File("${config.logsDirectory}/${name}.log")
        if (!logFile.exists()) {
            return emptyList()
        }

        return logFile.readLines().takeLast(limit)
    }

    private fun handleExit(app: AppProcess) = lockPerApp(app.name) {
        if (runner.status(app) != AppStatus.ERROR) {
            return@lockPerApp
        }

        val existing = failures[app.name]
        val new = if (existing == null || Instant.now().isAfter(existing.second.plusSeconds(60))) {
            Pair(1, Instant.now())
        } else {
            Pair(existing.first + 1, existing.second)
        }

        failures[app.name] = new

        if (new.first > config.maxRevivalsInOneMinute) {
            logger.info("${app.name} crash-looped")
            return@lockPerApp
        }

        logger.info("${app.name} crashed, reviving...")
        val dir = File("${config.appsDirectory}/${app.name}")
        processes[dir.name] = runner.start(dir, ::handleExit)
    }
}
