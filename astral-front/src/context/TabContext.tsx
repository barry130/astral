'use client';

import { createContext, useContext, useState, ReactNode } from 'react';

/** 标签页项接口 */
interface TabItem {
  /** 标签页唯一标识（通常为路由路径） */
  key: string;
  /** 标签页显示名称 */
  label: string;
  /** 标签页图标 */
  icon: ReactNode;
}

/** 标签页上下文类型定义 */
interface TabContextType {
  /** 当前激活的标签页 */
  activeTab: string;
  /** 已打开的标签页列表 */
  openTabs: TabItem[];
  /** 设置当前激活标签页 */
  setActiveTab: (key: string) => void;
  /** 添加新标签页 */
  addTab: (tab: TabItem) => void;
  /** 移除指定标签页 */
  removeTab: (key: string) => void;
}

/** 创建标签页上下文 */
export const TabContext = createContext<TabContextType | undefined>(undefined);

/**
 * 使用标签页上下文的Hook
 * 必须在TabProvider内部使用
 */
export function useTabs() {
  const context = useContext(TabContext);
  if (!context) {
    throw new Error('useTabs must be used within TabProvider');
  }
  return context;
}