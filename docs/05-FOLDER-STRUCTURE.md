# 📁 Estructura de Carpetas Profesional

## Raíz del Proyecto

```
whatsapp-MVP/
├── docs/                          # Documentación de arquitectura
├── backend/                       # Spring Boot API
├── frontend/                      # Angular Dashboard
├── whatsapp-service/              # Node.js + OpenWA/Baileys
├── infrastructure/                # Docker, Nginx, scripts
├── docker-compose.yml             # Dev local
├── docker-compose.prod.yml        # Producción
├── .env.example                   # Variables de entorno ejemplo
└── README.md                      # Guía de inicio rápido
```

---

## Backend (Spring Boot — Clean Architecture)

```
backend/
├── src/
│   ├── main/
│   │   ├── java/com/whatsappmvp/
│   │   │   │
│   │   │   ├── config/                        # Configuraciones Spring
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   ├── WebSocketConfig.java
│   │   │   │   ├── CorsConfig.java
│   │   │   │   ├── OpenAIConfig.java
│   │   │   │   └── AppProperties.java
│   │   │   │
│   │   │   ├── domain/                        # CAPA DE DOMINIO (sin dependencias)
│   │   │   │   ├── model/                     # Entidades de dominio puras
│   │   │   │   │   ├── Contact.java
│   │   │   │   │   ├── Conversation.java
│   │   │   │   │   ├── Message.java
│   │   │   │   │   ├── FaqItem.java
│   │   │   │   │   ├── KeywordRule.java
│   │   │   │   │   └── BusinessConfig.java
│   │   │   │   ├── enums/
│   │   │   │   │   ├── ConversationStatus.java
│   │   │   │   │   ├── MessageDirection.java
│   │   │   │   │   ├── MessageType.java
│   │   │   │   │   └── ProcessedBy.java
│   │   │   │   └── exception/
│   │   │   │       ├── BusinessException.java
│   │   │   │       ├── NotFoundException.java
│   │   │   │       └── ValidationException.java
│   │   │   │
│   │   │   ├── application/                   # CAPA DE APLICACIÓN (use cases)
│   │   │   │   ├── port/
│   │   │   │   │   ├── in/                    # Interfaces de entrada (use cases)
│   │   │   │   │   │   ├── ProcessMessageUseCase.java
│   │   │   │   │   │   ├── SendMessageUseCase.java
│   │   │   │   │   │   ├── ManageFaqUseCase.java
│   │   │   │   │   │   └── GetMetricsUseCase.java
│   │   │   │   │   └── out/                   # Interfaces de salida (repos/clients)
│   │   │   │   │       ├── MessageRepository.java
│   │   │   │   │       ├── ConversationRepository.java
│   │   │   │   │       ├── WhatsAppClient.java
│   │   │   │   │       └── AIClient.java
│   │   │   │   └── service/                   # Implementaciones de use cases
│   │   │   │       ├── MessageProcessingService.java
│   │   │   │       ├── ConversationService.java
│   │   │   │       ├── HybridEngineService.java
│   │   │   │       ├── KeywordMatchingService.java
│   │   │   │       ├── FaqMatchingService.java
│   │   │   │       ├── BusinessHoursService.java
│   │   │   │       ├── RateLimitService.java
│   │   │   │       ├── MetricsService.java
│   │   │   │       └── ResponseBuilderService.java
│   │   │   │
│   │   │   ├── infrastructure/                # CAPA DE INFRAESTRUCTURA
│   │   │   │   ├── persistence/               # JPA Entities + Repositories
│   │   │   │   │   ├── entity/
│   │   │   │   │   │   ├── ContactEntity.java
│   │   │   │   │   │   ├── ConversationEntity.java
│   │   │   │   │   │   ├── MessageEntity.java
│   │   │   │   │   │   ├── FaqItemEntity.java
│   │   │   │   │   │   ├── KeywordRuleEntity.java
│   │   │   │   │   │   ├── BusinessConfigEntity.java
│   │   │   │   │   │   ├── BotPromptEntity.java
│   │   │   │   │   │   ├── BusinessHoursEntity.java
│   │   │   │   │   │   └── WhatsappSessionEntity.java
│   │   │   │   │   ├── jpa/                   # Spring Data JPA interfaces
│   │   │   │   │   │   ├── ContactJpaRepository.java
│   │   │   │   │   │   ├── ConversationJpaRepository.java
│   │   │   │   │   │   └── MessageJpaRepository.java
│   │   │   │   │   └── adapter/               # Adapters que implementan port/out
│   │   │   │   │       ├── ContactRepositoryAdapter.java
│   │   │   │   │       └── ConversationRepositoryAdapter.java
│   │   │   │   ├── client/
│   │   │   │   │   ├── WhatsAppServiceClient.java  # HTTP client hacia Node.js
│   │   │   │   │   └── OpenAIServiceClient.java    # HTTP client hacia OpenAI
│   │   │   │   ├── websocket/
│   │   │   │   │   ├── WebSocketEventPublisher.java
│   │   │   │   │   └── DashboardWebSocketController.java
│   │   │   │   └── security/
│   │   │   │       ├── JwtTokenProvider.java
│   │   │   │       ├── JwtAuthenticationFilter.java
│   │   │   │       └── UserDetailsServiceImpl.java
│   │   │   │
│   │   │   └── adapter/                       # CAPA DE ADAPTADORES (REST)
│   │   │       ├── in/web/                    # Controllers REST
│   │   │       │   ├── AuthController.java
│   │   │       │   ├── WebhookController.java     # POST /api/webhook/message
│   │   │       │   ├── ConversationController.java
│   │   │       │   ├── MessageController.java
│   │   │       │   ├── ContactController.java
│   │   │       │   ├── FaqController.java
│   │   │       │   ├── KeywordRuleController.java
│   │   │       │   ├── BusinessConfigController.java
│   │   │       │   ├── BusinessHoursController.java
│   │   │       │   ├── MediaCatalogController.java
│   │   │       │   ├── MetricsController.java
│   │   │       │   ├── WhatsappSessionController.java
│   │   │       │   ├── UserController.java
│   │   │       │   └── AuditLogController.java
│   │   │       └── dto/                       # DTOs de Request/Response
│   │   │           ├── request/
│   │   │           │   ├── LoginRequest.java
│   │   │           │   ├── SendMessageRequest.java
│   │   │           │   ├── CreateFaqRequest.java
│   │   │           │   └── UpdateBusinessConfigRequest.java
│   │   │           └── response/
│   │   │               ├── LoginResponse.java
│   │   │               ├── ConversationResponse.java
│   │   │               ├── MessageResponse.java
│   │   │               ├── MetricsSummaryResponse.java
│   │   │               └── ApiResponse.java        # Wrapper genérico
│   │   │
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       └── db/migration/                  # Flyway migrations
│   │           ├── V1__init_schema.sql
│   │           ├── V2__seed_data.sql
│   │           └── V3__add_indexes.sql
│   │
│   └── test/
│       └── java/com/whatsappmvp/
│           ├── service/
│           │   ├── MessageProcessingServiceTest.java
│           │   ├── HybridEngineServiceTest.java
│           │   └── BusinessHoursServiceTest.java
│           └── controller/
│               └── WebhookControllerTest.java
│
├── Dockerfile
├── pom.xml
└── .env.example
```

