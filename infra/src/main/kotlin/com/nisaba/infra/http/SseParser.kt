package com.nisaba.infra.http

import okio.BufferedSource

/** Minimal server-sent-events reader: yields the `data:` payload of each event, skipping comments and keep-alives. */
class SseParser {
    /** Reads events until the stream ends. [onData] returns false to stop early. Comment and other fields are ignored. */
    inline fun read(source: BufferedSource, onData: (String) -> Boolean) {
        val data = StringBuilder()
        var line = source.readUtf8Line()
        while (line != null) {
            if (line.isEmpty() && data.isNotEmpty()) {
                val payload = data.toString()
                data.setLength(0)
                if (!onData(payload)) return
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
            line = source.readUtf8Line()
        }
        if (data.isNotEmpty()) onData(data.toString())
    }
}
