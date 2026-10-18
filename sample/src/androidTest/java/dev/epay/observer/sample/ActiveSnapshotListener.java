package dev.epay.observer.sample;

import android.app.Notification;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

/** Test APK only. Explicit one-shot snapshot; posted callbacks never access extras. */
public final class ActiveSnapshotListener extends NotificationListenerService {
    private static final String PACKAGE = "com.eg.android.AlipayGphone";
    private static final String SIGNER = "389b49f7832f53e9017923220aa85e14dfaa4886ecd7428818bf339543cf498a";
    private enum Scope {
        ORIGINAL("active-snapshot", false),
        RECOVERY("active-snapshot-recovery", false),
        CLASSIFICATION("active-snapshot-classification", true),
        FIELD_ROLES("active-snapshot-field-roles", true);

        final String prefix;
        final boolean includeMetadata;
        Scope(String prefix, boolean includeMetadata) {
            this.prefix = prefix;
            this.includeMetadata = includeMetadata;
        }
        String consent() { return prefix + "-once-max2-v1"; }
        String marker() { return "used-" + prefix + "-max2-v1"; }
    }
    private static final Object LOCK = new Object();

    @Override public void onNotificationPosted(StatusBarNotification notification) {
        // Deliberately no posted-event handling, fields or counters.
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        synchronized (LOCK) {
            Scope scope = null;
            for (Scope candidate : Scope.values()) {
                if (new File(getFilesDir(), candidate.prefix + "-consent").isFile()) {
                    if (scope != null) return; // Conflicting scopes never read fields.
                    scope = candidate;
                }
            }
            if (scope == null) return;
            String prefix = scope.prefix;
            File consent = new File(getFilesDir(), prefix + "-consent");
            File used = new File(getNoBackupFilesDir(), scope.marker());
            File raw = new File(getFilesDir(), prefix + "-raw.json");
            File status = new File(getFilesDir(), prefix + "-status.json");
            JSONObject report = new JSONObject();
            int read = 0;
            boolean consumed = false;
            try {
                if (!consent.isFile() || consent.length() > 80 ||
                        !scope.consent().equals(new String(Files.readAllBytes(consent.toPath()), StandardCharsets.UTF_8).trim())) return;
                for (Scope previous : Scope.values()) {
                    if (previous == scope) break;
                    if (!previousAttemptComplete(previous.prefix, previous.marker())) return;
                }
                // Refuse pending raw data; never overwrite an earlier attempt.
                if (raw.exists() || status.exists() || !used.createNewFile()) return;
                consumed = true;
                if (!consent.delete()) throw new IllegalStateException("consent_cleanup");
                PackageInfo info = getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.getLongVersionCode() != 212310L || !"12.12.30.8000".equals(info.versionName) ||
                        info.signingInfo == null || info.signingInfo.hasMultipleSigners() ||
                        info.signingInfo.getApkContentsSigners().length != 1) throw new IllegalStateException("source_version");
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
                if (!SIGNER.equals(hex.toString())) throw new IllegalStateException("source_signer");
                int uid = getPackageManager().getApplicationInfo(PACKAGE, 0).uid;
                JSONArray records = new JSONArray();
                StatusBarNotification[] notifications = getActiveNotifications();
                if (notifications == null) throw new IllegalStateException("snapshot_unavailable");
                for (StatusBarNotification sbn : notifications) {
                    if (read == 2) break;
                    if (!PACKAGE.equals(sbn.getPackageName()) || !PACKAGE.equals(sbn.getOpPkg()) ||
                            sbn.getUid() != uid || !Process.myUserHandle().equals(sbn.getUser())) continue;
                    // Only bounded presentations and explicitly scoped finite metadata cross the gate.
                    Notification notification = sbn.getNotification();
                    JSONObject record = new JSONObject();
                    record.put("title", bounded(notification, Notification.EXTRA_TITLE, 256));
                    record.put("text", bounded(notification, Notification.EXTRA_TEXT, 1024));
                    record.put("big_text", bounded(notification, Notification.EXTRA_BIG_TEXT, 1024));
                    if (scope.includeMetadata) record.put("metadata", metadata(sbn, notification));
                    records.put(record);
                    read++;
                }
                writeAtomic(raw, records.toString());
                report.put("phase", "COMPLETE");
            } catch (Exception failure) {
                // Fixed error code only; exception messages can contain personal data.
                try { report.put("phase", "ERROR"); } catch (Exception ignored) { }
                if (raw.exists()) raw.delete();
            } finally {
                if (consumed) {
                    try {
                        report.put("records", read);
                        report.put("limit", 2);
                        report.put("historical_snapshot", true);
                        report.put("new_notifications_read", false);
                        writeAtomic(status, report.toString());
                    } catch (Exception ignored) { }
                }
            }
        }
    }

    private boolean previousAttemptComplete(String prefix, String marker) throws Exception {
        if (!new File(getNoBackupFilesDir(), marker).isFile() ||
                new File(getFilesDir(), prefix + "-consent").exists() ||
                new File(getFilesDir(), prefix + "-raw.json").exists() ||
                new File(getFilesDir(), prefix + "-raw.json.pending").exists()) return false;
        File status = new File(getFilesDir(), prefix + "-status.json");
        if (!status.isFile() || status.length() > 1024) return false;
        JSONObject previous = new JSONObject(new String(Files.readAllBytes(status.toPath()), StandardCharsets.UTF_8));
        return previous.length() == 5 && "COMPLETE".equals(previous.optString("phase")) &&
                previous.optInt("records", -1) == 2 && previous.optInt("limit", -1) == 2 &&
                previous.optBoolean("historical_snapshot", false) && !previous.optBoolean("new_notifications_read", true);
    }

    private static JSONObject metadata(StatusBarNotification sbn, Notification notification) throws Exception {
        JSONObject result = new JSONObject();
        String category = notification.category;
        result.put("category", category == null ? "none" :
                java.util.Arrays.asList("msg", "email", "service", "status", "promo", "sys", "progress", "event", "reminder", "transport", "call", "alarm", "err", "recommendation", "social", "navigation", "workout", "location", "stopwatch", "missed_call").contains(category) ? category : "other");
        Object style = notification.extras.get(Notification.EXTRA_TEMPLATE);
        String styleName = style instanceof String ? (String) style : "";
        String styleEnum = "other";
        if (style == null) styleEnum = "none";
        for (String known : new String[]{"BigTextStyle", "BigPictureStyle", "InboxStyle", "MessagingStyle", "MediaStyle", "DecoratedCustomViewStyle", "CallStyle"}) {
            if (("android.app.Notification$" + known).equals(styleName)) styleEnum = known;
        }
        result.put("style", styleEnum);
        result.put("channel_present", notification.getChannelId() != null);
        result.put("messages_present", notification.extras.containsKey(Notification.EXTRA_MESSAGES));
        result.put("grouped", sbn.isGroup());
        result.put("group_summary", (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0);
        result.put("ongoing", (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        result.put("auto_cancel", (notification.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        return result;
    }

    private static void writeAtomic(File target, String value) throws java.io.IOException {
        File pending = new File(target.getParentFile(), target.getName() + ".pending");
        Files.write(pending.toPath(), value.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    @SuppressWarnings("deprecation")
    private static Object bounded(Notification notification, String name, int limit) {
        Object value = notification.extras.get(name);
        if (value == null) return JSONObject.NULL;
        if (!(value instanceof CharSequence) || ((CharSequence) value).length() > limit)
            throw new IllegalArgumentException("field_shape");
        return value.toString();
    }
}
