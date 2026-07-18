import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '@env/environment';
import {
  ApiResponse, Page, Conversation, Message,
  FaqItem, CreateFaqRequest, BusinessConfig,
  BusinessHours, WhatsappSession, DailyMetrics,
  Reservation, ReservationRequest, ReservationStatus, DashboardMetrics
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

  // ── Metrics ─────────────────────────────────────────────────
  getMetricsSummary(): Observable<ApiResponse<DashboardMetrics>> {
    return this.http.get<ApiResponse<DashboardMetrics>>(`${this.base}/metrics/summary`);
  }

  // ── Reservations ────────────────────────────────────────────
  getReservations(): Observable<ApiResponse<Reservation[]>> {
    return this.http.get<ApiResponse<Reservation[]>>(`${this.base}/reservations`);
  }

  createReservation(req: ReservationRequest): Observable<ApiResponse<Reservation>> {
    return this.http.post<ApiResponse<Reservation>>(`${this.base}/reservations`, req);
  }

  updateReservationStatus(id: string, status: ReservationStatus): Observable<ApiResponse<Reservation>> {
    let params = new HttpParams().set('status', status);
    return this.http.patch<ApiResponse<Reservation>>(`${this.base}/reservations/${id}/status`, null, { params });
  }

  deleteReservation(id: string): Observable<ApiResponse<void>> {
    return this.http.delete<ApiResponse<void>>(`${this.base}/reservations/${id}`);
  }
}
