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

        // Instruct the model it may optionally emit a single-line metadata header at the very start of its reply
        // either as a small JSON object (e.g. {"format":"pdf" , "note":"..."}) or a plain header like: FORMAT: pdf
        // If provided, the server will use that to decide whether to serve a PDF. If not provided, server may decide heuristically.
        String prompt = String.format(
                "Please redact a formal, civil, and concise letter addressed to the City Hall (Ajuntament) of El Prat de Llobregat based on the following complaint. " +
                        "If the complaint is not about El Prat de Llobregat, politely say you can't help with that request. " +
                        "Include a short summary, the specific request or remedy sought, and a polite closing.\n\n" +
                        "IMPORTANT (optional): If you believe this response is best provided as a PDF (for example because it is a formal letter), start your reply with a single metadata header indicating the format, either as JSON on the first line like:\n" +
                        "  {\"format\": \"pdf\"}\n" +
                        "or as a simple header like:\n" +
                        "  FORMAT: pdf\n\n" +
                        "After that header, leave an empty line and then provide the letter body. If you do not emit a header, the server will decide automatically.\n\nComplaint text:\n%s",
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

        boolean producePdf;
        switch (effectiveFormat) {
            case PDF:
                producePdf = true;
                break;
            case JSON:
                producePdf = false;
                break;
            case AUTO:
            default:
                // fallback to previous heuristic
                producePdf = shouldAiProducePdf(parsed.message);
                break;
        }

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
        if (aiMessage == null) return new AiParsed(OutputFormat.AUTO, "");
        String trimmed = aiMessage.trim();

        // Try JSON first-line
        if (trimmed.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.Map map = mapper.readValue(trimmed, java.util.Map.class);
                // If the entire response was JSON and contains a 'format' and 'body'/'message' fields
                Object fmt = map.get("format");
                Object body = map.get("body") != null ? map.get("body") : map.get("message");
                if (fmt != null) {
                    OutputFormat f = OutputFormat.fromString(fmt.toString());
                    String m = body != null ? body.toString() : "";
                    return new AiParsed(f, m);
                }
            } catch (Exception ignored) {
                // fall through to other parsing strategies
            }
        }

        // Check for simple header on the first line
        String[] lines = aiMessage.split("\\r?\\n", -1);
        if (lines.length > 0) {
            String first = lines[0].trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^format\\s*[:=]\\s*(pdf|json|auto)").matcher(first);
            if (m.find()) {
                OutputFormat f = OutputFormat.fromString(m.group(1));
                // Skip the first line and any immediately following blank line
                int start = 1;
                if (lines.length > 1 && lines[1].trim().isEmpty()) start = 2;
                StringBuilder body = new StringBuilder();
                for (int i = start; i < lines.length; i++) {
                    body.append(lines[i]);
                    if (i < lines.length - 1) body.append('\n');
                }
                return new AiParsed(f, body.toString().trim());
            }
        }

        // No header found: return AUTO and original message
        return new AiParsed(OutputFormat.AUTO, aiMessage);
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

    /**
     * Generate a simple PDF document containing the provided text. This method is intentionally small
     * and uses standard PDFBox fonts. It performs basic wrapping by character count to avoid
     * depending on advanced layout code.
     */
    private byte[] generatePdfFromText(String text) throws Exception {
        if (text == null) text = "";
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            try (PDPageContentStream contents = new PDPageContentStream(doc, page)) {
                contents.beginText();
                contents.setFont(PDType1Font.HELVETICA_BOLD, 16);
                contents.newLineAtOffset(50, 700);
                contents.showText("Redacted complaint");
                contents.endText();

                contents.beginText();
                contents.setFont(PDType1Font.HELVETICA, 12);
                contents.newLineAtOffset(50, 680);

                // naive wrapping: 90 characters per line
                int maxPerLine = 90;
                // Replace carriage returns and normalize paragraph breaks
                String normalized = text.replace("\r", "");
                String[] paragraphs = normalized.split("\n\\s*\n");

                for (String s : paragraphs) {
                    // Replace any leftover single newlines with spaces to avoid illegal characters for the font encoder
                    String paragraph = s.replace("\n", " ").trim();
                    if (paragraph.isEmpty()) {
                        // add a blank line
                        contents.newLineAtOffset(0, -14);
                        continue;
                    }
                    // word-wrap the paragraph to avoid breaking words and to ensure no newlines inside showText
                    String[] words = paragraph.split("\\s+");
                    StringBuilder lineBuilder = new StringBuilder();
                    for (String w : words) {
                        if (lineBuilder.length() + w.length() + 1 > maxPerLine) {
                            // flush current line
                            contents.showText(lineBuilder.toString().trim());
                            contents.newLineAtOffset(0, -14);
                            lineBuilder.setLength(0);
                        }
                        if (!lineBuilder.isEmpty()) lineBuilder.append(' ');
                        lineBuilder.append(w);
                    }
                    if (!lineBuilder.isEmpty()) {
                        contents.showText(lineBuilder.toString().trim());
                        contents.newLineAtOffset(0, -14);
                    }
                    // paragraph separation: add an extra line
                    contents.newLineAtOffset(0, -6);
                }

                contents.endText();
            }

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                return out.toByteArray();
            }
        }
    }
}
