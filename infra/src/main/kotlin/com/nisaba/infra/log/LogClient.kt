package com.nisaba.infra.log

import android.util.Log
import com.nisaba.domain.shared.Logger

class AndroidLogClient : Logger {
    override fun d(tag: String, msg: String) { Log.d("Nisaba/$tag", msg) }
    override fun i(tag: String, msg: String) { Log.i("Nisaba/$tag", msg) }
    override fun w(tag: String, msg: String, t: Throwable?) { Log.w("Nisaba/$tag", msg, t) }
    override fun e(tag: String, msg: String, t: Throwable?) { Log.e("Nisaba/$tag", msg, t) }
}
