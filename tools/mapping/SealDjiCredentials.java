import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Offline deployment tool. Input credentials never become APK plaintext or build logs. */
class SealDjiCredentials {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: java SealDjiCredentials.java PRIVATE_CREDENTIAL_JSON DEVICE_PUBLIC_PEM SEALED_OUTPUT_JSON");
        byte[] clear = Files.readAllBytes(Path.of(args[0]));
        if(clear.length>16*1024)throw new IllegalArgumentException("Credential input is too large.");
        String pem=Files.readString(Path.of(args[1])).replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
        byte[] encoded=Base64.getDecoder().decode(pem);
        PublicKey recipient=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        byte[] key=new byte[32],iv=new byte[12];SecureRandom random=new SecureRandom();random.nextBytes(key);random.nextBytes(iv);
        try {
            Cipher encrypt=Cipher.getInstance("AES/GCM/NoPadding");
            encrypt.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));encrypt.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext=encrypt.doFinal(clear);
            Cipher wrap=Cipher.getInstance("RSA/ECB/OAEPPadding");
            wrap.init(Cipher.ENCRYPT_MODE,recipient,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT));
            Base64.Encoder b64=Base64.getEncoder();
            String output="{\"type\":\"r2c_dji_device_provisioning\",\"version\":1,\"recipientSha256\":\""+id+"\",\"wrappedKey\":\""+b64.encodeToString(wrap.doFinal(key))+"\",\"iv\":\""+b64.encodeToString(iv)+"\",\"ciphertext\":\""+b64.encodeToString(ciphertext)+"\"}";
            Files.writeString(Path.of(args[2]),output);
        } finally {Arrays.fill(clear,(byte)0);Arrays.fill(key,(byte)0);}
        System.out.println("Protected device deployment bundle created; no credentials printed.");
    }
}
