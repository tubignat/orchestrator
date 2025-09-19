package com.example.orchestrator

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
class Controller(val manager: AppManager) {
    private fun isValidName(name: String): Boolean = name.isNotEmpty() && name.matches(Regex("^[A-Za-z0-9-]+$"))

    @PostMapping("/deploy")
    fun deploy(@RequestParam name: String, @RequestBody archive: ByteArray): ResponseEntity<Unit> {
        if (!isValidName(name)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }

        manager.deploy(name, archive)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/start")
    fun start(@RequestParam name: String): ResponseEntity<Unit> {
        if (!isValidName(name)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }

        if (!manager.exists(name)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }

        manager.start(name)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/stop")
    fun stop(@RequestParam name: String): ResponseEntity<Unit> {
        if (!isValidName(name)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }

        if (!manager.exists(name)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }
        manager.stop(name)
        return ResponseEntity.ok().build()
    }

    @GetMapping("/status")
    fun status(@RequestParam name: String): ResponseEntity<String> {
        if (!isValidName(name)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }

        if (!manager.exists(name)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }

        return ResponseEntity.ok(manager.status(name).name)
    }

    @GetMapping("/logs")
    fun logs(@RequestParam name: String, @RequestParam(required = false) limit: Int = 50): ResponseEntity<String> {
        if (!isValidName(name)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }

        if (!manager.exists(name)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }

        return ResponseEntity.ok(manager.logs(name, limit).joinToString("\n"))
    }
}
