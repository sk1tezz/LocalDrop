package com.skz.localdrop.protocol

import com.skz.localdrop.protocol.net.Announcement
import com.skz.localdrop.protocol.net.announce
import com.skz.localdrop.protocol.net.bool
import com.skz.localdrop.protocol.net.int
import com.skz.localdrop.protocol.net.parseBeacon
import com.skz.localdrop.protocol.net.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconTest {
    @Test
    fun parsesTheDesktopBeacon() {
        // exactly what desktop discovery._send_beacon(query=True) sends (json.dumps defaults)
        val raw = """{"ld": 1, "id": "ab12", "name": "Рабочий ПК", "os": "Windows 11", "kind": "windows", "icon": "desktop_windows", "port": 53317, "q": 1}"""
            .toByteArray()
        val h = parseBeacon(raw, raw.size, "192.168.1.12", "me")!!
        assertEquals("ab12", h.id)
        assertTrue(h.query)
        assertEquals("Рабочий ПК", h.info.str("name"))
        assertEquals(53317, h.info.int("port"))
        assertEquals("192.168.1.12", h.info.str("ip"))
        assertEquals(false, h.info.bool("hidden"))
    }

    @Test
    fun ignoresOwnAndForeignPackets() {
        val mine = announce(Announcement("me", "Phone", "Android 16", "android", "smartphone", 53317))
        assertNull(parseBeacon(mine, mine.size, "1.2.3.4", "me"))
        val junk = "hello".toByteArray()
        assertNull(parseBeacon(junk, junk.size, "1.2.3.4", "me"))
        val other = """{"ld": 2, "id": "x"}""".toByteArray()
        assertNull(parseBeacon(other, other.size, "1.2.3.4", "me"))
    }

    @Test
    fun ourBeaconIsReadableByTheDesktop() {
        val b = String(announce(Announcement("me", "Phone", "Android 16", "android", "smartphone", 53317), query = true, hidden = true))
        assertTrue(b.startsWith("""{"ld":1,"id":"me""""))
        assertTrue(b.contains(""""q":1""") && b.contains(""""hidden":1"""))
    }
}
