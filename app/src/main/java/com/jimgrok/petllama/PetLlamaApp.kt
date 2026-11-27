package com.jimgrok.petllama

import android.app.Application
import com.jimgrok.petllama.data.Db
import com.jimgrok.petllama.ui.mcp.seedDefaultMcpServers

class PetLlamaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        Db.init(this)
        seedDefaultMcpServers(this)
    }

    companion object {
        lateinit var app: PetLlamaApp
            private set
    }
}
