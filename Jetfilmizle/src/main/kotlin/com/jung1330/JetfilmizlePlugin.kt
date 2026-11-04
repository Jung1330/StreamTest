// StreamTest | Jung1330

package com.jung1330

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JetfilmizlePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Jetfilmizle())
    }
}
