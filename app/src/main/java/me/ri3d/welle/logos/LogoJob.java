package me.ri3d.welle.logos;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import me.ri3d.welle.core.Station;
import me.ri3d.welle.web.Net;

/**
 * Background run that fetches logos for a station list: RadioDNS for DAB stations, the
 * catalogue icon for web stations. State is read on the main thread by the logos screen.
 */
public final class LogoJob {
    public static final int IDLE = 0;
    public static final int RUNNING = 1;
    public static final int DONE = 2;
    public static final int NO_NETWORK = 3;

    public interface Listener {
        void onLogoJob();
    }

    public int state = IDLE;
    public int total;
    public int done;
    public int found;
    public final HashSet<String> waiting = new HashSet<String>();
    public final HashSet<String> notFound = new HashSet<String>();
    public Listener listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    public void start(final Context context, List<Station> stations, final LogoStore store) {
        if (state == RUNNING) return;
        waiting.clear();
        notFound.clear();
        done = 0;
        found = 0;
        final ArrayList<Station> todo = new ArrayList<Station>();
        for (Station s : stations) {
            if (store.source(s.id) == LogoStore.MANUAL) continue; // manual choices are never overwritten
            todo.add(s);
            waiting.add(s.id);
        }
        total = todo.size();
        if (!online(context)) {
            waiting.clear();
            state = NO_NETWORK;
            notifyListener();
            return;
        }
        state = RUNNING;
        cancelled = false;
        notifyListener();
        final Context app = context.getApplicationContext();
        new Thread("logo-job") {
            @Override
            public void run() {
                List<InetAddress> dns = dnsServers(app);
                Map<String, Map<String, String>> siCache = new HashMap<String, Map<String, String>>();
                for (final Station s : todo) {
                    if (cancelled) break;
                    boolean ok = false;
                    try {
                        byte[] image = s.isDab() ? RadioDns.fetchLogo(s, dns, siCache)
                                : s.logoUrl != null ? Net.get(s.logoUrl, 1024 * 1024) : null;
                        if (image != null) {
                            store.put(s.id, image, LogoStore.AUTO);
                            ok = true;
                        }
                    } catch (Exception e) {
                        // No logo for this one: unreachable server, broken image, no RadioDNS entry.
                        android.util.Log.d("WelleLogos", s.id + ": " + e);
                    }
                    final boolean gotIt = ok;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            waiting.remove(s.id);
                            done++;
                            if (gotIt) found++;
                            else if (!store.has(s.id)) notFound.add(s.id);
                            if (listener != null) listener.onLogoJob();
                        }
                    });
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        waiting.clear();
                        state = DONE;
                        if (listener != null) listener.onLogoJob();
                    }
                });
            }
        }.start();
    }

    public void cancel() {
        cancelled = true;
    }

    private void notifyListener() {
        if (listener != null) listener.onLogoJob();
    }

    @SuppressWarnings("deprecation") // NetworkInfo is the only connectivity API on Android 4.x
    public static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true; // cannot tell: let the request itself decide
        }
    }

    /**
     * The network's own resolvers. Android 6+ exposes them through LinkProperties, older
     * versions through the net.dns* system properties. Only if neither yields one is the
     * public Quad9 resolver (9.9.9.9) used.
     */
    @SuppressLint("PrivateApi")
    static List<InetAddress> dnsServers(Context c) {
        ArrayList<InetAddress> out = new ArrayList<InetAddress>();
        if (Build.VERSION.SDK_INT >= 23) addLinkDns(c, out);
        if (out.isEmpty()) {
            try {
                java.lang.reflect.Method get = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
                for (String key : new String[]{"net.dns1", "net.dns2"}) {
                    String v = (String) get.invoke(null, key);
                    if (v != null && !v.isEmpty()) out.add(InetAddress.getByName(v));
                }
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) {
            try {
                out.add(InetAddress.getByAddress(new byte[]{9, 9, 9, 9}));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    @TargetApi(23)
    private static void addLinkDns(Context c, List<InetAddress> out) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) out.addAll(lp.getDnsServers());
        } catch (Exception ignored) {
        }
    }
}
