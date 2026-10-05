package server.model;

public record RSAKeys(
        String privateKey,
        String certificate
) {
}
