package server.model;

public record RSAKeys(
        String privateKey,
        String publicKey,
        String certificate
) {
}
