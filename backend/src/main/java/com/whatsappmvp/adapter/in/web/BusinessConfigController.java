package com.whatsappmvp.adapter.in.web;

import com.whatsappmvp.adapter.dto.request.UpdateBusinessConfigRequest;
import com.whatsappmvp.adapter.dto.response.ApiResponse;
import com.whatsappmvp.domain.exception.NotFoundException;
import com.whatsappmvp.infrastructure.persistence.entity.BusinessConfigEntity;
import com.whatsappmvp.infrastructure.persistence.entity.BusinessHoursEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.BusinessConfigJpaRepository;
import com.whatsappmvp.infrastructure.persistence.jpa.BusinessHoursJpaRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BusinessConfigController {

    private final BusinessConfigJpaRepository configRepository;
    private final BusinessHoursJpaRepository hoursRepository;

    @GetMapping("/business-config")
    public ResponseEntity<ApiResponse<BusinessConfigEntity>> get() {
        BusinessConfigEntity config = configRepository.findFirstByOrderByCreatedAtAsc()
            .orElseThrow(() -> new NotFoundException("BusinessConfig not found"));
        return ResponseEntity.ok(ApiResponse.ok(config));
    }

    @PutMapping("/business-config")
    public ResponseEntity<ApiResponse<BusinessConfigEntity>> update(
            @Valid @RequestBody UpdateBusinessConfigRequest req) {
        BusinessConfigEntity config = configRepository.findFirstByOrderByCreatedAtAsc()
            .orElseThrow(() -> new NotFoundException("BusinessConfig not found"));
        config.setBusinessName(req.getBusinessName());
        if (req.getBusinessType()      != null) config.setBusinessType(req.getBusinessType());
        if (req.getWelcomeMessage()    != null) config.setWelcomeMessage(req.getWelcomeMessage());
        if (req.getOffHoursMessage()   != null) config.setOffHoursMessage(req.getOffHoursMessage());
        if (req.getHumanDelaySeconds() != null) config.setHumanDelaySeconds(req.getHumanDelaySeconds());
        if (req.getAiEnabled()         != null) config.setAiEnabled(req.getAiEnabled());
        if (req.getAiModel()           != null) config.setAiModel(req.getAiModel());
        if (req.getMaxAiTokens()       != null) config.setMaxAiTokens(req.getMaxAiTokens());
        return ResponseEntity.ok(ApiResponse.ok(configRepository.save(config)));
    }

    @GetMapping("/business-hours")
    public ResponseEntity<ApiResponse<List<BusinessHoursEntity>>> getHours() {
        BusinessConfigEntity config = configRepository.findFirstByOrderByCreatedAtAsc()
            .orElseThrow(() -> new NotFoundException("BusinessConfig not found"));
        return ResponseEntity.ok(ApiResponse.ok(
            hoursRepository.findByBusinessConfigIdOrderByDayOfWeekAsc(config.getId())
        ));
    }

    @PutMapping("/business-hours")
    public ResponseEntity<ApiResponse<List<BusinessHoursEntity>>> updateHours(
            @RequestBody List<BusinessHoursEntity> hours) {
        List<BusinessHoursEntity> saved = hoursRepository.saveAll(hours);
        return ResponseEntity.ok(ApiResponse.ok(saved));
    }
}
