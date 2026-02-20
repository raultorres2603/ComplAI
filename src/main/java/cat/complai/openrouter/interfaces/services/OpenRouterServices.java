package cat.complai.openrouter.interfaces.services;

import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

// PDFBox imports
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.io.ByteArrayOutputStream;

import cat.complai.openrouter.dto.OutputFormat;

@Singleton
public class OpenRouterServices implements IOpenRouterService {

    private final HttpWrapper httpWrapper;
    private final Logger logger = Logger.getLogger(OpenRouterServices.class.getName());

    @Inject
    public OpenRouterServices(HttpWrapper httpWrapper) {
        this.httpWrapper = Objects.requireNonNull(httpWrapper, "httpWrapper");
    }

    @Override
    public OpenRouterResponseDto ask(String question) {
        logger.info("ask() called");
        if (question == null || question.isBlank()) {
            logger.fine("ask() rejected: empty question");
            return new OpenRouterResponseDto(false, null, "Question must not be empty.", null, OpenRouterErrorCode.VALIDATION);
        }

        String prompt = String.format(
                "User question (from a resident). Please answer only if the question is about El Prat de Llobregat. If it's not about El Prat de Llobregat, politely say you can't help with that request.\n\nQuestion:\n%s\n\nPlease answer concisely and provide relevant local information or guidance.",
                question.trim()
        );

        logger.fine(() -> "ask() prompt prepared: " + (question.length() > 200 ? question.substring(0, 200) + "..." : question));
        return callOpenRouterAndExtract(prompt);
    }

