package cat.complai.openrouter.services.procedure;

import cat.complai.openrouter.dto.Source;
import cat.complai.openrouter.helpers.EventRagHelper;
import cat.complai.openrouter.helpers.EventRagHelperRegistry;
import cat.complai.openrouter.helpers.ProcedureRagHelper;
import cat.complai.openrouter.helpers.ProcedureRagHelperRegistry;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
public class ProcedureContextService {
    
    private final ProcedureRagHelperRegistry ragRegistry;
    private final EventRagHelperRegistry eventRagRegistry;
    private final RedactPromptBuilder promptBuilder;
    private final Logger logger = Logger.getLogger(ProcedureContextService.class.getName());
    
    @Inject
    public ProcedureContextService(ProcedureRagHelperRegistry ragRegistry, EventRagHelperRegistry eventRagRegistry, RedactPromptBuilder promptBuilder) {
        this.ragRegistry = ragRegistry;
        this.eventRagRegistry = eventRagRegistry;
        this.promptBuilder = promptBuilder;
    }
    
    /**
     * Result type for procedure context extraction: context block string and the list of source URLs.
     */
    public static class ProcedureContextResult {
        private final String contextBlock;
        private final List<Source> sources;

        public ProcedureContextResult(String contextBlock, List<Source> sources) {
            this.contextBlock = contextBlock;
            this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
        }

        public String getContextBlock() { return contextBlock; }
        public List<Source> getSources() { return sources; }
    }
    
    /**
     * Result type for event context extraction: context block string and the list of source URLs.
     */
    public static class EventContextResult {
        private final String contextBlock;
        private final List<Source> sources;

        public EventContextResult(String contextBlock, List<Source> sources) {
            this.contextBlock = contextBlock;
            this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
        }

        public String getContextBlock() { return contextBlock; }
        public List<Source> getSources() { return sources; }
    }
    
