package com.whatsappmvp.adapter.in.web;

import com.whatsappmvp.adapter.dto.request.SendMessageRequest;
import com.whatsappmvp.adapter.dto.response.ApiResponse;
import com.whatsappmvp.domain.enums.ConversationStatus;
import com.whatsappmvp.domain.enums.MessageDirection;
import com.whatsappmvp.domain.enums.MessageType;
import com.whatsappmvp.domain.enums.ProcessedBy;
import com.whatsappmvp.domain.exception.NotFoundException;
import com.whatsappmvp.infrastructure.client.WhatsAppServiceClient;
import com.whatsappmvp.infrastructure.persistence.entity.ConversationEntity;
import com.whatsappmvp.infrastructure.persistence.entity.MessageEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.ConversationJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.MessageJpaRepository;
import com.whatsappmvp.infrastructure.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationJpaRepository conversationRepository;
    private final MessageJpaRepository messageRepository;
    private final WhatsAppServiceClient whatsAppClient;
    private final WebSocketEventPublisher wsPublisher;

    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<Page<ConversationEntity>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {

        PageRequest pageable = PageRequest.of(page, size);
        Page<ConversationEntity> result;

        if (q != null && !q.isBlank()) {
            result = conversationRepository.searchByContactPhoneOrName(q, pageable);
        } else if (status != null && !status.isBlank()) {
            result = conversationRepository.findByStatusOrderByLastMessageAtDesc(
                ConversationStatus.valueOf(status.toUpperCase()), pageable);
        } else {
            result = conversationRepository.findAllByOrderByLastMessageAtDesc(pageable);
        }
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<ApiResponse<Page<MessageEntity>>> getMessages(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
            messageRepository.findByConversationIdOrderBySentAtAsc(id, PageRequest.of(page, size))
        ));
    }

    @PostMapping("/conversations/{id}/takeover")
    public ResponseEntity<ApiResponse<Void>> takeover(@PathVariable UUID id) {
        ConversationEntity c = conversationRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Conversation", id));
        c.setStatus(ConversationStatus.HUMAN_TAKEOVER);
        conversationRepository.save(c);

        // Notificar al dashboard via WebSocket
        wsPublisher.publishConversationUpdate(Map.of(
                "type", "CONVERSATION_UPDATE",
                "conversationId", c.getId().toString(),
                "status", "HUMAN_TAKEOVER",
                "unreadCount", c.getUnreadCount(),
                "lastMessageAt", c.getLastMessageAt() != null ? c.getLastMessageAt().toString() : ""
        ));

        return ResponseEntity.ok(ApiResponse.ok("Taken over", null));
    }

    @PostMapping("/conversations/{id}/release")
    public ResponseEntity<ApiResponse<Void>> release(@PathVariable UUID id) {
        ConversationEntity c = conversationRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Conversation", id));
        c.setStatus(ConversationStatus.AUTO);
        c.setUnreadCount(0);
        conversationRepository.save(c);

        // Notificar al dashboard via WebSocket
        wsPublisher.publishConversationUpdate(Map.of(
                "type", "CONVERSATION_UPDATE",
                "conversationId", c.getId().toString(),
                "status", "AUTO",
                "unreadCount", 0,
                "lastMessageAt", c.getLastMessageAt() != null ? c.getLastMessageAt().toString() : ""
        ));

        return ResponseEntity.ok(ApiResponse.ok("Released to bot", null));
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        ConversationEntity c = conversationRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Conversation", id));
        c.setIsDeleted(true);
        conversationRepository.save(c);

        wsPublisher.publishConversationUpdate(Map.of(
                "type", "CONVERSATION_DELETE",
                "conversationId", c.getId().toString()
        ));

        return ResponseEntity.ok(ApiResponse.ok("Conversation deleted", null));
    }

    @PostMapping("/messages/send")
    public ResponseEntity<ApiResponse<Void>> sendMessage(@RequestBody SendMessageRequest req) {
        ConversationEntity conv = conversationRepository.findById(UUID.fromString(req.getConversationId()))
            .orElseThrow(() -> new NotFoundException("Conversation", req.getConversationId()));

        // Usar remoteJid si está disponible, sino usar phone@s.whatsapp.net
        String targetJid = conv.getRemoteJid() != null && !conv.getRemoteJid().isBlank()
            ? conv.getRemoteJid()
            : conv.getContact().getPhone() + "@s.whatsapp.net";

        whatsAppClient.sendText(targetJid, req.getText());

        MessageEntity msg = MessageEntity.builder()
            .conversation(conv)
            .direction(MessageDirection.OUTBOUND)
            .content(req.getText())
            .messageType(MessageType.TEXT)
            .processedBy(ProcessedBy.HUMAN)
            .build();
        messageRepository.save(msg);

        conv.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conv);

        return ResponseEntity.ok(ApiResponse.ok("Message sent", null));
    }
}
