package com.frpc.android

import android.app.Application
import com.frpc.android.di.AppContainer
import com.frpc.android.service.FrpcSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FrpcApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        FrpcSupervisor.attach(this)
        appScope.launch { container.configRepository.load() }
    }
}
