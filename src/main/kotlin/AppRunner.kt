package com.example.orchestrator

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

interface AppRunner {
    fun start(appDirectory: File, onExit: (AppProcess) -> Unit): AppProcess
    fun stop(process: AppProcess)
    fun status(process: AppProcess?): AppStatus
}

class AppProcess(
    val id: Long,
    val name: String,
    val port: Int,
    val innerProcess: Any,
    var stoppedByUser: Boolean
)

enum class AppStatus { RUNNING, STOPPED, NOT_STARTED, ERROR }

@Component
class AppRunnerImpl(val config: Config) : AppRunner {
    private val logger = LoggerFactory.getLogger(AppRunner::class.java)

    override fun start(appDirectory: File, onExit: (AppProcess) -> Unit): AppProcess {
        return try {
            val logFile = File("${config.logsDirectory}/${appDirectory.name}.log")
            logFile.parentFile?.mkdirs()
            if (!logFile.exists()) {
                logFile.createNewFile()
            }

            val pb = ProcessBuilder("node", "index.js")
                .directory(appDirectory)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectErrorStream(true)

            val port = pickFreePort()
            pb.environment()["SERVER_PORT"] = port.toString()
            val process = pb.start()

            val result = AppProcess(process.pid(), appDirectory.name, port, process, false)

            process.onExit().thenApply { onExit(result) }

            logger.info("Started ${appDirectory.name} on port $port with pid ${result.id}")

            result
        } catch (e: IOException) {
            throw Error("Failed to start node process: ${e.message}", e)
        }
    }

    override fun stop(process: AppProcess) {
        process.stoppedByUser = true
        val osProcess = process.innerProcess as Process
        if (!osProcess.isAlive) {
            return
        }

        osProcess.destroy()
        if (!osProcess.waitFor(config.stopProcessTimeoutSeconds.toLong(), TimeUnit.SECONDS)) {
            osProcess.destroyForcibly()
        }

        logger.info("Stopped ${process.name}")
    }

    override fun status(process: AppProcess?): AppStatus {
        if (process == null) {
            return AppStatus.NOT_STARTED
        }

        val osProcess = process.innerProcess as Process
        return when {
            osProcess.isAlive -> AppStatus.RUNNING
            // If an app doesn't handle SIGTERM gracefully, it may yield an error code even if
            // it didn't crash. It seems prudent to show ERROR status for crashes only, that's why
            // stoppedByUser is excluded no matter the exit code
            osProcess.exitValue() != 0 && !process.stoppedByUser -> AppStatus.ERROR
            else -> AppStatus.STOPPED
        }
    }

    private fun pickFreePort(): Int {
        ServerSocket(0).use {
            if (it.localPort == -1) {
                throw Error("Could not find a free port to start the app")
            }
            return it.localPort
        }
    }
}
