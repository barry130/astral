'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { ArrowLeft, Files, Loader2 } from 'lucide-react';
import { useAuth } from '@/context/AuthContext';
import FileManager from '@/components/storage/FileManager';
import { Button } from '@/components/ui/button';

export default function FilesPage() {
  const { isLogin, loading } = useAuth();
  const router = useRouter();

  useEffect(() => { if (!loading && !isLogin) router.replace('/login'); }, [loading, isLogin, router]);

  if (loading || !isLogin) return <main className="flex min-h-dvh items-center justify-center bg-background"><Loader2 className="size-6 animate-spin text-muted-foreground" aria-label="正在加载" /></main>;

  return (
    <main className="min-h-dvh bg-background px-3 py-6 text-foreground sm:px-6 lg:px-10">
      <div className="mx-auto max-w-[1600px]">
        <header className="mb-6 flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3"><div className="rounded-xl border border-border bg-card p-3"><Files className="size-6 text-primary" /></div><div><h1 className="text-xl font-semibold tracking-tight sm:text-2xl">我的文件</h1><p className="mt-1 text-sm text-muted-foreground">整理文件夹、上传文件，管理访问与分享。</p></div></div>
          <Button variant="outline" asChild><Link href="/"><ArrowLeft className="size-4" />返回首页</Link></Button>
        </header>
        <FileManager />
      </div>
    </main>
  );
}
