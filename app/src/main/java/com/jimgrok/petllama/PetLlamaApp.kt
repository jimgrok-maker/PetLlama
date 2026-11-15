package com.jimgrok.petllama

import android.app.Application
import com.jimgrok.petllama.data.Db

class PetLlamaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        Db.init(this)
    }

    companion object {
        lateinit var app: PetLlamaApp
            private set
    }
}