---

## Frontend (Angular — Feature-based Architecture)

```
frontend/
├── src/
│   ├── app/
│   │   ├── core/                              # Singleton services, guards, interceptors
│   │   │   ├── auth/
│   │   │   │   ├── auth.service.ts
│   │   │   │   ├── auth.guard.ts
│   │   │   │   └── jwt.interceptor.ts
│   │   │   ├── services/
│   │   │   │   ├── websocket.service.ts
│   │   │   │   └── notification.service.ts
│   │   │   └── models/                        # Interfaces TypeScript
│   │   │       ├── conversation.model.ts
│   │   │       ├── message.model.ts
│   │   │       ├── faq.model.ts
│   │   │       └── metrics.model.ts
│   │   │
│   │   ├── shared/                            # Componentes reutilizables
│   │   │   ├── components/
│   │   │   │   ├── status-badge/
│   │   │   │   ├── confirm-dialog/
│   │   │   │   ├── empty-state/
│   │   │   │   └── loading-spinner/
│   │   │   └── pipes/
│   │   │       └── time-ago.pipe.ts
│   │   │
│   │   ├── layout/                            # Shell del dashboard
│   │   │   ├── sidebar/
│   │   │   ├── topbar/
│   │   │   └── dashboard-layout/
│   │   │
│   │   └── features/                          # Módulos por funcionalidad
│   │       ├── auth/
│   │       │   └── login/
│   │       ├── dashboard/                     # Home con métricas
│   │       ├── whatsapp/                      # Estado + QR
│   │       ├── conversations/                 # Inbox + detalle
│   │       ├── contacts/
│   │       ├── faqs/
│   │       ├── keyword-rules/
│   │       ├── business-config/               # Configuración general
│   │       ├── business-hours/
│   │       ├── bot-prompt/                    # Editor de prompt IA
│   │       ├── media-catalog/
│   │       ├── metrics/
│   │       ├── users/
│   │       └── audit-logs/
│   │
│   ├── environments/
│   │   ├── environment.ts
│   │   └── environment.prod.ts
│   └── styles/
│       ├── _variables.scss
│       ├── _mixins.scss
│       └── styles.scss
│
├── Dockerfile
├── nginx.conf                                 # Para servir la SPA
├── angular.json
├── package.json
└── tsconfig.json
```

---

## WhatsApp Service (Node.js)

```
whatsapp-service/
├── src/
│   ├── app.js                                 # Express app
│   ├── whatsapp/
│   │   ├── client.js                          # OpenWA/Baileys initialization
│   │   ├── session-manager.js                 # Persistencia y reconexión
│   │   └── message-handler.js                 # Al recibir mensaje → webhook
│   ├── routes/
│   │   ├── session.routes.js                  # GET /session/status, /session/qr
│   │   └── message.routes.js                  # POST /messages/send
│   ├── services/
│   │   ├── webhook.service.js                 # POST a Spring Boot
│   │   └── health.service.js
│   └── config/
│       └── index.js
├── sessions/                                  # Persistencia de sesión WA (volumen Docker)
├── Dockerfile
├── package.json
└── .env.example
```

---

## Infraestructura

```
infrastructure/
├── nginx/
│   ├── nginx.conf                             # Config principal
│   └── conf.d/
│       └── whatsapp-mvp.conf                  # Virtual host
├── postgres/
│   └── init.sql                               # Schema inicial
├── scripts/
│   ├── deploy.sh                              # Script de deploy en VPS
│   ├── backup-db.sh                           # Backup automático PostgreSQL
│   └── setup-vps.sh                           # Setup inicial del VPS
└── ssl/
    └── README.md                              # Instrucciones Let's Encrypt
```
