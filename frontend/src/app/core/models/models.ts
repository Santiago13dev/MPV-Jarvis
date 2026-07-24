// ============================================================
// Modelos de dominio — TypeScript interfaces
// ============================================================

export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// ── Auth ─────────────────────────────────────────────────────
export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  email: string;
  fullName: string;
  role: string;
}

export interface AuthUser {
  email: string;
  fullName: string;
  role: string;
}

// ── WhatsApp Session ─────────────────────────────────────────
export type SessionStatus =
  'DISCONNECTED' | 'CONNECTING' | 'QR_READY' | 'CONNECTED' | 'ERROR';

export interface WhatsappSession {
  id: string;
  sessionName: string;
  status: SessionStatus;
  phoneNumber?: string;
  qrCode?: string;
  connectedAt?: string;
  lastHeartbeat?: string;
  errorMessage?: string;
}

// ── Contact ──────────────────────────────────────────────────
export interface Contact {
  id: string;
  phone: string;
  displayName?: string;
  isBlocked: boolean;
  tags?: string[];
  notes?: string;
  firstSeen: string;
  lastSeen: string;
}

// ── Conversation ─────────────────────────────────────────────
export type ConversationStatus = 'AUTO' | 'HUMAN_TAKEOVER' | 'WAITING' | 'CLOSED';

export interface Conversation {
  id: string;
  contact: Contact;
  status: ConversationStatus;
  lastMessageAt?: string;
  unreadCount: number;
  assignedTo?: { id: string; fullName: string };
  createdAt: string;
  updatedAt: string;
}

// ── Message ──────────────────────────────────────────────────
export type MessageDirection = 'INBOUND' | 'OUTBOUND';
export type MessageType = 'TEXT' | 'IMAGE' | 'VIDEO' | 'AUDIO' | 'DOCUMENT' | 'LOCATION' | 'STICKER';
export type ProcessedBy = 'KEYWORD' | 'FAQ' | 'AI' | 'HUMAN' | 'SYSTEM';

export interface Message {
  id: string;
  conversationId: string;
  waMessageId?: string;
  direction: MessageDirection;
  content?: string;
  messageType: MessageType;
  status: string;
  processedBy?: ProcessedBy;
  aiTokensUsed?: number;
  sentAt: string;
  deliveredAt?: string;
  readAt?: string;
}

// ── FAQ ──────────────────────────────────────────────────────
export interface FaqItem {
  id: string;
  question: string;
  answer: string;
  keywords?: string[];
  isActive: boolean;
  priority: number;
  matchCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateFaqRequest {
  question: string;
  answer: string;
  keywords: string[];
  priority: number;
}

// ── Keyword Rule ─────────────────────────────────────────────
export interface KeywordRule {
  id: string;
  name: string;
  keywords: string[];
  matchMode: 'ANY' | 'ALL' | 'EXACT';
  response: string;
  isActive: boolean;
  priority: number;
  createdAt: string;
}

// ── Business Config ──────────────────────────────────────────
export interface BusinessConfig {
  id: string;
  businessName: string;
  businessType?: string;
  phoneNumber?: string;
  adminPhone?: string;
  welcomeMessage?: string;
  offHoursMessage?: string;
  humanDelaySeconds: number;
  aiEnabled: boolean;
  aiModel: string;
  maxAiTokens: number;
}

// ── Business Hours ───────────────────────────────────────────
export interface BusinessHours {
  id: string;
  dayOfWeek: number; // 0=Dom, 1=Lun...6=Sab
  openTime: string;  // "08:00"
  closeTime: string; // "18:00"
  isActive: boolean;
}

// ── Metrics ──────────────────────────────────────────────────
export interface DailyMetrics {
  id: string;
  metricDate: string;
  totalMessagesIn: number;
  totalMessagesOut: number;
  totalAiCalls: number;
  totalAiTokens: number;
  totalFaqMatches: number;
  totalKeywordMatches: number;
  totalHumanTakeovers: number;
  newContacts: number;
  activeConversations: number;
}

// ── WebSocket Events ─────────────────────────────────────────
export interface WsMessageEvent {
  type: 'NEW_MESSAGE';
  messageId: string;
  conversationId: string;
  phone: string;
  displayName: string;
  content: string;
  direction: MessageDirection;
  processedBy: ProcessedBy;
  sentAt: string;
}

export interface WsConversationEvent {
  type: 'CONVERSATION_UPDATE';
  conversationId: string;
  status: ConversationStatus;
  unreadCount: number;
  lastMessageAt: string;
}

export interface WsSessionEvent {
  status: SessionStatus;
  phoneNumber?: string;
  qrCode?: string;
  error?: string;
}

// ── Reservation ──────────────────────────────────────────────
export type ReservationStatus = 'PENDIENTE' | 'CONFIRMADA' | 'CANCELADA';

export interface Reservation {
  id: string;
  customerName: string;
  phoneNumber: string;
  reservationDate: string;
  amount: number;
  status: ReservationStatus;
  notes?: string;
  createdAt: string;
}

export interface ReservationRequest {
  customerName: string;
  phoneNumber: string;
  reservationDate: string;
  amount: number;
  status?: ReservationStatus;
  notes?: string;
}

export interface DashboardMetrics {
  metrics: DailyMetrics;
  totalReservations: number;
  pendingReservations: number;
  confirmedReservations: number;
}
