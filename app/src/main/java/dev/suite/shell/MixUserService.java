package dev.suite.shell;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioPolicy;
import android.os.HandlerThread;
import android.os.Looper;

import dev.suite.ISolCubeService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * "Play together", run by Shizuku as a UserService ("dev.suite:mix").
 *
 * Android normally pauses the playing app whenever another app asks for audio focus. This
 * registers an external audio-focus policy (shell holds MODIFY_AUDIO_ROUTING), which puts this
 * process in charge of focus: every request is granted and nobody is told to stop, so all apps
 * keep playing. Exception: a phone/VoIP call or ringtone pauses the others until it ends.
 * When this process exits, Android drops the policy and normal focus rules come back.
 */
public class MixUserService extends ISolCubeService.Stub {
    private static final int GRANTED = 1;            // AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    private static final int GAIN = 1;               // AudioManager.AUDIOFOCUS_GAIN
    private static final int LOSS_TRANSIENT = -2;    // AudioManager.AUDIOFOCUS_LOSS_TRANSIENT

    private final Object lock = new Object();
    private final Map<String, AudioFocusInfo> holders = new LinkedHashMap<>();   // clientId -> info
    private final Set<String> calls = new LinkedHashSet<>();                      // call clients
    private final Set<String> pausedForCall = new LinkedHashSet<>();

    private Context ctx;
    private AudioManager am;
    private Object policy;          // android.media.audiopolicy.AudioPolicy (hidden)
    private HandlerThread thread;
    private volatile boolean registered;
    private volatile String error = "";
    private Method setResult, dispatch;

    public MixUserService() { this(null); }

    public MixUserService(Context given) {
        L.tag = "SolCube.mix";
        try {
            ctx = shellContext(given);
            am = ctx.getSystemService(AudioManager.class);
            setResult = AudioManager.class.getMethod("setFocusRequestResult",
                    AudioFocusInfo.class, int.class, AudioPolicy.class);
            dispatch = AudioManager.class.getMethod("dispatchAudioFocusChange",
                    AudioFocusInfo.class, int.class, AudioPolicy.class);
            register();
        } catch (Throwable t) {
            error = String.valueOf(t);
            L.e("start failed", t);
        }
    }

    /** A Context whose package is com.android.shell, matching this process's uid. */
    static Context shellContext(Context given) throws Exception {
        Context base = given;
        if (base == null) {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            base = (Context) at.getMethod("getSystemContext").invoke(thread);
        }
        if ("com.android.shell".equals(base.getPackageName())) return base;
        try {
            return base.createPackageContext("com.android.shell", 0);
        } catch (Throwable t) {
            L.e("shell context", t);
            return base;
        }
    }

    private void register() throws Exception {
        thread = new HandlerThread("mix-focus");
        thread.start();
        Class<?> builderCls = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object b = builderCls.getConstructor(Context.class).newInstance(ctx);
        builderCls.getMethod("setLooper", Looper.class).invoke(b, thread.getLooper());
        builderCls.getMethod("setAudioPolicyFocusListener", AudioPolicy.AudioPolicyFocusListener.class)
                .invoke(b, new Listener());
        builderCls.getMethod("setIsAudioFocusPolicy", boolean.class).invoke(b, true);
        policy = builderCls.getMethod("build").invoke(b);
        int r = (int) AudioManager.class.getMethod("registerAudioPolicy", AudioPolicy.class).invoke(am, policy);
        registered = r == 0;
        if (registered) L.i("focus policy registered — apps play together");
        else { error = "registerAudioPolicy returned " + r; L.i(error); }
    }

    private static int usage(AudioFocusInfo afi) {
        try {
            AudioAttributes a = (AudioAttributes) AudioFocusInfo.class.getMethod("getAttributes").invoke(afi);
            return a == null ? 0 : a.getUsage();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isCall(AudioFocusInfo afi) {
        int u = usage(afi);
        return u == AudioAttributes.USAGE_VOICE_COMMUNICATION
                || u == AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                || u == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING;
    }

    private void send(AudioFocusInfo afi, int change) {
        try { dispatch.invoke(am, afi, change, policy); }
        catch (Throwable t) { L.e("dispatch " + change + " to " + afi.getPackageName(), t); }
    }

    private final class Listener extends AudioPolicy.AudioPolicyFocusListener {
        @Override public void onAudioFocusRequest(AudioFocusInfo afi, int requestResult) {
            // answer first: the requesting app waits for this (Android gives up after ~200 ms)
            try { setResult.invoke(am, afi, GRANTED, policy); }
            catch (Throwable t) { L.e("grant " + afi.getPackageName(), t); }
            String id = afi.getClientId();
            boolean call = isCall(afi);
            synchronized (lock) {
                holders.put(id, afi);
                if (call && calls.add(id)) {
                    L.i("call/ringtone from " + afi.getPackageName() + " — pausing others");
                    for (Map.Entry<String, AudioFocusInfo> e : holders.entrySet()) {
                        if (calls.contains(e.getKey()) || !pausedForCall.add(e.getKey())) continue;
                        send(e.getValue(), LOSS_TRANSIENT);
                    }
                } else if (!call) {
                    L.i("granted " + afi.getPackageName() + " (usage " + usage(afi) + ")");
                    if (!calls.isEmpty() && pausedForCall.add(id)) send(afi, LOSS_TRANSIENT);   // mid-call
                }
            }
        }

        @Override public void onAudioFocusAbandon(AudioFocusInfo afi) {
            String id = afi.getClientId();
            synchronized (lock) {
                holders.remove(id);
                pausedForCall.remove(id);
                if (calls.remove(id) && calls.isEmpty()) {
                    L.i("call ended — resuming " + pausedForCall.size() + " app(s)");
                    for (String p : pausedForCall) {
                        AudioFocusInfo h = holders.get(p);
                        if (h != null) send(h, GAIN);
                    }
                    pausedForCall.clear();
                }
            }
        }
    }

    @Override public void destroy() {
        try {
            if (registered) AudioManager.class.getMethod("unregisterAudioPolicy", AudioPolicy.class).invoke(am, policy);
        } catch (Throwable t) {
            L.e("unregister", t);
        }
        L.i("focus policy removed — normal audio focus");
        System.exit(0);
    }

    @Override public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", registered);
            o.put("error", error);
            JSONArray apps = new JSONArray();
            synchronized (lock) {
                for (AudioFocusInfo a : holders.values()) apps.put(a.getPackageName());
            }
            o.put("apps", apps);
            o.put("log", new JSONArray(L.recent(20)));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override public void configure(String json) {}

    @Override public String command(String cmd) { return ""; }
}
