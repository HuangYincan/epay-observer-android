package dev.epay.observer.sample;

import android.app.Notification;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** SPEC-042: test-only, one historical child and first posted child, never a payment source. */
public final class ReceiptChatListener extends NotificationListenerService {
    private static final String SOURCE = "com.eg.android.AlipayGphone";
    private static final String SIGNER = "389b49f7832f53e9017923220aa85e14dfaa4886ecd7428818bf339543cf498a";
    private static final String STEM = "receipt-chat-comparison";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONArray rows = new JSONArray();
    private final byte[] salt = new byte[32];
    private ComparisonBudget budget;
    private boolean consumed;
    private boolean posted;
    private boolean terminal;

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        if (budget != null || consumed) return;
        File consent = file("consent");
        try {
            if (!consent.isFile() || consent.length() > 80 ||
                    !"receipt-chat-comparison-once-120s-max2-v1".equals(Files.readString(consent.toPath()).trim())) return;
            if (file("raw.json").exists() || file("raw.json.pending").exists() || file("status.json").exists() ||
                    file("status.json.pending").exists() ||
                    !new File(getNoBackupFilesDir(), "used-" + STEM + "-v1").createNewFile()) return;
            consumed = true;
            if (!consent.delete()) throw new IllegalStateException();
            new SecureRandom().nextBytes(salt);
            budget = new ComparisonBudget(SystemClock.elapsedRealtime());
            verifiedUid();
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) throw new IllegalStateException();
            for (StatusBarNotification sbn : active) {
                if (capture(sbn, false)) break;
                if (!budget.running(SystemClock.elapsedRealtime())) break;
            }
            tick();
        } catch (Exception failure) { finish("ERROR"); }
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (budget == null || !budget.running(SystemClock.elapsedRealtime()) || posted) return;
        try {
            if (capture(sbn, true)) { posted = true; finish("COMPLETE"); }
        } catch (Exception failure) { finish("ERROR"); }
    }
    @Override public void onListenerDisconnected() { finish("DISCONNECTED"); super.onListenerDisconnected(); }
    @Override public void onDestroy() { finish("STOPPED"); super.onDestroy(); }

    private void tick() {
        if (budget == null || !budget.running(SystemClock.elapsedRealtime())) { finish("EXPIRED"); return; }
        try { status("RUNNING"); } catch (Exception failure) { finish("ERROR"); return; }
        handler.postDelayed(this::tick, 500);
    }
    private boolean capture(StatusBarNotification sbn, boolean live) throws Exception {
        if (!SOURCE.equals(sbn.getPackageName()) || !SOURCE.equals(sbn.getOpPkg()) ||
                !Process.myUserHandle().equals(sbn.getUser())) return false;
        if (sbn.getUid() != verifiedUid()) throw new IllegalStateException();
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if (!budget.claim(live, SystemClock.elapsedRealtime())) return false;
        // Slots are consumed before any field access, including failed reads.
        JSONObject row = new JSONObject();
        row.put("kind", live ? "posted" : "historical");
        row.put("title", bounded(n.extras.get(Notification.EXTRA_TITLE)));
        row.put("text", bounded(n.extras.get(Notification.EXTRA_TEXT)));
        row.put("big_text", bounded(n.extras.get(Notification.EXTRA_BIG_TEXT)));
        row.put("flags", n.flags);
        String category = n.category;
        row.put("category", category == null ? "none" : Arrays.asList("msg", "email", "service", "status", "promo", "sys", "progress", "event", "reminder", "transport", "call", "alarm", "err", "recommendation", "social", "navigation", "workout", "location", "stopwatch", "missed_call").contains(category) ? category : "other");
        Object style = n.extras.get(Notification.EXTRA_TEMPLATE);
        String styleEnum = style == null ? "none" : "other";
        for (String known : new String[]{"BigTextStyle", "BigPictureStyle", "InboxStyle", "MessagingStyle", "MediaStyle", "DecoratedCustomViewStyle", "CallStyle"})
            if (("android.app.Notification$" + known).equals(style)) styleEnum = known;
        row.put("style", styleEnum);
        row.put("presentation_valid", clean(n.category) && (style == null || style instanceof CharSequence && clean(style.toString())) &&
                clean(n.getChannelId()) && clean(n.getGroup()));
        String channel = n.getChannelId();
        bounded(channel); bounded(n.getGroup()); // Same maximum as production extraction.
        row.put("channel_present", channel != null && !channel.isEmpty());
        row.put("group_present", n.getGroup() != null && !n.getGroup().isEmpty());
        if (channel == null) row.put("channel_fingerprint", JSONObject.NULL);
        else {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            row.put("channel_fingerprint", hex(md.digest(channel.getBytes(StandardCharsets.UTF_8))));
        }
        JSONObject extra = new JSONObject();
        for (String name : new String[]{Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT,
                Notification.EXTRA_TEXT_LINES, Notification.EXTRA_MESSAGES, Notification.EXTRA_HISTORIC_MESSAGES,
                Notification.EXTRA_TITLE_BIG}) extra.put(name, n.extras.get(name) != null);
        row.put("additional", extra);
        rows.put(row);
        return true;
    }
    private int verifiedUid() throws Exception {
        PackageInfo info = getPackageManager().getPackageInfo(SOURCE, PackageManager.GET_SIGNING_CERTIFICATES);
        if (info.getLongVersionCode() != 212310L || !"12.12.30.8000".equals(info.versionName) ||
                info.signingInfo == null || info.signingInfo.hasMultipleSigners() ||
                info.signingInfo.getApkContentsSigners().length != 1 ||
                !SIGNER.equals(hex(MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray()))))
            throw new IllegalStateException();
        return getPackageManager().getApplicationInfo(SOURCE, 0).uid;
    }
    private void finish(String phase) {
        if (!consumed || terminal) return;
        terminal = true;
        if (budget != null) budget.stop();
        handler.removeCallbacksAndMessages(null);
        try {
            atomic(file("raw.json"), rows.toString());
            status(phase);
        } catch (Exception ignored) { /* host must treat missing/invalid terminal metadata as failure */ }
        rows.remove(1); rows.remove(0);
        Arrays.fill(salt, (byte)0);
    }
    private void status(String phase) throws Exception {
        JSONObject status = new JSONObject().put("phase", phase).put("records", rows.length())
            .put("remaining_seconds", "RUNNING".equals(phase) ? budget.remaining(SystemClock.elapsedRealtime()) : 0);
        atomic(file("status.json"), status.toString());
    }
    private File file(String suffix) { return new File(getFilesDir(), STEM + "-" + suffix); }
    private static void atomic(File target, String value) throws Exception {
        File pending = new File(target.getParentFile(), target.getName() + ".pending");
        Files.writeString(pending.toPath(), value, StandardCharsets.UTF_8);
        Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static Object bounded(Object value) {
        if (value == null) return JSONObject.NULL;
        if (!(value instanceof CharSequence) || ((CharSequence)value).length() > 512) throw new IllegalArgumentException();
        return value.toString();
    }
    private static boolean clean(String value) {
        if (value == null) return true;
        if (value.length() > 512) return false;
        for (int i = 0; i < value.length(); i++) {
            int type = Character.getType(value.charAt(i));
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE ||
                    type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) return false;
        }
        return true;
    }
    private static String hex(byte[] values) {
        StringBuilder result = new StringBuilder();
        for (byte value : values) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
