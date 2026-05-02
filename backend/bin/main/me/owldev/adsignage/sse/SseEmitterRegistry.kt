package me.owldev.adsignage.sse

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe registry of [SseEmitter]s keyed by device_id.
 *
 * One device may have multiple concurrent emitters (e.g. Android WebView
 * reconnecting after a network blip, or an admin debug page open in a
 * second tab) — every active emitter for a device receives every broadcast.
 *
 * # Public API (Sub-AC 1 contract)
 *  - [add]            — register an emitter under a deviceId
 *  - [remove]         — explicitly drop an emitter (also called automatically
 *                       when the underlying connection completes / times out
 *                       / errors)
 *  - [getByDeviceId]  — read-only snapshot of the live emitters for a device
 *
 * # Lifecycle guarantees
 *  - [add] wires the emitter's onCompletion / onTimeout / onError callbacks
 *    so a closed connection is removed without intervention from the caller.
 *  - [broadcast] sends an SSE event to every emitter currently registered
 *    for the given deviceId. Failures are caught per-emitter so one bad
 *    client cannot starve siblings; the failing emitter is completed-with-
 *    error and removed from the registry.
 *
 * # Why a CopyOnWriteArrayList per device
 * It lets us iterate during broadcast without holding a lock that would
 * block new registrations from the same device, while ConcurrentHashMap
 * keeps the outer map safe for concurrent add / broadcast / remove calls.
 *
 * # Where this fits in the demo
 * This component is the wire-side companion to
 * [me.owldev.adsignage.domain.assignment.DeviceAssignmentService] — when an
 * admin remaps a device to a different restaurant, an event is published,
 * and a listener forwards it through this registry to every connected
 * player page for the affected device. That path is what makes demo
 * scenario #3 (real-time device-to-restaurant remapping) feel instant to a
 * human watching the screen.
 */
@Component
class SseEmitterRegistry {

    private val log = LoggerFactory.getLogger(SseEmitterRegistry::class.java)

    /**
     * deviceId → list of live emitters. CopyOnWriteArrayList lets us iterate
     * during broadcast without holding a lock that would block new
     * registrations from the same device, while ConcurrentHashMap keeps the
     * outer map safe for concurrent add / broadcast / remove calls.
     */
    private val emitters: ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> =
        ConcurrentHashMap()

    /**
     * Registers [emitter] under [deviceId]. The emitter's onCompletion,
     * onTimeout and onError callbacks are wired here, so the caller does
     * not need to remember to deregister — the SSE infra does it for them
     * the moment the underlying HTTP connection closes for any reason.
     *
     * Returns [emitter] so callers can chain (`val e = registry.add(id, SseEmitter())`).
     */
    fun add(deviceId: String, emitter: SseEmitter): SseEmitter {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        val list = emitters.computeIfAbsent(deviceId) { CopyOnWriteArrayList() }
        list += emitter

        emitter.onCompletion {
            remove(deviceId, emitter)
            log.debug("SSE emitter completed for device={}", deviceId)
        }
        emitter.onTimeout {
            remove(deviceId, emitter)
            // Spring requires complete() in the timeout callback, otherwise
            // the response stays half-open until container teardown.
            try { emitter.complete() } catch (ignored: Exception) { /* best-effort */ }
            log.debug("SSE emitter timed out for device={}", deviceId)
        }
        emitter.onError { ex ->
            remove(deviceId, emitter)
            log.debug("SSE emitter error for device={}: {}", deviceId, ex.message)
        }

        log.info(
            "SSE register: device={} totalEmittersForDevice={}",
            deviceId,
            list.size,
        )
        return emitter
    }

    /**
     * Backwards-compatible alias for [add]. The pre-rename callers
     * (AC 5: DeviceSseController, DeviceMappingChangedSseListener) used
     * `registry.register(...)`; keep the entry point so any external/test
     * caller still compiles.
     */
    fun register(deviceId: String, emitter: SseEmitter): SseEmitter = add(deviceId, emitter)

    /**
     * Explicitly removes [emitter] from the registry for [deviceId].
     *
     * Idempotent: removing an emitter that was never registered (or has
     * already been removed by a lifecycle callback) is a no-op. The empty
     * device entry is also pruned so we never leak per-device lists for
     * devices that have fully disconnected.
     */
    fun remove(deviceId: String, emitter: SseEmitter) {
        val list = emitters[deviceId] ?: return
        list.remove(emitter)
        if (list.isEmpty()) {
            // CAS-style removal so we never delete a list that another
            // thread just refilled with a new emitter for the same device.
            emitters.remove(deviceId, list)
        }
    }

    /**
     * Returns an immutable snapshot of the live emitters for [deviceId],
     * or an empty list if the device has no current connections.
     *
     * The returned list is a defensive copy — mutating it does NOT affect
     * the registry, and the registry mutating itself does NOT invalidate
     * the snapshot. Callers that want to send to "all current emitters"
     * should prefer [broadcast] which adds per-emitter failure handling.
     */
    fun getByDeviceId(deviceId: String): List<SseEmitter> {
        val list = emitters[deviceId] ?: return emptyList()
        // CopyOnWriteArrayList iteration is already snapshot-safe, but we
        // hand callers an unmodifiable copy so they cannot mutate registry
        // state through the list reference.
        return Collections.unmodifiableList(list.toList())
    }

    /**
     * Broadcasts [event] to every emitter currently registered for [deviceId].
     *
     * Returns the number of emitters that received the event successfully.
     * Failures are logged and the offending emitter is purged from the
     * registry — the caller does not need to handle per-client errors.
     */
    fun broadcast(deviceId: String, event: SseEmitter.SseEventBuilder): Int {
        val list = emitters[deviceId] ?: return 0
        if (list.isEmpty()) return 0

        var delivered = 0
        // Iterate over the CopyOnWriteArrayList (already snapshot-safe) so
        // we can mutate `list` on failure without ConcurrentModification.
        for (emitter in list) {
            try {
                emitter.send(event)
                delivered++
            } catch (ex: IOException) {
                // Client disconnected mid-send — drop them.
                log.debug("SSE send failed (IO) for device={}: {}", deviceId, ex.message)
                remove(deviceId, emitter)
                try { emitter.completeWithError(ex) } catch (_: Exception) { /* best-effort */ }
            } catch (ex: IllegalStateException) {
                // Emitter already completed — drop them.
                log.debug("SSE send failed (state) for device={}: {}", deviceId, ex.message)
                remove(deviceId, emitter)
            } catch (ex: Exception) {
                log.warn("SSE send failed for device={}: {}", deviceId, ex.message)
                remove(deviceId, emitter)
                try { emitter.completeWithError(ex) } catch (_: Exception) { /* best-effort */ }
            }
        }
        log.info(
            "SSE broadcast: device={} delivered={}/{} attempted",
            deviceId,
            delivered,
            list.size,
        )
        return delivered
    }

    /**
     * Returns the current number of registered emitters for [deviceId].
     * Test/debug helper — not part of the production hot path.
     */
    fun connectionCount(deviceId: String): Int = emitters[deviceId]?.size ?: 0
}

/**
 * Backwards-compatible alias for the AC 5 name. New code should reference
 * [SseEmitterRegistry]; this typealias prevents the rename from breaking
 * any in-flight branches that still type the old name.
 *
 * Spring resolves the @Component by class identity (a typealias is the
 * same JVM type), so DI continues to work without a duplicate bean.
 */
typealias DeviceSseRegistry = SseEmitterRegistry
