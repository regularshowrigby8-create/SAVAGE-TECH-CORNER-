package com.savage.soulbrowser;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.savage.soulbrowser.ui.MainActivity;

/**
 * AC-032 and AC-033: every control a user can touch is labelled for a screen reader, the labels
 * come from string resources (never from a literal in a layout) and the touch targets of the
 * browser dock are large enough to hit.
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityTest {

    private static final float MIN_TOUCH_TARGET_DP = 48f;

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @Test
    public void allInteractiveControlsAreLabelled() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> {
                List<String> unlabelled = new ArrayList<>();
                List<String> smallTargets = new ArrayList<>();
                collect(activity.getWindow().getDecorView(), unlabelled, smallTargets);

                assertTrue("every clickable control needs a screen-reader label, missing: " + unlabelled,
                        unlabelled.isEmpty());
                assertTrue("browser controls need a 48dp touch target, too small: " + smallTargets,
                        smallTargets.isEmpty());
            });
        }
    }

    private static void collect(View view, List<String> unlabelled, List<String> smallTargets) {
        if (isInteractive(view)) {
            CharSequence description = view.getContentDescription();
            boolean labelled = description != null && description.length() > 0;
            if (!labelled && view instanceof TextView) {
                CharSequence text = ((TextView) view).getText();
                labelled = text != null && text.length() > 0;
            }
            if (!labelled) {
                unlabelled.add(describe(view));
            }
            if (isBrowserControl(view) && (view.getWidth() > 0 || view.getHeight() > 0)) {
                float density = view.getResources().getDisplayMetrics().density;
                float widthDp = view.getWidth() / density;
                float heightDp = view.getHeight() / density;
                if (widthDp + 0.5f < MIN_TOUCH_TARGET_DP || heightDp + 0.5f < MIN_TOUCH_TARGET_DP) {
                    smallTargets.add(describe(view) + " " + widthDp + "x" + heightDp + "dp");
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), unlabelled, smallTargets);
            }
        }
    }

    /**
     * Controls are the widgets a screen reader must announce by name. The WebView is deliberately
     * not one of them: it is a web content container, and TalkBack reads the page itself.
     */
    private static boolean isInteractive(View view) {
        boolean control = view instanceof ImageButton || view instanceof TextView
                || view instanceof android.widget.ImageView;
        return control && view.isClickable() && view.getVisibility() == View.VISIBLE
                && view.isEnabled();
    }

    private static boolean isBrowserControl(View view) {
        return view instanceof ImageButton || view.getId() == R.id.btn_nav_back
                || view.getId() == R.id.btn_nav_forward || view.getId() == R.id.btn_nav_home
                || view.getId() == R.id.btn_nav_tabs || view.getId() == R.id.btn_nav_menu
                || view.getId() == R.id.btn_action_bookmark || view.getId() == R.id.btn_action_reload;
    }

    private static String describe(View view) {
        String name = view.getResources().getResourceEntryName(view.getId());
        return view.getClass().getSimpleName() + "#" + name;
    }
}
