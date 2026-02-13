package cat.complai.http.dto;

public class HttpDto {
    private final String message;
    private final Integer statusCode;

    private final String method;
    private final String error;

    public HttpDto(String message, Integer statusCode, String method, String error) {
        this.message = message;
        this.statusCode = statusCode;
        this.method = method;
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public String getMethod() {
        return method;
    }

    public String getError() {
        return error;
    }
}
