package com.audit.server.rest;

import com.audit.server.projection.SuccessCriteriaProjection;
import com.audit.server.repo.SuccessCriteriaRepository;
import com.audit.server.service.AiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/criteria")
public class SuccessCriteriaController {

    @Autowired
    SuccessCriteriaRepository repo;

    @Autowired
    private AiService aiService;

    @GetMapping(path = "/all", produces = "application/json")
    public List<SuccessCriteriaProjection> getAllGuidelines() {
        return repo.findAllSuccessCriteriaOnly();
    }

    @GetMapping(path = "/ai_put", produces = "application/json")
    public String getAiRecommendation(@RequestParam String criteriaId, @RequestParam String auditId) throws Exception {
        return aiService.generateResponse(criteriaId, auditId);
    }

    @PostMapping(path = "/ai_picture", produces = "application/json")
    public String getAiRecommendationWithPicture(@RequestParam String criteriaId, @RequestParam String auditId, @RequestParam("image") List<MultipartFile> images ) throws Exception {
        List<byte[]> imageBytes = new ArrayList<>();
        for (MultipartFile image : images){
            imageBytes.add(image.getBytes());
        }

        return aiService.generateResponseWithPicture(criteriaId, auditId, imageBytes);
    }
}
