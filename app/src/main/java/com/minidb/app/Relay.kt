package com.minidb.app

import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Liga o telefone ao NOSSO relay (relay.py, num servidor nosso).
 * O telefone faz so ligacoes de SAIDA: pergunta "ha pedidos?", responde, repete.
 * Assim funciona atras de dados moveis, sem IP publico.
 */
class Relay(private val engine: Engine, private val prefs: SharedPreferences) {
    @Volatile var state = "desligado"
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }
    fun stop() { running = false; thread?.interrupt(); state = "desligado" }
    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun loop() {
        while (running) {
            val base = prefs.getString("relay_url", "")!!.trim().trimEnd('/')
            val tunnel = prefs.getString("relay_tunnel", "")!!.trim()
            val token = prefs.getString("relay_token", "")!!.trim()
            if (base.isEmpty() || tunnel.isEmpty() || token.isEmpty()) {
                state = "sem configuracao"; nap(3000); continue
            }
            try {
                val c = URL("$base/_poll/$tunnel").openConnection() as HttpURLConnection
                c.setRequestProperty("X-Relay-Token", token)
                c.connectTimeout = 10000; c.readTimeout = 40000
                when (val code = c.responseCode) {
                    200 -> {
                        state = "ligado"
                        val job = JSONObject(c.inputStream.readBytes().toString(Charsets.UTF_8))
                        Thread { handleJob(base, tunnel, token, job) }.start()
                    }
                    204 -> { state = "ligado"; c.disconnect() }
                    401 -> { state = "token errado"; c.disconnect(); nap(5000) }
                    else -> { state = "erro $code"; c.disconnect(); nap(3000) }
                }
            } catch (e: Exception) { state = "sem ligacao ao relay"; nap(3000) }
        }
    }

    private fun handleJob(base: String, tunnel: String, token: String, job: JSONObject) {
        try {
            val id = job.getString("id")
            val u = Uri.parse("http://x" + job.getString("path"))
            val q = HashMap<String, String>()
            for (n in u.queryParameterNames) q[n] = u.getQueryParameter(n) ?: ""
            val hs = HashMap<String, String>()
            val hj = job.optJSONObject("headers") ?: JSONObject()
            for (k in hj.keys()) hs[k.lowercase()] = hj.getString(k)
            val body = Base64.decode(job.optString("body", ""), Base64.DEFAULT)
            val r = engine.handle(job.getString("method"), u.path ?: "/", q, hs, body, true)
            val out = JSONObject().put("status", r.status).put("type", r.type)
                .put("body", Base64.encodeToString(r.body, Base64.NO_WRAP))
            val c = URL("$base/_respond/$tunnel/$id").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("X-Relay-Token", token)
            c.setRequestProperty("Content-Type", "application/json")
            c.connectTimeout = 10000; c.readTimeout = 15000
            c.outputStream.use { it.write(out.toString().toByteArray()) }
            c.responseCode; c.disconnect()
        } catch (_: Exception) {}
    }
}