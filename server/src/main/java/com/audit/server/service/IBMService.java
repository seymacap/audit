package com.audit.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IBMService {

    private final ConcurrentHashMap<String, String> scanCache = new ConcurrentHashMap<>();

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
        Path outputDir = Path.of("accessibility-reports").toAbsolutePath();
        Files.createDirectories(outputDir);

        try (var stream = Files.walk(outputDir)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(outputDir))
                    .forEach(p -> p.toFile().delete());
        }

        String nodeExecutable = findNodeExecutable();
        String acheckerScript = findAcheckerScript();

        ProcessBuilder builder = new ProcessBuilder(nodeExecutable, acheckerScript, url);
        builder.directory(new File(System.getProperty("user.dir")));
        builder.redirectErrorStream(false);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("NODE_NO_WARNINGS", "1");

        Process process = builder.start();

        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {}
            } catch (IOException ignored) {}
        });

        stdoutThread.start();
        stdoutThread.join();
        process.waitFor();

        try (var stream = Files.walk(outputDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("summary_"))
                    .max(Comparator.comparingLong(p -> p.toFile().length()))
                    .map(p -> {
                        try {
                            System.out.println("Reading report from: " + p);
                            return Files.readString(p);
                        } catch (IOException e) { throw new RuntimeException(e); }
                    })
                    .orElseThrow(() -> new RuntimeException("No JSON report generated for: " + url));
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
