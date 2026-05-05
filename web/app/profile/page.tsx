import { AuthGuard } from "@/components/AuthGuard";
import { ProfileClient } from "@/components/ProfileClient";

export const dynamic = "force-dynamic";

export const metadata = {
  title: "프로필 · AdSignage 어드민",
};

export default function ProfilePage() {
  return (
    <AuthGuard>
      <section className="mx-auto w-full max-w-narrow space-y-6">
        <header>
          <h1 className="text-2xl font-semibold tracking-tight">프로필</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            계정 정보와 알림 설정을 관리합니다.
          </p>
        </header>

        <ProfileClient />
      </section>
    </AuthGuard>
  );
}
