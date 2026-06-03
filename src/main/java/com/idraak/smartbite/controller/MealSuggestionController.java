package com.idraak.smartbite.controller;

import com.idraak.smartbite.dto.MealRequest;
import com.idraak.smartbite.dto.MealResponse;
import com.idraak.smartbite.service.AIModelService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MealSuggestionController {

    private final AIModelService aiModelService;

    public MealSuggestionController(AIModelService aiModelService) {
        this.aiModelService = aiModelService;
    }

    @PostMapping("/meal-suggestions")
    public ResponseEntity<MealResponse> suggest(@Valid @RequestBody MealRequest request) {
        return ResponseEntity.ok(aiModelService.getSuggestions(request));
    }
}