    /**
     * Determines if a question likely needs procedure/municipal information.
     * This avoids expensive RAG searches for conversational queries.
     * Checks against actual procedure titles for more accurate detection.
     */
    public boolean questionNeedsProcedureContext(String question, String cityId) {
        if (question == null || question.isBlank()) return false;
        
        String lower = question.toLowerCase();
        
        // First, check against actual procedure titles for this city (most accurate)
        try {
            ProcedureRagHelper helper = ragRegistry.getForCity(cityId);
            List<ProcedureRagHelper.Procedure> procedures = helper.getAllProcedures();
            for (ProcedureRagHelper.Procedure proc : procedures) {
                if (lower.contains(proc.title.toLowerCase())) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Fallback to keyword detection if procedure loading fails
            logger.fine(() -> "Failed to load procedures for title checking: " + e.getMessage());
        }
        
        // Keywords that indicate user wants procedural/municipal information
        String[] proceduralKeywords = {
            "how to", "how do i", "what is the process", "procedure", "tramit", "tràmit",
            "requirement", "requirements", "document", "documents", "apply", "application",
            "form", "forms", "permit", "license", "request", "complaint", "claim",
            "where can i", "how can i", "steps", "step by step", "process",
            "recycling", "waste", "garbage", "trash", "center", "collection",
            "event", "events", "agenda", "activity", "activities", "activitat", "activitats",
            "festival", "concert", "exhibition", "exposició", "theater", "teatre",
            "cinema", "sports", "esports", "culture", "cultura", "celebration", "celebració",
            "what's on", "what's happening", "què passa", "agenda cultural", "program"
        };
        
        for (String keyword : proceduralKeywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        
        // Questions about specific municipal services
        return lower.contains("ajuntament") || lower.contains("city hall") ||
                lower.contains("municipal") || lower.contains("council");
    }
    
    public ProcedureContextResult buildProcedureContextResult(String query, String cityId) {
        try {
            ProcedureRagHelper helper = ragRegistry.getForCity(cityId);
            List<ProcedureRagHelper.Procedure> matches = helper.search(query);
            if (matches.isEmpty()) {
                return new ProcedureContextResult(null, List.of());
            }
            List<Source> sources = matches.stream()
                    .map(p -> new Source(p.url, p.title))
                    .filter(source -> source.getUrl() != null && !source.getUrl().isBlank())
                    .toList();
            String contextBlock = promptBuilder.buildProcedureContextBlockFromMatches(matches, cityId);
            return new ProcedureContextResult(contextBlock, sources);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to build procedure context result for city=" + cityId
                    + "; returning empty context: " + e.getMessage(), e);
            return new ProcedureContextResult(null, List.of());
        }
    }
    
    /**
     * Determines if a question likely needs event information.
     * This avoids expensive RAG searches for conversational queries.
     * Checks against actual event titles for more accurate detection.
     */
    public boolean questionNeedsEventContext(String question, String cityId) {
        if (question == null || question.isBlank()) return false;
        
        String lower = question.toLowerCase();
        
        // First, check against actual event titles for this city (most accurate)
        try {
            EventRagHelper helper = eventRagRegistry.getForCity(cityId);
            List<EventRagHelper.Event> events = helper.getAllEvents();
            for (EventRagHelper.Event event : events) {
                if (lower.contains(event.title.toLowerCase())) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Fallback to keyword detection if event loading fails
            logger.fine(() -> "Failed to load events for title checking: " + e.getMessage());
        }
        
        // Keywords that indicate user wants event information
        String[] eventKeywords = {
            "event", "events", "agenda", "activity", "activities", "activitat", "activitats",
            "festival", "concert", "exhibition", "exposició", "theater", "teatre",
            "cinema", "sports", "esports", "culture", "cultura", "celebration", "celebració",
            "what's on", "what's happening", "què passa", "agenda cultural", "program",
            "this weekend", "next week", "today", "tomorrow", "upcoming"
        };
        
        for (String keyword : eventKeywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }
    
    public EventContextResult buildEventContextResult(String query, String cityId) {
        try {
            EventRagHelper helper = eventRagRegistry.getForCity(cityId);
            List<EventRagHelper.Event> matches = helper.search(query);
            if (matches.isEmpty()) {
                return new EventContextResult(null, List.of());
            }
            List<Source> sources = matches.stream()
                    .map(e -> new Source(e.url, e.title))
                    .filter(source -> source.getUrl() != null && !source.getUrl().isBlank())
                    .toList();
            String contextBlock = buildEventContextBlockFromMatches(matches, cityId);
            return new EventContextResult(contextBlock, sources);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to build event context result for city=" + cityId
                    + "; returning empty context: " + e.getMessage(), e);
            return new EventContextResult(null, List.of());
        }
    }
    
    private String buildEventContextBlockFromMatches(List<EventRagHelper.Event> matches, String cityId) {
        if (matches.isEmpty()) return "";
        
        StringBuilder sb = new StringBuilder();
        sb.append("Events in ").append(cityId).append(":\n\n");
        
        for (int i = 0; i < matches.size(); i++) {
            EventRagHelper.Event event = matches.get(i);
            sb.append(i + 1).append(". ").append(event.title).append("\n");
            
            if (event.date != null && !event.date.isBlank()) {
                sb.append("   Date: ").append(event.date).append("\n");
            }
            if (event.time != null && !event.time.isBlank()) {
                sb.append("   Time: ").append(event.time).append("\n");
            }
            if (event.location != null && !event.location.isBlank()) {
                sb.append("   Location: ").append(event.location).append("\n");
            }
            if (event.eventType != null && !event.eventType.isBlank()) {
                sb.append("   Type: ").append(event.eventType).append("\n");
            }
            if (event.targetAudience != null && !event.targetAudience.isBlank()) {
                sb.append("   Audience: ").append(event.targetAudience).append("\n");
            }
            if (event.description != null && !event.description.isBlank()) {
                sb.append("   Description: ").append(event.description).append("\n");
            }
            if (event.theme != null && !event.theme.isBlank()) {
                sb.append("   Theme: ").append(event.theme).append("\n");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * De-duplicates sources by URL and preserves order: first occurrence wins, stable ordering.
     */
    public List<Source> deDuplicateAndOrderSources(List<Source> sources) {
        LinkedHashSet<String> seenUrls = new LinkedHashSet<>();
        List<Source> deduped = new ArrayList<>();
        for (Source source : sources) {
            if (seenUrls.add(source.getUrl())) {
                deduped.add(source);
            }
        }
        return List.copyOf(deduped);
    }
}
