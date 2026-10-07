'use client';

/**
 * antd 图标兼容层
 *
 * 后端 `sys_menu.icon` 等字段里存的是 antd 图标名（如 `SettingOutlined`），
 * 页面代码也大量使用这些名字。这里保持**同名导出**，内部换成 lucide-react 实现，
 * 业务页只需把 `from '@ant-design/icons'` 换成 `from '@/components/antd-compat/icons'`。
 *
 * 说明：antd 图标组件支持 `style` / `className` / `spin`（部分），
 * lucide 原生支持前两者，`spin` 在此兼容为 `animate-spin` 类。
 */

import type { ComponentType, SVGProps } from 'react';

import {
  AlertCircle,
  ArrowLeft,
  BarChart3,
  Bell,
  Calendar,
  CheckCircle2,
  CloudDownload,
  CloudUpload,
  Code,
  Copy,
  Database,
  Download,
  Eraser,
  Eye,
  File,
  FileCheck,
  FileText,
  FlaskConical,
  Folder,
  GripVertical,
  History,
  Home,
  Megaphone,
  Image as ImageGlyph,
  Inbox,
  Info,
  KeyRound,
  Lightbulb,
  Link as LinkGlyph,
  ListChecks,
  Lock,
  LogIn,
  LogOut,
  Mail,
  Menu as MenuGlyph,
  MessageSquare,
  Minus,
  Pencil,
  Plug,
  Plus,
  RefreshCw,
  RotateCcw,
  Save,
  ScanSearch,
  Search,
  Send,
  Server,
  Settings,
  ShieldCheck,
  Smartphone,
  SquarePen,
  Star,
  Trash2,
  Upload,
  UploadCloud,
  User,
  Wrench,
  X,
  XCircle,
} from 'lucide-react';

type LucideIcon = ComponentType<SVGProps<SVGSVGElement>>;

/** antd 图标常见 props（额外允许 `spin`） */
export interface AntdIconProps extends SVGProps<SVGSVGElement> {
  /** 旋转动画（antd 语义） */
  spin?: boolean;
  /** 若为 true 则以两个图标叠加渲染（antd `twoTone` 用），此处忽略 */
  twoToneColor?: string;
}

/** 默认图标尺寸对齐 antd（1em），并支持 spin */
function wrap(Icon: LucideIcon, displayName: string) {
  const Wrapped = ({ spin, className, ...rest }: AntdIconProps) => (
    <Icon className={spin ? `animate-spin ${className ?? ''}` : className} {...rest} />
  );
  Wrapped.displayName = displayName;
  return Wrapped;
}

