package dev.sumin.skeleton.api

import dev.sumin.skeleton.auth.api.AuthController
import dev.sumin.skeleton.auth.social.api.OAuthSocialAuthController
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.javaMethod
import kotlin.test.Test
import kotlin.test.assertFalse

class RestResponseContractTest {
    @Test
    fun `REST handlers return explicit response DTOs`() {
        val handlers = listOf(
            HelloController::class,
            OperationExampleController::class,
            AuthController::class,
            OAuthSocialAuthController::class,
        )

        val violations = handlers.flatMap { handler ->
            handler.memberFunctions
                .filter { it.visibility == KVisibility.PUBLIC }
                .filterNot { it.name in ignoredFunctionNames }
                .filterNot { it.javaMethod?.isAnnotationPresent(ApiResponseEnvelope::class.java) == true }
                .mapNotNull { function ->
                    val returnType = function.returnType.toString()
                    val returnsRawType =
                        returnType.contains("kotlin.Any") ||
                            returnType.contains("java.lang.Object")
                    if (returnsRawType) {
                        "${handler.simpleName}.${function.name}: $returnType"
                    } else {
                        null
                    }
                }
        }

        assertFalse(
            violations.isNotEmpty(),
            "REST handlers must return explicit response DTOs: $violations",
        )
    }

    private companion object {
        val ignoredFunctionNames = setOf("equals", "hashCode", "toString")
    }
}
