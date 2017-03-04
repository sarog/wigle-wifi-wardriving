package net.wigle.wigleandroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.util.Calendar;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.security.auth.x500.X500Principal;

/**
 * Methods for enc/dec the API Token via the Android KeyStore if supported
 * Intended to improve secret cred storage in-place
 * Created by rksh on 3/2/17.
 */

public class TokenAccess {

    // identify WiGLE entry in the KeyStore
    public static final String KEYSTORE_WIGLE_CREDS_KEY_V0 = "WiGLEKeyOld";
    public static final String KEYSTORE_WIGLE_CREDS_KEY_V1 = "WiGLEKey";
    public static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    /**
     * test presence of a necessary API key
     * @param prefs
     * @return true if present, otherwise false
     */
    public static boolean hasApiToken(SharedPreferences prefs) {
        if (!prefs.getString(ListFragment.PREF_TOKEN, "").isEmpty()) return true;
        return false;
    }

    /**
     * test presence of a necessary API key, Keystore entry if applicable
     * @param prefs
     * @return true if present, otherwise false
     */
    public static boolean hasValidApiToken(SharedPreferences prefs) {
        if (!prefs.getString(ListFragment.PREF_TOKEN,"").isEmpty()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {

                try {
                    KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
                    keyStore.load(null);

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        if (keyStore.containsAlias(KEYSTORE_WIGLE_CREDS_KEY_V1)) {
                            //TODO: it would be best to test decrypt here, but makes this heavier
                            return true;
                        }
                    } else if  (keyStore.containsAlias(KEYSTORE_WIGLE_CREDS_KEY_V0)) {
                        return true;
                    }
                } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException e) {
                    MainActivity.error("[TOKEN] Error trying to test token existence: ", e);
                    return false;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * Set the appropriate API Token, stored with KeyStore crypto if suitable
     * @param prefs the shared preferences object in which to store the token
     * @param apiToken the token value to store
     * @return true if successful, otherwise false
     */
    public static boolean setApiToken(SharedPreferences prefs, String apiToken) {
        final SharedPreferences.Editor editor = prefs.edit();
        if (android.os.Build.VERSION.SDK_INT <
                android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
            //ALIBI: no crypto available here
            editor.putString(ListFragment.PREF_TOKEN, apiToken);
            return true;
        } else {
            try {
                byte[] cypherToken;

                String keyStr = KEYSTORE_WIGLE_CREDS_KEY_V0;
                if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    keyStr = KEYSTORE_WIGLE_CREDS_KEY_V1;
                }
                KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
                keyStore.load(null);

                KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry)
                        keyStore.getEntry(keyStr, null);

                if (null != privateKeyEntry) {
                    PublicKey publicKey =
                            privateKeyEntry.getCertificate().getPublicKey();
                    Cipher c = null;

                    if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
                    } else if (android.os.Build.VERSION.SDK_INT >=
                            android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
                        c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    }
                    if (null != c) {
                        c.init(Cipher.ENCRYPT_MODE, publicKey);
                        cypherToken = c.doFinal(apiToken.getBytes());
                        if (null != cypherToken) {
                            //TODO: use same prefskey? make a new one + clear old one?
                            editor.putString(ListFragment.PREF_TOKEN,
                                    Base64.encodeToString(cypherToken, Base64.DEFAULT));
                            editor.apply();
                            return true;
                        } else {
                            // ALIBI: DEBUG should be unreachable.
                            MainActivity.error("[TOKEN] ERROR: unreachable condition," +
                                    "cipherToken NULL.  APIv" +
                                    android.os.Build.VERSION.SDK_INT);
                        }
                    } else {
                        // ALIBI: DEBUG should be unreachable.
                        MainActivity.error("[TOKEN] ERROR: unreachable condition," +
                                "cipher NULL.  APIv" +
                                android.os.Build.VERSION.SDK_INT);
                    }
                } else {
                    // ALIBI: DEBUG should be unreachable.
                    MainActivity.error("[TOKEN] ERROR: unreachable condition," +
                            "privateKey Entry NULL. Key: " +
                            keyStr);
                }
            } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException |
                    IOException | UnrecoverableEntryException | NoSuchPaddingException |
                    InvalidKeyException | BadPaddingException | IllegalBlockSizeException ex) {
                MainActivity.error("[TOKEN] Failed to set token: ",ex);
                ex.printStackTrace();
            }
            return false;
        }
    }

    /**
     * get the get the API token independent of secure/insecure storage
     * @param prefs a SharedPreferences instance from which to retrieve the token
     * @return the String token or null if unavailable
     */
    public static String getApiToken(SharedPreferences prefs) {
        if (android.os.Build.VERSION.SDK_INT <
                android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
            //ALIBI: no crypto available here
            return prefs.getString(ListFragment.PREF_TOKEN, "");
        } else {
            try {
                KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
                keyStore.load(null);

                KeyStore.PrivateKeyEntry privateKeyEntry;

                // prefer v1 key, fall back to v0 key, nada as applicable
                int versionThreshold = android.os.Build.VERSION_CODES.M;
                if (keyStore.containsAlias(KEYSTORE_WIGLE_CREDS_KEY_V1)) {
                    privateKeyEntry = (KeyStore.PrivateKeyEntry)
                            keyStore.getEntry(KEYSTORE_WIGLE_CREDS_KEY_V1, null);
                } else if (keyStore.containsAlias(KEYSTORE_WIGLE_CREDS_KEY_V0)) {
                    privateKeyEntry = (KeyStore.PrivateKeyEntry)
                            keyStore.getEntry(KEYSTORE_WIGLE_CREDS_KEY_V0, null);
                    versionThreshold = Build.VERSION_CODES.JELLY_BEAN_MR2;
                } else {
                    MainActivity.warn("[TOKEN] Compatible build, but no key set: " +
                            android.os.Build.VERSION.SDK_INT + " - returning plaintext.");
                    return prefs.getString(ListFragment.PREF_TOKEN, "");
                }

                if (null != privateKeyEntry) {
                    String encodedCypherText = prefs.getString(ListFragment.PREF_TOKEN, "");
                    if (!encodedCypherText.isEmpty()) {
                        byte[] cypherText = Base64.decode(encodedCypherText, Base64.DEFAULT);
                        PrivateKey privateKey = privateKeyEntry.getPrivateKey();

                        Cipher c;
                        if (versionThreshold >= android.os.Build.VERSION_CODES.M) {
                            c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
                        } else {
                            c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                        }
                        c.init(Cipher.DECRYPT_MODE, privateKey);
                        String key = new String(c.doFinal(cypherText), "UTF-8");
                        return key;
                    } else {
                        MainActivity.error("[TOKEN] NULL encoded cyphertext on token decrypt.");
                        return null;
                    }
                } else {
                    MainActivity.error("[TOKEN] NULL Private Key on token decrypt.");
                    return null;
                }
            } catch (CertificateException | NoSuchAlgorithmException | IOException |
                    KeyStoreException | UnrecoverableEntryException | NoSuchPaddingException |
                    InvalidKeyException | BadPaddingException | IllegalBlockSizeException ex) {
                MainActivity.error("[TOKEN] Failed to get API Token: ", ex);
                return null;
            }
        }
    }

    /**
     * Initialization method - only intended for run at app onCreate
     * @param prefs preferences from root context
     * @param context root context
     * @return true if successful encryption takes place, else false.
     * @throws KeyStoreException
     * @throws CertificateException
     * @throws NoSuchAlgorithmException
     * @throws IOException
     * @throws NoSuchProviderException
     * @throws InvalidAlgorithmParameterException
     * @throws IllegalBlockSizeException
     * @throws InvalidKeyException
     * @throws BadPaddingException
     * @throws UnrecoverableEntryException
     * @throws NoSuchPaddingException
     */
    public static boolean checkMigrateKeystoreVersion(SharedPreferences prefs, Context context)
            throws KeyStoreException,
            CertificateException, NoSuchAlgorithmException, IOException, NoSuchProviderException,
            InvalidAlgorithmParameterException, IllegalBlockSizeException, InvalidKeyException,
            BadPaddingException, UnrecoverableEntryException, NoSuchPaddingException {

        if (prefs.getString(ListFragment.PREF_TOKEN, "").isEmpty()) {
            MainActivity.info("[TOKEN] No auth token stored - no preference migration possible.");
            return false;
        }

        if (android.os.Build.VERSION.SDK_INT <
                android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
            // no reliable keystore here
            MainActivity.info("[TOKEN] No KeyStore support - no preference migration possible.");
            return false;
        } else {

            MainActivity.info("[TOKEN] Using Android Keystore; attempting to generate a new key.");
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (keyStore.containsAlias(KEYSTORE_WIGLE_CREDS_KEY_V1)) {
                    MainActivity.info("Key present and up-to-date M - no action required.");
                    return false;
                }
                MainActivity.info("[TOKEN] Initializing SDKv23 Key...");
                String token = "";
                if (keyStore.containsAlias(KEYSTORE_WIGLE_CREDS_KEY_V0)) {
                    //ALIBI: fetch token with V0 key if it's stored that way
                    token = TokenAccess.getApiToken(prefs);
                }
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    KEYSTORE_WIGLE_CREDS_KEY_V1,
                    KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_ENCRYPT)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .build();

                kpg.initialize(spec);
                kpg.generateKeyPair();

                if (keyStore.containsAlias(KEYSTORE_WIGLE_CREDS_KEY_V0)) {
                    MainActivity.info("[TOKEN] Upgrading from v0->v1 token...");
                    if ((null == token) || token.isEmpty()) return false;
                    keyStore.deleteEntry(KEYSTORE_WIGLE_CREDS_KEY_V0);
                } else {
                    token = prefs.getString(ListFragment.PREF_TOKEN,"");
                    MainActivity.info("[TOKEN] Encrypting token at v1...");
                    if ((null == token) || token.isEmpty()) return false;
                }
                if (TokenAccess.setApiToken(prefs, token)) {
                    MainActivity.info("[TOKEN] ...token set at v1.");
                    return true;
                } else {
                    MainActivity.error("[TOKEN] Failed token update.");
                }
            } else if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                if (keyStore.containsAlias(KEYSTORE_WIGLE_CREDS_KEY_V0)) {
                    MainActivity.info(
                            "[TOKEN] Key present and up-to-date JB-MR2 - no action required.");
                    return false;
                }
                MainActivity.info("[TOKEN] Initializing SDKv18 Key...");
                Calendar notBefore = Calendar.getInstance();
                Calendar notAfter = Calendar.getInstance();
                notAfter.add(Calendar.YEAR, 3);
                KeyPairGeneratorSpec spec = null;
                spec = new KeyPairGeneratorSpec.Builder(context)
                        .setAlias(KEYSTORE_WIGLE_CREDS_KEY_V0)
                        // TODO: for some reason, type/size only supported >= SDKv19
                        //.setKeyType(KeyProperties.KEY_ALGORITHM_RSA)
                        //.setKeySize(4096)
                        .setSubject(new X500Principal("CN=wigle"))
                        .setSerialNumber(BigInteger.ONE)
                        .setStartDate(notBefore.getTime())
                        //TODO: does endDate for the generation cert => key expiration?
                        .setEndDate(notAfter.getTime())
                        .build();

                kpg.initialize(spec);
                kpg.generateKeyPair();

                String token = prefs.getString(ListFragment.PREF_TOKEN,"");
                if (token.isEmpty()) return false;
                MainActivity.info("[TOKEN] Encrypting token at v0...");

                if (TokenAccess.setApiToken(prefs, token)) {
                    MainActivity.info("[TOKEN] ...token set at v0.");
                    return true;
                } else {
                    MainActivity.error("[TOKEN] ...Failed token encryption.");
                }
            }
        }
        return false;
    }
}
