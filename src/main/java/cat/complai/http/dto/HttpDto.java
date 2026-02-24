package cat.complai.http.dto;

public record HttpDto(String message, Integer statusCode, String method, String error) {
}
