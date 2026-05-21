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

import java.io.File;
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
            ProcessBuilder pb = new ProcessBuilder("find",
                    "/mise/installs/node/22.22.2/lib/node_modules/accessibility-checker",
                    "-name", "*.js", "-maxdepth", "3");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            sb.append("achecker files:\n").append(result).append("\n");
        } catch (Exception e) {
            sb.append("find achecker failed: ").append(e.getMessage()).append("\n");
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

        try {
            String nodeExecutable = ibmService.findNodeExecutable();
            String acheckerScript = ibmService.findAcheckerScript();

            ProcessBuilder pb = new ProcessBuilder(nodeExecutable, acheckerScript, "https://www.nederlandveilig.nl/");
            pb.directory(new File(System.getProperty("user.dir")));
            pb.redirectErrorStream(true); // merge stderr into stdout

            pb.environment().put("PATH", "/usr/bin:/usr/local/bin:/bin");
            pb.environment().put("NODE_NO_WARNINGS", "1");
            pb.environment().put("PUPPETEER_EXECUTABLE_PATH", "/usr/bin/chromium-browser");

            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor();

            sb.append("exit: ").append(exit).append("\n");
            sb.append("output:\n").append(output).append("\n");

            // List what was written to accessibility-reports
            Path reportDir = Path.of("/app/accessibility-reports");
            if (Files.exists(reportDir)) {
                sb.append("report files:\n");
                Files.walk(reportDir).forEach(p2 -> sb.append(p2).append("\n"));
            } else {
                sb.append("accessibility-reports dir does not exist\n");
            }
        } catch (Exception e) {
            sb.append("failed: ").append(e.getMessage()).append("\n");
        }

        sb.append("puppeteer cache exists: ").append(Files.exists(Path.of("/root/.cache/puppeteer"))).append("\n");
        sb.append("working dir: ").append(System.getProperty("user.dir")).append("\n");
        sb.append("working dir writable: ").append(new File(System.getProperty("user.dir")).canWrite()).append("\n");
        sb.append("aceconfig.js exists: ").append(Files.exists(Path.of(System.getProperty("user.dir") + "/aceconfig.js"))).append("\n");
        sb.append("chromium exists: ").append(Files.exists(Path.of("/usr/bin/chromium"))).append("\n");
        sb.append("chromium-browser exists: ").append(Files.exists(Path.of("/usr/bin/chromium-browser"))).append("\n");

        return sb.toString();
    }
}
