package at.sv.hue.api;

import okhttp3.OkHttpClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

public class HueApiHttpsClientFactory {

    private static final String HUE_BRIDGE_CERTIFICATE = "/hue-bridge-certificate.pem";

    public static OkHttpClient createHttpsClient(String bridgeIp) throws Exception {
        X509TrustManager trustManager = createTrustManager();
        SSLContext sslContext = createSSLContext(trustManager);
        return new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                .hostnameVerifier((hostname, session) -> hostname.equals(bridgeIp))
                .build();
    }

    private static X509TrustManager createTrustManager() throws Exception {
        Certificate certificate = loadCertificate();
        KeyStore keyStore = createKeyStore(certificate);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);
        return ((X509TrustManager) trustManagerFactory.getTrustManagers()[0]);
    }

    private static Certificate loadCertificate() throws Exception {
        try (InputStream certInputStream = LightStateEventTrackerImpl.class.getResourceAsStream(HUE_BRIDGE_CERTIFICATE)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(certInputStream);
        }
    }

    private static KeyStore createKeyStore(Certificate certificate) throws Exception {
        KeyStore keystore = createEmptyKeyStore();
        keystore.setCertificateEntry("HueCertificate", certificate);
        return keystore;
    }

    private static KeyStore createEmptyKeyStore() throws Exception {
        KeyStore keystore = KeyStore.getInstance("JKS");
        keystore.load(null, new char[0]);
        return keystore;
    }

    private static SSLContext createSSLContext(X509TrustManager trustManager) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustManager}, null);
        return context;
    }
}
