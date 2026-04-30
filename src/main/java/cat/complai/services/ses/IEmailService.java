package cat.complai.services.ses;

import cat.complai.services.ses.models.StadisticsModel;

public interface IEmailService {
        void sendStadistics(String to, String subject, StadisticsModel body) throws Exception;

}
