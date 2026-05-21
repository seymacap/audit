package com.audit.server.service;

import com.audit.server.model.Audit;
import com.audit.server.model.SuccessCriteria;
import com.audit.server.projection.SuccessCriteriaProjection;
import com.audit.server.repo.AuditRepository;
import com.audit.server.repo.SuccessCriteriaRepository;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.imageio.*;
import javax.imageio.stream.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;

import java.util.*;
import java.util.List;
import java.util.function.BiFunction;

@Service
public class AiService {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    private final SuccessCriteriaRepository criteriaRepo;
    private final AuditRepository auditRepo;
    private final JSoupService jSoupService;
    private final IBMService ibmService;

    private final Map<String, BiFunction<String, String, String>> criteriaHandlers;
    private final Map<String, BiFunction<List<byte[]>, String, String>> imageHandlers;

    private final String formatMessage = "Respond in the format I hand to you, making sure to respond only with that JSON and nothing else. " +
            "Give the criteria an answer of failing or passing. Don't use backticks in your answer, only respond with the JSON." +
            "The format is in JSON and right after this line \n" +
            "{\n" +
            "    \"overall_violation\": \"PASSED or FAILED\",\n" +
            "    \"violated_elements_and_reasons\": [\n" +
            "        {\n" +
            "            \"title\": \"A single sentence to describe the problem\",\n" +
            "            \"description\": \"Explanation of why it violates the criterion\",\n" +
            "            \"recommendation\": \"Recommendation to fix the violation for this specific element\",\n" +
            "            \"comment\": \"\" (You can leave this empty)" +
            "        }\n" +
            "    ]\n" +
            "    \"thought_process\": \"Briefly describe your thought process \",\n" +
            "    \"ibm\": \"If you've been provided anything flagged by IBM's Accessibility Checker, put them here." +
            "}" +
            "If there are no violations, the response should be: \n" +
            "{\n" +
            "    \"overall_violation\": \"PASSED\",\n" +
            "    \"violated_elements_and_reasons\": []\n" +
            "    \"thought_process\": \"Briefly describe your thought process \",\n" +
            "    \"ibm\": \"If you've been provided anything flagged by IBM's Accessibility Checker, put them here." +
            "}" +
            "\n If no element is provided you can return (elements can not be forgotten): " +
            "{\n" +
            "    \"overall_violation\": \"NA\",\n" +
            "    \"violated_elements_and_reasons\": []\n" +
            "    \"thought_process\": \"Briefly describe your thought process \",\n" +
            "    \"ibm\": \"If you've been provided anything flagged by IBM's Accessibility Checker, put them here." +
            "}\n";

    public AiService(SuccessCriteriaRepository criteriaRepo, AuditRepository auditRepo, JSoupService jSoupService, IBMService ibmService) {
        this.criteriaRepo = criteriaRepo;
        this.auditRepo = auditRepo;
        this.jSoupService = jSoupService;
        this.ibmService = ibmService;

        this.criteriaHandlers = Map.ofEntries(
                Map.entry("1.1.1", (url, ibm) -> checkAltText(jSoupService.getAltText(url), ibm)),
                Map.entry("1.3.5", (url, ibm) -> checkLabels(jSoupService.getLabelsAndInput(url), ibm)),
                Map.entry("1.4.2", (url, ibm) -> checkAudio(jSoupService.getAudioElements(url), ibm)),
                Map.entry("2.4.2", (url, ibm) -> checkPageTitle(jSoupService.getTitle(url), ibm)),
                Map.entry("2.4.4", (url, ibm) -> checkLinkPurpose(jSoupService.getLinks(url), ibm)),
                Map.entry("2.4.6", (url, ibm) -> checkLabelHeadings(jSoupService.getLabelsHeading(url), ibm)),
                Map.entry("2.5.3", (url, ibm) -> checkLabelNames(jSoupService.getLabels(url), ibm)),
                Map.entry("3.1.1", (url, ibm) -> checkLanguage(jSoupService.getLangElement(url), ibm)),
                Map.entry("3.1.2", (url, ibm) -> checkAllLanguage(jSoupService.getAllLangElements(url), ibm)),
                Map.entry("3.3.2", (url, ibm) -> checkFormInstructions(jSoupService.getFormElements(url), ibm)),
                Map.entry("4.1.2", (url, ibm) -> checkRole(jSoupService.getCustomElements(url), ibm))
        );

        this.imageHandlers = Map.of(
                "1.3.2",  (images, ibm) -> checkMeaningfulness(images, ibm),
                "1.3.4",  (images, ibm) -> checkOrientation(images, ibm),
                "1.4.1",  (images, ibm) -> checkUseOfColor(images, ibm),
                "1.4.3",  (images, ibm) -> checkContrast(images, ibm),
                "1.4.4",  (images, ibm) -> checkResize(images, ibm),
                "1.4.5",  (images, ibm) -> checkImageText(images, ibm),
                "1.4.10", (images, ibm) -> checkReflow(images, ibm),
                "1.4.12", (images, ibm) -> checkSpacing(images, ibm)
        );
    }

