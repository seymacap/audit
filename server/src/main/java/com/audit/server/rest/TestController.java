package com.audit.server.rest;

import com.audit.server.service.AiService;
import com.audit.server.service.JSoupService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    JSoupService jSoupService;

    private final AiService aiService;

    public TestController(AiService aiService) {
        this.aiService = aiService;
    }

    @GetMapping("/ai/generate")
    public Map<String, Object> generate(@RequestParam String message) {
        return Map.of("generation", aiService.ask(message));
    }

    @GetMapping(path = "/jsoup", produces = "application/json")
    public ResponseEntity<String> testApi(){
        jSoupService.getData("https://en.wikipedia.org/wiki/STAYC");
        String response = "crawler works";
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping(path = "/ibm", produces = "application/json")
    public ResponseEntity<String> testIbm() throws Exception {
        String response = aiService.runIbmScan("https://en.wikipedia.org/wiki/STAYC");
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }
}
