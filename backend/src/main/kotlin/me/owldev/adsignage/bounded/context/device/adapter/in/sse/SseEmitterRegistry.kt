package me.owldev.adsignage.bounded.context.device.adapter.`in`.sse

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * device_id 로 키잉된 [SseEmitter] 의 스레드 안전 registry.
 *
 * 한 디바이스가 동시에 여러 emitter 를 가질 수 있음(네트워크 끊김 후 재연결되는
 * 안드로이드 WebView, 두 번째 탭에서 열린 관리자 디버그 페이지) — 디바이스의
 * 모든 활성 emitter 는 모든 브로드캐스트를 받음.
 *
 * **device 컨텍스트 소속**: registry 는 사실상 디바이스별 emitter 풀이고,
 * 디바이스가 connect 하는 SSE 엔드포인트(`/events`, `/stream`)와 함께
 * device 컨텍스트의 inbound 어댑터를 형성한다.
 *
 * 라이프사이클 보장:
 *  - [add] 는 emitter 의 onCompletion / onTimeout / onError 콜백을 연결.
 *  - [broadcast] 는 emitter 별 실패를 흡수하고 실패한 emitter 를 제거.
 *  - keepalive 가 10초마다 dead TCP 를 빠르게 청소.
 */
@Component
class SseEmitterRegistry {

    private val log = LoggerFactory.getLogger(SseEmitterRegistry::class.java)

    private val emitters: ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> =
        ConcurrentHashMap()

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
            try { emitter.complete() } catch (_: Exception) { /* best-effort */ }
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
     * [add] 의 하위 호환 별칭.
     */
    fun register(deviceId: String, emitter: SseEmitter): SseEmitter = add(deviceId, emitter)

    fun remove(deviceId: String, emitter: SseEmitter) {
        val list = emitters[deviceId] ?: return
        list.remove(emitter)
        if (list.isEmpty()) {
            emitters.remove(deviceId, list)
        }
    }

    fun getByDeviceId(deviceId: String): List<SseEmitter> {
        val list = emitters[deviceId] ?: return emptyList()
        return Collections.unmodifiableList(list.toList())
    }

    fun broadcast(deviceId: String, event: SseEmitter.SseEventBuilder): Int {
        val list = emitters[deviceId] ?: return 0
        if (list.isEmpty()) return 0

        var delivered = 0
        for (emitter in list) {
            try {
                emitter.send(event)
                delivered++
            } catch (ex: IOException) {
                log.debug("SSE send failed (IO) for device={}: {}", deviceId, ex.message)
                remove(deviceId, emitter)
                try { emitter.completeWithError(ex) } catch (_: Exception) { /* best-effort */ }
            } catch (ex: IllegalStateException) {
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

    fun connectionCount(deviceId: String): Int = emitters[deviceId]?.size ?: 0

    /**
     * [deviceId] 의 디바이스가 현재 어떤 SSE 연결이라도 가지고 있는지.
     * 어드민 모니터링 화면이 "송출 중" / "오프라인" 을 결정하는 가장 정확한 신호.
     */
    fun isDeviceConnected(deviceId: String): Boolean {
        val list = emitters[deviceId] ?: return false
        return list.isNotEmpty()
    }

    /**
     * [deviceId] 의 모든 SSE emitter 를 즉시 종료. 디바이스가 명시적으로 종료를
     * 알려올 때 호출되어 keepalive 30s 를 기다리지 않고 어드민 모니터에서 즉시
     * 오프라인 표시되도록 한다.
     */
    fun forceCloseAll(deviceId: String) {
        val list = emitters[deviceId] ?: return
        for (emitter in list.toList()) {
            try { emitter.complete() } catch (_: Exception) { /* best-effort */ }
            remove(deviceId, emitter)
        }
    }

    /**
     * 모든 emitter 에 10초마다 SSE 주석(`:keepalive`) 을 보내, 클라이언트가 사라진
     * phantom 연결을 빨리 발견·제거한다.
     */
    @Scheduled(fixedRate = KEEPALIVE_INTERVAL_MS)
    fun sendKeepalive() {
        if (emitters.isEmpty()) return
        var totalSent = 0
        var totalRemoved = 0
        for ((deviceId, list) in emitters) {
            for (emitter in list) {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"))
                    totalSent++
                } catch (ex: IOException) {
                    remove(deviceId, emitter)
                    totalRemoved++
                    try { emitter.completeWithError(ex) } catch (_: Exception) { /* best-effort */ }
                } catch (ex: IllegalStateException) {
                    remove(deviceId, emitter)
                    totalRemoved++
                } catch (ex: Exception) {
                    remove(deviceId, emitter)
                    totalRemoved++
                    try { emitter.completeWithError(ex) } catch (_: Exception) { /* best-effort */ }
                }
            }
        }
        if (totalRemoved > 0 || log.isDebugEnabled) {
            log.debug(
                "SSE keepalive: sent={} removed={} liveDevices={}",
                totalSent, totalRemoved, emitters.size,
            )
        }
    }

    companion object {
        /** SSE keepalive 주기. 10초면 50K 디바이스 환경에서 평균 5K writes/s. */
        const val KEEPALIVE_INTERVAL_MS: Long = 10_000L
    }
}

/**
 * AC 5 이름에 대한 하위 호환 별칭. typealias 는 동일한 JVM 타입이므로 DI 에 영향 없음.
 */
typealias DeviceSseRegistry = SseEmitterRegistry
