package com.jimgrok.petllama

import android.app.Application
import com.jimgrok.petllama.data.Db

class PetLlamaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Db.init(this)
    }
}
