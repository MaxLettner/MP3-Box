package com.local.mp3box

import android.app.Application
import com.local.mp3box.di.AppContainer

class Mp3BoxApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
