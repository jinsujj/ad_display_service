package com.owldev.adsignage

/**
 * AC 11 — WebView가 로드할 플레이어 페이지 URL을 만들어낸다.
 *
 * 계약은 의도적으로 좁게 잡았다:
 *   - 입력: 베이스 URL(예: https://stream.owl-dev.me) + device_id(UUID)
 *   - 출력: "${base}/player/${deviceId}"
 *
 * 베이스 URL은 문자열 리소스(`R.string.player_base_url`)에서 가져오므로
 * 코드를 건드리지 않고도 QA/스테이징/프로덕션을 교체할 수 있으며, 끝에 붙은
 * 슬래시는 모두 제거한다. device_id는 필수이며 공백일 수 없다 — 공백 id를
 * 넘기는 것은 프로그래밍 오류이므로, WebView가 `…/player/`를 로드해 조용히
 * 404가 나는 대신 즉시 실패(fail fast)한다.
 *
 * Android import가 없는 순수 object로 유지하여 Robolectric 없이 JVM 단위
 * 테스트에서 검증 가능하다 — 배포 환경에 따라 어긋나기 가장 쉬운 부분이
 * URL 계약이므로, 가장 저렴한 계층의 테스트로 커버되도록 한다.
 */
object PlayerUrl {

    private const val PLAYER_PATH = "player"

    /**
     * [baseUrl] 하위에서 [deviceId]에 해당하는 표준 플레이어 URL을 반환한다.
     *
     * @throws IllegalArgumentException [baseUrl] 또는 [deviceId]가 공백인 경우
     */
    fun forDevice(baseUrl: String, deviceId: String): String {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        val trimmedBase = baseUrl.trim().trimEnd('/')
        val trimmedDevice = deviceId.trim()

        return "$trimmedBase/$PLAYER_PATH/$trimmedDevice"
    }
}
