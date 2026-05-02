package me.owldev.adsignage.sse

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * device_id로 키잉된 [SseEmitter]의 스레드 안전 registry.
 *
 * 한 디바이스가 동시에 여러 emitter를 가질 수 있음(예: 네트워크 끊김 후
 * 재연결되는 안드로이드 WebView, 또는 두 번째 탭에서 열린 관리자 디버그
 * 페이지) — 디바이스의 모든 활성 emitter는 모든 브로드캐스트를 받음.
 *
 * # 공개 API (Sub-AC 1 계약)
 *  - [add]            — deviceId 아래에 emitter 등록
 *  - [remove]         — emitter를 명시적으로 제거(기저 연결이 완료/타임아웃/
 *                       오류가 날 때 자동으로도 호출됨)
 *  - [getByDeviceId]  — 디바이스의 라이브 emitter에 대한 읽기 전용 스냅샷
 *
 * # 라이프사이클 보장
 *  - [add]는 emitter의 onCompletion / onTimeout / onError 콜백을 연결하므로
 *    호출자의 개입 없이 닫힌 연결이 제거됨.
 *  - [broadcast]는 주어진 deviceId에 현재 등록된 모든 emitter로 SSE 이벤트를
 *    전송. 실패는 emitter별로 잡히므로 잘못된 클라이언트 하나가 형제들을
 *    굶주리게 할 수 없음; 실패한 emitter는 complete-with-error 처리되고
 *    registry에서 제거됨.
 *
 * # 왜 디바이스별 CopyOnWriteArrayList인가
 * 같은 디바이스의 새 등록을 차단할 수 있는 락을 잡지 않고도 브로드캐스트
 * 중에 순회할 수 있게 해주며, ConcurrentHashMap은 동시 add / broadcast /
 * remove 호출에 대해 외부 맵을 안전하게 유지함.
 *
 * # 데모에서의 위치
 * 이 컴포넌트는 [me.owldev.adsignage.domain.assignment.DeviceAssignmentService]의
 * 와이어 측 동반자(companion) — 관리자가 디바이스를 다른 음식점으로
 * 리매핑하면 이벤트가 발행되고, 리스너가 이 registry를 통해 영향받는
 * 디바이스의 모든 연결된 플레이어 페이지로 전달함. 이 경로가 데모 시나리오
 * #3(실시간 device-to-restaurant 리매핑)을 화면을 보는 사람에게 즉각적으로
 * 느껴지게 만듦.
 */
@Component
class SseEmitterRegistry {

    private val log = LoggerFactory.getLogger(SseEmitterRegistry::class.java)

    /**
     * deviceId → 라이브 emitter 리스트. CopyOnWriteArrayList는 같은 디바이스의
     * 새 등록을 차단할 수 있는 락을 잡지 않고도 브로드캐스트 중에 순회할 수
     * 있게 해주며, ConcurrentHashMap은 동시 add / broadcast / remove 호출에
     * 대해 외부 맵을 안전하게 유지함.
     */
    private val emitters: ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> =
        ConcurrentHashMap()

