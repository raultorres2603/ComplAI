package cat.complai.openrouter.interfaces.services;

import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.*;

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

    private static final int MAX_HISTORY_TURNS = 10;
    private final HttpWrapper httpWrapper;
    private final Logger logger = Logger.getLogger(OpenRouterServices.class.getName());
    private final Cache<String, List<MessageEntry>> conversationCache;

    public record MessageEntry(String role, String content) {}

    @Inject
    public OpenRouterServices(HttpWrapper httpWrapper) {
        this.httpWrapper = Objects.requireNonNull(httpWrapper, "httpWrapper");
        this.conversationCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
    }

    @Override
    public OpenRouterResponseDto ask(String question, String conversationId) {
        logger.info("ask() called");
        if (question == null || question.isBlank()) {
            logger.fine("ask() rejected: empty question");
            return new OpenRouterResponseDto(false, null, "Question must not be empty.", null, OpenRouterErrorCode.VALIDATION);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        // System message
        messages.add(Map.of("role", "system", "content", getSystemMessage()));
        // Conversation history
        if (conversationId != null && !conversationId.isBlank()) {
            List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
            if (history != null) {
                for (MessageEntry entry : history) {
                    messages.add(Map.of("role", entry.role(), "content", entry.content()));
                }
            }
        }
        // Current user message
        messages.add(Map.of("role", "user", "content", question.trim()));

        logger.fine(() -> "ask() messages prepared: " + messages.size() + " total");
        OpenRouterResponseDto response = callOpenRouterAndExtract(messages);

        // On success, update conversation history
        if (conversationId != null && !conversationId.isBlank() && response != null && response.getMessage() != null) {
            List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
            if (history == null) history = new ArrayList<>();
            history.add(new MessageEntry("user", question.trim()));
            history.add(new MessageEntry("assistant", response.getMessage()));
            // Cap history
            if (history.size() > MAX_HISTORY_TURNS * 2) {
                history = history.subList(history.size() - MAX_HISTORY_TURNS * 2, history.size());
            }
            conversationCache.put(conversationId, history);
        }
        return response;
    }

    @Override
    public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId) {
        logger.info("redactComplaint() called");
        if (complaint == null || complaint.isBlank()) {
            logger.fine("redactComplaint() rejected: empty complaint");
            return new OpenRouterResponseDto(false, null, "Complaint must not be empty.", null, OpenRouterErrorCode.VALIDATION);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", getSystemMessage()));
        if (conversationId != null && !conversationId.isBlank()) {
            List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
            if (history != null) {
                for (MessageEntry entry : history) {
                    messages.add(Map.of("role", entry.role(), "content", entry.content()));
                }
            }
        }
        messages.add(Map.of("role", "user", "content", buildRedactPrompt(complaint)));

        logger.fine(() -> "redactComplaint() messages prepared: " + messages.size() + " total");
        OpenRouterResponseDto aiDto = callOpenRouterAndExtract(messages);

        // Propagate any non-success result (refusal, upstream error, timeout, internal error) immediately.
        // Continuing to the header-parsing logic with a failed AI response would produce incorrect
        // fallback behaviour — e.g. a refusal would silently become a 200 JSON response.
        if (aiDto.getErrorCode() != OpenRouterErrorCode.NONE) {
            return aiDto;
        }

        // On success, update conversation history
        if (conversationId != null && !conversationId.isBlank() && aiDto.getMessage() != null) {
            List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
            if (history == null) history = new ArrayList<>();
            history.add(new MessageEntry("user", buildRedactPrompt(complaint)));
            history.add(new MessageEntry("assistant", aiDto.getMessage()));
            if (history.size() > MAX_HISTORY_TURNS * 2) {
                history = history.subList(history.size() - MAX_HISTORY_TURNS * 2, history.size());
            }
            conversationCache.put(conversationId, history);
        }

        // Parse optional AI-supplied header: either JSON first-line or simple 'FORMAT: pdf' header.
        AiParsed parsed = AiParsed.parseAiFormatHeader(aiDto.getMessage());

        // If client explicitly requested PDF/JSON, honor that. If client requested AUTO and AI supplied a header, use AI's format.
        OutputFormat effectiveFormat = format == null ? OutputFormat.AUTO : format;
        if (effectiveFormat == OutputFormat.AUTO && parsed.format() != null && parsed.format() != OutputFormat.AUTO) {
            effectiveFormat = parsed.format();
        }

        // The AI did not emit the required JSON header. Log the raw response prefix to aid diagnosis.
        // Graceful fallback: if the client explicitly requested PDF we cannot produce one without a
        // clean letter body, so we return an error. In all other cases (AUTO or JSON) we treat the
        // full AI message as the letter body and return it as a JSON response — the user still gets
        // their letter rather than an opaque 400.
        if (parsed.format() == null || parsed.format() == OutputFormat.AUTO) {
            String rawPreview = aiDto.getMessage() == null ? "<null>"
                    : (aiDto.getMessage().length() > 200 ? aiDto.getMessage().substring(0, 200) + "..." : aiDto.getMessage());
            logger.warning("AI response missing required JSON header; raw response prefix: " + rawPreview);

            if (effectiveFormat == OutputFormat.PDF) {
                return new OpenRouterResponseDto(false, null, "AI response missing required JSON header; cannot produce PDF.", aiDto.getStatusCode(), OpenRouterErrorCode.UPSTREAM);
            }
            // Fall back: return the raw AI message as a JSON response so the user gets their letter.
            return new OpenRouterResponseDto(true, aiDto.getMessage(), null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
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

    private OpenRouterResponseDto callOpenRouterAndExtract(List<Map<String, Object>> messages) {
        logger.fine("callOpenRouterAndExtract: calling HttpWrapper");
        try {
            CompletableFuture<HttpDto> future = httpWrapper.postToOpenRouterAsync(messages);
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

    private String getSystemMessage() {
        return """
                Ets un assistent que es diu Gall Potablava, amable i proper per als veïns d'El Prat de Llobregat. Ajudes a redactar cartes i queixes clares i civils adreçades a l'Ajuntament i ofereixes informació pràctica i local d'El Prat. Mantén les respostes curtes, respectuoses i fàcils de llegir, com un veí que vol ajudar. Si la consulta no és sobre El Prat de Llobregat, digues-ho educadament i explica que no pots ajudar amb aquesta petició; també pots suggerir que facin una pregunta sobre assumptes locals.\
                
                
                En español: Eres un asistente que se llama Gall Potablava amable y cercano para los vecinos de El Prat de Llobregat. Ayuda a redactar cartes i queixes dirigides al Ayuntamiento i ofereix informació pràctica i local. Mantén las respuestas cortas y fáciles de entender. Si la consulta no trata sobre El Prat, dilo educadamente y sugiere que pregunten sobre asuntos locales.\
                
                
                In English (support): You are a friendly local assistant named Gall Potablava for residents of El Prat de Llobregat. Help draft clear, civil letters to the City Hall and provide practical local information. Keep answers short and easy to read. If the request is not about El Prat de Llobregat, politely say you can't help with that request.""";
    }

    private String buildRedactPrompt(String complaint) {
        return String.format(
                """
                        IMPORTANT: Your response MUST start with a single-line JSON header on line 1. 
                        No text, no markdown, no explanation before it. 
                        The header must contain the 'format' field set to "pdf" or "json". 
                        Example of a valid first line: {"format": "pdf"}
                        
                        After that JSON header, leave one blank line, then write the letter body.
                        
                        Task: Redact a formal, civil, and concise letter addressed to the City Hall 
                        (Ajuntament) of El Prat de Llobregat based on the complaint below. 
                        If the complaint is not about El Prat de Llobregat, politely say you can't 
                        help with that request. 
                        Include a short summary, the specific request or remedy sought, and a polite closing.
                        
                        Reminder: first line of your response MUST be the JSON header {"format": "pdf"} or {"format": "json"}.
                        
                        Complaint text:
                        %s""",
                complaint.trim()
        );
    }
}
