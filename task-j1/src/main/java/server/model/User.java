package server.model;

public record User(
        String userName,
        RSAKeys keys
) {
}
