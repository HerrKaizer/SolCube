package dev.suite.shell;

import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Shell-side half of dev.suite.Ipc (same HMAC-SHA256 handshake). */
final class IpcAuth {
    private IpcAuth() {}

    static String nonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    static String mac(String secret, String msg) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
        return hex(m.doFinal(msg.getBytes()));
    }

    static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
