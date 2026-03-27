package cat.complai.openrouter.dto;

import org.reactivestreams.Publisher;

public sealed interface AskStreamResult {
    record Success(Publisher<String> stream) implements AskStreamResult {
    }

    record Error(OpenRouterResponseDto errorResponse) implements AskStreamResult {
    }
}