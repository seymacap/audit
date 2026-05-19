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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IBMService {

    public String runIbmScan(String url) throws Exception {
        Path outputDir = Path.of("accessibility-reports").toAbsolutePath();
        Files.createDirectories(outputDir);

        try (var stream = Files.list(outputDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> p.toFile().delete());
        }

        String nodeExecutable = findNodeExecutable();
        String acheckerScript = findAcheckerScript();

        ProcessBuilder builder = new ProcessBuilder(
                "cmd", "/c", nodeExecutable, acheckerScript, url
        );

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

        try (var stream = Files.list(outputDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .findFirst()
                    .map(p -> {
                        try { return Files.readString(p); }
                        catch (IOException e) { throw new RuntimeException(e); }
                    })
                    .orElseThrow(() -> new RuntimeException("No JSON report generated for: " + url));
        }
    }

    // Extracts a compact summary from the raw JSON for AI analysis
    public String buildPrompt(String rawJson) {
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder summary = new StringBuilder();

        try {
            JsonNode root = mapper.readTree(rawJson);

            // achecker wraps results in a "results" array
            JsonNode results = root.path("results");
            if (results.isMissingNode()) {
                results = root.path("data").path("results");
            }

            Map<String, List<String>> grouped = new LinkedHashMap<>();
            grouped.put("violation", new ArrayList<>());
            grouped.put("potentialviolation", new ArrayList<>());
            grouped.put("recommendation", new ArrayList<>());

            for (JsonNode issue : results) {
                String level = issue.path("level").asText("").toLowerCase();
                String message = issue.path("message").asText("");
                String snippet = issue.path("snippet").asText("");

                if (grouped.containsKey(level)) {
                    String entry = "- " + message +
                            (snippet.isBlank() ? "" : " [" + snippet.trim() + "]");
                    grouped.get(level).add(entry);
                }
            }

            int violations = grouped.get("violation").size();
            int potential = grouped.get("potentialviolation").size();
            int recommendations = grouped.get("recommendation").size();

            summary.append("Accessibility scan results:\n");
            summary.append("Violations: ").append(violations)
                    .append(", Potential violations: ").append(potential)
                    .append(", Recommendations: ").append(recommendations).append("\n\n");

            // Only include top 10 per category
            appendSection(summary, "Violations", grouped.get("violation"), 10);
            appendSection(summary, "Potential Violations", grouped.get("potentialviolation"), 10);
            appendSection(summary, "Recommendations", grouped.get("recommendation"), 10);

        } catch (Exception e) {
            return "Failed to parse accessibility report: " + e.getMessage();
        }

        return summary.toString();
    }

    private void appendSection(StringBuilder sb, String title, List<String> items, int max) {
        if (items.isEmpty()) return;
        sb.append(title).append(":\n");
        items.stream().limit(max).forEach(item -> sb.append(item).append("\n"));
        if (items.size() > max) {
            sb.append("  ...and ").append(items.size() - max).append(" more.\n");
        }
        sb.append("\n");
    }

    private String findNodeExecutable() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "where", "node");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String path = new String(p.getInputStream().readAllBytes()).trim().split("\n")[0].trim();
        p.waitFor();
        if (path.isEmpty()) throw new RuntimeException("Could not find node executable");
        return path;
    }

    private String findAcheckerScript() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "npm", "root", "-g");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String globalRoot = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();

        String base = globalRoot + "\\accessibility-checker\\";
        String[] candidates = {
                base + "src\\lib\\ace.js",
                base + "src\\lib\\index.js",
                base + "bin\\achecker.js",
                base + "lib\\ace.js",
                base + "index.js"
        };

        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) return candidate;
        }

        throw new RuntimeException("Cannot find achecker script under: " + base);
    }
}
