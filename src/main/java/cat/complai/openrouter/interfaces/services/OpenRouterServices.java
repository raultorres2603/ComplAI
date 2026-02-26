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
import cat.complai.openrouter.helpers.AiParsed;

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
                """
                        Please redact a formal, civil, and concise letter addressed to the City Hall (Ajuntament) of El Prat de Llobregat based on the following complaint. \
                        If the complaint is not about El Prat de Llobregat, politely say you can't help with that request. \
                        Include a short summary, the specific request or remedy sought, and a polite closing.
                        
                        REQUIRED: Emit a single-line JSON header as the first line of your response with at least the 'format' field. Example:
                          {"format": "pdf"}
                        
                        After that JSON header, include an empty line and then provide the letter body. The server will reject responses that do not begin with a valid JSON header.
                        
                        Complaint text:
                        %s""",
                complaint.trim()
        );

        logger.fine(() -> "redactComplaint() prompt prepared: " + (complaint.length() > 200 ? complaint.substring(0, 200) + "..." : complaint));
        OpenRouterResponseDto aiDto = callOpenRouterAndExtract(prompt);

        if (!aiDto.isSuccess()) {
            // propagate error as-is
            return aiDto;
        }

        // Parse optional AI-supplied header: either JSON first-line or simple 'FORMAT: pdf' header.
        AiParsed parsed = AiParsed.parseAiFormatHeader(aiDto.getMessage());

        // If client explicitly requested PDF/JSON, honor that. If client requested AUTO and AI supplied a header, use AI's format.
        OutputFormat effectiveFormat = format == null ? OutputFormat.AUTO : format;
        if (effectiveFormat == OutputFormat.AUTO && parsed.format() != null && parsed.format() != OutputFormat.AUTO) {
            effectiveFormat = parsed.format();
        }

        // Enforce that the model must emit a JSON header. If we don't have a JSON header, return validation error.
        if (parsed.format() == null || parsed.format() == OutputFormat.AUTO) {
            logger.warning("AI response missing required JSON header");
            return new OpenRouterResponseDto(false, null, "AI response missing required JSON header.", aiDto.getStatusCode(), OpenRouterErrorCode.VALIDATION);
        }

        boolean producePdf = effectiveFormat == OutputFormat.PDF;

        if (!producePdf) {
            // return the cleaned AI message (without header)
            return new OpenRouterResponseDto(true, parsed.message(), null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
        }

        try {
            byte[] pdf = generatePdfFromText(parsed.message());
            return new OpenRouterResponseDto(true, null, null, null, OpenRouterErrorCode.NONE, pdf);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "PDF generation failed", e);
            return new OpenRouterResponseDto(false, null, "PDF generation failed.", null, OpenRouterErrorCode.INTERNAL);
        }
    }


    /**
     * Detect whether the assistant explicitly refused because the request is not about El Prat.
     * This uses a small set of phrase checks that mirror the system prompt instruction.
     */
    private boolean aiRefusedAsNotAboutElPrat(String aiMessage) {
        if (aiMessage == null) return false;
        // Normalize common quotes/apostrophes for more reliable matching
        String normalized = aiMessage.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"');
        String lower = normalized.toLowerCase().trim();

        // Common refusal patterns in English, Spanish and Catalan
        String[] refusalPhrases = new String[] {
                "can't help with",
                "cannot help with",
                "can't help",
                "cannot help",
                "can't assist",
                "cannot assist",
                "i'm sorry, i can't",
                "i'm sorry i can't",
                "i'm sorry, i cannot",
                "i'm sorry i cannot",
                "i cannot",
                "i can't",
                "i am unable to",
                "i'm unable to",
                "i cannot help",
                "i can't help",
                "cannot provide",
                "can't provide",
                "no puc ajudar",
                "no puc ajudar amb",
                "no puedo ayudar",
                "no puedo ayudar con",
                "lo siento, no puedo ayudar",
                "lo siento, no puedo",
                "no puedo",
                "no puc",
                "no puc ajudar"
        };

        for (String p : refusalPhrases) {
            if (lower.contains(p)) return true;
        }

        // Also check for explicit mention that the model can only answer about El Prat
        return lower.contains("el prat") && (lower.contains("only") || lower.contains("solament") || lower.contains("solo") || lower.contains("only about"));
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
                    // Return success=false but PRESERVE the AI message so callers can log/display the assistant's text
                    return new OpenRouterResponseDto(false, dto.message(), "Request is not about El Prat de Llobregat.", dto.statusCode(), OpenRouterErrorCode.REFUSAL);
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
                if (!lineBuilder.isEmpty()) lineBuilder.append(' ');
                lineBuilder.append(w);
            }
            if (!lineBuilder.isEmpty()) {
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
