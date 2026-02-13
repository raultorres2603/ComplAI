package cat.complai.openrouter.interfaces.services;

import cat.complai.openrouter.interfaces.IOpenRouterService;
import jakarta.inject.Singleton;

@Singleton
public class OpenRouterServices implements IOpenRouterService {


    @Override
    public String ask(String question) {
        return "";
    }

    @Override
    public String redactComplaint(String complaint) {
        return "";
    }
}
