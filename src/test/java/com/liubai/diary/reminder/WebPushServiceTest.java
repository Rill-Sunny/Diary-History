package com.liubai.diary.reminder;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebPushServiceTest {
    @Test
    void reproducesRfc8291EncryptedPayload() throws Exception {
        var sender = new KeyPair(
                WebPushService.decodePublicKey(decode(
                        "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")),
                WebPushService.decodePrivateKey(decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")));
        byte[] encrypted = WebPushService.encrypt(
                decode("V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24"),
                decode("DGv6ra1nlYgDCS1FRnbzlw"),
                decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
                decode("BTBZMqHH6r4Tts7J_aSIgg"), sender);
        assertEquals("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIg"
                        + "Dll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_y"
                        + "l95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qu"
                        + "lcy4a-fN",
                Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted));
    }

    private static byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
}
