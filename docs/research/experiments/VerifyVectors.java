// M0 offline byte-contract experiment only. No network, Android, or payment code.
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

class VerifyVectors {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var hex = HexFormat.of();
        int count = 0;
        for (String line; (line = input.readLine()) != null;) {
            var f = line.split("\t", -1);
            byte[] body = Base64.getDecoder().decode(f[2]);
            String digest = hex.formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            byte[] canonical = String.join("\n", "epay-observer-v1", f[3], f[4], f[5], f[6], f[7], digest)
                    .getBytes(StandardCharsets.UTF_8);
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hex.parseHex(f[1]), "HmacSHA256"));
            String signature = hex.formatHex(mac.doFinal(canonical));
            if (!digest.equals(f[8]) || !Base64.getEncoder().encodeToString(canonical).equals(f[9])
                    || !signature.equals(f[10])) throw new AssertionError(f[0]);
            count++;
        }
        if (count != 3) throw new AssertionError("Expected 3 vectors");
        System.out.println("Java JCA: 3/3 exact body hashes, signing inputs and HMAC values agree");
    }
}
