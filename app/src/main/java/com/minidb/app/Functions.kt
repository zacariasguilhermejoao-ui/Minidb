package com.minidb.app

import android.database.Cursor
import org.json.JSONObject
import org.json.JSONTokener
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class FnResult(val ok: Boolean, val value: String?, val log: String, val error: String?)

class FnRow(
    val name: String, val code: String, val kind: String,
    val secret: String, val scheduleMin: Int, val enabled: Boolean
)

private class Job(val id: Long, val fn: String, val payload: String, val attempts: Int)

class Functions(private val engine: Engine) {
    private class Timeout : Error("tempo limite excedido (10s)")
    private class Host(val f: (Array<Any?>) -> Any?) : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? = f(args)
    }
    private val factory = object : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1
            cx.instructionObserverThreshold = 20000
            return cx
        }
        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val start = cx.getThreadLocal("start") as Long
            if (System.currentTimeMillis() - start > 10_000) throw Timeout()
        }
    }
    private val gate = Semaphore(4)
    private fun rowOf(c: Cursor) = FnRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3) ?: "", c.getInt(4), c.getInt(5) == 1)
    fun load(pid: String, name: String): FnRow? =
        engine.db(pid).rawQuery("SELECT name, code, kind, secret, schedule_min, enabled FROM _minidb_functions WHERE name=?", arrayOf(name)).use { if (it.moveToFirst()) rowOf(it) else null }
    fun envGet(pid: String, key: String): String? =
        engine.db(pid).rawQuery("SELECT v FROM _minidb_env WHERE k=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun enqueue(pid: String, fn: String, payload: String, delaySec: Long) {
        val db = engine.db(pid)
        val pending = db.rawQuery("SELECT COUNT(*) FROM _minidb_jobs WHERE status='pending'", null).use { it.moveToFirst(); it.getLong(0) }
        if (pending >= 1000) throw IllegalStateException("fila de tarefas cheia (1000)")
        db.execSQL("INSERT INTO _minidb_jobs(fn, payload, run_at) VALUES(?,?,?)", arrayOf<Any?>(fn, payload, System.currentTimeMillis() + delaySec.coerceAtLeast(0) * 1000))
    }
    fun baseReq(): JSONObject = JSONObject().put("method", "INTERNAL").put("path", "/").put("query", JSONObject()).put("headers", JSONObject()).put("body", JSONObject.NULL).put("raw", "").put("role", "service").put("user", JSONObject.NULL)
    fun exec(pid: String, row: FnRow, req: JSONObject): FnResult {
        if (!gate.tryAcquire(5, TimeUnit.SECONDS)) return FnResult(false, null, "", "servidor ocupado, tenta de novo")
        val r = try { run(pid, req, row.code) } finally { gate.release() }
        try {
            engine.db(pid).execSQL("UPDATE _minidb_functions SET last_run=?, last_status=?, last_log=? WHERE name=?", arrayOf<Any?>(System.currentTimeMillis(), if (r.ok) "ok" else "erro: " + r.error, r.log.take(4000), row.name))
        } catch (_: Exception) {}
        return r
    }
    private fun run(pid: String, req: JSONObject, code: String): FnResult {
        val log = StringBuilder()
        val cx = factory.enterContext()
        try {
            cx.putThreadLocal("start", System.currentTimeMillis())
            val scope = cx.initSafeStandardObjects()
            fun def(name: String, f: (Array<Any?>) -> Any?) { ScriptableObject.putProperty(scope, name, Host(f)) }
            def("__log") { a -> if (log.length < 4000) log.append(Context.toString(a.getOrNull(0))).append('\n'); null }
            def("__db") { a -> engine.runSqlFor(pid, Context.toString(a[0]), org.json.JSONArray(Context.toString(a[1]))).toString() }
            def("__http") { a -> httpCall(JSONObject(Context.toString(a[0]))) }
            def("__job") { a -> enqueue(pid, Context.toString(a[0]), Context.toString(a[1]), Context.toNumber(a[2]).toLong()); null }
            def("__env") { a -> envGet(pid, Context.toString(a[0])) }
            def("__hmac") { a -> hmac(Context.toString(a[0]), Context.toString(a[1])) }
            cx.evaluateString(scope, PRELUDE, "prelude", 1, null)
            cx.evaluateString(scope, code, "funcao", 1, null)
            val h = scope.get("handler", scope)
            if (h !is org.mozilla.javascript.Function) return FnResult(false, null, log.toString(), "falta a funcao handler(req)")
            ScriptableObject.putProperty(scope, "__req", req.toString())
            val reqObj = cx.evaluateString(scope, "JSON.parse(__req)", "req", 1, null)
            val res = h.call(cx, scope, scope, arrayOf(reqObj))
            ScriptableObject.putProperty(scope, "__res", res)
            val out = cx.evaluateString(scope, "(typeof __res==='undefined')?null:JSON.stringify(__res)", "res", 1, null)
            val txt = if (out == null || out is Undefined) null else Context.toString(out)
            return FnResult(true, txt, log.toString(), null)
        } catch (e: Throwable) { return FnResult(false, null, log.toString(), e.message ?: e.toString()) }
        finally { Context.exit() }
    }
    private fun hmac(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun httpCall(o: JSONObject): String {
        val url = URL(o.getString("url"))
        require(url.protocol == "http" || url.protocol == "https") { "so http e https" }
        val host = url.host.lowercase()
        require(!(host == "localhost" || host.startsWith("127.") || host == "0.0.0.0" || host.contains("::1"))) { "enderecos locais bloqueados" }
        val c = url.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = o.optString("method", "GET").uppercase()
        c.connectTimeout = 8000; c.readTimeout = 8000
        o.optJSONObject("headers")?.let { hs -> for (k in hs.keys()) c.setRequestProperty(k, hs.getString(k)) }
        val body = o.optString("body", "")
        if (body.isNotEmpty() && c.requestMethod != "GET") { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        c.disconnect()
        return JSONObject().put("status", code).put("body", String(bytes, 0, minOf(bytes.size, 1_000_000))).toString()
    }
    @Volatile private var running = false
    fun startScheduler() {
        if (running) return
        running = true
        Thread {
            try { for (pid in engine.projectIds()) engine.db(pid).execSQL("UPDATE _minidb_jobs SET status='pending' WHERE status='running'") } catch (_: Throwable) {}
            while (running) { try { tick() } catch (_: Throwable) {}; try { Thread.sleep(10_000) } catch (_: InterruptedException) {} }
        }.also { it.isDaemon = true; it.start() }
    }
    fun stopScheduler() { running = false }
    private fun tick() {
        val now = System.currentTimeMillis()
        for (pid in engine.projectIds()) {
            val db = engine.db(pid)
            val due = ArrayList<FnRow>()
            db.rawQuery("SELECT name, code, kind, secret, schedule_min, enabled FROM _minidb_functions WHERE enabled=1 AND schedule_min>0 AND (last_run IS NULL OR last_run + schedule_min*60000 <= CAST(? AS INTEGER))", arrayOf(now.toString())).use { while (it.moveToNext()) due.add(rowOf(it)) }
            for (f in due) {
                db.execSQL("UPDATE _minidb_functions SET last_run=? WHERE name=?", arrayOf<Any?>(now, f.name))
                exec(pid, f, baseReq().put("method", "SCHEDULE").put("scheduled", true))
            }
            val jobs = ArrayList<Job>()
            db.rawQuery("SELECT id, fn, payload, attempts FROM _minidb_jobs WHERE status='pending' AND run_at<=CAST(? AS INTEGER) ORDER BY run_at LIMIT 20", arrayOf(now.toString())).use { while (it.moveToNext()) jobs.add(Job(it.getLong(0), it.getString(1), it.getString(2) ?: "null", it.getInt(3))) }
            for (j in jobs) {
                db.execSQL("UPDATE _minidb_jobs SET status='running' WHERE id=?", arrayOf<Any?>(j.id))
                val row = load(pid, j.fn)
                val payload: Any = try { JSONTokener(j.payload).nextValue() } catch (_: Exception) { JSONObject.NULL }
                val res = if (row == null || !row.enabled) FnResult(false, null, "", "funcao nao existe ou esta desativada") else exec(pid, row, baseReq().put("method", "JOB").put("job", true).put("payload", payload))
                if (res.ok) db.execSQL("UPDATE _minidb_jobs SET status='done', attempts=?, error=NULL WHERE id=?", arrayOf<Any?>(j.attempts + 1, j.id))
                else {
                    val a = j.attempts + 1
                    if (a >= 3) db.execSQL("UPDATE _minidb_jobs SET status='failed', attempts=?, error=? WHERE id=?", arrayOf<Any?>(a, res.error, j.id))
                    else db.execSQL("UPDATE _minidb_jobs SET status='pending', attempts=?, error=?, run_at=? WHERE id=?", arrayOf<Any?>(a, res.error, System.currentTimeMillis() + 60_000L * a, j.id))
                }
            }
        }
    }
    companion object {
        private const val PRELUDE = """
var console={log:function(){var p=[];for(var i=0;i<arguments.length;i++){var a=arguments[i];p.push(typeof a==='string'?a:JSON.stringify(a))}__log(p.join(' '))}};
var db={
 query:function(sql,p){var r=JSON.parse(__db(sql,JSON.stringify(p||[])));if(!r.columns)return r;return r.rows.map(function(row){var o={};for(var i=0;i<r.columns.length;i++)o[r.columns[i]]=row[i];return o})},
 exec:function(sql,p){return JSON.parse(__db(sql,JSON.stringify(p||[])))}
};
var http={
 request:function(o){return JSON.parse(__http(JSON.stringify(o)))},
 get:function(u,h){return http.request({method:'GET',url:u,headers:h||{}})},
 post:function(u,b,h){return http.request({method:'POST',url:u,headers:h||{'Content-Type':'application/json'},body:typeof b==='string'?b:JSON.stringify(b)})}
};
var jobs={enqueue:function(fn,payload,delay){__job(fn,JSON.stringify(payload===undefined?null:payload),delay||0)}};
var env={get:function(k){return __env(String(k))}};
var crypto={hmacSha256:function(key,data){return __hmac(String(key),String(data))}};
"""
    }
}