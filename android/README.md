# AdSignage Android (Kotlin WebView 래퍼)

안드로이드 광고판 디바이스에 설치되는 키오스크용 APK. 풀스크린 WebView 1개로 구성되며, 모든 재생 로직은 Next.js 플레이어 페이지가 담당합니다.

## 기술 스택

- Kotlin / Android Gradle Plugin 8.2.2
- WebView (시스템 컴포넌트)
- WindowInsetsController (IMMERSIVE_STICKY)

## 동작 흐름

```
부팅 (BOOT_COMPLETED)
        │
        ▼
BootReceiver → MainActivity 자동 실행
        │
        ▼
DeviceIdManager
  • 첫 부팅 시 UUID 생성 → SharedPreferences 저장
  • 이후 부팅엔 기존 UUID 재사용
        │
        ▼
PlayerUrl.build(deviceId)
  → https://stream.owl-dev.me/player/{deviceId}
        │
        ▼
풀스크린 WebView 로드
  • IMMERSIVE_STICKY (상태바/내비게이션바 숨김)
  • FLAG_KEEP_SCREEN_ON (화면 항상 ON)
  • JS / 자동재생 / Fullscreen API 활성화
```

## 주요 파일

```
app/src/main/java/com/owldev/adsignage/
├── MainActivity.kt          — 풀스크린 WebView 호스팅
├── DeviceIdManager.kt       — UUID 생성/영속 (SharedPreferences)
├── BootReceiver.kt          — BOOT_COMPLETED 자동 시작
└── PlayerUrl.kt             — 플레이어 URL 빌더

app/src/main/AndroidManifest.xml
  • 인터넷 권한
  • RECEIVE_BOOT_COMPLETED 권한
  • MainActivity launcher 등록
  • BootReceiver 등록
```

## 빌드

```bash
# 프로젝트 루트에서 한 번만 실행 (gradle wrapper 부재 시)
gradle wrapper --gradle-version 8.5

# local.properties 작성 (Android SDK 경로)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# Debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release APK (서명 키 필요)
./gradlew assembleRelease
```

## 디바이스 설치

```bash
# 1) USB 디버깅 활성화 후
adb install app/build/outputs/apk/debug/app-debug.apk

# 2) 자동 시작은 BOOT_COMPLETED 수신 시 발동
#    초기 1회는 앱 아이콘으로 실행
```

## 데모 디바이스 2대 운영

각 디바이스 첫 부팅 시 서로 다른 UUID가 생성되어 SharedPreferences에 저장됩니다. 어드민에서 두 device_id를 각각 다른 음식점에 매핑하면 끝.

## 테스트

```bash
./gradlew test
```

`MainActivityTest`, `DeviceIdManagerTest`, `BootReceiverTest`, `PlayerUrlTest` 4종.

## 권장 디바이스 설정

- 화면 자동 잠금 끄기 (또는 매우 길게)
- 시스템 시각 자동 동기화 켜기 (스케줄 정확도)
- 음식점 WiFi 자동 연결 등록
