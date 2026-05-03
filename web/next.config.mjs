/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Docker 운영용 — `output: 'standalone'` 을 켜면 next build 결과에 필요한
  // node_modules 만 추려 `.next/standalone/` 트리를 만들어 준다. Dockerfile
  // runtime 스테이지가 이 디렉토리만 복사하므로, 600MB 가 넘는 dev
  // dependencies 를 이미지에 끌고 가지 않아도 된다.
  //
  // `next start` 로 띄우는 호스트 / systemd 배포에서도 무해 — `.next/standalone`
  // 이 추가로 만들어질 뿐 기존 동작에 영향 없음. nginx 가 앞단에 있는 케이스도
  // 그대로 작동한다.
  output: "standalone",
};

export default nextConfig;
