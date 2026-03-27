package cat.complai.http.dto;

import cat.complai.http.OpenRouterStreamingException;
import org.reactivestreams.Publisher;

public sealed interface OpenRouterStreamStartResult {
    record Success(Publisher<String> stream) implements OpenRouterStreamStartResult {
    }

    record Error(OpenRouterStreamingException failure) implements OpenRouterStreamStartResult {
    }
}