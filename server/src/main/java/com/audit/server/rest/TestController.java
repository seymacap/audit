package com.audit.server.rest;

import com.audit.server.service.AiService;
import com.audit.server.service.IBMService;
import com.audit.server.service.JSoupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    JSoupService jSoupService;

    private final AiService aiService;

    private final IBMService ibmService;

    public TestController(AiService aiService, IBMService ibmService) {
        this.aiService = aiService;
        this.ibmService = ibmService;
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
        String response = ibmService.runIbmScan("https://en.wikipedia.org/wiki/STAYC");
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping("/debug/env")
    public String debugEnv() throws Exception {
        StringBuilder sb = new StringBuilder();

        // Print PATH
        sb.append("PATH: ").append(System.getenv("PATH")).append("\n\n");

        try {
            ProcessBuilder pb = new ProcessBuilder("find", "/mise/installs", "-name", "node", "-type", "f");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            sb.append("find node binaries: ").append(result).append("\n");
        } catch (Exception e) {
            sb.append("find failed: ").append(e.getMessage()).append("\n");
        }

        // Try common node locations
        String[] commonPaths = {
                "/usr/bin/node",
                "/usr/local/bin/node",
                "/nix/var/nix/profiles/default/bin/node",
                "/root/.nix-profile/bin/node"
        };
        for (String path : commonPaths) {
            sb.append(path).append(" exists: ").append(Files.exists(Path.of(path))).append("\n");
        }

        return sb.toString();
    }
}
