import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { HTTP_INTERCEPTORS, HttpClientModule } from '@angular/common/http';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';

import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { JwtInterceptor } from './core/auth/jwt.interceptor';

// Layout
import { DashboardLayoutComponent } from './layout/dashboard-layout/dashboard-layout.component';
import { SidebarComponent } from './layout/sidebar/sidebar.component';
import { TopbarComponent } from './layout/topbar/topbar.component';

// Features
import { LoginComponent } from './features/auth/login.component';
import { DashboardHomeComponent } from './features/dashboard/dashboard-home.component';
import { WhatsappStatusComponent } from './features/whatsapp/whatsapp-status.component';
import { ConversationsComponent } from './features/conversations/conversations.component';
import { ConversationDetailComponent } from './features/conversations/conversation-detail.component';
import { FaqsComponent } from './features/faqs/faqs.component';
import { BusinessConfigComponent } from './features/config/business-config.component';
import { ReservationsComponent } from './features/reservations/reservations.component';

@NgModule({
  declarations: [
    AppComponent,
    DashboardLayoutComponent,
    SidebarComponent,
    TopbarComponent,
    LoginComponent,
    DashboardHomeComponent,
    WhatsappStatusComponent,
    ConversationsComponent,
    ConversationDetailComponent,
    FaqsComponent,
    BusinessConfigComponent,
    ReservationsComponent,
  ],
  imports: [
    BrowserModule,
    BrowserAnimationsModule,
    HttpClientModule,
    FormsModule,
    ReactiveFormsModule,
    AppRoutingModule,
  ],
  providers: [
    { provide: HTTP_INTERCEPTORS, useClass: JwtInterceptor, multi: true },
  ],
  bootstrap: [AppComponent],
})
export class AppModule {}
