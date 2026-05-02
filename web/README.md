# AdSignage Web (Next.js)

광고주용 어드민 페이지와 디바이스에서 풀스크린으로 띄우는 플레이어 페이지를 모두 담당하는 Next.js App Router 프로젝트.

## 기술 스택

- Next.js 14 (App Router)
- React 18 + TypeScript
- EventSource (SSE 클라이언트)
- HTML5 `<video>` (HTTP Range 스트리밍)

## 라우트

| 경로 | 설명 |
|---|---|
| `/` | 랜딩 |
| `/ads` | 광고 목록 |
| `/ads/[id]` | 광고 상세/스케줄 편집 |
| `/videos` | 본인 업로드 영상 목록 + 신규 업로드 |
| `/devices` | 디바이스 목록 + 음식점 매핑 변경 |
| `/player/[deviceId]` | **광고판 디바이스가 띄우는 풀스크린 플레이어** |

## 플레이어 페이지의 동작

`/player/[deviceId]`는 안드로이드 WebView가 부팅 시 자동 로드하는 페이지입니다.

```
1. URL에서 deviceId 추출
2. 초기 플레이리스트 fetch: GET /api/devices/{deviceId}/playlist
3. SSE 연결: EventSource(/api/devices/{deviceId}/stream)
4. 라운드 로빈 + 스케줄 시간대 필터 + 일일 송출횟수 카운팅
5. 매 재생마다 POST /api/play-events 로 송출 증빙 기록
6. 스케줄 외 시간 / 배정 광고 없음 → /splash.png 표시
7. SSE 이벤트(PLAYLIST_UPDATE / MAPPING_CHANGED) 수신 시 즉시 갱신
```

## 디렉토리 구조

```
app/
├── page.tsx                       — 랜딩
├── ads/                           — 광고 목록 + 상세
├── videos/                        — 영상 업로드/목록
├── devices/                       — 디바이스 매핑 관리
└── player/[deviceId]/
    ├── page.tsx                   — 서버 컴포넌트 (deviceId 전달)
    └── PlayerClient.tsx           — 클라이언트 (SSE + 재생 로직)

components/
├── VideoUploadForm.tsx
├── VideosListTable.tsx
├── AdScheduleForm.tsx
├── AdLookupForm.tsx
├── DevicesTableClient.tsx
├── DeviceRemapModal.tsx
└── RestaurantAssignmentSelector.tsx

hooks/
└── usePlayerSse.ts                — EventSource 래퍼

lib/
├── api.ts                         — fetch + JWT 헤더
├── ads.ts / videos.ts / devices.ts / restaurants.ts
├── assignments.ts                 — 디바이스 매핑 API
├── playEvents.ts                  — 송출 증빙 전송
├── playlist.ts                    — 플레이리스트 정규화
├── roundRobin.ts                  — 라운드 로빈 인덱스 산출
└── dailyCount.ts                  — 일일 카운터(localStorage) + 자정 리셋
```

## 실행

```bash
npm install
npm run dev          # http://localhost:3000
npm run build        # 운영 빌드
npm start
```

## 환경변수

```
NEXT_PUBLIC_API_BASE_URL=https://stream.owl-dev.me
```

(개발 시 `http://localhost:8080`)
