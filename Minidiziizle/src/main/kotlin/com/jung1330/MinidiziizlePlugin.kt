// StreamTest | Jung1330

package com.jung1330

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MinidiziizlePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Minidiziizle())
    }
}
