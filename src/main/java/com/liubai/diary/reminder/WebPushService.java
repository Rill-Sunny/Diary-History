package com.liubai.diary.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liubai.diary.config.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WebPushService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String JWT_HEADER = base64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
    private static final ECGenParameterSpec CURVE = new ECGenParameterSpec("secp256r1");
    private static final byte[] PAYLOAD = "{\"title\":\"留白日记\",\"body\":\"留一点时间给今天。\",\"url\":\"/\"}".getBytes(StandardCharsets.UTF_8);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final Clock clock;
    private final List<String> allowedHosts;
    private final String publicKey;
    private final String subject;
    private final PrivateKey privateKey;
    private final String configuration;

    public WebPushService(ObjectMapper json, Clock clock,
                          @Value("${app.push.public-key:}") String publicKey,
                          @Value("${app.push.private-key:}") String privateKey,
                          @Value("${app.push.subject:}") String subject,
                          @Value("${app.push.allowed-hosts:fcm.googleapis.com,android.googleapis.com,push.services.mozilla.com,push.apple.com,notify.windows.com}") String allowedHosts) {
        this.json = json;
        this.clock = clock;
        this.publicKey = publicKey.strip();
        this.subject = subject.strip();
        this.allowedHosts = Arrays.stream(allowedHosts.toLowerCase(Locale.ROOT).split(","))
                .map(String::strip).filter(host -> !host.isBlank()).toList();
        PrivateKey key = null;
        String status = "unconfigured";
        if (!(publicKey.isBlank() && privateKey.isBlank() && subject.isBlank())) {
            try {
                key = validateVapidPair(this.publicKey, privateKey.strip());
                URI contact = URI.create(this.subject);
                if (!("mailto".equals(contact.getScheme()) && contact.getSchemeSpecificPart().contains("@"))
                        && !("https".equals(contact.getScheme()) && contact.getHost() != null))
                    throw new IllegalArgumentException("Invalid VAPID subject");
                status = "configured";
            } catch (GeneralSecurityException | IllegalArgumentException exception) {
                key = null;
                status = "invalid";
            }
        }
        this.privateKey = key;
        this.configuration = status;
    }

    public String configuration() { return configuration; }
    public String publicKey() { return privateKey == null ? null : publicKey; }

    public void validateSubscription(String endpoint, String p256dh, String auth) {
        validateEndpoint(endpoint);
        try {
            decodePublicKey(DECODER.decode(p256dh));
            if (DECODER.decode(auth).length != 16) throw new IllegalArgumentException("Invalid push auth secret");
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw ApiException.badRequest("浏览器提供了无效的推送密钥，请重新订阅。");
        }
    }

    public URI validateEndpoint(String endpoint) {
        URI target;
        try {
            target = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("浏览器提供了无效的推送地址。");
        }
        String host = target.getHost();
        if (!"https".equalsIgnoreCase(target.getScheme()) || host == null || target.getUserInfo() != null
                || target.getFragment() != null || (target.getPort() != -1 && target.getPort() != 443)
                || allowedHosts.stream().noneMatch(domain -> host.equalsIgnoreCase(domain)
                    || host.toLowerCase(Locale.ROOT).endsWith("." + domain))) {
            throw ApiException.badRequest("此浏览器使用的推送服务未获服务器配置支持。");
        }
        return target;
    }

    int send(String endpoint, String recipient, String auth) throws GeneralSecurityException, IOException, InterruptedException {
        URI target = validateEndpoint(endpoint);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(CURVE);
        KeyPair ephemeral = generator.generateKeyPair();
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] body = encrypt(PAYLOAD, salt, DECODER.decode(recipient), DECODER.decode(auth), ephemeral);
        String aud = "https://" + target.getHost().toLowerCase(Locale.ROOT);
        String claims = base64(json.writeValueAsBytes(Map.of(
                "aud", aud, "exp", clock.instant().plus(Duration.ofHours(12)).getEpochSecond(), "sub", subject)));
        String signingInput = JWT_HEADER + "." + claims;
        String jwt = signingInput + "." + base64(sign(privateKey, signingInput.getBytes(StandardCharsets.US_ASCII)));
        var request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(6))
                .header("Authorization", "vapid t=" + jwt + ", k=" + publicKey)
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", "3600")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    // RFC 8291 single-record aes128gcm payload, including the RFC 8188 header.
    static byte[] encrypt(byte[] payload, byte[] salt, byte[] recipient, byte[] auth, KeyPair ephemeral)
            throws GeneralSecurityException {
        byte[] sender = encodePublicKey((ECPublicKey) ephemeral.getPublic());
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ephemeral.getPrivate());
        agreement.doPhase(decodePublicKey(recipient), true);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), recipient, sender);
        byte[] ikm = expand(mac(auth, agreement.generateSecret()), keyInfo, 32);
        byte[] prk = mac(salt, ikm);
        byte[] key = expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(concat(payload, new byte[]{2}));
        return ByteBuffer.allocate(21 + sender.length + ciphertext.length)
                .put(salt).putInt(4096).put((byte) sender.length).put(sender).put(ciphertext).array();
    }

    private static byte[] mac(byte[] key, byte[] value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }

    private static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        return Arrays.copyOf(mac(prk, concat(info, new byte[]{1})), length);
    }

    private static byte[] concat(byte[]... parts) {
        ByteBuffer bytes = ByteBuffer.allocate(Arrays.stream(parts).mapToInt(part -> part.length).sum());
        for (byte[] part : parts) bytes.put(part);
        return bytes.array();
    }

    static ECPublicKey decodePublicKey(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 65 || raw[0] != 4) throw new InvalidKeyException("Invalid P-256 point");
        var x = new BigInteger(1, raw, 1, 32);
        var y = new BigInteger(1, raw, 33, 32);
        ECParameterSpec parameters = curveParameters();
        var field = (ECFieldFp) parameters.getCurve().getField();
        var prime = field.getP();
        var curve = parameters.getCurve();
        if (x.compareTo(prime) >= 0 || y.compareTo(prime) >= 0
                || !y.modPow(BigInteger.TWO, prime).equals(x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(prime)))
            throw new InvalidKeyException("P-256 point is not on the curve");
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), parameters));
    }

    static byte[] encodePublicKey(ECPublicKey key) {
        return concat(new byte[]{4}, fixedSize(key.getW().getAffineX()), fixedSize(key.getW().getAffineY()));
    }

    static byte[] fixedSize(BigInteger integer) {
        byte[] source = integer.toByteArray();
        byte[] output = new byte[32];
        int copied = Math.min(source.length, 32);
        System.arraycopy(source, source.length - copied, output, 32 - copied, copied);
        return output;
    }

    static PrivateKey decodePrivateKey(byte[] raw) throws GeneralSecurityException {
        ECParameterSpec parameters = curveParameters();
        var scalar = new BigInteger(1, raw);
        if (raw.length != 32 || scalar.signum() == 0 || scalar.compareTo(parameters.getOrder()) >= 0)
            throw new InvalidKeyException("Invalid P-256 private key");
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(scalar, parameters));
    }

    private static ECParameterSpec curveParameters() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(CURVE);
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    private static PrivateKey validateVapidPair(String pub, String priv) throws GeneralSecurityException {
        PrivateKey key = decodePrivateKey(DECODER.decode(priv));
        var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(decodePublicKey(DECODER.decode(pub)));
        verifier.update(new byte[]{1});
        if (!verifier.verify(sign(key, new byte[]{1}))) throw new InvalidKeyException("Mismatched VAPID keys");
        return key;
    }

    private static byte[] sign(PrivateKey privateKey, byte[] input) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(privateKey);
        signer.update(input);
        return signer.sign();
    }

    private static String base64(byte[] bytes) { return ENCODER.encodeToString(bytes); }
}
