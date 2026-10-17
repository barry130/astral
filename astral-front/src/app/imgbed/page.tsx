import { redirect } from 'next/navigation';

/** 保留旧图床地址，让已有收藏继续进入文件管理。 */
export default function ImageBedPage() {
  redirect('/files');
}
