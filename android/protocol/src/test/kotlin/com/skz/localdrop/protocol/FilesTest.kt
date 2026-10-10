package com.skz.localdrop.protocol

import com.skz.localdrop.protocol.crypto.hex
import com.skz.localdrop.protocol.files.Transfer
import com.skz.localdrop.protocol.files.scanFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class FilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).hex()
    private fun str(e: kotlinx.serialization.json.JsonElement) = (e as JsonPrimitive).content
    private fun py(path: File) = "r'" + path.path + "'"

    private var watch: () -> String = { "" }

    private suspend fun until(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        var shown = 0L
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "condition not met: " + watch() }
            if (System.currentTimeMillis() - shown > 2000) { shown = System.currentTimeMillis(); println("[test] " + watch()) }
            delay(30)
        }
    }

    private fun randomFile(f: File, size: Int): File {
        f.parentFile.mkdirs()
        f.writeBytes(Random(size).nextBytes(size))
        return f
    }

    @Test
    fun phoneSendsFolderToDesktop() = runBlocking<Unit> {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode(inboxDir = tmp.newFolder("phone")).use { k ->
                k.pairWith(py, pyPort, pyId)
                val src = tmp.newFolder("src", "Фото_2026")
                randomFile(File(src, "a.jpg"), 300_000)
                randomFile(File(src, "sub/b.mp4"), 3 * 1024 * 1024 + 17)
                File(src, "empty").mkdirs()
                File(src, "zero.txt").writeBytes(ByteArray(0))
                val t = k.files.send(pyId, src.name, scanFile(src), src.path, isDir = true)!!
                until { t.status == "done" }
                val inbox = File(str(py.cmd("app.settings.get('save_dir')")))
                val got = File(inbox, "Фото_2026")
                assertEquals(sha(File(src, "a.jpg")), sha(File(got, "a.jpg")))
                assertEquals(sha(File(src, "sub/b.mp4")), sha(File(got, "sub/b.mp4")))
                assertTrue(File(got, "empty").isDirectory)
                assertEquals(0L, File(got, "zero.txt").length())
                py.waitFor("[x.status for x in f.transfers.values()] == ['done']")
                assertTrue(src.name in k.history)

                // the same name again gets a numbered copy, like on the desktop
                val one = randomFile(File(tmp.root, "src2/note.txt"), 10)
                val t2 = k.files.send(pyId, one.name, scanFile(one), one.path, isDir = false)!!
                until { t2.status == "done" }
                val t3 = k.files.send(pyId, one.name, scanFile(one), one.path, isDir = false)!!
                until { t3.status == "done" }
                assertTrue(File(inbox, "note (1).txt").exists())
            }
        }
    }

    @Test
    fun desktopSendsFileToPhone() = runBlocking<Unit> {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode(inboxDir = tmp.newFolder("phone")).use { k ->
                k.pairWith(py, pyPort, pyId)
                val f = randomFile(File(tmp.root, "out/видео.mp4"), 8 * 1024 * 1024 + 3)
                py.cmd("f.send_paths('${k.ident.id}', [${py(f)}])")
                until { k.files.list().any { it.status == "done" } }
                assertEquals(sha(f), sha(File(k.inboxDir, "видео.mp4")))
                assertTrue("received" in k.notices && "incoming" in k.notices)
                py.waitFor("[x.status for x in f.transfers.values()] == ['done']")
            }
        }
    }

    @Test
    fun untrustedDesktopNeedsApproval() = runBlocking<Unit> {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode(inboxDir = tmp.newFolder("phone")).use { k ->
                k.pairWith(py, pyPort, pyId, trusted = false)
                val f = randomFile(File(tmp.root, "out/doc.pdf"), 50_000)
                py.cmd("f.send_paths('${k.ident.id}', [${py(f)}])")
                until { k.files.list().any { it.status == "ask" } }
                py.waitFor("[x.status for x in f.transfers.values()] == ['waiting']")
                val t = k.files.list().first()
                k.files.respond(t.id, accept = true)
                until { t.status == "done" }
                assertEquals(sha(f), sha(File(k.inboxDir, "doc.pdf")))

                // declined: the desktop sees "declined"
                py.cmd("f.send_paths('${k.ident.id}', [${py(f)}])")
                until { k.files.list().any { it.status == "ask" } }
                k.files.respond(k.files.list().first { it.status == "ask" }.id, accept = false)
                py.waitFor("'declined' in [x.status for x in f.transfers.values()]")
            }
        }
    }

    @Test
    fun pauseAndResumeContinueFromThePart() = runBlocking<Unit> {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode(inboxDir = tmp.newFolder("phone")).use { k ->
                k.pairWith(py, pyPort, pyId)
                val f = randomFile(File(tmp.root, "out/big.bin"), 160 * 1024 * 1024)
                val t: Transfer = k.files.send(pyId, f.name, scanFile(f), f.path, isDir = false)!!
                watch = { "kotlin ${t.status} ${t.done} err=${t.error} | desktop " + py.cmd("[(x.status, x.done, x.error) for x in f.transfers.values()]") }
                until { t.status == "active" && t.done > 8 * 1024 * 1024 }
                k.files.toggle(t.id)                                       // pause both sides
                assertEquals("paused", t.status)
                py.waitFor("[x.status for x in f.transfers.values()] == ['paused']")
                val inbox = File(str(py.cmd("app.settings.get('save_dir')")))
                val part = File(inbox, "big.bin.ldpart")
                until { part.exists() && part.length() > 0 }
                val partBefore = part.length()
                k.files.toggle(t.id)                                       // resume: continues from the part
                until(60_000) { t.status == "done" }
                assertEquals(sha(f), sha(File(inbox, "big.bin")))
                assertTrue(partBefore < f.length())
            }
        }
    }

    @Test
    fun brokenConnectionResumes() = runBlocking<Unit> {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode(inboxDir = tmp.newFolder("phone")).use { k ->
                k.pairWith(py, pyPort, pyId)
                val f = randomFile(File(tmp.root, "out/big2.bin"), 160 * 1024 * 1024)
                py.cmd("f.send_paths('${k.ident.id}', [${py(f)}])")
                until { k.files.list().any { it.status == "active" && it.done > 8 * 1024 * 1024 } }
                val t = k.files.list().first()
                watch = { "kotlin ${t.status} ${t.done} err=${t.error} | desktop " + py.cmd("[(x.status, x.done, x.error) for x in f.transfers.values()]") }
                t.conn!!.close()                                           // the Wi-Fi drops
                until { t.status == "offline" || t.status == "active" && t.conn == null }
                until(60_000) { t.status == "done" }                       // the desktop retries and resumes
                assertEquals(sha(f), sha(File(k.inboxDir, "big2.bin")))
            }
        }
    }
}
