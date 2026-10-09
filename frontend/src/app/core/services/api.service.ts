import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '@env/environment';
import {
  ApiResponse, Page, Conversation, Message,
  FaqItem, CreateFaqRequest, BusinessConfig,
  BusinessHours, WhatsappSession, DailyMetrics,
  Reservation, ReservationRequest, ReservationUpdate, ReservationFilter, ReservationStats,
  SlotAvailability, ReservationReceipt, ReservationSettings, CapacityRule, ReservationBlock,
  DashboardMetrics
} from '../models/models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private base = environment.apiUrl;

  constructor(private http: HttpClient) {}

  // ── Conversations ───────────────────────────────────────────
  getConversations(page = 0, size = 20, status?: string, q?: string):
      Observable<ApiResponse<Page<Conversation>>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) params = params.set('status', status);
    if (q)      params = params.set('q', q);
    return this.http.get<ApiResponse<Page<Conversation>>>(`${this.base}/conversations`, { params });
  }

  getMessages(conversationId: string, page = 0, size = 50):
      Observable<ApiResponse<Page<Message>>> {
    return this.http.get<ApiResponse<Page<Message>>>(
      `${this.base}/conversations/${conversationId}/messages`,
      { params: new HttpParams().set('page', page).set('size', size) }
    );
  }

  takeover(conversationId: string): Observable<ApiResponse<void>> {
    return this.http.post<ApiResponse<void>>(
      `${this.base}/conversations/${conversationId}/takeover`, {}
    );
  }

  releaseConversation(conversationId: string): Observable<ApiResponse<void>> {
    return this.http.post<ApiResponse<void>>(
      `${this.base}/conversations/${conversationId}/release`, {}
    );
  }

  sendMessage(conversationId: string, text: string): Observable<ApiResponse<void>> {
    return this.http.post<ApiResponse<void>>(
      `${this.base}/messages/send`, { conversationId, text }
    );
  }

  deleteConversation(conversationId: string): Observable<ApiResponse<void>> {
    return this.http.delete<ApiResponse<void>>(
      `${this.base}/conversations/${conversationId}`
    );
  }

  // ── FAQs ────────────────────────────────────────────────────
  getFaqs(): Observable<ApiResponse<FaqItem[]>> {
    return this.http.get<ApiResponse<FaqItem[]>>(`${this.base}/faqs`);
  }

  createFaq(req: CreateFaqRequest): Observable<ApiResponse<FaqItem>> {
    return this.http.post<ApiResponse<FaqItem>>(`${this.base}/faqs`, req);
  }

  updateFaq(id: string, req: CreateFaqRequest): Observable<ApiResponse<FaqItem>> {
    return this.http.put<ApiResponse<FaqItem>>(`${this.base}/faqs/${id}`, req);
  }

  deleteFaq(id: string): Observable<ApiResponse<void>> {
    return this.http.delete<ApiResponse<void>>(`${this.base}/faqs/${id}`);
  }

  toggleFaq(id: string): Observable<ApiResponse<FaqItem>> {
    return this.http.patch<ApiResponse<FaqItem>>(`${this.base}/faqs/${id}/toggle`, {});
  }

  // ── Business Config ─────────────────────────────────────────
  getBusinessConfig(): Observable<ApiResponse<BusinessConfig>> {
    return this.http.get<ApiResponse<BusinessConfig>>(`${this.base}/business-config`);
  }

  updateBusinessConfig(config: Partial<BusinessConfig>): Observable<ApiResponse<BusinessConfig>> {
    return this.http.put<ApiResponse<BusinessConfig>>(`${this.base}/business-config`, config);
  }

  getBusinessHours(): Observable<ApiResponse<BusinessHours[]>> {
    return this.http.get<ApiResponse<BusinessHours[]>>(`${this.base}/business-hours`);
  }

  updateBusinessHours(hours: BusinessHours[]): Observable<ApiResponse<BusinessHours[]>> {
    return this.http.put<ApiResponse<BusinessHours[]>>(`${this.base}/business-hours`, hours);
  }

  // ── WhatsApp Session ────────────────────────────────────────
  getSessionStatus(): Observable<ApiResponse<WhatsappSession>> {
    return this.http.get<ApiResponse<WhatsappSession>>(`${this.base}/whatsapp/session`);
  }

  getQrCode(): Observable<ApiResponse<{ status: string; qr: string | null }>> {
    return this.http.get<ApiResponse<{ status: string; qr: string | null }>>(`${this.base}/whatsapp/qr`);
  }

  reconnectWhatsapp(): Observable<ApiResponse<void>> {
    return this.http.post<ApiResponse<void>>(`${this.base}/whatsapp/reconnect`, {});
  }

  disconnectWhatsapp(): Observable<ApiResponse<void>> {
    return this.http.post<ApiResponse<void>>(`${this.base}/whatsapp/disconnect`, {});
  }

  resetWhatsapp(): Observable<ApiResponse<void>> {
    return this.http.post<ApiResponse<void>>(`${this.base}/whatsapp/reset`, {});
  }

  // ── Metrics ─────────────────────────────────────────────────
  getMetricsSummary(): Observable<ApiResponse<DashboardMetrics>> {
    return this.http.get<ApiResponse<DashboardMetrics>>(`${this.base}/metrics/summary`);
  }

  // ── Reservations ────────────────────────────────────────────
  searchReservations(f: ReservationFilter = {}): Observable<ApiResponse<Reservation[]>> {
    let params = new HttpParams().set('size', 300);
    if (f.from) params = params.set('from', f.from);
    if (f.to)   params = params.set('to', f.to);
    if (f.type) params = params.set('type', f.type);
    if (f.q && f.q.trim()) params = params.set('q', f.q.trim());
    (f.status || []).forEach(s => params = params.append('status', s));
    return this.http.get<ApiResponse<Reservation[]>>(`${this.base}/reservations`, { params });
  }

  getReservation(id: string): Observable<ApiResponse<Reservation>> {
    return this.http.get<ApiResponse<Reservation>>(`${this.base}/reservations/${id}`);
  }

  /** Creación manual: usa el mismo motor que WhatsApp. El Idempotency-Key evita duplicados por doble clic. */
  createReservation(req: ReservationRequest, idempotencyKey?: string): Observable<ApiResponse<Reservation>> {
    const headers: Record<string, string> = idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {};
    return this.http.post<ApiResponse<Reservation>>(`${this.base}/reservations`, req, { headers });
  }

  updateReservation(id: string, req: ReservationUpdate): Observable<ApiResponse<Reservation>> {
    return this.http.patch<ApiResponse<Reservation>>(`${this.base}/reservations/${id}`, req);
  }

  confirmReservation(id: string): Observable<ApiResponse<Reservation>> {
    return this.http.post<ApiResponse<Reservation>>(`${this.base}/reservations/${id}/confirm`, {});
  }

  /** Las reservas no se borran: cancelar libera el cupo y conserva el historial. */
  cancelReservation(id: string, reason?: string): Observable<ApiResponse<Reservation>> {
    return this.http.post<ApiResponse<Reservation>>(`${this.base}/reservations/${id}/cancel`, { reason: reason || null });
  }

  rejectReceipt(id: string, note?: string): Observable<ApiResponse<Reservation>> {
    return this.http.post<ApiResponse<Reservation>>(`${this.base}/reservations/${id}/reject-receipt`, { note: note || null });
  }

  uploadReceipt(id: string, file: File): Observable<ApiResponse<Reservation>> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<ApiResponse<Reservation>>(`${this.base}/reservations/${id}/payment-receipt`, form);
  }

  getReceipts(id: string): Observable<ApiResponse<ReservationReceipt[]>> {
    return this.http.get<ApiResponse<ReservationReceipt[]>>(`${this.base}/reservations/${id}/receipts`);
  }

  /** El archivo requiere JWT, por eso se pide como blob y no como <img src>. */
  getReceiptFile(id: string, receiptId: string): Observable<Blob> {
    return this.http.get(`${this.base}/reservations/${id}/receipts/${receiptId}/file`, { responseType: 'blob' });
  }

  getReservationStats(): Observable<ApiResponse<ReservationStats>> {
    return this.http.get<ApiResponse<ReservationStats>>(`${this.base}/reservations/stats`);
  }

  getDayAvailability(date: string): Observable<ApiResponse<SlotAvailability[]>> {
    return this.http.get<ApiResponse<SlotAvailability[]>>(`${this.base}/reservations/day-availability`,
      { params: new HttpParams().set('date', date) });
  }

  getAvailableSlots(date: string, type: string, partySize: number): Observable<ApiResponse<string[]>> {
    const params = new HttpParams().set('date', date).set('type', type).set('partySize', partySize);
    return this.http.get<ApiResponse<string[]>>(`${this.base}/reservations/available-slots`, { params });
  }

  // ── Reservation settings (precios, horarios, capacidades, bloqueos) ──
  getReservationSettings(): Observable<ApiResponse<ReservationSettings>> {
    return this.http.get<ApiResponse<ReservationSettings>>(`${this.base}/reservations/configuration`);
  }

  updateReservationSettings(patch: Partial<ReservationSettings>): Observable<ApiResponse<ReservationSettings>> {
    return this.http.patch<ApiResponse<ReservationSettings>>(`${this.base}/reservations/configuration`, patch);
  }

  getCapacityRules(): Observable<ApiResponse<CapacityRule[]>> {
    return this.http.get<ApiResponse<CapacityRule[]>>(`${this.base}/reservations/configuration/capacity-rules`);
  }

  addCapacityRule(rule: { dayOfWeek: string | null; slotTime: string | null; capacity: number }):
      Observable<ApiResponse<CapacityRule>> {
    return this.http.post<ApiResponse<CapacityRule>>(`${this.base}/reservations/configuration/capacity-rules`, rule);
  }

  deleteCapacityRule(id: string): Observable<ApiResponse<void>> {
    return this.http.delete<ApiResponse<void>>(`${this.base}/reservations/configuration/capacity-rules/${id}`);
  }

  getReservationBlocks(from?: string): Observable<ApiResponse<ReservationBlock[]>> {
    const params = from ? new HttpParams().set('from', from) : new HttpParams();
    return this.http.get<ApiResponse<ReservationBlock[]>>(`${this.base}/reservations/configuration/blocks`, { params });
  }

  addReservationBlock(block: { date: string; slotTime?: string | null; reason?: string | null }):
      Observable<ApiResponse<ReservationBlock>> {
    return this.http.post<ApiResponse<ReservationBlock>>(`${this.base}/reservations/configuration/blocks`, block);
  }

  deleteReservationBlock(id: string): Observable<ApiResponse<void>> {
    return this.http.delete<ApiResponse<void>>(`${this.base}/reservations/configuration/blocks/${id}`);
  }
}
