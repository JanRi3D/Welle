package me.ri3d.welle.web;

import android.content.Context;
import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import me.ri3d.welle.core.Io;

/**
 * HTTP(S) access with the limits a head unit needs: timeouts, a redirect cap, size caps.
 *
 * TLS on Android 4.x has three problems, all solved here without weakening validation:
 *  - the system TLS library has no AEAD cipher suites and no TLS 1.3, so servers that only
 *    allow modern suites refuse the handshake. Below Android 5 the bundled Conscrypt
 *    provider (Google's BoringSSL-based TLS stack) does the handshake instead;
 *  - TLS 1.1/1.2 are switched off by default before Android 5, so every socket gets all
 *    supported protocols except SSLv3 enabled (this also covers a missing Conscrypt);
 *  - the system root store is frozen at the firmware date (2013 on the target unit), so a
 *    chain the system store rejects is checked a second time against a bundled copy of the
 *    OpenJDK root store (assets/cacerts.pem). Hostname verification is the platform default.
 */
public final class Net {
    private Net() { }

    public static final String USER_AGENT = "Welle/1.0 (Android DAB and web radio)";
    private static final int MAX_REDIRECTS = 5;

    @android.annotation.SuppressLint("StaticFieldLeak") // the application context, which lives as long as the process
    private static Context sApp;
    private static SSLSocketFactory sFactory;

    public static void init(Context app) {
        sApp = app.getApplicationContext();
    }

    /**
     * Opens a GET request and follows redirects, including between http and https.
     * A response code of -1 is passed through: old Shoutcast servers answer "ICY 200 OK",
     * which Android 4.x reports as -1 while the body is readable.
     */
    public static HttpURLConnection open(String url, Map<String, String> headers, int readTimeoutMs) throws IOException {
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL u = new URL(url);
            String protocol = u.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) throw new IOException("unsupported scheme " + protocol);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            if (c instanceof HttpsURLConnection) ((HttpsURLConnection) c).setSSLSocketFactory(factory());
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestProperty("User-Agent", USER_AGENT);
            if (u.getUserInfo() != null) {
                c.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(u.getUserInfo().getBytes("UTF-8"), Base64.NO_WRAP));
            }
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            }
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) throw new IOException("redirect without target");
                url = new URL(u, location).toString();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            return c;
        }
        throw new IOException("too many redirects");
    }

    /** Fetches a small resource completely; fails if it exceeds {@code max} bytes. */
    public static byte[] get(String url, int max) throws IOException {
        HttpURLConnection c = open(url, null, 15000);
        try {
            InputStream in = c.getInputStream();
            try {
                return Io.readAll(in, max);
            } finally {
                Io.close(in);
            }
        } finally {
            c.disconnect();
        }
    }

    /** URL without credentials and query, for log lines and error messages. */
    public static String redact(String url) {
        if (url == null) return "";
        String s = url.replaceFirst("://[^/@]*@", "://");
        int q = s.indexOf('?');
        return q < 0 ? s : s.substring(0, q);
    }

    // The custom trust manager only ever delegates to two platform TrustManagerImpl
    // instances (system roots, then bundled roots); it never accepts a chain by itself.
    @android.annotation.SuppressLint("CustomX509TrustManager")
    private static synchronized SSLSocketFactory factory() throws IOException {
        if (sFactory != null) return sFactory;
        try {
            final X509TrustManager system = trustManager(null);
            final X509TrustManager bundled = trustManager(bundledRoots());
            TrustManager both = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    system.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        system.checkServerTrusted(chain, authType);
                    } catch (CertificateException e) {
                        if (bundled == null) throw e;
                        bundled.checkServerTrusted(chain, authType);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return system.getAcceptedIssuers();
                }
            };
            SSLContext ctx = null;
            if (android.os.Build.VERSION.SDK_INT < 21) {
                try {
                    ctx = SSLContext.getInstance("TLS", org.conscrypt.Conscrypt.newProvider());
                } catch (Throwable t) {
                    // Native library missing for this ABI: the platform stack still works for many servers.
                    android.util.Log.w("WelleNet", "Conscrypt unavailable, using platform TLS: " + t);
                }
            }
            if (ctx == null) ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{both}, null);
            sFactory = new AllProtocols(ctx.getSocketFactory());
            return sFactory;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("TLS setup failed: " + e);
        }
    }

    private static X509TrustManager trustManager(KeyStore roots) throws Exception {
        // A null key store selects the platform's own root store.
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(roots);
        for (TrustManager t : f.getTrustManagers()) if (t instanceof X509TrustManager) return (X509TrustManager) t;
        throw new IOException("no X509 trust manager");
    }

    private static KeyStore bundledRoots() throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        if (sApp == null) return ks;
        InputStream in = sApp.getAssets().open("cacerts.pem");
        try {
            int n = 0;
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                ks.setCertificateEntry("root" + n++, c);
            }
        } finally {
            Io.close(in);
        }
        return ks;
    }

    /** Enables every protocol version the device implements, minus SSLv3. */
    private static final class AllProtocols extends SSLSocketFactory {
        private final SSLSocketFactory d;

        AllProtocols(SSLSocketFactory delegate) {
            d = delegate;
        }

        private Socket tune(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                ArrayList<String> on = new ArrayList<String>();
                for (String p : ssl.getSupportedProtocols()) if (!p.startsWith("SSL")) on.add(p);
                if (!on.isEmpty()) ssl.setEnabledProtocols(on.toArray(new String[on.size()]));
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return tune(d.createSocket()); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return tune(d.createSocket(s, host, port, autoClose)); }
        @Override public Socket createSocket(String host, int port) throws IOException { return tune(d.createSocket(host, port)); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return tune(d.createSocket(host, port, local, localPort)); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return tune(d.createSocket(host, port)); }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException { return tune(d.createSocket(address, port, local, localPort)); }
    }
}