    @Override
    public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format) {
        logger.info("redactComplaint() called");
        if (complaint == null || complaint.isBlank()) {
            logger.fine("redactComplaint() rejected: empty complaint");
            return new OpenRouterResponseDto(false, null, "Complaint must not be empty.", null, OpenRouterErrorCode.VALIDATION);
        }

        // Instruct the model: the model MUST emit a JSON metadata header on the first line. The header should be a small JSON
        // object containing at least a "format" field ("pdf" | "json"). The server will parse that JSON header and use it
        // to decide whether to generate a PDF. If the header is missing or malformed, the request will be rejected.
        String prompt = String.format(
                "Please redact a formal, civil, and concise letter addressed to the City Hall (Ajuntament) of El Prat de Llobregat based on the following complaint. " +
                        "If the complaint is not about El Prat de Llobregat, politely say you can't help with that request. " +
                        "Include a short summary, the specific request or remedy sought, and a polite closing.\n\n" +
                        "REQUIRED: Emit a single-line JSON header as the first line of your response with at least the 'format' field. Example:\n" +
                        "  {\"format\": \"pdf\"}\n\n" +
                        "After that JSON header, include an empty line and then provide the letter body. The server will reject responses that do not begin with a valid JSON header.\n\nComplaint text:\n%s",
                complaint.trim()
        );

        logger.fine(() -> "redactComplaint() prompt prepared: " + (complaint.length() > 200 ? complaint.substring(0, 200) + "..." : complaint));
        OpenRouterResponseDto aiDto = callOpenRouterAndExtract(prompt);

        if (!aiDto.isSuccess()) {
            // propagate error as-is
            return aiDto;
        }

        // Parse optional AI-supplied header: either JSON first-line or simple 'FORMAT: pdf' header.
        AiParsed parsed = parseAiFormatHeader(aiDto.getMessage());

        // If client explicitly requested PDF/JSON, honor that. If client requested AUTO and AI supplied a header, use AI's format.
        OutputFormat effectiveFormat = format == null ? OutputFormat.AUTO : format;
        if (effectiveFormat == OutputFormat.AUTO && parsed.format != null && parsed.format != OutputFormat.AUTO) {
            effectiveFormat = parsed.format;
        }

        // Enforce that the model must emit a JSON header. If we don't have a JSON header, return validation error.
        if (parsed.format == null || parsed.format == OutputFormat.AUTO) {
            logger.warning("AI response missing required JSON header");
            return new OpenRouterResponseDto(false, null, "AI response missing required JSON header.", aiDto.getStatusCode(), OpenRouterErrorCode.VALIDATION);
        }

        boolean producePdf = effectiveFormat == OutputFormat.PDF || (effectiveFormat == OutputFormat.AUTO && shouldAiProducePdf(parsed.message));

        if (!producePdf) {
            // return the cleaned AI message (without header)
            return new OpenRouterResponseDto(true, parsed.message, null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
        }

        try {
            byte[] pdf = generatePdfFromText(parsed.message);
            return new OpenRouterResponseDto(true, null, null, null, OpenRouterErrorCode.NONE, pdf);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "PDF generation failed", e);
            return new OpenRouterResponseDto(false, null, "PDF generation failed.", null, OpenRouterErrorCode.INTERNAL);
        }
    }

    // Small holder for parsed AI reply
    private static class AiParsed {
        OutputFormat format;
        String message;

        AiParsed(OutputFormat f, String m) { this.format = f; this.message = m; }
    }

    /**
     * Parse a model reply for an optional metadata header. Supports:
     * - First-line JSON like: {"format":"pdf"}\n\n<message>
     * - First-line simple header like: FORMAT: pdf\n\n<message>
     * If no header found, returns format AUTO and the original message.
     */
    private AiParsed parseAiFormatHeader(String aiMessage) {
        // Strict: only accept a JSON header on the first line. If not present, treat as missing header.
        if (aiMessage == null) return new AiParsed(OutputFormat.AUTO, "");
        String trimmed = aiMessage.trim();

        if (!trimmed.startsWith("{")) {
            return new AiParsed(OutputFormat.AUTO, aiMessage);
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            // Read first JSON object at the start: find the closing brace matching the first open
            int depth = 0;
            int idx = -1;
            for (int i = 0; i < aiMessage.length(); i++) {
                char c = aiMessage.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { idx = i; break; }
                }
            }
            if (idx < 0) return new AiParsed(OutputFormat.AUTO, aiMessage);

            String jsonHeader = aiMessage.substring(0, idx + 1).trim();
            String rest = aiMessage.substring(idx + 1).trim();
            java.util.Map map = mapper.readValue(jsonHeader, java.util.Map.class);
            Object fmt = map.get("format");
            Object body = map.get("body") != null ? map.get("body") : map.get("message");
            OutputFormat f = fmt == null ? OutputFormat.AUTO : OutputFormat.fromString(fmt.toString());
            String m = (body != null) ? body.toString() : rest;
            // If body missing in JSON, use the remaining text after the JSON header
            if ((m == null || m.isBlank()) && rest != null) m = rest;
            return new AiParsed(f, m == null ? "" : m.trim());
        } catch (Exception e) {
            // parsing failed: treat as missing strict header
            return new AiParsed(OutputFormat.AUTO, aiMessage);
        }
    }

    /**
     * Heuristic to decide whether AI message should be returned as a PDF when client asked AUTO.
     * Conservative rules: if message is long, contains salutations/closings or multiple paragraphs.
     */
    private boolean shouldAiProducePdf(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        if (message.length() > 300) return true;
        if (lower.contains("dear ") || lower.contains("sincerely") || lower.contains("regards") || lower.contains("yours faithfully")) return true;
        // multiple paragraphs
        return message.contains("\n\n") || message.split("\\n").length > 4;
    }

    /**
     * Detect whether the assistant explicitly refused because the request is not about El Prat.
     * This uses a small set of phrase checks that mirror the system prompt instruction.
     */
    private boolean aiRefusedAsNotAboutElPrat(String aiMessage) {
        if (aiMessage == null) return false;
        String lower = aiMessage.toLowerCase();
        return lower.contains("can't help") || lower.contains("cannot help") || lower.contains("can't help with that request") || lower.contains("no puc ajudar") || lower.contains("no puedo ayudar");
    }

    private OpenRouterResponseDto callOpenRouterAndExtract(String prompt) {
        logger.fine("callOpenRouterAndExtract: calling HttpWrapper");
        try {
            CompletableFuture<HttpDto> future = httpWrapper.postToOpenRouterAsync(prompt);
            HttpDto dto = future.get(30, TimeUnit.SECONDS);
            logger.fine(() -> "callOpenRouterAndExtract: received dto=" + (dto == null ? "null" : String.valueOf(dto.statusCode())));
            if (dto == null) {
                logger.warning("callOpenRouterAndExtract: No response from AI service");
                return new OpenRouterResponseDto(false, null, "No response from AI service.", null, OpenRouterErrorCode.UPSTREAM);
            }
            if (dto.error() != null && !dto.error().isBlank()) {
                logger.log(Level.WARNING, "AI wrapper returned error: {0}", dto.error());
                return new OpenRouterResponseDto(false, dto.message(), dto.error(), dto.statusCode(), OpenRouterErrorCode.UPSTREAM);
            }
            if (dto.message() != null && !dto.message().isBlank()) {
                // If the AI refused because it's not about El Prat, map to a standardized error
                if (aiRefusedAsNotAboutElPrat(dto.message())) {
                    logger.info("AI refused - not about El Prat");
                    return new OpenRouterResponseDto(false, null, "Request is not about El Prat de Llobregat.", dto.statusCode(), OpenRouterErrorCode.REFUSAL);
                }
                logger.fine("AI returned a message successfully");
                return new OpenRouterResponseDto(true, dto.message(), null, dto.statusCode(), OpenRouterErrorCode.NONE);
            }
            logger.warning("AI returned no message");
            return new OpenRouterResponseDto(false, null, "AI returned no message.", dto.statusCode(), OpenRouterErrorCode.UPSTREAM);
        } catch (TimeoutException te) {
            logger.log(Level.SEVERE, "AI service timed out", te);
            return new OpenRouterResponseDto(false, null, "AI service timed out.", null, OpenRouterErrorCode.TIMEOUT);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error calling AI service", e);
            return new OpenRouterResponseDto(false, null, e.getMessage(), null, OpenRouterErrorCode.INTERNAL);
        }
    }

    // ------------------ PDF generation refactor helpers ------------------
    private byte[] generatePdfFromText(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            writeBodyToDocument(doc, text);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                return out.toByteArray();
            }
        }
    }

    private void writeBodyToDocument(PDDocument doc, String text) throws Exception {
        final float margin = 50f;
        final PDRectangle pageSize = PDRectangle.LETTER;
        final float pageHeight = pageSize.getHeight();
        final float titleGap = 20f;
        final float leading = 14f;

        float yStart = pageHeight - margin - titleGap;
        float yPosition = yStart;

        PDPage page = new PDPage(pageSize);
        doc.addPage(page);
        PDPageContentStream contents = new PDPageContentStream(doc, page);

        // Title
        contents.beginText();
        contents.setFont(PDType1Font.HELVETICA_BOLD, 16);
        contents.newLineAtOffset(margin, yStart + 10);
        contents.showText("Redacted complaint");
        contents.endText();

        // Begin body
        contents.beginText();
        contents.setFont(PDType1Font.HELVETICA, 12);
        contents.newLineAtOffset(margin, yPosition);

        // Normalize and split paragraphs
        String normalized = (text == null) ? "" : text.replace("\r", "");
        String[] paragraphs = normalized.split("\n\\s*\n");
        int maxPerLine = 90;

        for (String s : paragraphs) {
            String paragraph = s.replace("\n", " ").trim();
            if (paragraph.isEmpty()) {
                yPosition -= leading;
                contents.newLineAtOffset(0, -leading);
                if (yPosition < margin) {
                    contents.endText();
                    contents.close();
                    page = addNewPage(doc);
                    contents = new PDPageContentStream(doc, page);
                    yPosition = yStart;
                    contents.beginText();
                    contents.setFont(PDType1Font.HELVETICA, 12);
                    contents.newLineAtOffset(margin, yPosition);
                }
                continue;
            }

            String[] words = paragraph.split("\\s+");
            StringBuilder lineBuilder = new StringBuilder();
            for (String w : words) {
                if (lineBuilder.length() + w.length() + 1 > maxPerLine) {
                    contents.showText(lineBuilder.toString().trim());
                    contents.newLineAtOffset(0, -leading);
                    yPosition -= leading;
                    lineBuilder.setLength(0);

                    if (yPosition < margin) {
                        contents.endText();
                        contents.close();
                        page = addNewPage(doc);
                        contents = new PDPageContentStream(doc, page);
                        yPosition = yStart;
                        contents.beginText();
                        contents.setFont(PDType1Font.HELVETICA, 12);
                        contents.newLineAtOffset(margin, yPosition);
                    }
                }
                if (lineBuilder.length() > 0) lineBuilder.append(' ');
                lineBuilder.append(w);
            }
            if (lineBuilder.length() > 0) {
                contents.showText(lineBuilder.toString().trim());
                contents.newLineAtOffset(0, -leading);
                yPosition -= leading;
            }

            // paragraph gap
            yPosition -= (leading / 2f);
            contents.newLineAtOffset(0, -(leading / 2f));
            if (yPosition < margin) {
                contents.endText();
                contents.close();
                page = addNewPage(doc);
                contents = new PDPageContentStream(doc, page);
                yPosition = yStart;
                contents.beginText();
                contents.setFont(PDType1Font.HELVETICA, 12);
                contents.newLineAtOffset(margin, yPosition);
            }
        }

        contents.endText();
        contents.close();
    }

    private PDPage addNewPage(PDDocument doc) {
        PDPage newPage = new PDPage(PDRectangle.LETTER);
        doc.addPage(newPage);
        return newPage;
    }
}
