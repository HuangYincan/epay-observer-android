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
    private static final String CONSENT = "active-snapshot-once-max2-v1";
    private static final Object LOCK = new Object();

    @Override public void onNotificationPosted(StatusBarNotification notification) {
        // Deliberately no posted-event handling, fields or counters.
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        synchronized (LOCK) {
            File consent = new File(getFilesDir(), "active-snapshot-consent");
            File used = new File(getNoBackupFilesDir(), "used-active-snapshot-max2-v1");
            File raw = new File(getFilesDir(), "active-snapshot-raw.json");
            File status = new File(getFilesDir(), "active-snapshot-status.json");
            JSONObject report = new JSONObject();
            int read = 0;
            boolean consumed = false;
            try {
                if (!consent.isFile() || consent.length() > 80 ||
                        !CONSENT.equals(new String(Files.readAllBytes(consent.toPath()), StandardCharsets.UTF_8).trim())) return;
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
                    // Only these three title/body presentations may cross the gate.
                    Notification notification = sbn.getNotification();
                    JSONObject record = new JSONObject();
                    record.put("title", bounded(notification, Notification.EXTRA_TITLE, 256));
                    record.put("text", bounded(notification, Notification.EXTRA_TEXT, 1024));
                    record.put("big_text", bounded(notification, Notification.EXTRA_BIG_TEXT, 1024));
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
