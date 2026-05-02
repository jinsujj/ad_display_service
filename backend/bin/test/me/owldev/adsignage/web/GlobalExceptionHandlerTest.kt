package me.owldev.adsignage.web

import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Path
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sub-AC 5 unit coverage for the two new handlers added to
 * [GlobalExceptionHandler] in this iteration. The other handlers
 * (`MethodArgumentNotValidException`, `VideoTooLargeException`,
 * `HttpMediaTypeNotSupportedException`, etc.) are already exercised end-to-end
 * by `VideoControllerIntegrationTest` via `@SpringBootTest`.
 *
 * We invoke the handler methods directly here rather than spinning up a
 * MockMvc stack because:
 *  - `MaxUploadSizeExceededException` originates inside Spring's multipart
 *    parser and cannot be triggered cleanly from MockMvc (which bypasses the
 *    parser when it sees a `MockMultipartFile`); we'd otherwise need a real
 *    embedded Tomcat with a custom `multipart.max-file-size`.
 *  - `ConstraintViolationException` requires an `@Validated` controller with
 *    constrained `@PathVariable`s; that's controller plumbing that doesn't
 *    yet exist on any endpoint.
 *
 * Direct invocation gives us deterministic, fast coverage of the response
 * shape and HTTP status without that scaffolding.
 */
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `MaxUploadSizeExceededException maps to 413 with byte cap in message`() {
        val response = handler.handleMaxUploadSize(MaxUploadSizeExceededException(1024L))

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.statusCode)
        val body = assertNotNull(response.body, "ApiError body must be present")
        assertEquals(413, body.status)
        assertEquals("Payload Too Large", body.error)
        assertTrue(
            body.message.contains("1024"),
            "message should surface the configured cap (got: '${body.message}')",
        )
        assertNull(body.fieldErrors, "size errors are not field-level")
    }

    @Test
    fun `MultipartException maps to 400 with original message`() {
        val response = handler.handleMultipart(MultipartException("boundary missing"))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val body = assertNotNull(response.body)
        assertEquals(400, body.status)
        assertEquals("Bad Request", body.error)
        assertEquals("boundary missing", body.message)
    }

    @Test
    fun `ConstraintViolationException maps to 400 with field error map`() {
        val violation = stubViolation(
            propertyPath = "getDevice.deviceId",
            message = "must not be blank",
        )
        val ex = ConstraintViolationException(setOf(violation))

        val response = handler.handleConstraintViolation(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val body = assertNotNull(response.body)
        assertEquals(400, body.status)
        assertEquals("Bad Request", body.error)
        assertEquals("Request parameter validation failed", body.message)

        val fields = assertNotNull(body.fieldErrors, "field errors must be present")
        // propertyPath is reduced to the leaf segment so the response matches
        // the parameter the client actually submitted.
        assertEquals(mapOf("deviceId" to "must not be blank"), fields)
    }

    @Test
    fun `ConstraintViolationException with empty violations omits fieldErrors`() {
        val response = handler.handleConstraintViolation(ConstraintViolationException(emptySet()))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val body = assertNotNull(response.body)
        assertNull(body.fieldErrors, "no violations → no field map (clean JSON)")
    }

    /**
     * Build a minimal [ConstraintViolation] stub. We only mock the two
     * accessors that [GlobalExceptionHandler.handleConstraintViolation] reads
     * (`propertyPath` and `message`); everything else throws so the test fails
     * loudly if the production code starts depending on more of the API
     * surface than it should.
     */
    private fun stubViolation(propertyPath: String, message: String): ConstraintViolation<Any> {
        val path = object : Path {
            override fun iterator(): MutableIterator<Path.Node> =
                throw UnsupportedOperationException("not used by handler")
            override fun toString(): String = propertyPath
        }
        return object : ConstraintViolation<Any> {
            override fun getMessage(): String = message
            override fun getMessageTemplate(): String = message
            override fun getRootBean(): Any = throw UnsupportedOperationException()
            override fun getRootBeanClass(): Class<Any> = throw UnsupportedOperationException()
            override fun getLeafBean(): Any = throw UnsupportedOperationException()
            override fun getExecutableParameters(): Array<Any> = throw UnsupportedOperationException()
            override fun getExecutableReturnValue(): Any = throw UnsupportedOperationException()
            override fun getPropertyPath(): Path = path
            override fun getInvalidValue(): Any = throw UnsupportedOperationException()
            override fun getConstraintDescriptor(): jakarta.validation.metadata.ConstraintDescriptor<*> =
                throw UnsupportedOperationException()
            override fun <U : Any?> unwrap(type: Class<U>?): U = throw UnsupportedOperationException()
        }
    }
}
