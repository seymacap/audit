package com.audit.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IBMService {

    private final ConcurrentHashMap<String, String> scanCache = new ConcurrentHashMap<>();

    private static final Path TMP_DIR      = Path.of(System.getProperty("java.io.tmpdir"));
    private static final Path OUTPUT_DIR   = TMP_DIR.resolve("accessibility-reports");
    private static final Path ACHECKER_CFG = TMP_DIR.resolve(".achecker.yml");

    @PostConstruct
    public void writeAcheckerConfig() throws IOException {
        // Write the config file into the OS temp dir at startup so it's always present,
        // on both Windows (locally) and Linux (Railway). This removes the need to rely
        // on the .achecker.yml being in any particular working directory.
        String config = """
                ruleArchive: latest
                policies:
                  - IBM_Accessibility
                failLevels: []
                reportLevels:
                  - violation
                  - potentialviolation
                  - recommendation
                  - potentialrecommendation
                  - manual
                outputFormat:
                  - json
                outputFolder: %s
                cacheFolder: %s
                puppeteerArgs:
                  - "--no-sandbox"
                  - "--disable-setuid-sandbox"
                  - "--disable-dev-shm-usage"
                """.formatted(
                OUTPUT_DIR.toString().replace("\\", "/"),
                TMP_DIR.resolve("accessibility-checker").toString().replace("\\", "/")
        );

        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(ACHECKER_CFG, config);
        System.out.println("[IBM] Config written to: " + ACHECKER_CFG);
        System.out.println("[IBM] Reports will be written to: " + OUTPUT_DIR);
    }

    public String getOrRunScan(String auditId, String url) throws Exception {
        return scanCache.computeIfAbsent(auditId, id -> {
            try {
                return runIbmScan(url);
            } catch (Exception e) {
                throw new RuntimeException("IBM scan failed for url: " + url, e);
            }
        });
    }

    private static final Map<String, List<String>> WCAG_TO_RULE_IDS = Map.ofEntries(
            Map.entry("1.1.1", List.of("img_alt_valid", "img_alt_background")),
            Map.entry("1.3.2", List.of("text_block_heading")),
            Map.entry("1.3.4", List.of()),
            Map.entry("1.3.5", List.of("input_label_visible", "label_content_exists")),
            Map.entry("1.4.1", List.of("style_color_misuse")),
            Map.entry("1.4.3", List.of("text_contrast_sufficient")),
            Map.entry("2.4.4", List.of("a_text_purpose")),
            Map.entry("2.4.6", List.of("label_content_exists", "text_block_heading")),
            Map.entry("2.5.3", List.of("aria_accessiblename_exists")),
            Map.entry("3.3.2", List.of("input_label_visible", "input_checkboxes_grouped", "label_content_exists")),
            Map.entry("4.1.2", List.of("aria_role_valid", "aria_attribute_valid", "aria_accessiblename_exists",
                    "element_id_unique", "element_tabbable_role_valid"))
    );

    public String getIssuesPerCriteria(String rawJson, String criteriaId) throws Exception {
        List<String> ruleIds = WCAG_TO_RULE_IDS.getOrDefault(criteriaId, List.of());
        if (ruleIds.isEmpty()) return "";

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(rawJson);
        JsonNode results = root.path("results").isMissingNode()
                ? root.path("data").path("results")
                : root.path("results");

        StringBuilder sb = new StringBuilder();

        for (JsonNode issue : results) {
            String ruleId = issue.path("ruleId").asText("");
            if (ruleIds.contains(ruleId)) {
                String level   = issue.path("level").asText("");
                String message = issue.path("message").asText("");
                String snippet = issue.path("snippet").asText("").trim();

                sb.append("- [").append(level.toUpperCase()).append("] ")
                        .append(message);
                if (!snippet.isBlank()) {
                    sb.append(" | Element: ").append(snippet);
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    public String runIbmScan(String url) throws Exception {
        if (Files.exists(OUTPUT_DIR)) {
            try (var stream = Files.walk(OUTPUT_DIR)) {
                stream.sorted(Comparator.reverseOrder())
                        .filter(p -> !p.equals(OUTPUT_DIR))
                        .forEach(p -> p.toFile().delete());
            }
        }

        String nodeExecutable = findNodeExecutable();
        String acheckerScript = findAcheckerScript();

        System.out.println("[IBM] node:      " + nodeExecutable);
        System.out.println("[IBM] script:    " + acheckerScript);
        System.out.println("[IBM] outputDir: " + OUTPUT_DIR);
        System.out.println("[IBM] config:    " + ACHECKER_CFG);

        ProcessBuilder builder = new ProcessBuilder(nodeExecutable, acheckerScript, "--config", ACHECKER_CFG.toString(), url);
        builder.directory(TMP_DIR.toFile());
        builder.redirectErrorStream(false);
        builder.environment().put("NODE_NO_WARNINGS", "1");

        Process process = builder.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) stdout.append(line).append("\n");
            } catch (IOException ignored) {}
        });

        Thread stderrThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) stderr.append(line).append("\n");
            } catch (IOException ignored) {}
        });

        stdoutThread.start();
        stderrThread.start();
        stdoutThread.join();
        stderrThread.join();
        int exitCode = process.waitFor();

        System.out.println("[IBM] exit code: " + exitCode);
        if (!stdout.isEmpty()) System.out.println("[IBM] stdout:\n" + stdout);
        if (!stderr.isEmpty()) System.err.println("[IBM] stderr:\n" + stderr);

        try (var stream = Files.walk(OUTPUT_DIR)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("summary_"))
                    .max(Comparator.comparingLong(p -> p.toFile().length()))
                    .map(p -> {
                        try {
                            System.out.println("[IBM] Reading report from: " + p);
                            return Files.readString(p);
                        } catch (IOException e) { throw new RuntimeException(e); }
                    })
                    .orElseThrow(() -> new RuntimeException(
                            "No JSON report generated for: " + url
                                    + "\n[IBM] stderr: " + stderr
                                    + "\n[IBM] stdout: " + stdout
                    ));
        }
    }

    private String findNodeExecutable() throws Exception {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        ProcessBuilder pb = isWindows
                ? new ProcessBuilder("cmd", "/c", "where", "node")
                : new ProcessBuilder("which", "node");

        pb.redirectErrorStream(true);
        Process p = pb.start();
        String path = new String(p.getInputStream().readAllBytes()).trim().split("\n")[0].trim();
        p.waitFor();
        if (path.isEmpty()) throw new RuntimeException("Could not find node executable");
        return path;
    }

    private String findAcheckerScript() throws Exception {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        ProcessBuilder pb = isWindows
                ? new ProcessBuilder("cmd", "/c", "npm", "root", "-g")
                : new ProcessBuilder("npm", "root", "-g");

        pb.redirectErrorStream(true);
        Process p = pb.start();
        String globalRoot = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();

        String sep = isWindows ? "\\" : "/";
        String base = globalRoot + sep + "accessibility-checker" + sep;

        String[] candidates = {
                base + "src" + sep + "lib" + sep + "ace.js",
                base + "src" + sep + "lib" + sep + "index.js",
                base + "bin" + sep + "achecker.js",
                base + "lib" + sep + "ace.js",
                base + "index.js"
        };

        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) return candidate;
        }

        throw new RuntimeException("Cannot find achecker script under: " + base);
    }
}
