package cat.complai.services.ses;

public interface IEmailService {
        void sendStadistics(String to, String subject) throws Exception;

}
