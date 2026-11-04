package dev.tinyvault;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class Check {
    private static final SecretKeySpec KEY = new SecretKeySpec(random(32), "AES");
    private static final SecretKeySpec OTHER = new SecretKeySpec(random(32), "AES");

    public static void main(String[] args) throws Exception {
        byte[] text = utf8("attack at dawn");
        byte[] file = Vault.seal(sealer(KEY), "plan", text);
        check(Arrays.equals(text, new byte[14]), "sealing zeroes its input");
        check(Vault.title(file).equals("plan"), "the title is read back from the header");
        check(Arrays.equals(open(KEY, file), utf8("attack at dawn")), "the right key opens");
        check(!Arrays.equals(Vault.seal(sealer(KEY), "plan", utf8("attack at dawn")), file), "sealing twice differs: a fresh nonce");
        check(open(KEY, Vault.seal(sealer(KEY), "empty", new byte[0])).length == 0, "an empty note round-trips");
        refused(OTHER, file, "another key");
        for (int at : new int[] {0, 1, 13, 15, 19, file.length - 1}) {
            byte[] bad = file.clone();
            bad[at] ^= 1;
            refused(KEY, bad, "a changed byte at " + at);
        }
        refused(KEY, Arrays.copyOf(file, file.length - 1), "a truncated note");

        char[] mixed = "kéy 🔑 \ud83d".toCharArray();
        byte[] bytes = Vault.bytes(mixed);
        check(Arrays.equals(mixed, new char[mixed.length]), "encoding zeroes the chars");
        check(new String(Vault.chars(bytes)).equals("kéy 🔑 ?"), "UTF-8 round trip, lone surrogate replaced");

        String syllable = "[bcdfghjkmnprstvwxz][aeiou][bcdfghjkmnprstvwxz]", word = "(" + syllable + "){2}[2-9]?";
        boolean shaped = true, even = true;
        int[] numbered = new int[3], seen = new int[128];
        for (int i = 0; i < 3000; i++) {
            String password = new String(Vault.password());
            shaped &= password.length() == 21 && password.toLowerCase().matches(word + "-" + word + "-" + word)
                    && password.chars().filter(Character::isUpperCase).count() == 1
                    && password.chars().filter(Character::isDigit).count() == 1;
            numbered[password.indexOf('-') == 7 ? 0 : password.lastIndexOf('-') == 14 ? 1 : 2]++;
            for (char c : password.toLowerCase().toCharArray()) seen[c]++;
        }
        check(shaped, "3000 generated passwords have the promised shape, e.g. " + new String(Vault.password()));
        check(numbered[0] > 800 && numbered[1] > 800 && numbered[2] > 800, "the digit lands on each word about equally");
        for (char c : "bcdfghjkmnprstvwxz".toCharArray()) even &= Math.abs(seen[c] - 2000) < 6 * Math.sqrt(2000);
        for (char c : "aeiou".toCharArray()) even &= Math.abs(seen[c] - 3600) < 6 * Math.sqrt(3600);
        check(even, "every letter is drawn about equally often");
        double bits = 6 * Math.log(18 * 5 * 18) / Math.log(2) + Math.log(18) / Math.log(2) + Math.log(8 * 3) / Math.log(2);
        check(bits > 72, "a generated password has " + Math.round(bits * 10) / 10.0 + " bits of entropy");
        System.out.println("ok    Check");
    }

    private static Cipher sealer(SecretKeySpec key) throws Exception {
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, key);
        return aes;
    }

    private static byte[] open(SecretKeySpec key, byte[] file) throws Exception {
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, key, Vault.nonce(file));
        return Vault.open(aes, file);
    }

    private static void refused(SecretKeySpec key, byte[] file, String what) throws Exception {
        try {
            open(key, file);
        } catch (AEADBadTagException | IllegalArgumentException refusal) {
            System.out.println("ok    " + what + " is refused: " + refusal.getMessage());
            return;
        }
        throw new AssertionError(what + " was accepted");
    }

    private static void check(boolean holds, String what) {
        if (!holds) throw new AssertionError(what);
        System.out.println("ok    " + what);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
