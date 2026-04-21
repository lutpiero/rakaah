package com.lutpiero.rakaah

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PoseDataStoreCatalogTest {

    @Test
    fun `prayer pose list includes tashahhud and salam`() {
        val ids = PoseDataStore.PrayerPose.entries.map { it.id }

        assertTrue(ids.contains("tashahhud"))
        assertTrue(ids.contains("salam"))
    }

    @Test
    fun `fromId returns matching pose and null for unknown`() {
        assertEquals(
            PoseDataStore.PrayerPose.RUKU,
            PoseDataStore.PrayerPose.fromId("ruku")
        )
        assertNull(PoseDataStore.PrayerPose.fromId("does_not_exist"))
    }
}
