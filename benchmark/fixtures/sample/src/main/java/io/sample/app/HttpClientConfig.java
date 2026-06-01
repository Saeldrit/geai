package io.sample.app;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Builds the outbound HTTP client used by services. Benchmark fixture — intentionally unsafe so a
 * "find and fix the TLS config" task has a real target.
 */
public final class HttpClientConfig {

    private HttpClientConfig() {
    }

    public static HttpClient client() {
        try {
            // BUG: trusts EVERY server certificate (MITM-vulnerable) and sets no connect timeout.
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder().sslContext(ctx).build();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build http client", e);
        }
    }
}