    /**
     * Compresses and resizes an image to keep base64-encoded size well under
     * request limit. Scales down to a max dimension of 1280 px
     * and re-encodes as JPEG at 75% quality.
     */
    private byte[] compressImage(byte[] imageBytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img == null) return imageBytes;

            int maxDim = 1280;
            if (img.getWidth() > maxDim || img.getHeight() > maxDim) {
                double scale = Math.min((double) maxDim / img.getWidth(),
                        (double) maxDim / img.getHeight());
                int w = (int) (img.getWidth()  * scale);
                int h = (int) (img.getHeight() * scale);
                Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
                BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                resized.getGraphics().drawImage(scaled, 0, 0, null);
                img = resized;
            } else {
                BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                rgb.getGraphics().drawImage(img, 0, 0, null);
                img = rgb;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.75f);
            writer.setOutput(ImageIO.createImageOutputStream(out));
            writer.write(null, new IIOImage(img, null, null), param);
            writer.dispose();
            return out.toByteArray();
        } catch (Exception e) {
            return imageBytes;
        }
    }

    private List<Map<String, Object>> buildImageContentParts(String systemPrompt, List<byte[]> images) {
        List<Map<String, Object>> contentParts = new ArrayList<>();
        contentParts.add(Map.of("type", "text", "text", systemPrompt));

        for (byte[] imageBytes : images) {
            byte[] compressed = compressImage(imageBytes);
            String base64Image = Base64.getEncoder().encodeToString(compressed);
            String dataUrl = "data:image/jpeg;base64," + base64Image;
            contentParts.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", dataUrl)
            ));
        }
        return contentParts;
    }


    private String callApi(String model, Object messageContent) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> message = Map.of("role", "user", "content", messageContent);
        Map<String, Object> body = Map.of("model", model, "messages", List.of(message));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        Map<String, Object> response = restTemplate.postForObject(
                "https://api.groq.com/openai/v1/chat/completions",
                request,
                Map.class
        );

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        return msg.get("content").toString();
    }

    private String callTextApi(String model, String prompt) {
        return callApi(model, prompt);
    }

    private String callImageApi(String systemPrompt, List<byte[]> images) {
        List<Map<String, Object>> contentParts = buildImageContentParts(systemPrompt, images);
        return callApi("meta-llama/llama-4-scout-17b-16e-instruct", contentParts);
    }


    public String ask(String message) {
        return callTextApi("meta-llama/llama-4-scout-17b-16e-instruct", message);
    }

    public String generateResponse(String criteriaId, String auditId) throws Exception {
        SuccessCriteriaProjection projection = criteriaRepo.findBySuccessCriteriaRefId(criteriaId);
        Optional<Audit> audit = auditRepo.findById(auditId);

        if (projection == null || projection.getSuccessCriteria().isEmpty())
            throw new RuntimeException("No criteria found for refId: " + criteriaId);
        if (audit.isEmpty())
            throw new RuntimeException("No audit found for id: " + auditId);

        String url = audit.get().getUrl();
        SuccessCriteria criteria = projection.getSuccessCriteria().getFirst();

        String rawJson = ibmService.getOrRunScan(auditId, url);
        String ibmIssues = ibmService.getIssuesPerCriteria(rawJson, criteriaId);

        BiFunction<String, String, String> handler = criteriaHandlers.get(criteria.getRefId());
        if (handler == null)
            throw new UnsupportedOperationException("No handler for criteria: " + criteria.getRefId());

        return handler.apply(url, ibmIssues);
    }

    public String generateResponseWithPicture(String criteriaId, String auditId, List<byte[]> images) throws Exception {
        SuccessCriteriaProjection projection = criteriaRepo.findBySuccessCriteriaRefId(criteriaId);
        Optional<Audit> audit = auditRepo.findById(auditId);

        if (projection == null || projection.getSuccessCriteria().isEmpty())
            throw new RuntimeException("No criteria found for refId: " + criteriaId);
        if (audit.isEmpty())
            throw new RuntimeException("No audit found for id: " + auditId);

        String url = audit.get().getUrl();

        String rawJson = ibmService.getOrRunScan(auditId, url);
        String ibmIssues = ibmService.getIssuesPerCriteria(rawJson, criteriaId);

        BiFunction<List<byte[]>, String, String> handler = imageHandlers.get(criteriaId);
        if (handler == null)
            throw new UnsupportedOperationException("No handler for criteria: " + criteriaId);

        return handler.apply(images, ibmIssues);
    }

    /** Used for rule 1.1.1 */
    public String checkAltText(Elements e, String ibmIssues){
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callTextApi("openai/gpt-oss-120b",
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Do not limit your findings to the violations mentioned in common failures or test rules; explore beyond these areas for potential issues." +
                        "The elements I am going to share needs to be checked based on criteria 1.1.1., mentioned in the WCAG 2.2 rules." +
                        "Check to see if any images found are accompanied with an alt text. Also check if this alt text, in general, is descriptive enough. " +
                        formatMessage +
                        "Here are the elements that need to be examined: " + e +
                ibmSection);
    }

    /** Used for rule 1.3.5 */
    public String checkLabels(String e, String ibmIssues){
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callTextApi("openai/gpt-oss-120b",
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Only follow the rules provided by the official WCAG rules themselves." +
                        "The elements I am going to share needs to be checked based on criteria 1.3.5, mentioned in the WCAG 2.2 rules. " +
                        "Labels and input tags will be shared, and it is your job to make sure inputs have a corresponding label." +
                        formatMessage +
                        "Here are the elements that need to be examined: " + e +
                ibmSection);
    }

    /** Used for rule 1.4.2 */
    public String checkAudio(Elements e, String ibmIssues){
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callTextApi("openai/gpt-oss-120b",
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Only follow the rules provided by the official WCAG rules themselves." +
                        "The elements I am going to share needs to be checked based on criteria 1.4.2, mentioned in the WCAG 2.2 rules. " +
                        "Audio tags will be shared, and you have to determine if they pass the criteria mention in 1.4.2" +
                        formatMessage +
                        "Here are the elements that need to be examined: " + e +
                ibmSection);
    }

    /** Used for rule 2.4.2 */
    public String checkPageTitle(Elements e, String ibmIssues){
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callTextApi("openai/gpt-oss-120b",
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Only follow the rules provided by the official WCAG rules themselves." +
                        "The elements I am going to share needs to be checked based on criteria 2.4.2, mentioned in the WCAG 2.2 rules. " +
                        "Determine if the titles are descriptive enough and give you a clue on what the website could be about. " +
                        "The elements that belong to the titles will not be shared, so try to be mindful and look at a broader picture. " +
                        "Try to see if you can determine what might be said with the title and if it, in general, is a descriptive title." +
                        formatMessage +
                        "Here are the elements that need to be examined: " + e +
                ibmSection);
    }

    /** Used for rule 2.4.4 */
    public String checkLinkPurpose(Elements e, String ibmIssues){
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callTextApi("openai/gpt-oss-120b",
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Only follow the rules provided by the official WCAG rules themselves." +
                        "The elements I am going to share needs to be checked based on criteria 2.4.4, mentioned in the WCAG 2.2 rules. " +
                        "Determine if the titles are descriptive enough and give you a clue on what the link could lead to." +
                        formatMessage +
                        "Here are the elements that need to be examined: " + e +
                ibmSection);
    }

    /** Used for rule 2.4.6 */
    public String checkLabelHeadings(Elements e, String ibmIssues){
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callTextApi("openai/gpt-oss-120b",
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Only follow the rules provided by the official WCAG rules themselves." +
                        "The elements I am going to share needs to be checked based on criteria 2.4.6, mentioned in the WCAG 2.2 rules. " +
                        "Determine if the labels and heading are descriptive enough and give you a clue on what they mean." +
                        formatMessage +
                        "Here are the elements that need to be examined: " + e +
                ibmSection);
    }

    /** Used for rule 2.5.3 */
    public String checkLabelNames(Elements e, String ibmIssues){
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callTextApi("openai/gpt-oss-120b",
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Only follow the rules provided by the official WCAG rules themselves." +
                        "The elements I am going to share needs to be checked based on criteria 2.5.3, mentioned in the WCAG 2.2 rules. " +
                        "Determine if the labels have a similar name as the text they are presenting." +
                        formatMessage +
                        "Here are the elements that need to be examined: " + e +
                ibmSection);

    }

    /** Used for rule 3.1.1 */
    public String checkLanguage(boolean e, String ibmIssues){
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callTextApi("openai/gpt-oss-120b",
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Do not limit your findings to the violations mentioned in common failures or test rules; explore beyond these areas for potential issues." +
                        "The elements I am going to share needs to be checked based on criteria 3.1.1, mentioned in the WCAG 2.2 rules. " +
                        formatMessage +
                        "The element has already been checked, i will provide you with a boolean that determines whether the website contains a lang attribute. The boolean is: " + e +
                        " which means that it is " + e + " that the website contains a lang element. Please only check if the element is present, the other rule will check if it is valid" +
                ibmSection);
    }

    /** Used for rule 3.1.2 */
    public String checkAllLanguage(String s, String ibmIssues){
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callTextApi("openai/gpt-oss-120b",
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Only follow the rules provided by the official WCAG rules themselves." +
                        "The elements I am going to share needs to be checked based on criteria 3.1.2, mentioned in the WCAG 2.2 rules. " +
                        formatMessage +
                        "Here are the elements that need to be examined: " + s +
                ibmSection);
    }

    /** Used for rule 3.3.2 */
    public String checkFormInstructions(Elements e, String ibmIssues){
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callTextApi("openai/gpt-oss-120b",
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Only follow the rules provided by the official WCAG rules themselves." +
                        "The elements I am going to share needs to be checked based on criteria 3.3.2, mentioned in the WCAG 2.2 rules. " +
                        "The entire form will be shared with you, and it is your job to make sure any form of error detection is present." +
                        formatMessage +
                        "Here are the elements that need to be examined: " + e +
                ibmSection);
    }

    /** Used for rule 4.1.2 */
    public String checkRole(String s, String ibmIssues){
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callTextApi("openai/gpt-oss-120b",
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Only follow the rules provided by the official WCAG rules themselves." +
                        "The elements I am going to share needs to be checked based on criteria 4.1.2, mentioned in the WCAG 2.2 rules. " +
                        "The entire form will be shared with you, and it is your job to make sure any form of error detection is present." +
                        formatMessage +
                        "Here are the elements that need to be examined: " + s +
                ibmSection);
    }

    /** Used for rule 1.3.2 */
    public String checkMeaningfulness(List<byte[]> images, String ibmIssues) {
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callImageApi(
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Do not limit your findings to the violations mentioned in common failures or test rules; explore beyond these areas for potential issues." +
                        "The image I am going to share needs to be checked based on criteria 1.3.2, mentioned in the WCAG 2.2 rules. " +
                        formatMessage +
                        "Remember no backticks and only respond with the given format." + ibmSection,
                images);
    }

    /** Used for rule 1.3.4 */
    public String checkOrientation(List<byte[]> images, String ibmIssues) {
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callImageApi(
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        " Do not limit your findings to the violations mentioned in common failures or test rules; explore beyond these areas for potential issues." +
                        "The two images I am going to share needs to be checked based on criteria 1.3.4, mentioned in the WCAG 2.2 rules. " +
                        "One of them will be in landscape orientation, while the other one is in portrait orientation." +
                        formatMessage +
                        "Remember no backticks and only respond with the given format." + ibmSection,
                images);
    }

    /** Used for rule 1.4.1 */
    public String checkUseOfColor(List<byte[]> images, String ibmIssues) {
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        System.out.println(ibmIssues);

        return callImageApi(
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        "Do not limit your findings to the violations mentioned in common failures or test rules; explore beyond these areas for potential issues." +
                        "The image/images I am going to share need to be checked based on criteria 1.4.1, mentioned in the WCAG 2.2 rules. " +
                        "They will contain screenshots of a web application, it is up for you to analyse them and determine if color is not used as the " +
                        "only visual means of conveying information." +
                        formatMessage +
                        " Remember no backticks and only respond with the given format." + ibmSection,
                images);
    }

    /** Used for rule 1.4.3 */
    public String checkContrast(List<byte[]> images, String ibmIssues) {
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callImageApi(
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        " Do not limit your findings to the violations mentioned in common failures or test rules; explore beyond these areas for potential issues." +
                        "The image/images I am going to share need to be checked based on criteria 1.4.3, mentioned in the WCAG 2.2 rules. " +
                        "They will contain screenshots of a web application, it is up for you to analyse them and determine if the contrast of text and images of text is " +
                        "according to the criteria mentioned in WCAG 1.4.3." +
                        formatMessage +
                        " Remember no backticks and only respond with the given format." + ibmSection,
                images);
    }

    /** Used for rule 1.4.4 */
    public String checkResize(List<byte[]> images, String ibmIssues) {
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callImageApi(
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        " Do not limit your findings to the violations mentioned in common failures or test rules; explore beyond these areas for potential issues." +
                        "The images I am going to share need to be checked based on criteria 1.4.4, mentioned in the WCAG 2.2 rules. " +
                        "They will contain screenshots of a web application, one that is in its regular size, and one zoomed in 200% " +
                        "Analyze these according to the criteria mentioned in WCAG 1.4.4." +
                        formatMessage +
                        " Remember no backticks and only respond with the given format." + ibmSection,
                images);
    }

    /** Used for rule 1.4.5 */
    public String checkImageText(List<byte[]> images, String ibmIssues) {
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callImageApi(
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        " Do not limit your findings to the violations mentioned in common failures or test rules; explore beyond these areas for potential issues." +
                        "The images I am going to share need to be checked based on criteria 1.4.5, mentioned in the WCAG 2.2 rules. Focus on this criterion only, " +
                        "with the exception of: 1. decorative images 2. text that is not significant 3. the text in the image is essential" +
                        formatMessage +
                        " Remember no backticks and only respond with the given format." + ibmSection,
                images);
    }

    /** Used for rule 1.4.10 */
    public String checkReflow(List<byte[]> images, String ibmIssues) {
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callImageApi(
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        " Do not limit your findings to the violations mentioned in common failures or test rules; explore beyond these areas for potential issues." +
                        "The images I am going to share need to be checked based on criteria 1.4.10, mentioned in the WCAG 2.2 rules. " +
                        "They will contain screenshots of a web application, one that is in its regular size, and one zoomed in 400% " +
                        "Analyze these according to the criteria mentioned in WCAG 1.4.10. Please pay attention to: 1. content disappearing 2. content requiring scrolling in two dimensions" +
                        formatMessage +
                        " Remember no backticks and only respond with the given format." + ibmSection,
                images);
    }

    /** Used for rule 1.4.12 */
    public String checkSpacing(List<byte[]> images, String ibmIssues) {
        String ibmSection = ibmIssues.isBlank() ? "" :
                "\n\nIBM Accessibility Checker also flagged these issues for this criterion, please take these issues into " +
                        "accountability and try to check if they are true:\n" + ibmIssues;

        return callImageApi(
                "You are an Accessibility Expert (WCAG Specialist) responsible for detecting WCAG 2.2 violations on websites." +
                        " Do not limit your findings to the violations mentioned in common failures or test rules; explore beyond these areas for potential issues." +
                        "The image/images I am going to share need to be checked based on criteria 1.4.12, mentioned in the WCAG 2.2 rules." +
                        formatMessage +
                        " Remember no backticks and only respond with the given format." + ibmSection,
                images);
    }
}