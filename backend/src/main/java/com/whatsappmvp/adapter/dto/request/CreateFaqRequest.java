package com.whatsappmvp.adapter.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

@Data
public class CreateFaqRequest {
    @NotBlank private String question;
    @NotBlank private String answer;
    private List<String> keywords;
    private Integer priority = 0;
}
