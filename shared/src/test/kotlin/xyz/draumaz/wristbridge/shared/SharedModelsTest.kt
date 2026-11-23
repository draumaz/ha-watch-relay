package xyz.draumaz.wristbridge.shared

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedModelsTest {

    @Test
    fun watchConfigRoundTripSerialization() {
        val original = WatchConfig(
            isHaConfigured = true,
            selectedEntities = listOf(
                HaEntityConfig("light.living_room", "Living Room"),
                HaEntityConfig("button.garage_door", "Garage"),
            ),
            cameraEntityId = "camera.front_door",
            cameraName = "Front Door",
            cameraRefreshIntervalSec = 3,
            cameraQuality = CameraQualityPreset.FAST,
            updatedAtEpochMs = 1_700_000_000_000L,
        )

        val encoded = HaJson.encodeToString(original)
        val decoded = HaJson.decodeFromString<WatchConfig>(encoded)

        assertEquals(original, decoded)
        assertFalse(decoded.selectedEntities[0].isMomentary)
        assertTrue(decoded.selectedEntities[1].isMomentary)
    }

    @Test
    fun serviceMapperResolvesDomainsCorrectly() {
        assertEquals(
            HaServiceMapper.ServiceCall("homeassistant", "turn_on"),
            HaServiceMapper.resolveAction(EntityActionRequest("switch.porch", "switch", "off")),
        )
        assertEquals(
            HaServiceMapper.ServiceCall("homeassistant", "turn_off"),
            HaServiceMapper.resolveAction(EntityActionRequest("light.lamp", "light", "on")),
        )
        assertEquals(
            HaServiceMapper.ServiceCall("homeassistant", "toggle"),
            HaServiceMapper.resolveAction(EntityActionRequest("switch.porch", "switch", null)),
        )
        assertEquals(
            HaServiceMapper.ServiceCall("button", "press"),
            HaServiceMapper.resolveAction(EntityActionRequest("button.reboot", "button", null)),
        )
        assertEquals(
            HaServiceMapper.ServiceCall("script", "turn_on"),
            HaServiceMapper.resolveAction(EntityActionRequest("script.goodnight", "script", null)),
        )
        assertEquals(
            HaServiceMapper.ServiceCall("scene", "turn_on"),
            HaServiceMapper.resolveAction(EntityActionRequest("scene.movie", "scene", null)),
        )
        assertEquals(
            HaServiceMapper.ServiceCall("lock", "unlock"),
            HaServiceMapper.resolveAction(EntityActionRequest("lock.front", "lock", "locked")),
        )
        assertEquals(
            HaServiceMapper.ServiceCall("lock", "lock"),
            HaServiceMapper.resolveAction(EntityActionRequest("lock.front", "lock", "unlocked")),
        )
        assertEquals(
            HaServiceMapper.ServiceCall("cover", "close_cover"),
            HaServiceMapper.resolveAction(EntityActionRequest("cover.blinds", "cover", "open")),
        )
    }
}
