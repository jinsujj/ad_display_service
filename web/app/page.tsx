import Link from "next/link";

export default function HomePage() {
  return (
    <section>
      <div className="page-header">
        <div>
          <h1>AdSignage 어드민</h1>
          <div className="subtitle">
            음식점 주류 냉장고 디지털 광고판 — 운영자 콘솔
          </div>
        </div>
      </div>
      <p className="muted">아래에서 작업할 영역을 선택하세요:</p>
      <ul>
        <li>
          <Link href="/videos">영상</Link> — 업로드된 모든 MP4 광고를 조회하고
          새 영상을 업로드합니다 (진행 표시 포함).
        </li>
        <li>
          <Link href="/ads">광고</Link> — 광고 ID로 들어가 일일 송출 스케줄
          (시작/종료 시간 + 일일 송출 횟수)을 편집합니다.
        </li>
        <li>
          <Link href="/devices">디바이스</Link> — 등록된 모든 광고판 디바이스와
          현재 매핑된 음식점을 확인하고, 즉시 재할당할 수 있습니다.
        </li>
      </ul>
      <p className="muted" style={{ marginTop: 24 }}>
        처음 사용하시나요? <Link href="/signup">회원가입</Link> 후{" "}
        <Link href="/login">로그인</Link> 하세요. 광고 업로드와 스케줄 변경에는
        로그인된 광고주 토큰이 필요합니다.
      </p>
    </section>
  );
}