export const ApiOutlined = wrap(Plug, 'ApiOutlined');
export const BackwardOutlined = wrap(ArrowLeft, 'BackwardOutlined');
export const BarChartOutlined = wrap(BarChart3, 'BarChartOutlined');
export const BellOutlined = wrap(Bell, 'BellOutlined');
export const BulbOutlined = wrap(Lightbulb, 'BulbOutlined');
export const CalendarOutlined = wrap(Calendar, 'CalendarOutlined');
export const CheckCircleOutlined = wrap(CheckCircle2, 'CheckCircleOutlined');
export const CheckOutlined = wrap(CheckCircle2, 'CheckOutlined');
export const ClearOutlined = wrap(Eraser, 'ClearOutlined');
export const CloseCircleOutlined = wrap(XCircle, 'CloseCircleOutlined');
export const CloseOutlined = wrap(X, 'CloseOutlined');
export const CloudDownloadOutlined = wrap(CloudDownload, 'CloudDownloadOutlined');
export const CloudUploadOutlined = wrap(CloudUpload, 'CloudUploadOutlined');
export const CodeOutlined = wrap(Code, 'CodeOutlined');
export const CopyOutlined = wrap(Copy, 'CopyOutlined');
export const DatabaseOutlined = wrap(Database, 'DatabaseOutlined');
export const DeleteOutlined = wrap(Trash2, 'DeleteOutlined');
export const DownloadOutlined = wrap(Download, 'DownloadOutlined');
export const EditOutlined = wrap(SquarePen, 'EditOutlined');
export const ExclamationCircleOutlined = wrap(AlertCircle, 'ExclamationCircleOutlined');
export const ExperimentOutlined = wrap(FlaskConical, 'ExperimentOutlined');
export const EyeOutlined = wrap(Eye, 'EyeOutlined');
export const FileOutlined = wrap(File, 'FileOutlined');
export const FileDoneOutlined = wrap(FileCheck, 'FileDoneOutlined');
export const FileTextOutlined = wrap(FileText, 'FileTextOutlined');
export const FolderOutlined = wrap(Folder, 'FolderOutlined');
export const HistoryOutlined = wrap(History, 'HistoryOutlined');
export const HolderOutlined = wrap(GripVertical, 'HolderOutlined');
export const HomeOutlined = wrap(Home, 'HomeOutlined');
export const InboxOutlined = wrap(Inbox, 'InboxOutlined');
export const InfoCircleOutlined = wrap(Info, 'InfoCircleOutlined');
export const KeyOutlined = wrap(KeyRound, 'KeyOutlined');
export const LinkOutlined = wrap(LinkGlyph, 'LinkOutlined');
export const ListOutlined = wrap(ListChecks, 'ListOutlined');
export const LockOutlined = wrap(Lock, 'LockOutlined');
export const LoginOutlined = wrap(LogIn, 'LoginOutlined');
export const LogoutOutlined = wrap(LogOut, 'LogoutOutlined');
export const MailOutlined = wrap(Mail, 'MailOutlined');
export const MenuOutlined = wrap(MenuGlyph, 'MenuOutlined');
export const MessageOutlined = wrap(MessageSquare, 'MessageOutlined');
export const MinusOutlined = wrap(Minus, 'MinusOutlined');
export const PencilOutlined = wrap(Pencil, 'PencilOutlined');
export const PictureOutlined = wrap(ImageGlyph, 'PictureOutlined');
export const PlusOutlined = wrap(Plus, 'PlusOutlined');
export const ReloadOutlined = wrap(RefreshCw, 'ReloadOutlined');
export const RollbackOutlined = wrap(RotateCcw, 'RollbackOutlined');
export const SafetyOutlined = wrap(ShieldCheck, 'SafetyOutlined');
export const SaveOutlined = wrap(Save, 'SaveOutlined');
export const SearchOutlined = wrap(Search, 'SearchOutlined');
export const SecurityScanOutlined = wrap(ScanSearch, 'SecurityScanOutlined');
export const SendOutlined = wrap(Send, 'SendOutlined');
export const SettingOutlined = wrap(Settings, 'SettingOutlined');
export const NotificationOutlined = wrap(Megaphone, 'NotificationOutlined');
export const MobileOutlined = wrap(Smartphone, 'MobileOutlined');
export const StarOutlined = wrap(Star, 'StarOutlined');
export const ToolOutlined = wrap(Wrench, 'ToolOutlined');
export const UploadOutlined = wrap(Upload, 'UploadOutlined');
export const ExportOutlined = wrap(UploadCloud, 'ExportOutlined');
export const ImportOutlined = wrap(Download, 'ImportOutlined');
export const UserOutlined = wrap(User, 'UserOutlined');
export const CloudServerOutlined = wrap(Server, 'CloudServerOutlined');

/**
 * 后端菜单表 `icon` 字段（存 antd 名字符串）→ 图标组件
 *
 * 用于侧边栏渲染，避免后端数据改动。
 */
export const ANTD_ICON_MAP: Record<string, LucideIcon> = {
  ApiOutlined,
  BackwardOutlined,
  BarChartOutlined,
  BellOutlined,
  BulbOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  ClearOutlined,
  CloseCircleOutlined,
  CloudDownloadOutlined,
  CloudUploadOutlined,
  CloudServerOutlined,
  CodeOutlined,
  CopyOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  ExperimentOutlined,
  EyeOutlined,
  FileDoneOutlined,
  FileOutlined,
  FileTextOutlined,
  FolderOutlined,
  HistoryOutlined,
  HolderOutlined,
  HomeOutlined,
  InboxOutlined,
  InfoCircleOutlined,
  KeyOutlined,
  LinkOutlined,
  ListOutlined,
  LockOutlined,
  LoginOutlined,
  LogoutOutlined,
  MailOutlined,
  MenuOutlined,
  MessageOutlined,
  MobileOutlined,
  MinusOutlined,
  PencilOutlined,
  PictureOutlined,
  PlusOutlined,
  ReloadOutlined,
  RollbackOutlined,
  SafetyOutlined,
  SaveOutlined,
  SearchOutlined,
  SecurityScanOutlined,
  SendOutlined,
  SettingOutlined,
  StarOutlined,
  ToolOutlined,
  ExportOutlined,
  ImportOutlined,
  NotificationOutlined,
  UploadOutlined,
  UserOutlined,
};
