import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { DashboardLayoutComponent } from './layout/dashboard-layout/dashboard-layout.component';
import { LoginComponent } from './features/auth/login.component';
import { DashboardHomeComponent } from './features/dashboard/dashboard-home.component';
import { WhatsappStatusComponent } from './features/whatsapp/whatsapp-status.component';
import { ConversationsComponent } from './features/conversations/conversations.component';
import { ConversationDetailComponent } from './features/conversations/conversation-detail.component';
import { FaqsComponent } from './features/faqs/faqs.component';
import { BusinessConfigComponent } from './features/config/business-config.component';
import { ReservationsComponent } from './features/reservations/reservations.component';

const routes: Routes = [
  { path: 'auth/login', component: LoginComponent },
  {
    path: '',
    component: DashboardLayoutComponent,
    canActivate: [authGuard],
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      { path: 'dashboard', component: DashboardHomeComponent },
      { path: 'whatsapp', component: WhatsappStatusComponent },
      { path: 'conversations', component: ConversationsComponent },
      { path: 'conversations/:id', component: ConversationDetailComponent },
      { path: 'reservations', component: ReservationsComponent },
      { path: 'faqs', component: FaqsComponent },
      { path: 'config', component: BusinessConfigComponent },
    ]
  },
  { path: '**', redirectTo: 'dashboard' }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule {}
