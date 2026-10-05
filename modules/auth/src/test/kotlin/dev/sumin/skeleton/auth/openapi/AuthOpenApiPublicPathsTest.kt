package dev.sumin.skeleton.auth.openapi

import dev.sumin.skeleton.common.web.PublicEndpoint
import dev.sumin.skeleton.common.web.PublicEndpointRegistry
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthOpenApiPublicPathsTest {
    private fun openApi() = OpenAPI().paths(
        Paths()
            .addPathItem("/api/v1/account/sign-up", PathItem().post(Operation()))
            .addPathItem("/api/v1/account/me", PathItem().get(Operation()))
            .addPathItem("/api/v1/auth/magic-link/redeem", PathItem().post(Operation())),
    )

    @Test
    fun `endpoints that modules registered as public carry no bearer requirement, the others still do`() {
        val registry = PublicEndpointRegistry(listOf(PublicEndpoint.method("POST", "/api/v1/account/sign-up"), PublicEndpoint.method("POST", "/api/v1/auth/magic-link/**")))
        val api = openApi()
        val provider = org.springframework.beans.factory.support.StaticListableBeanFactory().apply { addBean("registry", registry) }.getBeanProvider(PublicEndpointRegistry::class.java)
        AuthOpenApiAutoConfiguration().authOpenApiCustomizer(provider).customise(api)

        assertTrue(api.paths["/api/v1/account/sign-up"]!!.post.security.isNullOrEmpty(), "sign-up is public")
        assertTrue(api.paths["/api/v1/auth/magic-link/redeem"]!!.post.security.isNullOrEmpty(), "pattern entries count")
        assertEquals(1, api.paths["/api/v1/account/me"]!!.get.security.size, "me needs the bearer token")
    }
}
