package cat.complai.services.ses;

import cat.complai.exceptions.ses.CloudWatchLogsException;

public interface IEmailService {
        void sendStadistics(String to, String subject) throws CloudWatchLogsException;

        /**
         * Sends a statistics report email with city-specific prediction.
         *
         * @param to      The recipient email address
         * @param subject The email subject line
         * @param cityId  The city identifier for prediction (null for all cities)
         * @throws CloudWatchLogsException if CloudWatch is unavailable
         */
        void sendStadistics(String to, String subject, String cityId) throws CloudWatchLogsException;
}
