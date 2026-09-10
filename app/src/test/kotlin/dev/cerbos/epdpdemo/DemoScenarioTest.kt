package dev.cerbos.epdpdemo

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoScenarioTest {
    @Test
    fun ownedResourceUsesPrincipalAsOwner() {
        val scenario =
            DemoScenario(principalId = "alice@example.com", role = "USER", ownsResource = true)
        val request = scenario.request()
        assertEquals(listOf("USER"), request.principal.roles)
        assertEquals(DemoScenario.RESOURCE_KIND, request.resources.first().resource.kind)
        assertEquals(
            JsonPrimitive("alice@example.com"),
            request.resources.first().resource.attributes["ownerId"],
        )
        assertEquals(DemoScenario.ACTIONS, request.resources.first().actions)
        assertTrue(request.includeMetadata)
    }

    @Test
    fun foreignResourceUsesAnotherOwner() {
        val scenario =
            DemoScenario(principalId = "alice@example.com", role = "ADMIN", ownsResource = false)
        assertEquals(
            JsonPrimitive("someone-else@example.com"),
            scenario.request().resources.first().resource.attributes["ownerId"],
        )
    }
}

class HubSettingsTest {
    @Test
    fun hubUrlValidation() {
        org.junit.Assert.assertNull(HubSettings.hubUrlProblem(""))
        org.junit.Assert.assertNull(HubSettings.hubUrlProblem("  https://api.cerbos.cloud  "))
        org.junit.Assert.assertNull(HubSettings.hubUrlProblem("http://localhost:3592"))
        org.junit.Assert.assertNotNull(HubSettings.hubUrlProblem("http://10.0.2.2:3592"))
        org.junit.Assert.assertNotNull(HubSettings.hubUrlProblem("api.cerbos.cloud"))
        org.junit.Assert.assertNotNull(HubSettings.hubUrlProblem("not a url"))
    }
}
