package com.nomily.app.wifi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The permission the hotspot join needs changes at API 33, and the failure it causes is silent:
 * the picker times out instead of reporting a missing permission. Pin both branches.
 */
class FastTransferPermissionsTest {

    @Test
    fun `below api 33 asks for fine location`() {
        for (sdk in 29..32) {
            assertEquals(listOf(android.Manifest.permission.ACCESS_FINE_LOCATION), fastTransferPermissions(sdk))
        }
    }

    @Test
    fun `api 33 and up asks for nearby wifi devices`() {
        for (sdk in 33..36) {
            assertEquals(listOf(android.Manifest.permission.NEARBY_WIFI_DEVICES), fastTransferPermissions(sdk))
        }
    }
}
