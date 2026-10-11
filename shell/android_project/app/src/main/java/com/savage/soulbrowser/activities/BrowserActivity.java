package com.savage.soulbrowser.activities;

import com.savage.soulbrowser.MainActivity;

/**
 * Legacy compatibility entrypoint redirecting/aliasing to MainActivity.
 * Guarantees zero crash if launched via legacy BrowserActivity intent.
 */
public class BrowserActivity extends MainActivity {
}
