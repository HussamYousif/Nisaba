package com.nisaba.domain.shared

interface Clock {
    fun nowMillis(): Long
}

interface IdGen {
    fun next(): String
}

interface Logger {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
}
