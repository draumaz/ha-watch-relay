package xyz.draumaz.hawidget.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.draumaz.hawidget.mobile.ha.HaApiClient

class HaApiClientTest {

    @Test
    fun buildUrlNormalizesTrailingSlashes() {
        assertEquals(
            "http://ha.tailnet.ts.net:8123/api/states",
            HaApiClient.buildUrl("http://ha.tailnet.ts.net:8123/", "/api/states"),
        )
        assertEquals(
            "https://home.example.com/api/camera_proxy/camera.porch",
            HaApiClient.buildUrl("https://home.example.com", "api/camera_proxy/camera.porch"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildUrlRejectsMissingScheme() {
        HaApiClient.buildUrl("homeassistant.local:8123", "/api/")
    }

    @Test
    fun parseStatesListExtractsFriendlyNamesAndDomains() {
        val sampleJson = """
            [
              {
                "entity_id": "light.kitchen",
                "state": "on",
                "attributes": { "friendly_name": "Kitchen Ceiling" }
              },
              {
                "entity_id": "switch.desk_fan",
                "state": "off",
                "attributes": {}
              },
              {
                "entity_id": "camera.driveway",
                "state": "streaming",
                "attributes": { "friendly_name": "Driveway Cam" }
              }
            ]
        """.trimIndent()

        val parsed = HaApiClient.parseStatesList(sampleJson, 123456L)
        assertEquals(3, parsed.size)

        assertEquals("light.kitchen", parsed[0].entityId)
        assertEquals("Kitchen Ceiling", parsed[0].friendlyName)
        assertEquals("light", parsed[0].domain)
        assertTrue(parsed[0].isOn)

        assertEquals("desk fan", parsed[1].friendlyName)
        assertFalse(parsed[1].isOn)

        assertEquals("camera", parsed[2].domain)
        assertEquals("Driveway Cam", parsed[2].friendlyName)
    }
}
