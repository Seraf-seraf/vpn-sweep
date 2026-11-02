package com.seraf.vpnsweep

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BootstrapDnsTest {
    @Test
    fun skipsScopedIpv6AndUsesNextNetworkDns(): Unit {
        val scoped = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 7)
        val ipv4 = InetAddress.getByName("192.0.2.53")
        assertEquals("192.0.2.53", selectBootstrapDns(listOf(scoped, ipv4)))
        assertNull(selectBootstrapDns(listOf(scoped)))
        assertEquals(InetAddress.getByName("2001:db8::53").hostAddress, selectBootstrapDns(listOf(InetAddress.getByName("2001:db8::53"))))
    }
}
