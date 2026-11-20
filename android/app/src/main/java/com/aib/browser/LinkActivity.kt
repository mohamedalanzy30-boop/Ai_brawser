package com.aib.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives links tapped in other apps and hands them to the browser. MainActivity used to be singleTask for this, but then
 * tapping the launcher icon closed everything above it (the native player, the file chooser). This invisible step keeps
 * MainActivity a normal activity: the link reaches the running browser through onNewIntent, or starts it.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(Intent.ACTION_VIEW, intent?.data, this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish() // Theme.NoDisplay: must finish before it would be shown
    }
}
