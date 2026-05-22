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
import java.util.concurrent.TimeUnit;

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

            // TODO add more values from ibm website
    );

    private static final boolean IS_WINDOWS = System.getProperty("os.name")
            .toLowerCase().contains("win");

    private ProcessBuilder buildNodeProcess(String nodeExecutable, String acheckerScript, String url) {
        if (IS_WINDOWS) {
            return new ProcessBuilder("cmd", "/c", nodeExecutable, acheckerScript, url);
        } else {
            return new ProcessBuilder(nodeExecutable, acheckerScript, url);
        }
    }

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
        Path outputDir = Path.of("accessibility-reports", UUID.randomUUID().toString());
        Files.createDirectories(outputDir);

        ProcessBuilder builder = new ProcessBuilder(
                "accessibility-checker",
                url,
                "--output",
                outputDir.toString(),
                "--headless",
                "--chromeOptions=--no-sandbox --disable-dev-shm-usage"
        );

        builder.directory(new File(System.getProperty("user.dir")));
        builder.redirectErrorStream(true); // merge stdout + stderr

        Map<String, String> env = builder.environment();
        env.put("NODE_NO_WARNINGS", "1");

        Process process = builder.start();

        StringBuilder outputLog = new StringBuilder();
        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLog.append(line).append("\n");
                }
            } catch (IOException ignored) {}
        });

        logThread.start();

        // Timeout protection (important for cloud)
        boolean finished = process.waitFor(2, TimeUnit.MINUTES);
        logThread.join();

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Accessibility scan timed out");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Accessibility checker failed (exit " + exitCode + ")\n" + outputLog
            );
        }

        try (var stream = Files.walk(outputDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("summary_"))
                    .max(Comparator.comparingLong(p -> p.toFile().length()))
                    .map(p -> {
                        try {
                            System.out.println("Reading report from: " + p);
                            return Files.readString(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .orElseThrow(() ->
                            new RuntimeException("No JSON report generated for: " + url));
        }
    }


//    public String findNodeExecutable() throws Exception {
//        String[] knownPaths = {
//                "/usr/bin/node",           // Dockerfile apt-get install nodejs
//                "/usr/local/bin/node",
//                "/mise/installs/node/22.22.2/bin/node"
//        };
//
//        for (String path : knownPaths) {
//            if (Files.exists(Path.of(path))) return path;
//        }
//
//        Path miseInstalls = Path.of("/mise/installs/node");
//        if (Files.exists(miseInstalls)) {
//            Optional<Path> latest = Files.list(miseInstalls)
//                    .filter(Files::isDirectory)
//                    .max(Comparator.naturalOrder());
//            if (latest.isPresent()) {
//                Path nodeBin = latest.get().resolve("bin/node");
//                if (Files.exists(nodeBin)) return nodeBin.toString();
//            }
//        }
//
//        if (IS_WINDOWS) {
//            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "where", "node");
//            pb.redirectErrorStream(true);
//            Process p = pb.start();
//            String path = new String(p.getInputStream().readAllBytes()).trim().split("\n")[0].trim();
//            p.waitFor();
//            if (!path.isEmpty()) return path;
//        }
//
//        throw new RuntimeException("Could not find node. PATH: " + System.getenv("PATH"));
//    }
//
//    public String findAcheckerScript() throws Exception {
//        String sep = File.separator;
//
//        String localBase = System.getProperty("user.dir") + sep + "node_modules" + sep + "accessibility-checker" + sep;
//
//        String miseBase = null;
//        Path miseInstalls = Path.of("/mise/installs/node");
//        if (Files.exists(miseInstalls)) {
//            Optional<Path> latest = Files.list(miseInstalls)
//                    .filter(Files::isDirectory)
//                    .max(Comparator.naturalOrder());
//            if (latest.isPresent()) {
//                miseBase = latest.get() + sep + "lib" + sep + "node_modules" + sep + "accessibility-checker" + sep;
//            }
//        }
//
//        String[] suffixes = {
//                "src" + sep + "lib" + sep + "ace.js",
//                "src" + sep + "lib" + sep + "index.js",
//                "bin" + sep + "achecker.js",
//                "lib" + sep + "ace.js",
//                "index.js"
//        };
//
//        for (String suffix : suffixes) {
//            Path candidate = Path.of(localBase + suffix);
//            if (Files.exists(candidate)) return candidate.toString();
//        }
//
//        if (miseBase != null) {
//            for (String suffix : suffixes) {
//                Path candidate = Path.of(miseBase + suffix);
//                if (Files.exists(candidate)) return candidate.toString();
//            }
//        }
//
//        if (IS_WINDOWS) {
//            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "npm", "root", "-g");
//            pb.redirectErrorStream(true);
//            Process p = pb.start();
//            String globalRoot = new String(p.getInputStream().readAllBytes()).trim();
//            p.waitFor();
//            String winBase = globalRoot + "\\accessibility-checker\\";
//            for (String suffix : new String[]{"src\\lib\\ace.js", "src\\lib\\index.js",
//                    "bin\\achecker.js", "lib\\ace.js", "index.js"}) {
//                if (Files.exists(Path.of(winBase + suffix))) return winBase + suffix;
//            }
//        }
//
//        throw new RuntimeException("Cannot find achecker script. localBase=" + localBase);
//    }
}
