package com.jugaad.agent

import android.app.Application
import com.jugaad.agent.core.Logx
import com.jugaad.agent.di.ServiceLocator

class JugaadApp : Application() {

    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        services = ServiceLocator.get(this)
        Logx.i("JugaadApp start — SoC=${services.socInfo.displayName} htpCapable=${services.socInfo.htpCapable}")
        Logx.i("flags: EXECUTORCH_ENABLED=${BuildConfig.EXECUTORCH_ENABLED} GEMMA_ENABLED=${BuildConfig.GEMMA_ENABLED}")
        services.warmUp()
    }
}
