import { Injectable } from '@angular/core';
import { RxStomp } from '@stomp/rx-stomp';
import { Observable, EMPTY } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { environment } from '@env/environment';
import { WsConversationEvent, WsMessageEvent, WsSessionEvent } from '../models/models';

@Injectable({ providedIn: 'root' })
export class WebSocketService {
  private rxStomp = new RxStomp();
  private connected = false;

  connect(token: string): void {
    if (this.connected) return;

    const wsBase = environment.wsUrl.replace(/^http/, 'ws');

    this.rxStomp.configure({
      brokerURL: wsBase,
      connectHeaders: { Authorization: `Bearer ${token}` },
      heartbeatIncoming: 0,
      heartbeatOutgoing: 20000,
      reconnectDelay: 5000,
    });

    this.rxStomp.activate();
    this.connected = true;
  }

  disconnect(): void {
    this.rxStomp.deactivate();
    this.connected = false;
  }

  /** Nuevos mensajes entrantes/salientes */
  onNewMessage(): Observable<WsMessageEvent> {
    return new Observable(observer => {
      const sub = this.rxStomp.watch('/topic/messages').pipe(
        catchError(() => EMPTY)
      ).subscribe(msg => {
        observer.next(JSON.parse(msg.body) as WsMessageEvent);
      });
      return () => sub.unsubscribe();
    });
  }

  /** Cambios en conversaciones */
  onConversationUpdate(): Observable<WsConversationEvent> {
    return new Observable(observer => {
      const sub = this.rxStomp.watch('/topic/conversations').pipe(
        catchError(() => EMPTY)
      ).subscribe(msg => {
        observer.next(JSON.parse(msg.body) as WsConversationEvent);
      });
      return () => sub.unsubscribe();
    });
  }

  /** Cambios de estado de sesión WhatsApp */
  onSessionStatus(): Observable<WsSessionEvent> {
    return new Observable(observer => {
      const sub = this.rxStomp.watch('/topic/session').pipe(
        catchError(() => EMPTY)
      ).subscribe(msg => {
        observer.next(JSON.parse(msg.body) as WsSessionEvent);
      });
      return () => sub.unsubscribe();
    });
  }
}
