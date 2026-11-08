import dayjs, { type Dayjs } from 'dayjs';
import { type NoticeWrite, type SysNotice } from '@/api/notice';
import { statisticsTimeRange } from '@/api/message-statistics';
import { pickWriteFields } from '@/api/write-fields';
const NOTICE_EDITOR_SCALARS = [
  'noticeType',
  'userId',
  'title',
  'content',
  'url',
  'isShow',
  'isTop',
  'dialogClosable',
  'firstLoginOnly',
  'marquee',
  'readSeconds',
  'termsVersion',
  'versionMin',
  'versionMax',
  'audience',
] as const;
import { sanitizeRichText } from '@/components/rich-text';

export const DEFAULT_NOTICE_CHANNELS = ['app-android', 'app-ios'] as const;
export const DISCLAIMER_NOTICE_CHANNELS = [
  'app-android',
  'app-ios',
  'app-windows',
  'app-linux',
  'app-macos',
  'app-harmony',
] as const;
export type NoticeEditorValues = Omit<
  NoticeWrite,
  'channel' | 'display' | 'effectiveStart' | 'effectiveEnd'
> & {
  channel?: string[];
  display?: number[];
  effectiveRange?: [Dayjs | null, Dayjs | null];
  firstLaunchPopup?: number;
};

/** Legacy aliases expand item-by-item; they never replace another selected platform. */
export function parseNoticeChannels(channel?: string): string[] {
  const values = (channel || '')
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean);
  if (!values.length) return [...DEFAULT_NOTICE_CHANNELS];
  if (values.includes('all')) return ['all'];
  return [
    ...new Set(
      values.flatMap((value) =>
        value === 'app' ? [...DEFAULT_NOTICE_CHANNELS] : [value === 'pc' ? 'app-windows' : value],
      ),
    ),
  ];
}
export function joinNoticeChannels(selected: ReadonlyArray<string>): string {
  if (selected.some((value) => typeof value !== 'string')) throw new Error('投放平台字段无效');
  const values = [...new Set(selected.map((value) => value.trim()).filter(Boolean))];
  return values.includes('all') ? 'all' : values.join(',');
}
export function toggleNoticeChannels(next: string[], previous: ReadonlyArray<string>): string[] {
  if (next.includes('all') && !previous.includes('all')) return ['all'];
  return previous.includes('all') && next.length > 1
    ? next.filter((value) => value !== 'all')
    : next;
}
/** Arithmetic avoids truncating future bits to signed 32-bit integers. */
export function splitNoticeDisplay(value = 4): number[] {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error('展示位置无效');
  if (value === 0) return [0];
  const result: number[] = [];
  let remaining = value,
    bit = 1;
  while (remaining > 0) {
    if (remaining % 2 === 1) result.push(bit);
    remaining = Math.floor(remaining / 2);
    bit *= 2;
  }
  return result;
}
function displayMask(values: ReadonlyArray<number>): number {
  if (
    values.some(
      (value) =>
        !Number.isSafeInteger(value) || value < 0 || splitNoticeDisplay(value).length !== 1,
    )
  )
    throw new Error('展示位置无效');
  const result = [...new Set(values)].reduce((sum, value) => sum + value, 0);
  if (!Number.isSafeInteger(result)) throw new Error('展示位置无效');
  return result;
}
export function noticeEditorValues(record: SysNotice): NoticeEditorValues {
  const range = statisticsTimeRange(
    record.effectiveStart || undefined,
    record.effectiveEnd || undefined,
  );
  return {
    ...pickWriteFields(record, NOTICE_EDITOR_SCALARS),
    channel: parseNoticeChannels(record.channel),
    display: splitNoticeDisplay(record.display),
    noticeType:
      record.noticeType === 'feedback' || record.noticeType === 'request'
        ? 'notice'
        : (record.noticeType ?? 'announce'),
    effectiveRange:
      range.start || range.end
        ? [range.start ? dayjs(range.start) : null, range.end ? dayjs(range.end) : null]
        : undefined,
    firstLaunchPopup: record.termsVersion?.trim() ? 1 : 0,
    isShow: record.isShow ?? 1,
    isTop: record.isTop ?? 0,
    marquee: record.marquee ?? 0,
    firstLoginOnly: record.firstLoginOnly ?? 0,
    dialogClosable: record.dialogClosable ?? 1,
    audience: record.audience ?? 'ALL',
  };
}

/** Complete form save: readonly entity fields never enter the request, and clearing is explicit. */
export function noticeEditorPayload(
  values: NoticeEditorValues,
  context: { feedbackReply: boolean; defaultBatch: string },
): NoticeWrite {
  if (typeof values.title !== 'string' || !values.title.trim()) throw new Error('请输入标题');
  if (!Array.isArray(values.channel) || !Array.isArray(values.display) || !values.display.length)
    throw new Error('请选择投放平台与展示位置');
  const payload: NoticeWrite = pickWriteFields(values, NOTICE_EDITOR_SCALARS);
  if (
    values.firstLaunchPopup !== undefined &&
    values.firstLaunchPopup !== 0 &&
    values.firstLaunchPopup !== 1
  )
    throw new Error('首次启动开关值无效');
  let display = displayMask(values.display);
  const firstLaunch = values.firstLaunchPopup === 1;
  let channel = joinNoticeChannels(values.channel);
  if (firstLaunch) {
    if (display % 2 === 0) display += 1;
    channel = DISCLAIMER_NOTICE_CHANNELS.join(',');
    const batch = (values.termsVersion ?? '').trim() || context.defaultBatch;
    if (!batch || batch.length > 32) throw new Error('弹出批次须为 1–32 个字符');
    const seconds = values.readSeconds === undefined ? 3 : values.readSeconds;
    if (seconds !== null && (!Number.isSafeInteger(seconds) || seconds < 0 || seconds > 60))
      throw new Error('确认读秒须为 0–60 的整数');
    payload.termsVersion = batch;
    payload.readSeconds = seconds;
    payload.dialogClosable = 1;
  } else {
    payload.termsVersion = null;
    payload.readSeconds = null;
  }
  if (context.feedbackReply) channel = 'all';
  if (!channel) throw new Error('请选择投放平台');
  for (const key of ['isShow', 'isTop', 'marquee', 'firstLoginOnly', 'dialogClosable'] as const) {
    if (payload[key] !== undefined && payload[key] !== 0 && payload[key] !== 1)
      throw new Error('开关值须为 0 或 1');
  }
  if (values.userId != null && (!Number.isSafeInteger(values.userId) || values.userId <= 0))
    throw new Error('目标用户ID无效');
  for (const version of [values.versionMin, values.versionMax]) {
    if (version != null && (!Number.isSafeInteger(version) || version < 0))
      throw new Error('生效版本须为非负整数');
  }
  if (
    values.versionMin != null &&
    values.versionMax != null &&
    values.versionMin > values.versionMax
  )
    throw new Error('生效版本下限不能大于上限');
  const range = values.effectiveRange;
  if (range?.some((value) => value && !value.isValid())) throw new Error('生效时间无效');
  const time = statisticsTimeRange(
    range?.[0]?.format('YYYY-MM-DD HH:mm:ss'),
    range?.[1]?.format('YYYY-MM-DD HH:mm:ss'),
  );
  payload.effectiveStart = time.start || null;
  payload.effectiveEnd = time.end || null;
  payload.userId = values.userId ?? null;
  payload.versionMin = values.versionMin ?? null;
  payload.versionMax = values.versionMax ?? null;
  payload.channel = channel;
  payload.display = display;
  payload.content = sanitizeRichText(values.content ?? '');
  return payload;
}
