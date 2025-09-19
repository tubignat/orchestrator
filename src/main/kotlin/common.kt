package com.example.orchestrator

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

fun unpackTarGz(tarGzFile: ByteArray, outputDir: File) {
    ByteArrayInputStream(tarGzFile).use { fis ->
        GzipCompressorInputStream(fis).use { gzis ->
            TarArchiveInputStream(gzis).use { tis ->
                var entry = tis.nextEntry
                while (entry != null) {
                    val outputFile = outputDir.resolve(entry.name)

                    if (entry.isDirectory) {
                        Files.createDirectories(outputFile.toPath())
                    } else {
                        Files.createDirectories(outputFile.parentFile.toPath())
                        FileOutputStream(outputFile).use { fos ->
                            tis.copyTo(fos)
                        }
                    }

                    entry = tis.nextEntry
                }
            }
        }
    }
}
