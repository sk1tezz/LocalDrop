package com.skz.localdrop.protocol

import com.skz.localdrop.protocol.net.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /** Toasts the desktop node showed: [kind, title, text, args]. */
    val toasts = mutableListOf<List<String>>()

    /** Runs one python expression in `node` mode and returns its result. */
    fun cmd(expr: String): JsonElement {
        proc.outputStream.write((expr.replace('\n', ' ') + "\n").toByteArray(Charsets.UTF_8))
        proc.outputStream.flush()
        while (true) {
            val o = obj()
            o["toast"]?.let { t -> toasts.add((t as JsonArray).map { if (it is JsonPrimitive) it.content else it.toString() }) }
            o["err"]?.let { error("python: ${'$'}it (for ${'$'}expr)") }
            o["r"]?.let { return it }
        }
    }

    /** Polls a python expression until it is truthy. */
    fun waitFor(expr: String, timeoutMs: Long = 8000): JsonElement {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val r = cmd(expr)
            val truthy = !(r is JsonNull || (r is JsonPrimitive && (r.content == "false" || r.content == "0" || r.content == "")) || (r is JsonArray && r.isEmpty()))
            if (truthy) return r
            if (System.currentTimeMillis() > end) error("timed out waiting for ${'$'}expr")
            Thread.sleep(50)
        }
    }

    fun waitExit(): Int {
        proc.waitFor(30, TimeUnit.SECONDS)
        return proc.exitValue()
    }

    override fun close() {
        runCatching { proc.outputStream.write("quit\n".toByteArray()); proc.outputStream.flush() }
        proc.waitFor(3, TimeUnit.SECONDS)
        if (proc.isAlive) proc.destroyForcibly()
    }
}
