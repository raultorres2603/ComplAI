package cat.complai.controllers.ses;

import cat.complai.config.ISesRecipientProvider;
import cat.complai.config.ISesSenderConfig;
import cat.complai.services.ses.IEmailService;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import static org.mockito.Mockito.*;

/**
 * Test factory for SesController tests.
 * Provides mock/stub beans to avoid circular dependencies and AWS calls.
 */
@Factory
public class SesControllerTestFactory {

    private static final String RECIPIENT_EMAIL = "admin@test.com";

    @Primary
    @Singleton
    @Replaces(IEmailService.class)
    public IEmailService emailService() {
        // Return a simple mock that does nothing (success case)
        return mock(IEmailService.class);
    }

    @Primary
    @Singleton
    @Replaces(ISesRecipientProvider.class)
    public ISesRecipientProvider sesRecipientProvider() {
        ISesRecipientProvider provider = mock(ISesRecipientProvider.class);
        when(provider.getRecipientEmail()).thenReturn(RECIPIENT_EMAIL);
        return provider;
    }

    @Primary
    @Singleton
    @Replaces(ISesSenderConfig.class)
    public ISesSenderConfig sesSenderConfig() {
        ISesSenderConfig config = mock(ISesSenderConfig.class);
        when(config.getFromEmail()).thenReturn("noreply@test.com");
        when(config.getRegion()).thenReturn("eu-west-1");
        return config;
    }
}