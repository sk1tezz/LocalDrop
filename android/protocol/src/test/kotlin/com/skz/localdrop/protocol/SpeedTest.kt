package com.skz.localdrop.protocol

import com.skz.localdrop.protocol.files.scanFile
import com.skz.localdrop.protocol.net.obj
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/** Throughput between two Kotlin nodes on loopback (prints MB/s). */
class SpeedTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun kotlinToKotlin() = runBlocking<Unit> {
        KNode("A", tmp.newFolder("a")).use { a ->
            KNode("B", tmp.newFolder("b")).use { b ->
                a.peers.seen(b.ident.id, obj("name" to "B", "ip" to "127.0.0.1", "port" to b.host.port))
                b.peers.seen(a.ident.id, obj("name" to "A", "ip" to "127.0.0.1", "port" to a.host.port))
                a.peers.setPaired(b.ident.id, true)
                b.peers.setPaired(a.ident.id, true)
                val f = File(tmp.root, "x.bin").also { it.writeBytes(Random(1).nextBytes(100 * 1024 * 1024)) }
                val t0 = System.currentTimeMillis()
                val t = a.files.send(b.ident.id, f.name, scanFile(f), f.path, false)!!
                while (t.status != "done") {
                    check(System.currentTimeMillis() - t0 < 120_000) { "slow: ${t.status} ${t.done}" }
                    delay(20)
                }
                val s = (System.currentTimeMillis() - t0) / 1000.0
                println("[speed] kotlin->kotlin 100 MB in $s s = ${100 / s} MB/s")
            }
        }
    }
}
