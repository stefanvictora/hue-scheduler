package at.sv.hue.api.hue;

import okhttp3.OkHttpClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

public class TestHttpsClientFactory {

    public static OkHttpClient createTrustAllHttpsClient() throws Exception {
        X509TrustManager trustManager = createTrustManager();
        SSLContext sslContext = createSSLContext(trustManager);
        return new OkHttpClient.Builder().sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                                         .hostnameVerifier((hostname, session) -> true)
                                         .build();
    }

    private static X509TrustManager createTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {

            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {

            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static SSLContext createSSLContext(X509TrustManager trustManager) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustManager}, null);
        return context;
    }
}
