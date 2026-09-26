package dev.cerbos.epdp

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestEncodingTest {
    private inline fun <reified T> json(value: T): JsonObject =
        BridgeJson.decode<JsonObject>(BridgeJson.encode(value))

    private fun JsonArray.strings() = map { it.jsonPrimitive.contentOrNull }

    @Test
    fun `checkResources matches the cerbos core request shape`() {
        val request =
            CheckResourcesRequest(
                principal =
                    Principal(
                        id = "alice",
                        roles = listOf("USER"),
                        attributes =
                            attributesOf("tier" to "PREMIUM", "level" to 3, "beta" to true),
                    ),
                resources =
                    listOf(
                        ResourceCheck(
                            resource =
                                Resource(
                                    kind = "resource",
                                    id = "1",
                                    attributes = attributesOf("ownerId" to "alice"),
                                ),
                            actions = listOf("read", "update"),
                        )
                    ),
                includeMetadata = true,
                requestId = "req-1",
            )
        val obj = json(request)

        assertEquals(true, obj["includeMetadata"]?.jsonPrimitive?.boolean)
        assertEquals("req-1", obj["requestId"]?.jsonPrimitive?.contentOrNull)
        assertNull("null optionals must be omitted", obj["auxData"])

        val principal = obj["principal"]!!.jsonObject
        assertEquals("alice", principal["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(listOf("USER"), principal["roles"]?.jsonArray?.strings())
        val attr = principal["attr"]!!.jsonObject
        assertEquals("PREMIUM", attr["tier"]?.jsonPrimitive?.contentOrNull)
        assertEquals(3, attr["level"]?.jsonPrimitive?.int)
        assertEquals(true, attr["beta"]?.jsonPrimitive?.boolean)
        assertNull(principal["policyVersion"])

        val resources = obj["resources"]!!.jsonArray
        val resource = resources.first().jsonObject["resource"]!!.jsonObject
        assertEquals("resource", resource["kind"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "alice",
            resource["attr"]!!.jsonObject["ownerId"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            listOf("read", "update"),
            resources.first().jsonObject["actions"]?.jsonArray?.strings(),
        )
    }

    @Test
    fun `JWT aux data and plan requests`() {
        val plan =
            PlanResourcesRequest(
                principal = Principal(id = "alice", roles = listOf("USER")),
                resource = PlanResource(kind = "document"),
                action = "view",
                auxData = AuxData(jwt = JWT(token = "abc", keySetId = "auth0")),
            )
        val obj = json(plan)
        assertEquals(listOf("view"), obj["actions"]?.jsonArray?.strings())
        val jwt = obj["auxData"]!!.jsonObject["jwt"]!!.jsonObject
        assertEquals("abc", jwt["token"]?.jsonPrimitive?.contentOrNull)
        assertEquals("auth0", jwt["keySetId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("document", obj["resource"]!!.jsonObject["kind"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `attributesOf converts nested Kotlin values`() {
        val attributes =
            attributesOf(
                "name" to "alice",
                "age" to 30,
                "ratio" to 0.5,
                "admin" to false,
                "tags" to listOf("a", "b"),
                "nested" to mapOf("k" to arrayOf(1, 2)),
                "nothing" to null,
            )
        assertEquals(JsonPrimitive("alice"), attributes["name"])
        assertEquals(30, attributes["age"]?.jsonPrimitive?.int)
        assertEquals(0.5, attributes["ratio"]?.jsonPrimitive?.double ?: 0.0, 0.0)
        assertEquals(false, attributes["admin"]?.jsonPrimitive?.boolean)
        assertEquals(listOf("a", "b"), attributes["tags"]?.jsonArray?.strings())
        assertEquals(
            listOf(1, 2),
            attributes["nested"]!!.jsonObject["k"]!!.jsonArray.map { it.jsonPrimitive.int },
        )
        assertEquals(kotlinx.serialization.json.JsonNull, attributes["nothing"])
        assertThrows(IllegalArgumentException::class.java) { attributesOf("bad" to Any()) }
    }
}

class ResponseDecodingTest {
    companion object {
        // Captured from the bridge smoke test (CerbosBridge/test/smoke.mjs).
        val checkResourcesJSON =
            """
            {"ok":true,"result":{"requestId":"7fabe13a-412a-410c-aeb4-d4b427fc56be","cerbosCallId":"","results":[
              {"resource":{"kind":"resource","id":"1","policyVersion":"","scope":""},
               "actions":{"read":"EFFECT_ALLOW","update":"EFFECT_DENY"},
               "allowedActions":["read"],
               "validationErrors":[{"path":"/tier","message":"expected string","source":"SOURCE_PRINCIPAL"}],
               "metadata":{"actions":{"read":{"matchedPolicy":"resource.resource.vdefault","matchedScope":""},"update":{"matchedPolicy":"NO_MATCH","matchedScope":""}},"effectiveDerivedRoles":["owner"]},
               "outputs":[{"source":"resource.resource.vdefault#rule-001","value":{"greeting":"hi","n":1}}]},
              {"resource":{"kind":"resource","id":"2"},"actions":{"read":"EFFECT_DENY"}}
            ]}}
            """
                .trimIndent()
    }

    @Test
    fun `checkResources envelope`() {
        val response =
            BridgeJson.unwrap<CheckResourcesResponse>(checkResourcesJSON) {
                CerbosException.Request(it)
            }
        assertEquals(2, response.results.size)
        assertTrue(response.isAllowed("resource", "1", "read"))
        assertFalse(response.isAllowed("resource", "1", "update"))
        assertFalse(response.isAllowed("resource", "1", "unknown"))
        assertFalse(response.isAllowed("resource", "missing", "read"))

        val first = checkNotNull(response.result("resource", "1"))
        assertEquals(listOf("read"), first.allowedActions)
        assertFalse(first.allAllowed)
        assertEquals("SOURCE_PRINCIPAL", first.validationErrors.first().source)
        assertEquals(
            "resource.resource.vdefault",
            first.metadata?.actions?.get("read")?.matchedPolicy,
        )
        assertEquals(listOf("owner"), first.metadata?.effectiveDerivedRoles)
        assertEquals(
            "hi",
            first.outputs.first().value.jsonObject["greeting"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(1, first.outputs.first().value.jsonObject["n"]?.jsonPrimitive?.int)

        val second = checkNotNull(response.result("resource", "2"))
        assertNull(second.metadata)
        assertTrue(second.validationErrors.isEmpty())
        assertTrue(second.outputs.isEmpty())
    }

    @Test
    fun `initialisation failures map to engine, policy source or generic errors`() {
        val engine =
            BridgeError(name = "WebAssemblyUnavailable", message = "no wasm", kind = "engine")
        assertEquals(
            CerbosException.Engine("no wasm"),
            CerbosException.initialisationFailure(engine),
        )
        val hub = BridgeError(name = "NotOK", message = "denied", kind = "policySource", code = 7)
        assertEquals(CerbosException.PolicySource(hub), CerbosException.initialisationFailure(hub))
        val other = BridgeError(name = "Error", message = "boom")
        assertEquals(
            CerbosException.Initialisation(other),
            CerbosException.initialisationFailure(other),
        )
        assertTrue(CerbosException.WebView("gone").isWebViewFailure)
        assertFalse(CerbosException.NotReady.isWebViewFailure)
    }

    @Test
    fun `unknown effects decode as deny without failing the response`() {
        val response =
            BridgeJson.unwrap<CheckResourcesResponse>(
                """{"ok":true,"result":{"requestId":"r","results":[{"resource":{"kind":"k","id":"1"},"actions":{"read":"EFFECT_ALLOW","edit":"EFFECT_SOMETHING_NEW"}}]}}"""
            ) {
                CerbosException.Request(it)
            }
        assertTrue(response.isAllowed("k", "1", "read"))
        assertFalse(response.isAllowed("k", "1", "edit"))
        assertEquals(Effect.DENY, response.results[0].actions["edit"])
    }

    @Test
    fun `failed envelope maps to CerbosException with gRPC code`() {
        val json =
            """{"ok":false,"error":{"name":"NotOK","message":"gRPC error 3 (INVALID_ARGUMENT): bad request","code":3,"details":"bad request"}}"""
        val error =
            assertThrows(CerbosException.Request::class.java) {
                BridgeJson.unwrap<CheckResourcesResponse>(json) { CerbosException.Request(it) }
            }
        assertEquals(
            BridgeError(
                name = "NotOK",
                message = "gRPC error 3 (INVALID_ARGUMENT): bad request",
                code = 3,
                details = "bad request",
            ),
            error.error,
        )
        assertEquals("gRPC error 3 (INVALID_ARGUMENT): bad request (bad request)", error.message)
    }

    @Test
    fun `plan and init results`() {
        val plan =
            BridgeJson.unwrap<PlanResourcesResponse>(
                """{"ok":true,"result":{"requestId":"r","cerbosCallId":"","validationErrors":[],"kind":"KIND_CONDITIONAL","condition":{"operator":"eq","operands":[{"name":"request.resource.attr.ownerId"},{"value":"alice"}]}}}"""
            ) {
                CerbosException.Request(it)
            }
        assertEquals(PlanKind.CONDITIONAL, plan.kind)
        assertEquals(
            "eq",
            plan.condition?.jsonObject?.get("operator")?.jsonPrimitive?.contentOrNull,
        )

        val initResult =
            BridgeJson.unwrap<BridgeInitResult>(
                """{"ok":true,"result":{"status":"ready","bundle":{"bundleId":"SPPO3BI3O0ON0K59","ruleRevision":"8","source":"network","receivedAt":"2026-09-09T11:20:53.082Z"},"server":{"version":"0.55.0","commit":"2901f64","builtAt":"2026-08-13T09:19:02.287Z"}}}"""
            ) {
                CerbosException.Initialisation(it)
            }
        assertEquals(BridgeStatus.READY, initResult.status)
        assertEquals(BundleSource.NETWORK, initResult.bundle?.source)
        assertEquals(Instant.parse("2026-09-09T11:20:53.082Z"), initResult.bundle?.receivedAt)
        assertEquals("0.55.0", initResult.server.version)
    }

    @Test
    fun `envelope without a result is invalid`() {
        assertThrows(CerbosException.InvalidResponse::class.java) {
            BridgeJson.unwrap<CheckResourcesResponse>("""{"ok":true}""") {
                CerbosException.Request(it)
            }
        }
        assertThrows(CerbosException.InvalidResponse::class.java) {
            BridgeJson.unwrap<CheckResourcesResponse>("not json") { CerbosException.Request(it) }
        }
    }
}

class BridgeEventTest {
    @Test
    fun `all event types`() {
        assertEquals(BridgeEvent.BridgeReady, BridgeEvent.decode("""{"type":"bridgeReady"}"""))

        val status =
            BridgeEvent.decode(
                """{"type":"status","status":"failed","error":{"name":"Error","message":"boom"}}"""
            ) as BridgeEvent.Status
        assertEquals(BridgeStatus.FAILED, status.status)
        assertEquals("boom", status.error?.message)

        val bundles =
            BridgeEvent.decode(
                """{"type":"bundles","active":{"bundleId":"B","ruleRevision":"2","source":"cache","receivedAt":"2026-09-09T11:20:53Z"},"pending":{"bundleId":"C","ruleRevision":"3","source":"network","receivedAt":"2026-09-09T11:21:53Z"}}"""
            ) as BridgeEvent.Bundles
        assertEquals("B", bundles.active?.bundleId)
        assertEquals(BundleSource.CACHE, bundles.active?.source)
        assertEquals("C", bundles.pending?.bundleId)

        val update =
            BridgeEvent.decode(
                """{"type":"policyUpdate","ok":false,"error":{"name":"NotOK","message":"unavailable","code":14}}"""
            ) as BridgeEvent.PolicyUpdate
        assertFalse(update.ok)
        assertEquals(14, update.error?.code)
        assertNull(update.bundle)
        assertNull(update.pending)

        val decision =
            BridgeEvent.decode(
                """{"type":"decision","entry":{"callId":"01M","method":{"name":"CheckResources"}}}"""
            ) as BridgeEvent.Decision
        assertEquals("01M", decision.entry.jsonObject["callId"]?.jsonPrimitive?.contentOrNull)

        val validation =
            BridgeEvent.decode(
                """{"type":"validationErrors","errors":[{"path":"/a","message":"m","source":"SOURCE_RESOURCE"}]}"""
            ) as BridgeEvent.ValidationErrors
        assertEquals(1, validation.errors.size)

        val cache =
            BridgeEvent.decode(
                """{"type":"bundleCache","key":"k","body":"AAEC","bundleId":"B","ruleRevision":"3"}"""
            ) as BridgeEvent.BundleCache
        assertEquals("k", cache.key)
        assertEquals("AAEC", cache.body)
        assertEquals("B", cache.bundleId)
        assertEquals("3", cache.ruleRevision)

        val log =
            BridgeEvent.decode("""{"type":"log","level":"warn","message":"careful"}""")
                as BridgeEvent.Log
        assertEquals("warn", log.level)
        assertEquals("careful", log.message)

        val jwt =
            BridgeEvent.decode("""{"type":"jwtDecode","id":"jwt-1","token":"t","keySetId":""}""")
                as BridgeEvent.JwtDecode
        assertEquals("jwt-1", jwt.id)
        assertEquals("t", jwt.token)
        assertTrue(jwt.keySetId.isEmpty())

        assertEquals(
            BridgeEvent.Unknown("somethingNew"),
            BridgeEvent.decode("""{"type":"somethingNew"}"""),
        )
    }

    @Test
    fun `malformed events are rejected`() {
        assertThrows(CerbosException.InvalidResponse::class.java) {
            BridgeEvent.decode("""{"status":"ready"}""")
        }
        assertThrows(CerbosException.InvalidResponse::class.java) {
            BridgeEvent.decode("""{"type":"bundleCache","key":"k"}""")
        }
    }

    @Test
    fun `JavaScript string literals are safely escaped`() {
        val value = "it's \"quoted\"\n</script> "
        val literal = BridgeJson.jsLiteral(value)
        assertTrue(literal.startsWith("\"") && literal.endsWith("\""))
        assertEquals(value, BridgeJson.decode<String>(literal))
    }
}

class OfflineCacheKeyTest {
    @Test
    fun `matches the bridge's key format`() {
        val configuration =
            CerbosEmbeddedPDP.Configuration(
                ruleId = " AVGB9RP6HFBL ",
                scopes = listOf("b", "a"),
                hubBaseUrl = "https://hub.example.com//",
            )
        assertEquals(
            "https://hub.example.com|AVGB9RP6HFBL|a,b",
            CerbosEmbeddedPDP.offlineCacheKey(configuration),
        )
        assertEquals(
            "https://api.cerbos.cloud|R|",
            CerbosEmbeddedPDP.offlineCacheKey(CerbosEmbeddedPDP.Configuration(ruleId = "R")),
        )
    }
}

class HubBaseUrlValidationTest {
    @Test
    fun `https and loopback http are accepted, everything else is rejected`() {
        assertNull(CerbosEmbeddedPDP.validateHubBaseUrl(null))
        assertNull(CerbosEmbeddedPDP.validateHubBaseUrl("https://api.cerbos.cloud"))
        assertNull(CerbosEmbeddedPDP.validateHubBaseUrl("HTTPS://hub.example.com:8443/base/"))
        assertNull(CerbosEmbeddedPDP.validateHubBaseUrl("http://localhost:3592"))
        assertNull(CerbosEmbeddedPDP.validateHubBaseUrl("http://127.0.0.1:9"))
        assertTrue(
            CerbosEmbeddedPDP.validateHubBaseUrl("http://[::1]:9") is CerbosException.InvalidRequest
        )
        assertTrue(
            CerbosEmbeddedPDP.validateHubBaseUrl("http://10.0.2.2:3592")
                is CerbosException.InvalidRequest
        )
        assertTrue(
            CerbosEmbeddedPDP.validateHubBaseUrl("http://hub.example.com")
                is CerbosException.InvalidRequest
        )
        assertTrue(
            CerbosEmbeddedPDP.validateHubBaseUrl("api.cerbos.cloud")
                is CerbosException.InvalidRequest
        )
        assertTrue(
            CerbosEmbeddedPDP.validateHubBaseUrl("ftp://api.cerbos.cloud")
                is CerbosException.InvalidRequest
        )
        assertTrue(
            CerbosEmbeddedPDP.validateHubBaseUrl("not a url") is CerbosException.InvalidRequest
        )
    }
}
