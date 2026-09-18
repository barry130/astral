'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, Empty, List, Modal, Popover, Tag } from 'antd';
import { BellOutlined, CheckOutlined } from '@ant-design/icons';
import { request } from '@/api/client';
import type { SysNotice } from '@/api/feedback';
import { noticeTypeLabel } from '@/api/feedback';

/**
 * 顶栏消息提示铃铛
 * <p>数据源为统一通知（sys_notice）的 App 端消息中心接口（channel=pc，管理端会话同样放行），
 * 复用既有「已读由前端缓存判断」的机制：已读 id 记录在 localStorage，后端无已读状态。</p>
 * <p>反馈插件禁用或接口异常时静默降级为无角标，不影响控制台使用。</p>
 */

/** 已读记录的本地缓存键（PC 管理端收件箱） */
const READ_KEY = 'astral:notice-read:admin';
/** 本地最多保留的已读 id 数，防止 localStorage 无限增长 */
const READ_LIMIT = 500;

function loadReadIds(): Set<number> {
  try {
    const raw = localStorage.getItem(READ_KEY);
    if (!raw) return new Set();
    const arr = JSON.parse(raw);
    return new Set(Array.isArray(arr) ? arr.filter((v) => typeof v === 'number') : []);
  } catch {
    return new Set();
  }
}

function saveReadIds(set: Set<number>) {
  try {
    // 只保留最近 READ_LIMIT 个，避免长期膨胀
    const arr = Array.from(set).slice(-READ_LIMIT);
    localStorage.setItem(READ_KEY, JSON.stringify(arr));
  } catch {
    // localStorage 不可用（隐私模式等）：本次会话内仍生效，不持久化
  }
}

/** 通知类型 → 标签颜色（纯展示配色） */
const TYPE_COLOR: Record<string, string> = {
  announce: 'blue',
  feedback: 'orange',
  request: 'green',
};

function fmtTime(v?: string): string {
  if (!v) return '';
  return v.slice(0, 16).replace('T', ' ');
}

export default function NoticeBell({ buttonStyle }: { buttonStyle?: React.CSSProperties }) {
  const [notices, setNotices] = useState<SysNotice[]>([]);
  const [readIds, setReadIds] = useState<Set<number>>(() => loadReadIds());
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState<SysNotice | null>(null);

  const load = useCallback(() => {
    // 管理端收件箱：不按 channel 过滤，广播 + 发给当前管理员的点对点全可见
    request.get('/api/v1/admin/message/inbox')
      .then((res: any) => setNotices(res.data || []))
      .catch(() => {
        // 反馈插件禁用 / 网络异常：角标归零，不弹错误打扰
        setNotices([]);
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const unread = useMemo(
    () => notices.filter((n) => n.id != null && !readIds.has(n.id!)),
    [notices, readIds],
  );

  const markRead = (n: SysNotice) => {
    if (n.id == null || readIds.has(n.id)) return;
    const next = new Set(readIds);
    next.add(n.id);
    setReadIds(next);
    saveReadIds(next);
  };

  const markAllRead = () => {
    const next = new Set(readIds);
    notices.forEach((n) => {
      if (n.id != null) next.add(n.id);
    });
    setReadIds(next);
    saveReadIds(next);
  };

  const content = (
    <div style={{ width: 340, maxHeight: 420, overflowY: 'auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '4px 4px 8px' }}>
        <span style={{ fontWeight: 600 }}>消息通知（{unread.length} 未读）</span>
        {unread.length > 0 && (
          <Button type="link" size="small" icon={<CheckOutlined />} onClick={markAllRead}>
            全部已读
          </Button>
        )}
      </div>
      {notices.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无通知" style={{ padding: '12px 0' }} />
      ) : (
        <List
          dataSource={notices}
          rowKey={(n) => String(n.id)}
          renderItem={(n) => {
            const isUnread = n.id != null && !readIds.has(n.id);
            return (
              <List.Item
                style={{ cursor: 'pointer', padding: '8px 4px' }}
                onClick={() => {
                  markRead(n);
                  setDetail(n);
                }}
              >
                <List.Item.Meta
                  title={
                    <span style={{ fontWeight: isUnread ? 600 : 400 }}>
                      {n.isTop === 1 && <Tag color="red" style={{ marginRight: 4 }}>置顶</Tag>}
                      {isUnread && <Badge status="processing" style={{ marginRight: 4 }} />}
                      {n.title}
                    </span>
                  }
                  description={
                    <span style={{ fontSize: 12, color: '#999' }}>
                      <Tag color={TYPE_COLOR[n.noticeType || ''] || 'default'} style={{ marginRight: 6 }}>
                        {noticeTypeLabel(n.noticeType)}
                      </Tag>
                      {fmtTime(n.createTime)}
                    </span>
                  }
                />
              </List.Item>
            );
          }}
        />
      )}
    </div>
  );

  return (
    <>
      <Popover
        content={content}
        trigger="click"
        placement="bottomRight"
        open={open}
        onOpenChange={(v) => {
          setOpen(v);
          if (v) load(); // 展开时刷新一次，角标跟进最新通知
        }}
      >
        <Button
          type="text"
          icon={
            <Badge count={unread.length} size="small" offset={[-2, 2]}>
              <BellOutlined />
            </Badge>
          }
          style={buttonStyle}
          title="消息通知"
          aria-label="消息通知"
        />
      </Popover>
      <Modal
        open={detail != null}
        title={detail?.title}
        footer={null}
        onCancel={() => setDetail(null)}
        width={520}
      >
        <div style={{ marginBottom: 8, fontSize: 12, color: '#999' }}>
          <Tag color={TYPE_COLOR[detail?.noticeType || ''] || 'default'}>
            {noticeTypeLabel(detail?.noticeType)}
          </Tag>
          {fmtTime(detail?.createTime)}
        </div>
        <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>{detail?.content}</div>
        {detail?.url && (
          <div style={{ marginTop: 12 }}>
            <a href={detail.url} target="_blank" rel="noreferrer">{detail.url}</a>
          </div>
        )}
      </Modal>
    </>
  );
}
