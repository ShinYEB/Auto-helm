package dev.autohelm.control

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/** The browser sends this header only to the control tower; no service is exposed to it. */
@Component
class ApiTokenFilter(private val properties: ControlProperties) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val supplied = request.getHeader("X-Control-Token")?.toByteArray(Charsets.UTF_8)
        val expected = properties.token.toByteArray(Charsets.UTF_8)
        if (supplied == null || !MessageDigest.isEqual(supplied, expected)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            return
        }
        response.setHeader("Cache-Control", "no-store")
        chain.doFilter(request, response)
    }
}
