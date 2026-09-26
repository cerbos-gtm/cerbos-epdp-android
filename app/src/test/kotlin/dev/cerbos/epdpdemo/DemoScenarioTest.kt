package dev.cerbos.epdpdemo

import dev.cerbos.epdp.CerbosEmbeddedPDP
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertNull(HubSettings.hubUrlProblem(""))
        assertNull(HubSettings.hubUrlProblem("  https://api.cerbos.cloud  "))
        assertNull(HubSettings.hubUrlProblem("http://localhost:3592"))
        assertNotNull(HubSettings.hubUrlProblem("http://10.0.2.2:3592"))
        assertNotNull(HubSettings.hubUrlProblem("api.cerbos.cloud"))
        assertNotNull(HubSettings.hubUrlProblem("not a url"))
        assertNotNull(HubSettings.hubUrlProblem("http://[::1]:3592"))
    }

    @Test
    fun configurationTrimsInputAndSkipsIncompleteCredentials() {
        val partial =
            HubSettings(ruleId = " RULE ", hubBaseUrl = " http://10.0.2.2 ", clientId = " id ")
                .toConfiguration()
        assertEquals("RULE", partial.ruleId)
        assertNull(partial.hubBaseUrl)
        assertNull(partial.credentials)

        val complete =
            HubSettings(
                    hubBaseUrl = "https://hub.example.com",
                    clientId = " id ",
                    clientSecret = "secret",
                )
                .toConfiguration()
        assertEquals("https://hub.example.com", complete.hubBaseUrl)
        assertEquals(CerbosEmbeddedPDP.HubCredentials("id", "secret"), complete.credentials)
    }
}
