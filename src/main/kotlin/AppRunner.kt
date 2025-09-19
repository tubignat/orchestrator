package com.example.orchestrator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

interface AppRunner {
    fun start(appDirectory: File, onExit: (AppProcess) -> Unit): AppProcess
    fun stop(process: AppProcess)
    fun status(process: AppProcess?): ProcessStatus
    fun usage(process: AppProcess?): ProcessUsage?
}

class AppProcess(
    val id: Long,
    val name: String,
    val port: Int,
    val innerProcess: Any,
    var stoppedByUser: Boolean
)

enum class ProcessStatus { RUNNING, STOPPED, NOT_STARTED, ERROR }
data class ProcessUsage(val cpu: Double, val mem: Double)

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

            limitUsage(result)

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

    override fun status(process: AppProcess?): ProcessStatus {
        if (process == null) {
            return ProcessStatus.NOT_STARTED
        }

        val osProcess = process.innerProcess as Process
        return when {
            osProcess.isAlive -> ProcessStatus.RUNNING
            // If an app doesn't handle SIGTERM gracefully, it may yield an error code even if
            // it didn't crash. It seems prudent to show ERROR status for crashes only, that's why
            // stoppedByUser is excluded no matter the exit code
            osProcess.exitValue() != 0 && !process.stoppedByUser -> ProcessStatus.ERROR
            else -> ProcessStatus.STOPPED
        }
    }

    override fun usage(process: AppProcess?): ProcessUsage? {
        if (process == null) {
            return null
        }

        return getProcessUsage(process.id.toInt())
    }

    private fun pickFreePort(): Int {
        ServerSocket(0).use {
            if (it.localPort == -1) {
                throw Error("Could not find a free port to start the app")
            }
            return it.localPort
        }
    }

    // poor-man's usage limiter, but it does the trick
    private fun limitUsage(process: AppProcess) {
        val osProcess = process.innerProcess as Process

        CoroutineScope(Dispatchers.Default).launch {
            var stopped = false

            while (osProcess.isAlive) {
                if (stopped) {
                    logger.debug("Resuming ${process.name}...")
                    Runtime.getRuntime().exec("kill -CONT ${osProcess.pid()}")
                    stopped = false
                }

                getProcessUsage(osProcess.pid().toInt())?.let {
                    if (it.mem > config.memLimitPerAppInMB * 1024) {
                        logger.info("${process.name} is using ${it.mem / 1024}MB of memory, killing it...")
                        osProcess.destroyForcibly()
                        return@launch
                    }

                    if (it.cpu > config.cpuLimitPerApp) {
                        logger.debug("${process.name} is using ${it.cpu}% of CPU, pausing it for 100ms...")
                        Runtime.getRuntime().exec("kill -STOP ${osProcess.pid()}")
                        stopped = true
                    }
                }

                delay(100)
            }
        }
    }

    private fun getProcessUsage(pid: Int): ProcessUsage? {
        return try {
            val process = ProcessBuilder("ps", "-p", "$pid", "-o", "pid,%cpu,rss")
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = reader.readLines()

            if (lines.size < 2) return null

            val values = lines[1].trim().split("\\s+".toRegex())
            val cpu = values[1].toDouble()
            val mem = values[2].toDouble()

            ProcessUsage(cpu, mem)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
