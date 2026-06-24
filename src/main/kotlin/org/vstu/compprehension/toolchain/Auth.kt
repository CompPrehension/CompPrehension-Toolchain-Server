package org.vstu.compprehension.toolchain

import io.javalin.http.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Optional shared-secret gate for the RPC routes.
 *
 * If [secret] is configured (non-blank), every RPC request must present a matching secret, otherwise
 * the request is rejected with HTTP 403. If [secret] is not configured, access is open and the secret
 * (if a client happens to send one) is ignored.
 *
 * A client may supply the secret via any of:
 *  - the `X-Access-Secret` request header,
 *  - an `Authorization: Bearer <secret>` header,
 *  - an `access_secret` query parameter.
 */
object Auth {

    @Volatile
    var secret: String? = null

    fun isConfigured(): Boolean = !secret.isNullOrBlank()

    /** True when the request may proceed. */
    fun authorized(ctx: Context): Boolean {
        val expected = secret
        if (expected.isNullOrBlank()) return true
        val provided = provided(ctx) ?: return false
        return constantTimeEquals(expected, provided)
    }

    private fun provided(ctx: Context): String? {
        ctx.header("X-Access-Secret")?.takeIf { it.isNotEmpty() }?.let { return it }
        ctx.header("Authorization")?.let { header ->
            if (header.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) {
                return header.substring(7).trim().takeIf { it.isNotEmpty() }
            }
        }
        return ctx.queryParam("access_secret")?.takeIf { it.isNotEmpty() }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))
}