    /**
     * [emitter]를 [deviceId] 아래에 등록. emitter의 onCompletion, onTimeout,
     * onError 콜백이 여기서 연결되므로 호출자는 등록 해제를 기억할 필요가
     * 없음 — 기저 HTTP 연결이 어떤 이유로든 닫히는 순간 SSE 인프라가 대신
     * 처리해 줌.
     *
     * 호출자가 체이닝할 수 있도록 [emitter]를 반환
     * (`val e = registry.add(id, SseEmitter())`).
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
            // 스프링은 timeout 콜백에서 complete()를 요구함. 그렇지 않으면
            // 응답이 컨테이너 셧다운까지 반-개방(half-open) 상태로 남음.
            try { emitter.complete() } catch (ignored: Exception) { /* 베스트 에포트 */ }
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
     * [add]에 대한 하위 호환 별칭. 이름 변경 이전 호출자
     * (AC 5: DeviceSseController, DeviceMappingChangedSseListener)가
     * `registry.register(...)`를 사용했으므로, 외부/테스트 호출자가 이전
     * 이름으로도 컴파일되도록 진입점을 유지.
     */
    fun register(deviceId: String, emitter: SseEmitter): SseEmitter = add(deviceId, emitter)

    /**
     * [deviceId]에 대해 registry에서 [emitter]를 명시적으로 제거.
     *
     * 멱등(Idempotent): 등록된 적이 없는(혹은 라이프사이클 콜백에 의해 이미
     * 제거된) emitter를 제거하는 것은 no-op. 또한 빈 디바이스 엔트리는
     * 가지치기되어, 완전히 끊어진 디바이스에 대해 디바이스별 리스트를 결코
     * 누수시키지 않음.
     */
    fun remove(deviceId: String, emitter: SseEmitter) {
        val list = emitters[deviceId] ?: return
        list.remove(emitter)
        if (list.isEmpty()) {
            // 다른 스레드가 같은 디바이스에 대해 새 emitter로 방금 채운
            // 리스트를 절대 삭제하지 않도록 CAS 스타일로 제거.
            emitters.remove(deviceId, list)
        }
    }

    /**
     * [deviceId]의 라이브 emitter에 대한 불변 스냅샷을 반환하거나, 디바이스에
     * 현재 연결이 없으면 빈 리스트를 반환.
     *
     * 반환된 리스트는 방어적 복사본 — 변경해도 registry에 영향이 없고,
     * registry가 자체 변경되어도 스냅샷은 무효화되지 않음. "현재 모든
     * emitter로 전송"을 원하는 호출자는 emitter별 실패 처리가 추가된
     * [broadcast]를 선호해야 함.
     */
    fun getByDeviceId(deviceId: String): List<SseEmitter> {
        val list = emitters[deviceId] ?: return emptyList()
        // CopyOnWriteArrayList 순회는 이미 스냅샷 안전이지만, 호출자가 리스트
        // 참조를 통해 registry 상태를 변경할 수 없도록 수정 불가능한 복사본을
        // 건네줌.
        return Collections.unmodifiableList(list.toList())
    }

    /**
     * [deviceId]에 현재 등록된 모든 emitter로 [event]를 브로드캐스트.
     *
     * 이벤트를 성공적으로 받은 emitter의 수를 반환. 실패는 로그로 남기고
     * 문제가 된 emitter는 registry에서 제거됨 — 호출자는 클라이언트별
     * 오류를 처리할 필요가 없음.
     */
    fun broadcast(deviceId: String, event: SseEmitter.SseEventBuilder): Int {
        val list = emitters[deviceId] ?: return 0
        if (list.isEmpty()) return 0

        var delivered = 0
        // 실패 시 ConcurrentModification 없이 `list`를 변경할 수 있도록
        // CopyOnWriteArrayList(이미 스냅샷 안전)를 순회.
        for (emitter in list) {
            try {
                emitter.send(event)
                delivered++
            } catch (ex: IOException) {
                // 클라이언트가 전송 도중 끊김 — 제거.
                log.debug("SSE send failed (IO) for device={}: {}", deviceId, ex.message)
                remove(deviceId, emitter)
                try { emitter.completeWithError(ex) } catch (_: Exception) { /* 베스트 에포트 */ }
            } catch (ex: IllegalStateException) {
                // emitter가 이미 완료됨 — 제거.
                log.debug("SSE send failed (state) for device={}: {}", deviceId, ex.message)
                remove(deviceId, emitter)
            } catch (ex: Exception) {
                log.warn("SSE send failed for device={}: {}", deviceId, ex.message)
                remove(deviceId, emitter)
                try { emitter.completeWithError(ex) } catch (_: Exception) { /* 베스트 에포트 */ }
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
     * [deviceId]에 대해 현재 등록된 emitter 수를 반환.
     * 테스트/디버그 헬퍼 — 운영 핫패스에는 포함되지 않음.
     */
    fun connectionCount(deviceId: String): Int = emitters[deviceId]?.size ?: 0
}

/**
 * AC 5 이름에 대한 하위 호환 별칭. 새 코드는 [SseEmitterRegistry]를
 * 참조해야 함; 이 typealias는 이름 변경이 아직 이전 이름을 사용하는
 * 진행 중 브랜치를 깨지 않도록 함.
 *
 * 스프링은 클래스 정체성으로 @Component를 해석하며(typealias는 동일한
 * JVM 타입), 따라서 중복 빈 없이 DI가 계속 동작함.
 */
typealias DeviceSseRegistry = SseEmitterRegistry
