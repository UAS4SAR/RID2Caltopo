import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.regex.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Independent decrypt/tamper/wrong-recipient checks using synthetic registration values. */
class TestSealedDjiProvisioning {
    static String field(String text,String name) {
        Matcher match=Pattern.compile("\""+name+"\":\"([^\"]+)\"").matcher(text);
        if(!match.find())throw new AssertionError("Missing envelope field");return match.group(1);
    }
    static byte[] decrypt(String envelope,PrivateKey recipient,String aad) throws Exception {
        Base64.Decoder b64=Base64.getDecoder();
        Cipher unwrap=Cipher.getInstance("RSA/ECB/OAEPPadding");
        unwrap.init(Cipher.DECRYPT_MODE,recipient,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT));
        byte[] key=unwrap.doFinal(b64.decode(field(envelope,"wrappedKey")));
        Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,b64.decode(field(envelope,"iv"))));
        aes.updateAAD(aad.getBytes(StandardCharsets.UTF_8));return aes.doFinal(b64.decode(field(envelope,"ciphertext")));
    }
    public static void main(String[] args) throws Exception {
        Path work=Files.createTempDirectory("r2c-sealed-deployment-test");
        try {
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair pair=generator.generateKeyPair();
            Path clear=work.resolve("synthetic.json"),pub=work.resolve("public.pem"),sealed=work.resolve("sealed.json");
            String input="{\"appId\":\"123456\",\"appKey\":\"synthetic-registration-key\",\"appLicense\":\"synthetic-registration-license-for-test-only\"}";
            Files.writeString(clear,input);Files.writeString(pub,"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n");
            Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"tools/mapping/SealDjiCredentials.java",clear.toString(),pub.toString(),sealed.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if(process.waitFor()!=0)throw new AssertionError("Provisioning tool failed");
            String envelope=Files.readString(sealed),aad=field(envelope,"recipientSha256");
            if(!input.equals(new String(decrypt(envelope,pair.getPrivate(),aad),StandardCharsets.UTF_8)))throw new AssertionError("Registration did not round-trip");
            if(envelope.contains("synthetic-registration"))throw new AssertionError("Envelope contains plaintext credentials");
            try {decrypt(envelope,generator.generateKeyPair().getPrivate(),aad);throw new AssertionError("Wrong tablet could decrypt");}catch(BadPaddingException expected){}
            try {decrypt(envelope,pair.getPrivate(),"wrong-recipient");throw new AssertionError("Tampered recipient binding accepted");}catch(AEADBadTagException expected){}
            System.out.println("Protected deployment: recipient decryption, plaintext exclusion, wrong-device rejection, and authenticated binding passed.");
        } finally {
            try(var files=Files.walk(work)){for(Path item:files.sorted(Comparator.reverseOrder()).toList())Files.delete(item);}
        }
    }
}
