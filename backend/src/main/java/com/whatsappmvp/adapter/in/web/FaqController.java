package com.whatsappmvp.adapter.in.web;

import com.whatsappmvp.adapter.dto.request.CreateFaqRequest;
import com.whatsappmvp.adapter.dto.response.ApiResponse;
import com.whatsappmvp.domain.exception.NotFoundException;
import com.whatsappmvp.infrastructure.persistence.entity.FaqItemEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.FaqJpaRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/faqs")
@RequiredArgsConstructor
public class FaqController {

    private final FaqJpaRepository faqRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<FaqItemEntity>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(faqRepository.findAll()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FaqItemEntity>> create(@Valid @RequestBody CreateFaqRequest req) {
        FaqItemEntity faq = FaqItemEntity.builder()
            .question(req.getQuestion())
            .answer(req.getAnswer())
            .keywords(req.getKeywords() != null ? req.getKeywords().toArray(new String[0]) : new String[0])
            .priority(req.getPriority() != null ? req.getPriority() : 0)
            .isActive(true)
            .build();
        return ResponseEntity.ok(ApiResponse.ok(faqRepository.save(faq)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<FaqItemEntity>> update(
            @PathVariable UUID id, @Valid @RequestBody CreateFaqRequest req) {
        FaqItemEntity faq = faqRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("FAQ", id));
        faq.setQuestion(req.getQuestion());
        faq.setAnswer(req.getAnswer());
        faq.setKeywords(req.getKeywords() != null ? req.getKeywords().toArray(new String[0]) : new String[0]);
        faq.setPriority(req.getPriority() != null ? req.getPriority() : 0);
        return ResponseEntity.ok(ApiResponse.ok(faqRepository.save(faq)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        faqRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.ok("FAQ deleted", null));
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<ApiResponse<FaqItemEntity>> toggle(@PathVariable UUID id) {
        FaqItemEntity faq = faqRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("FAQ", id));
        faq.setIsActive(!Boolean.TRUE.equals(faq.getIsActive()));
        return ResponseEntity.ok(ApiResponse.ok(faqRepository.save(faq)));
    }
}
