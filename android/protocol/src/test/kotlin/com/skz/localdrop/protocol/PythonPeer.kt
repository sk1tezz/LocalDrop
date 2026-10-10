package com.skz.localdrop.protocol

import com.skz.localdrop.protocol.net.json
import kotlinx.serialization.json.JsonObject
import org.junit.Assume
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Runs desktop/tools/interop_peer.py from the repo's .venv; tests are skipped when it is missing. */
class PythonPeer(vararg args: String) : AutoCloseable {
    private val proc: Process
    private val reader: BufferedReader

    init {
        val repo = File(System.getProperty("ld.repo") ?: "..")
        val py = listOf(".venv/Scripts/python.exe", ".venv/bin/python").map { File(repo, it) }.firstOrNull { it.exists() }
        Assume.assumeTrue("desktop .venv not found", py != null)
        proc = ProcessBuilder(listOf(py!!.path, "-u", File(repo, "desktop/tools/interop_peer.py").path) + args)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .also { it.environment()["PYTHONIOENCODING"] = "utf-8" }.start()
        reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
    }

    fun line(): String = reader.readLine() ?: error("python peer exited")

    fun obj(): JsonObject = json.parseToJsonElement(line()) as JsonObject

    fun waitExit(): Int {
        proc.waitFor(30, TimeUnit.SECONDS)
        return proc.exitValue()
    }

    override fun close() {
        if (proc.isAlive) proc.destroyForcibly()
    }
}
