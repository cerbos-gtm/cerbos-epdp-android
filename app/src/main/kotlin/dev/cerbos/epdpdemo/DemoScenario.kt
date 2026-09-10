package dev.cerbos.epdpdemo

import dev.cerbos.epdp.CheckResourcesRequest
import dev.cerbos.epdp.Principal
import dev.cerbos.epdp.Resource
import dev.cerbos.epdp.ResourceCheck
import dev.cerbos.epdp.attributesOf

/**
 * The access check exercised by the demo UI. It targets the sample policy used by the Cerbos GTM
 * demos: a `resource` kind where `ADMIN` can do anything, `USER` can `read`/`create`, and `USER`
 * can `update`/`delete`/`publish` only resources they own (`R.attr.ownerId == P.id`).
 */
data class DemoScenario(
    val principalId: String = "alice@example.com",
    val role: String = "USER",
    val ownsResource: Boolean = true,
) {
    val ownerId: String
        get() = if (ownsResource) principalId else "someone-else@example.com"

    fun request(): CheckResourcesRequest =
        CheckResourcesRequest(
            principal =
                Principal(
                    id = principalId,
                    roles = listOf(role),
                    attributes = attributesOf("tier" to "PREMIUM"),
                ),
            resources =
                listOf(
                    ResourceCheck(
                        resource =
                            Resource(
                                kind = RESOURCE_KIND,
                                id = "1",
                                attributes = attributesOf("ownerId" to ownerId),
                            ),
                        actions = ACTIONS,
                    )
                ),
            includeMetadata = true,
        )

    companion object {
        val ROLES = listOf("USER", "ADMIN")
        val ACTIONS = listOf("read", "create", "update", "delete", "publish")
        const val RESOURCE_KIND = "resource"
    }
}
