package com.minidb.app

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

/** Servidor HTTP local (WebView do app + rede Wi-Fi). Toda a logica esta no Engine. */
class HttpServer(private val engine: Engine, port: Int) : NanoHTTPD(port) {
    private fun status(code: Int) = object : Response.IStatus {
        override fun getRequestStatus() = code
        override fun getDescription() = "$code MiniDB"
    }
    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Headers", "apikey, authorization, content-type, x-admin-token")
        r.addHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS")
        return r
    }
    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(status(204), "text/plain", ByteArrayInputStream(ByteArray(0)), 0))
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 25 * 1024 * 1024) {
            val msg = "{\"error\":\"corpo grande demais (maximo 25 MB)\"}".toByteArray()
            return cors(newFixedLengthResponse(status(413), "application/json", ByteArrayInputStream(msg), msg.size.toLong()))
        }
        val body = ByteArray(len); var off = 0
        while (off < len) { val n = session.inputStream.read(body, off, len - off); if (n < 0) break; off += n }
        val r = engine.handle(session.method.name, session.uri, session.parms, session.headers, body, false)
        return cors(newFixedLengthResponse(status(r.status), r.type, ByteArrayInputStream(r.body), r.body.size.toLong()))
    }
}
