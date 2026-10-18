package dev.epay.observer.sample;

import android.app.Activity;
import android.os.Bundle;

/** Marks only the test APK as launched. No UI, intent access or notification access. */
public final class SnapshotPrepareActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
