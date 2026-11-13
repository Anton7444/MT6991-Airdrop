package com.airdrop.android.xposed

import android.app.Application
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class Module : XposedModule() {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (!param.isFirstPackage || param.packageName != SHARE_PKG) return
        try {
            val loader = param.defaultClassLoader
            val app = loader.loadClass("com.oplus.oshare.OShareApplication")
            val onCreate = app.getDeclaredMethod("onCreate")
            hook(onCreate).intercept(XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                try {
                    if (Application.getProcessName() == SHARE_PKG) {
                        Island.install(this, chain.thisObject as Application, loader)
                    }
                } catch (t: Throwable) {
                    log(android.util.Log.ERROR, TAG, "island install failed", t)
                }
                r
            })
        } catch (t: Throwable) {
            log(android.util.Log.ERROR, TAG, "unsupported share app", t)
        }
    }

    companion object {
        const val TAG = "AirDrop"
        const val SHARE_PKG = "com.coloros.oshare"
    }
}
