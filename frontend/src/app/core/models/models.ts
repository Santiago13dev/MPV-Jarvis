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
export type ReservationStatus =
  'PENDING' | 'PAYMENT_PENDING' | 'PAYMENT_RECEIPT_SENT' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED';

export type ReservationType = 'NORMAL' | 'VIP';

export interface Reservation {
  id: string;
  reservationNumber: number;
  customerName: string;
  phoneNumber: string;
  receiptPhone?: string | null;
  reservationType: ReservationType;
  date: string;          // yyyy-MM-dd
  time: string;          // HH:mm:ss
  partySize: number;
  unitPrice: number;
  totalPrice: number;    // lo calcula el backend
  status: ReservationStatus;
  notes?: string | null;
  holdExpiresAt?: string | null;
  confirmedAt?: string | null;
  cancelledAt?: string | null;
  cancelReason?: string | null;
  hasReceipt: boolean;
  paymentReceiptUploadedAt?: string | null;
  createdAt: string;
}

/** Creación manual (dashboard). Pasa por el mismo ReservationService que WhatsApp. */
export interface ReservationRequest {
  customerName: string;
  phoneNumber: string;
  receiptPhone?: string;
  reservationType?: ReservationType;
  date: string;
  time: string;
  partySize: number;
  notes?: string;
  status?: ReservationStatus;   // PAYMENT_PENDING (default) | PENDING | CONFIRMED
}

/** null/undefined = sin cambio. */
export interface ReservationUpdate {
  date?: string;
  time?: string;
  partySize?: number;
  reservationType?: ReservationType;
}

export interface ReservationFilter {
  from?: string;
  to?: string;
  status?: ReservationStatus[];
  type?: ReservationType | '';
  q?: string;
}

export interface ReservationStats {
  date: string;
  reservationsToday: number;
  peopleToday: number;
  vipToday: number;
  confirmedRevenueFromToday: number;
  pendingPayments: number;
  remainingCapacityToday: number;
}

export interface CapacityPool {
  capacity: number;
  reserved: number;
  available: number;
}

export interface SlotAvailability {
  time: string;
  blocked: boolean;
  passed: boolean;
  normal: CapacityPool;
  vip: CapacityPool;
}

export type ReceiptStatus = 'RECEIVED' | 'ACCEPTED' | 'REJECTED';

export interface ReservationReceipt {
  id: string;
  mimeType: string;
  status: ReceiptStatus;
  uploadedAt: string;
  reviewedBy?: string | null;
  reviewedAt?: string | null;
  reviewNote?: string | null;
}

export interface ReservationSettings {
  normalPrice: number;
  vipPrice: number;
  vipCapacity: number;
  operatingDays: string[];            // 'SATURDAY', 'SUNDAY'...
  reservationStartTime: string;       // HH:mm:ss
  reservationEndTime: string;
  slotIntervalMinutes: number;
  defaultSlotCapacity: number;
  maxPartySize: number;
  holdMinutes: number;
  vipConsumesNormalCapacity: boolean;
  paymentInstructions?: string | null;
}

export interface CapacityRule {
  id: string;
  dayOfWeek?: string | null;          // null = todos los días
  slotTime?: string | null;           // null = todas las franjas
  capacity: number;
}

export interface ReservationBlock {
  id: string;
  date: string;
  slotTime?: string | null;           // null = día completo
  reason?: string | null;
}

export interface DashboardMetrics {
  metrics: DailyMetrics;
  totalReservations: number;
  pendingReservations: number;
  confirmedReservations: number;
}
