package com.minidb.app

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Resp(val status: Int, val body: ByteArray, val type: String = "application/json; charset=utf-8")

class ApiError(val status: Int, msg: String) : Exception(msg)

/**
 * O cerebro do MiniDB. Recebe um pedido (metodo, caminho, query, headers, corpo)
 * e devolve uma resposta. Nao sabe nada de sockets: o mesmo codigo serve o
 * servidor local (NanoHTTPD) e o relay.
 */
class Engine(private val ctx: Context, private val prefs: SharedPreferences) {

    var relayState: () -> String = { "desligado" }
    val functions: Functions by lazy { Functions(this) }

    private val ident = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val rnd = SecureRandom()

    private val meta: SQLiteDatabase = open("meta.db").also {
        it.execSQL("CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY, name TEXT, pk TEXT, sk TEXT, created INTEGER)")
        it.execSQL("CREATE TABLE IF NOT EXISTS limits(project_id TEXT PRIMARY KEY, rpm INTEGER, storage_mb INTEGER)")
        try { it.execSQL("ALTER TABLE limits ADD COLUMN transfer_mb INTEGER DEFAULT 5000") } catch (_: Exception) { }
        it.execSQL("CREATE TABLE IF NOT EXISTS transfer(project_id TEXT, month TEXT, bytes INTEGER, PRIMARY KEY(project_id, month))")
    }
    private val dbs = HashMap<String, SQLiteDatabase>()

    private fun open(name: String): SQLiteDatabase {
        val f = ctx.getDatabasePath(name)
        f.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(f, null)
    }

    @Synchronized
    private fun pdb(id: String): SQLiteDatabase = dbs.getOrPut(id) {
        val d = open("p_$id.db")
        d.setForeignKeyConstraintsEnabled(true)
        d.execSQL("CREATE TABLE IF NOT EXISTS _minidb_rules(tbl TEXT PRIMARY KEY, access TEXT NOT NULL DEFAULT 'private')")
        d.execSQL("CREATE TABLE IF NOT EXISTS _minidb_users(id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT UNIQUE, salt TEXT, hash TEXT, created INTEGER)")
        d.execSQL("CREATE TABLE IF NOT EXISTS _minidb_sessions(token TEXT PRIMARY KEY, user_id INTEGER, created INTEGER)")
        d.execSQL("CREATE TABLE IF NOT EXISTS _minidb_resets(code TEXT, user_id INTEGER, expires INTEGER, PRIMARY KEY(code, user_id))")
        d.execSQL("CREATE TABLE IF NOT EXISTS _minidb_buckets(name TEXT PRIMARY KEY, public INTEGER NOT NULL DEFAULT 0)")
        d.execSQL("CREATE TABLE IF NOT EXISTS _minidb_files(id TEXT PRIMARY KEY, bucket TEXT, name TEXT, path TEXT, size INTEGER, mime TEXT, created INTEGER, owner INTEGER)")
        d.execSQL("INSERT OR IGNORE INTO _minidb_buckets(name, public) VALUES('public', 1)")
        d.execSQL("INSERT OR IGNORE INTO _minidb_buckets(name, public) VALUES('private', 0)")
        d.execSQL("CREATE TABLE IF NOT EXISTS _minidb_functions(name TEXT PRIMARY KEY, code TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'endpoint', secret TEXT, schedule_min INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1, last_run INTEGER, last_status TEXT, last_log TEXT)")
        d.execSQL("CREATE TABLE IF NOT EXISTS _minidb_jobs(id INTEGER PRIMARY KEY AUTOINCREMENT, fn TEXT, payload TEXT, run_at INTEGER, attempts INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'pending', error TEXT)")
        d.execSQL("CREATE TABLE IF NOT EXISTS _minidb_env(k TEXT PRIMARY KEY, v TEXT)")
        d
    }

    // ---------------------------------------------------------------- util
    private fun token(n: Int): String {
        val b = ByteArray(n); rnd.nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun json(status: Int, o: Any): Resp = Resp(status, o.toString().toByteArray())

    private fun hashPw(pw: String, salt: String): String {
        val spec = PBEKeySpec(pw.toCharArray(), salt.toByteArray(), 60000, 256)
        val h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return h.joinToString("") { "%02x".format(it) }
    }

    private fun body(b: ByteArray): JSONObject =
        if (b.isEmpty()) JSONObject() else try { JSONObject(String(b)) } catch (e: Exception) { throw ApiError(400, "JSON invalido") }

    private fun checkIdent(s: String, what: String): String {
        if (!ident.matches(s)) throw ApiError(400, "$what invalido (use letras, numeros e _)")
        return s
    }

    private fun cursorJson(c: Cursor): JSONObject {
        val cols = JSONArray()
        for (i in 0 until c.columnCount) cols.put(c.getColumnName(i))
        val rows = JSONArray()
        while (c.moveToNext()) {
            val r = JSONArray()
            for (i in 0 until c.columnCount) when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> r.put(JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> r.put(c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> r.put(c.getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> r.put("base64:" + Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
                else -> r.put(c.getString(i))
            }
            rows.put(r)
        }
        return JSONObject().put("columns", cols).put("rows", rows)
    }

    private fun cursorObjects(c: Cursor): JSONArray {
        val out = JSONArray()
        while (c.moveToNext()) {
            val o = JSONObject()
            for (i in 0 until c.columnCount) {
                val n = c.getColumnName(i)
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> o.put(n, JSONObject.NULL)
                    Cursor.FIELD_TYPE_INTEGER -> o.put(n, c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> o.put(n, c.getDouble(i))
                    Cursor.FIELD_TYPE_BLOB -> o.put(n, "base64:" + Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
                    else -> o.put(n, c.getString(i))
                }
            }
            out.put(o)
        }
        return out
    }

    private fun splitSql(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q: Char? = null
        for (ch in s) {
            if (q != null) { sb.append(ch); if (ch == q) q = null }
            else if (ch == '\'' || ch == '"') { q = ch; sb.append(ch) }
            else if (ch == ';') { out.add(sb.toString()); sb.clear() }
            else sb.append(ch)
        }
        out.add(sb.toString())
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun paramsAny(a: JSONArray?): Array<Any?> {
        if (a == null) return arrayOf()
        return Array(a.length()) { i ->
            val v = a.get(i)
            when {
                v == JSONObject.NULL -> null
                v is Boolean -> if (v) 1L else 0L
                else -> v
            }
        }
    }

    /** Executa SQL de verdade (varias instrucoes separadas por ;). Devolve o resultado da ultima. */
    private fun runSql(db: SQLiteDatabase, sql: String, params: JSONArray?): JSONObject {
        val stmts = splitSql(sql)
        if (stmts.isEmpty()) throw ApiError(400, "SQL vazio")
        var result = JSONObject().put("changes", 0)
        for (s in stmts) {
            val first = s.trimStart('(', ' ', '\n').substringBefore(' ').lowercase()
            val args = if (stmts.size == 1) params else null
            try {
                if (first in setOf("select", "pragma", "with", "explain", "values")) {
                    val a = paramsAny(args).map { it?.toString() ?: "" }.toTypedArray()
                    db.rawQuery(s, a).use { result = cursorJson(it) }
                } else {
                    db.execSQL(s, paramsAny(args))
                    val ch = db.rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getLong(0) }
                    result = JSONObject().put("changes", ch)
                }
            } catch (e: ApiError) {
                throw e
            } catch (e: Exception) {
                throw ApiError(400, "SQL: " + (e.message ?: "erro"))
            }
        }
        return result
    }

    private fun accessOf(db: SQLiteDatabase, table: String): String =
        db.rawQuery("SELECT access FROM _minidb_rules WHERE tbl=?", arrayOf(table)).use {
            if (it.moveToFirst()) it.getString(0) else "private"
        }

    private fun tableExists(db: SQLiteDatabase, t: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(t)).use { it.moveToFirst() }

    // ------------------------------------------------------ limites e quota
    private val hits = HashMap<String, java.util.ArrayDeque<Long>>()

    private fun limitsOf(pid: String): Pair<Int, Int> =
        meta.rawQuery("SELECT rpm, storage_mb FROM limits WHERE project_id=?", arrayOf(pid)).use {
            if (it.moveToFirst()) Pair(it.getInt(0), it.getInt(1)) else Pair(600, 500)
        }

    private fun checkRate(key: String, pid: String) {
        val rpm = limitsOf(pid).first
        if (rpm <= 0) return
        val now = System.currentTimeMillis()
        synchronized(hits) {
            val dq = hits.getOrPut(key) { java.util.ArrayDeque() }
            while (dq.isNotEmpty() && now - dq.first() > 60000) dq.removeFirst()
            if (dq.size >= rpm) throw ApiError(429, "limite de pedidos por minuto atingido")
            dq.addLast(now)
        }
    }

    private fun usedBytes(pid: String): Long =
        ctx.getDatabasePath("p_$pid.db").length() +
            File(ctx.filesDir, "p_$pid").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun checkQuota(pid: String) {
        val mb = limitsOf(pid).second
        if (mb > 0 && usedBytes(pid) > mb.toLong() * 1024 * 1024) throw ApiError(507, "limite de armazenamento atingido")
    }

    private fun tableNames(db: SQLiteDatabase): List<String> {
        val names = ArrayList<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '\\_minidb\\_%' ESCAPE '\\' ORDER BY name", null
        ).use { while (it.moveToNext()) names.add(it.getString(0)) }
        return names
    }

    private fun exportAll(db: SQLiteDatabase): JSONObject {
        val tables = JSONArray()
        for (n in tableNames(db)) {
            val sql = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(n)).use { it.moveToFirst(); it.getString(0) }
            val idx = JSONArray()
            db.rawQuery("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL", arrayOf(n)).use {
                while (it.moveToNext()) idx.put(it.getString(0))
            }
            val rows = db.rawQuery("SELECT * FROM \"$n\"", null).use { cursorObjects(it) }
            tables.put(JSONObject().put("name", n).put("sql", sql).put("indexes", idx).put("access", accessOf(db, n)).put("rows", rows))
        }
        return JSONObject().put("minidb", 1).put("tables", tables)
    }

    private fun importAll(db: SQLiteDatabase, b: JSONObject): JSONObject {
        val tables = b.optJSONArray("tables") ?: throw ApiError(400, "ficheiro de backup invalido")
        var rowsIn = 0
        db.setForeignKeyConstraintsEnabled(false)
        try {
            db.beginTransaction()
            try {
                for (i in 0 until tables.length()) {
                    val t = tables.getJSONObject(i)
                    val n = checkIdent(t.getString("name"), "tabela")
                    if (!tableExists(db, n)) {
                        val create = t.getString("sql")
                        if (!create.trimStart().startsWith("CREATE TABLE", ignoreCase = true)) throw ApiError(400, "SQL de tabela invalido")
                        db.execSQL(create)
                    }
                    val idx = t.optJSONArray("indexes")
                    if (idx != null) {
                        for (k in 0 until idx.length()) {
                            try { db.execSQL(idx.getString(k)) } catch (_: Exception) { }
                        }
                    }
                    val rows = t.optJSONArray("rows") ?: JSONArray()
                    for (r in 0 until rows.length()) {
                        val o = rows.getJSONObject(r)
                        val keys = o.keys().asSequence().toList()
                        val sql = if (keys.isEmpty()) "INSERT INTO \"$n\" DEFAULT VALUES" else
                            "INSERT INTO \"$n\"(" + keys.joinToString(",") { "\"${checkIdent(it, "coluna")}\"" } +
                                ") VALUES(" + keys.joinToString(",") { "?" } + ")"
                        val vals = Array<Any?>(keys.size) { j ->
                            val v = o.get(keys[j])
                            if (v == JSONObject.NULL) null else if (v is Boolean) (if (v) 1L else 0L) else v
                        }
                        db.execSQL(sql, vals)
                        rowsIn++
                    }
                    val acc = t.optString("access", "private")
                    if (acc in setOf("private", "read", "users", "open"))
                        db.execSQL("INSERT OR REPLACE INTO _minidb_rules(tbl, access) VALUES(?,?)", arrayOf<Any?>(n, acc))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError(400, "importacao falhou: " + e.message)
        } finally {
            db.setForeignKeyConstraintsEnabled(true)
        }
        return JSONObject().put("imported_tables", tables.length()).put("imported_rows", rowsIn)
    }

    // ------------------------------------------------------- funcoes de backend
    fun db(pid: String): SQLiteDatabase = pdb(pid)

    fun projectIds(): List<String> {
        val ids = ArrayList<String>()
        meta.rawQuery("SELECT id FROM projects", null).use { while (it.moveToNext()) ids.add(it.getString(0)) }
        return ids
    }

    fun runSqlFor(pid: String, sql: String, params: JSONArray?): JSONObject {
        if (!Regex("^\\s*(select|pragma|with|explain)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql)) checkQuota(pid)
        return runSql(pdb(pid), sql, params)
    }

    private fun jsonAny(status: Int, v: Any?): Resp {
        val s = when (v) { null -> "null"; is String -> JSONObject.quote(v); else -> v.toString() }
        return Resp(status, s.toByteArray())
    }

    private fun fnRequest(
        method: String, path: String, q: Map<String, String>, h: Map<String, String>,
        bodyBytes: ByteArray, role: String, userId: Long?, db: SQLiteDatabase?
    ): JSONObject {
        val raw = String(bodyBytes)
        val bodyVal: Any = if (raw.isEmpty()) JSONObject.NULL else try { JSONTokener(raw).nextValue() } catch (_: Exception) { raw }
        var user: Any = JSONObject.NULL
        if (userId != null && db != null) {
            db.rawQuery("SELECT id, email FROM _minidb_users WHERE id=?", arrayOf(userId.toString())).use {
                if (it.moveToFirst()) user = JSONObject().put("id", it.getLong(0)).put("email", it.getString(1))
            }
        }
        return JSONObject().put("method", method).put("path", path).put("query", JSONObject(q))
            .put("headers", JSONObject(h)).put("body", bodyVal).put("raw", raw).put("role", role).put("user", user)
    }

    private fun fnResponse(r: FnResult): Resp {
        if (!r.ok) return json(500, JSONObject().put("error", r.error ?: "erro").put("log", r.log))
        val txt = r.value ?: return json(200, JSONObject().put("ok", true))
        val v: Any? = try { JSONTokener(txt).nextValue() } catch (_: Exception) { txt }
        if (v is JSONObject && (v.has("status") || v.has("json") || v.has("text"))) {
            val st = v.optInt("status", 200)
            if (v.has("json")) return jsonAny(st, v.get("json"))
            return Resp(st, v.optString("text", "").toByteArray(), v.optString("type", "text/plain; charset=utf-8"))
        }
        return jsonAny(200, v)
    }

    /** POST /hooks/{projeto}/{funcao}/{segredo}: para servicos externos (sem apikey). */
    private fun hook(
        method: String, parts: List<String>, q: Map<String, String>, h: Map<String, String>, bodyBytes: ByteArray
    ): Resp {
        val pid = parts[1]
        if (!Regex("^[A-Za-z0-9]+$").matches(pid)) throw ApiError(404, "nao existe")
        val exists = meta.rawQuery("SELECT 1 FROM projects WHERE id=?", arrayOf(pid)).use { it.moveToFirst() }
        if (!exists) throw ApiError(404, "nao existe")
        val row = functions.load(pid, checkIdent(parts[2], "funcao")) ?: throw ApiError(404, "nao existe")
        if (row.kind != "hook" || !row.enabled ||
            !java.security.MessageDigest.isEqual(parts[3].toByteArray(), row.secret.toByteArray())
        ) throw ApiError(404, "nao existe")
        checkRate("hook:$pid", pid)
        val req = fnRequest(method, "/hooks/" + parts[2], q, h, bodyBytes, "webhook", null, null)
        return fnResponse(functions.exec(pid, row, req))
    }

    // -------------------------------------------------------------- entrada
    fun handle(
        method: String, path: String, q: Map<String, String>,
        h: Map<String, String>, body: ByteArray, viaRelay: Boolean
    ): Resp = try {
        route(method, path, q, h, body, viaRelay)
    } catch (e: ApiError) {
        json(e.status, JSONObject().put("error", e.message))
    } catch (e: Exception) {
        json(500, JSONObject().put("error", "erro interno: " + e.message))
    }

    private fun route(
        method: String, path: String, q: Map<String, String>,
        h: Map<String, String>, body: ByteArray, viaRelay: Boolean
    ): Resp {
        val parts = path.split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            if (viaRelay) return json(200, JSONObject().put("ok", true).put("name", "MiniDB"))
            val html = ctx.assets.open("admin.html").readBytes()
            return Resp(200, html, "text/html; charset=utf-8")
        }
        when (parts[0]) {
            "health" -> return json(200, JSONObject().put("ok", true))
            "admin" -> {
                if (viaRelay) throw ApiError(404, "nao existe")
                if (h["x-admin-token"] != prefs.getString("admin_token", null)) throw ApiError(401, "token de admin invalido")
                return admin(method, parts, body)
            }
            "files" -> if (method == "GET" && parts.size == 3) return serveFile(parts[1], parts[2], q)
            "hooks" -> if (parts.size == 4) return hook(method, parts, q, h, body)
        }
        return projectRoute(method, parts, q, h, body)
    }

    // ---------------------------------------------------------------- admin
    private fun admin(method: String, parts: List<String>, bodyBytes: ByteArray): Resp {
        val b = body(bodyBytes)
        val seg = parts.drop(1)

        if (method == "GET" && seg == listOf("status")) {
            val url = prefs.getString("relay_url", "")!!.trimEnd('/')
            val tun = prefs.getString("relay_tunnel", "")!!
            return json(200, JSONObject()
                .put("relay_state", relayState())
                .put("relay_url", url).put("tunnel", tun)
                .put("has_token", prefs.getString("relay_token", "")!!.isNotEmpty())
                .put("public_url", if (url.isNotEmpty() && tun.isNotEmpty()) "$url/t/$tun" else ""))
        }
        if (method == "POST" && seg == listOf("settings")) {
            val e = prefs.edit()
            e.putString("relay_url", b.optString("relay_url", "").trim())
            e.putString("relay_tunnel", b.optString("tunnel", "").trim())
            if (b.optString("token", "").isNotEmpty()) e.putString("relay_token", b.getString("token").trim())
            e.apply()
            return json(200, JSONObject().put("ok", true))
        }

        if (seg.firstOrNull() == "projects") {
            if (method == "GET" && seg.size == 1) {
                val arr = JSONArray()
                meta.rawQuery("SELECT id,name,pk,sk FROM projects ORDER BY created DESC", null).use {
                    while (it.moveToNext()) arr.put(JSONObject()
                        .put("id", it.getString(0)).put("name", it.getString(1))
                        .put("pk", it.getString(2)).put("sk", it.getString(3)))
                }
                return json(200, JSONObject().put("projects", arr))
            }
            if (method == "POST" && seg.size == 1) {
                val name = b.optString("name", "").trim()
                if (name.isEmpty()) throw ApiError(400, "falta o nome")
                val id = UUID.randomUUID().toString().replace("-", "").take(10)
                meta.execSQL("INSERT INTO projects VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(id, name, "pk_" + token(18), "sk_" + token(24), System.currentTimeMillis()))
                pdb(id)
                return json(201, JSONObject().put("id", id))
            }
            if (method == "DELETE" && seg.size == 2) {
                val id = checkIdent("p" + seg[1], "id").substring(1)
                synchronized(this) { dbs.remove(id)?.close() }
                meta.execSQL("DELETE FROM projects WHERE id=?", arrayOf<Any?>(id))
                ctx.getDatabasePath("p_$id.db").delete()
                File(ctx.filesDir, "p_$id").deleteRecursively()
                return json(200, JSONObject().put("ok", true))
            }
        }

        if (seg.firstOrNull() == "p" && seg.size >= 3) {
            val id = seg[1]
            meta.rawQuery("SELECT 1 FROM projects WHERE id=?", arrayOf(id)).use {
                if (!it.moveToFirst()) throw ApiError(404, "projeto nao existe")
            }
            val db = pdb(id)
            if (method == "GET" && seg[2] == "tables") {
                val arr = JSONArray()
                val names = ArrayList<String>()
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '\\_minidb\\_%' ESCAPE '\\' ORDER BY name", null
                ).use { while (it.moveToNext()) names.add(it.getString(0)) }
                for (n in names) {
                    val cnt = db.rawQuery("SELECT COUNT(*) FROM \"$n\"", null).use { it.moveToFirst(); it.getLong(0) }
                    arr.put(JSONObject().put("name", n).put("rows", cnt).put("access", accessOf(db, n)))
                }
                return json(200, JSONObject().put("tables", arr))
            }
            if (method == "POST" && seg[2] == "sql") {
                return json(200, runSql(db, b.optString("sql", ""), b.optJSONArray("params")))
            }
            if (method == "GET" && seg[2] == "schema" && seg.size == 4) {
                val t = checkIdent(seg[3], "tabela")
                if (!tableExists(db, t)) throw ApiError(404, "tabela nao existe")
                return json(200, JSONObject()
                    .put("columns", db.rawQuery("PRAGMA table_info(\"$t\")", null).use { cursorObjects(it) })
                    .put("fks", db.rawQuery("PRAGMA foreign_key_list(\"$t\")", null).use { cursorObjects(it) })
                    .put("indexes", db.rawQuery("PRAGMA index_list(\"$t\")", null).use { cursorObjects(it) }))
            }
            if (method == "GET" && seg[2] == "export") return json(200, exportAll(db))
            if (method == "POST" && seg[2] == "import") return json(200, importAll(db, b))
            if (method == "GET" && seg[2] == "limits") {
                val (rpm, mb) = limitsOf(id)
                return json(200, JSONObject().put("rpm", rpm).put("storage_mb", mb).put("used_bytes", usedBytes(id))
                    .put("transfer_mb", transferLimitMb(id)).put("transfer_used", transferUsed(id)))
            }
            if (method == "POST" && seg[2] == "limits") {
                val rpm = b.optInt("rpm", 600).coerceAtLeast(0)
                val mb = b.optInt("storage_mb", 500).coerceAtLeast(0)
                val tmb = b.optInt("transfer_mb", 5000).coerceAtLeast(0)
                meta.execSQL("INSERT OR REPLACE INTO limits(project_id, rpm, storage_mb, transfer_mb) VALUES(?,?,?,?)", arrayOf<Any?>(id, rpm, mb, tmb))
                return json(200, JSONObject().put("ok", true))
            }
            if (method == "POST" && seg[2] == "rotate") {
                val col = when (b.optString("which")) { "pk" -> "pk"; "sk" -> "sk"; else -> throw ApiError(400, "which deve ser pk ou sk") }
                val nk = (if (col == "pk") "pk_" else "sk_") + token(if (col == "pk") 18 else 24)
                meta.execSQL("UPDATE projects SET $col=? WHERE id=?", arrayOf<Any?>(nk, id))
                return json(200, JSONObject().put(col, nk))
            }
            if (method == "GET" && seg[2] == "buckets") {
                val arr = JSONArray()
                db.rawQuery(
                    "SELECT b.name, b.public, COUNT(f.id), COALESCE(SUM(f.size),0) FROM _minidb_buckets b LEFT JOIN _minidb_files f ON f.bucket=b.name GROUP BY b.name ORDER BY b.name", null
                ).use {
                    while (it.moveToNext()) arr.put(JSONObject().put("name", it.getString(0)).put("public", it.getInt(1) == 1)
                        .put("files", it.getLong(2)).put("bytes", it.getLong(3)))
                }
                return json(200, JSONObject().put("buckets", arr))
            }
            if (method == "POST" && seg[2] == "buckets" && seg.size == 3) {
                val n = checkIdent(b.optString("name", ""), "bucket")
                db.execSQL("INSERT OR REPLACE INTO _minidb_buckets(name, public) VALUES(?,?)",
                    arrayOf<Any?>(n, if (b.optBoolean("public", false)) 1L else 0L))
                return json(200, JSONObject().put("ok", true))
            }
            if (method == "POST" && seg[2] == "buckets" && seg.size == 4 && seg[3] == "delete") {
                val n = checkIdent(b.optString("name", ""), "bucket")
                val dir = File(ctx.filesDir, "p_$id")
                db.rawQuery("SELECT path FROM _minidb_files WHERE bucket=?", arrayOf(n)).use {
                    while (it.moveToNext()) File(dir, it.getString(0)).delete()
                }
                db.execSQL("DELETE FROM _minidb_files WHERE bucket=?", arrayOf<Any?>(n))
                db.execSQL("DELETE FROM _minidb_buckets WHERE name=?", arrayOf<Any?>(n))
                return json(200, JSONObject().put("ok", true))
            }
            if (method == "POST" && seg[2] == "files" && seg.size == 3) {
                val rows = db.rawQuery(
                    "SELECT id, name, path, size, mime, created FROM _minidb_files WHERE bucket=? ORDER BY created DESC LIMIT 500",
                    arrayOf(b.optString("bucket", ""))
                ).use { cursorObjects(it) }
                return json(200, JSONObject().put("files", rows))
            }
            if (method == "POST" && seg[2] == "files" && seg.size == 4 && seg[3] == "delete") {
                val fid = b.optString("id", "")
                db.rawQuery("SELECT path FROM _minidb_files WHERE id=?", arrayOf(fid)).use {
                    if (it.moveToFirst()) File(File(ctx.filesDir, "p_$id"), it.getString(0)).delete()
                }
                db.execSQL("DELETE FROM _minidb_files WHERE id=?", arrayOf<Any?>(fid))
                return json(200, JSONObject().put("ok", true))
            }
            if (method == "GET" && seg[2] == "users") {
                val rows = db.rawQuery("SELECT id, email, created FROM _minidb_users ORDER BY id DESC LIMIT 500", null).use { cursorObjects(it) }
                return json(200, JSONObject().put("users", rows))
            }
            if (method == "POST" && seg[2] == "users" && seg.size == 4 && seg[3] == "delete") {
                val uid = b.optLong("id", -1)
                db.execSQL("DELETE FROM _minidb_sessions WHERE user_id=?", arrayOf<Any?>(uid))
                db.execSQL("DELETE FROM _minidb_resets WHERE user_id=?", arrayOf<Any?>(uid))
                db.execSQL("DELETE FROM _minidb_users WHERE id=?", arrayOf<Any?>(uid))
                return json(200, JSONObject().put("ok", true))
            }
            if (method == "POST" && seg[2] == "users" && seg.size == 4 && seg[3] == "reset") {
                val uid = b.optLong("id", -1)
                val exists = db.rawQuery("SELECT 1 FROM _minidb_users WHERE id=?", arrayOf(uid.toString())).use { it.moveToFirst() }
                if (!exists) throw ApiError(404, "usuario nao existe")
                val code = "%06d".format(rnd.nextInt(1000000))
                db.execSQL("DELETE FROM _minidb_resets WHERE user_id=?", arrayOf<Any?>(uid))
                db.execSQL("INSERT INTO _minidb_resets VALUES(?,?,?)", arrayOf<Any?>(code, uid, System.currentTimeMillis() + 3600000L))
                return json(200, JSONObject().put("code", code))
            }
            if (method == "GET" && seg[2] == "functions") {
                val rows = db.rawQuery(
                    "SELECT name, kind, secret, schedule_min, enabled, last_run, last_status, last_log, code FROM _minidb_functions ORDER BY name", null
                ).use { cursorObjects(it) }
                return json(200, JSONObject().put("functions", rows))
            }
            if (method == "POST" && seg[2] == "functions" && seg.size == 3) {
                val n = checkIdent(b.optString("name", ""), "funcao")
                val kind = b.optString("kind", "endpoint")
                if (kind != "endpoint" && kind != "hook") throw ApiError(400, "kind deve ser endpoint ou hook")
                val code = b.optString("code", "")
                if (code.length > 100000) throw ApiError(400, "codigo grande demais")
                val sched = b.optInt("schedule_min", 0).coerceIn(0, 10080)
                db.execSQL("INSERT OR IGNORE INTO _minidb_functions(name, code, kind, secret) VALUES(?,?,?,?)",
                    arrayOf<Any?>(n, code, kind, token(18)))
                db.execSQL("UPDATE _minidb_functions SET code=?, kind=?, schedule_min=?, enabled=? WHERE name=?",
                    arrayOf<Any?>(code, kind, sched, if (b.optBoolean("enabled", true)) 1L else 0L, n))
                return json(200, JSONObject().put("ok", true))
            }
            if (method == "POST" && seg[2] == "functions" && seg.size == 4 && seg[3] == "delete") {
                db.execSQL("DELETE FROM _minidb_functions WHERE name=?", arrayOf<Any?>(b.optString("name", "")))
                return json(200, JSONObject().put("ok", true))
            }
            if (method == "POST" && seg[2] == "functions" && seg.size == 4 && seg[3] == "test") {
                val row = functions.load(id, checkIdent(b.optString("name", ""), "funcao")) ?: throw ApiError(404, "funcao nao existe")
                val bodyVal: Any = b.opt("body") ?: JSONObject.NULL
                val raw = if (bodyVal == JSONObject.NULL) "" else bodyVal.toString()
                val req = JSONObject().put("method", "POST").put("path", "/test").put("query", JSONObject())
                    .put("headers", JSONObject()).put("body", bodyVal).put("raw", raw).put("role", "service")
                    .put("user", JSONObject.NULL).put("test", true)
                val r = functions.exec(id, row, req)
                val parsed: Any = r.value?.let { try { JSONTokener(it).nextValue() } catch (_: Exception) { it } } ?: JSONObject.NULL
                return json(200, JSONObject().put("ok", r.ok).put("value", parsed).put("log", r.log)
                    .put("error", r.error ?: JSONObject.NULL))
            }
            if (method == "GET" && seg[2] == "jobs") {
                val rows = db.rawQuery("SELECT id, fn, payload, run_at, attempts, status, error FROM _minidb_jobs ORDER BY id DESC LIMIT 50", null).use { cursorObjects(it) }
                return json(200, JSONObject().put("jobs", rows))
            }
            if (method == "POST" && seg[2] == "jobs" && seg.size == 4 && seg[3] == "clear") {
                db.execSQL("DELETE FROM _minidb_jobs WHERE status IN ('done','failed')")
                return json(200, JSONObject().put("ok", true))
            }
            if (method == "GET" && seg[2] == "env") {
                val rows = db.rawQuery("SELECT k, v FROM _minidb_env ORDER BY k", null).use { cursorObjects(it) }
                return json(200, JSONObject().put("env", rows))
            }
            if (method == "POST" && seg[2] == "env" && seg.size == 3) {
                val k = checkIdent(b.optString("key", ""), "nome da variavel")
                db.execSQL("INSERT OR REPLACE INTO _minidb_env(k, v) VALUES(?,?)", arrayOf<Any?>(k, b.optString("value", "")))
                return json(200, JSONObject().put("ok", true))
            }
            if (method == "POST" && seg[2] == "env" && seg.size == 4 && seg[3] == "delete") {
                db.execSQL("DELETE FROM _minidb_env WHERE k=?", arrayOf<Any?>(b.optString("key", "")))
                return json(200, JSONObject().put("ok", true))
            }
            if (method == "POST" && seg[2] == "access") {
                val t = checkIdent(b.optString("table", ""), "tabela")
                val a = b.optString("access", "private")
                if (a !in setOf("private", "read", "users", "open")) throw ApiError(400, "acesso invalido")
                db.execSQL("INSERT OR REPLACE INTO _minidb_rules(tbl, access) VALUES(?,?)", arrayOf<Any?>(t, a))
                return json(200, JSONObject().put("ok", true))
            }
        }
        throw ApiError(404, "nao existe")
    }

    // --------------------------------------------------------------- ficheiros
    private fun month(): String = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())

    private fun transferUsed(pid: String): Long =
        meta.rawQuery("SELECT bytes FROM transfer WHERE project_id=? AND month=?", arrayOf(pid, month())).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    private fun transferLimitMb(pid: String): Int =
        meta.rawQuery("SELECT transfer_mb FROM limits WHERE project_id=?", arrayOf(pid)).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else 5000
        }

    private fun checkTransfer(pid: String) {
        val mb = transferLimitMb(pid)
        if (mb > 0 && transferUsed(pid) > mb.toLong() * 1024 * 1024) throw ApiError(429, "limite de transferencia mensal atingido")
    }

    private fun addTransfer(pid: String, n: Long) {
        meta.execSQL("INSERT OR IGNORE INTO transfer(project_id, month, bytes) VALUES(?,?,0)", arrayOf<Any?>(pid, month()))
        meta.execSQL("UPDATE transfer SET bytes = bytes + ? WHERE project_id=? AND month=?", arrayOf<Any?>(n, pid, month()))
    }

    private fun sign(secret: String, data: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun skOf(pid: String): String =
        meta.rawQuery("SELECT sk FROM projects WHERE id=?", arrayOf(pid)).use {
            if (it.moveToFirst()) it.getString(0) else throw ApiError(404, "projeto nao existe")
        }

    /** GET /files/{projeto}/{ficheiro}: publico em buckets publicos; privado exige URL assinada. */
    private fun serveFile(pid: String, name: String, q: Map<String, String>): Resp {
        if (!Regex("^[A-Za-z0-9]+$").matches(pid) || name.contains("/") || name.startsWith(".")) throw ApiError(400, "nome invalido")
        val exists = meta.rawQuery("SELECT 1 FROM projects WHERE id=?", arrayOf(pid)).use { it.moveToFirst() }
        if (!exists) throw ApiError(404, "ficheiro nao existe")
        val db = pdb(pid)
        var found = false
        var isPublic = false
        var mime = "application/octet-stream"
        db.rawQuery(
            "SELECT f.mime, b.public FROM _minidb_files f LEFT JOIN _minidb_buckets b ON b.name=f.bucket WHERE f.path=?",
            arrayOf(name)
        ).use {
            if (it.moveToFirst()) { found = true; mime = it.getString(0) ?: mime; isPublic = it.getInt(1) == 1 }
        }
        if (!found) throw ApiError(404, "ficheiro nao existe")
        if (!isPublic) {
            val exp = q["exp"]?.toLongOrNull()
            val sig = q["sig"]
            if (exp == null || sig == null || exp < System.currentTimeMillis() / 1000 ||
                !java.security.MessageDigest.isEqual(sig.toByteArray(), sign(skOf(pid), "$name.$exp").toByteArray())
            ) throw ApiError(403, "ficheiro privado: precisa de URL assinada")
        }
        checkTransfer(pid)
        val f = File(File(ctx.filesDir, "p_$pid"), name)
        if (!f.isFile) throw ApiError(404, "ficheiro nao existe")
        val bytes = f.readBytes()
        addTransfer(pid, bytes.size.toLong())
        return Resp(200, bytes, mime)
    }

    private fun fileOwner(db: SQLiteDatabase, id: String, bucket: String): Triple<String, Long?, String>? =
        db.rawQuery("SELECT path, owner, mime FROM _minidb_files WHERE id=? AND bucket=?", arrayOf(id, bucket)).use {
            if (it.moveToFirst())
                Triple(it.getString(0), if (it.isNull(1)) null else it.getLong(1), it.getString(2) ?: "application/octet-stream")
            else null
        }

    /** /storage/...: buckets, upload, listagem, download, apagar e URLs assinadas. */
    private fun storage(
        method: String, parts: List<String>, h: Map<String, String>,
        db: SQLiteDatabase, pid: String, service: Boolean, userId: Long?, bodyBytes: ByteArray
    ): Resp {
        val logged = userId != null
        val dir = File(ctx.filesDir, "p_$pid")

        if (parts.size == 2 && parts[1] == "sign" && method == "POST") {
            val b = body(bodyBytes)
            val sec = b.optLong("expires_in", 3600).coerceIn(10L, 7L * 86400)
            var path: String? = null
            var owner: Long? = null
            db.rawQuery("SELECT path, owner FROM _minidb_files WHERE id=?", arrayOf(b.optString("id"))).use {
                if (it.moveToFirst()) { path = it.getString(0); owner = if (it.isNull(1)) null else it.getLong(1) }
            }
            val p = path ?: throw ApiError(404, "ficheiro nao existe")
            if (!service && !(logged && owner == userId)) throw ApiError(403, "sem permissao")
            val exp = System.currentTimeMillis() / 1000 + sec
            return json(200, JSONObject()
                .put("url", "/files/$pid/$p?exp=$exp&sig=" + sign(skOf(pid), "$p.$exp"))
                .put("expires_at", exp))
        }

        if (parts.size < 2) throw ApiError(404, "rota nao existe")
        val bucket = checkIdent(parts[1], "bucket")
        val isPublic = db.rawQuery("SELECT public FROM _minidb_buckets WHERE name=?", arrayOf(bucket)).use {
            if (it.moveToFirst()) it.getInt(0) == 1 else throw ApiError(404, "bucket nao existe")
        }

        if (method == "GET" && parts.size == 2) {
            val sql: String
            val args: Array<String>
            if (service || isPublic) {
                sql = "SELECT id, name, path, size, mime, created, owner FROM _minidb_files WHERE bucket=? ORDER BY created DESC LIMIT 500"
                args = arrayOf(bucket)
            } else if (logged) {
                sql = "SELECT id, name, path, size, mime, created, owner FROM _minidb_files WHERE bucket=? AND owner=? ORDER BY created DESC LIMIT 500"
                args = arrayOf(bucket, userId.toString())
            } else throw ApiError(403, "precisa de login")
            val rows = db.rawQuery(sql, args).use { cursorObjects(it) }
            if (isPublic) for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                o.put("url", "/files/$pid/" + o.getString("path"))
            }
            return json(200, rows)
        }

        if (method == "POST" && parts.size == 3) {
            if (!service && !logged) throw ApiError(403, "precisa de login para enviar ficheiros")
            if (parts[2].contains("/") || parts[2].startsWith(".")) throw ApiError(400, "nome invalido")
            checkTransfer(pid)
            val ext = parts[2].substringAfterLast('.', "").replace(Regex("[^A-Za-z0-9]"), "").take(8)
            val id = UUID.randomUUID().toString().replace("-", "").take(16)
            val path = id + if (ext.isEmpty()) "" else ".$ext"
            val ct = h["content-type"]
            val mime = if (ct != null && !ct.startsWith("application/x-www-form-urlencoded")) ct
                else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
            dir.mkdirs()
            File(dir, path).writeBytes(bodyBytes)
            db.execSQL("INSERT INTO _minidb_files VALUES(?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(id, bucket, parts[2], path, bodyBytes.size.toLong(), mime, System.currentTimeMillis(), userId))
            addTransfer(pid, bodyBytes.size.toLong())
            val o = JSONObject().put("id", id).put("bucket", bucket).put("name", parts[2])
                .put("size", bodyBytes.size).put("mime", mime)
            if (isPublic) o.put("url", "/files/$pid/$path")
            return json(201, o)
        }

        if (method == "GET" && parts.size == 3) {
            val f = fileOwner(db, parts[2], bucket) ?: throw ApiError(404, "ficheiro nao existe")
            if (!isPublic && !service && !(logged && f.second == userId)) throw ApiError(403, "ficheiro privado")
            checkTransfer(pid)
            val bytes = File(dir, f.first).readBytes()
            addTransfer(pid, bytes.size.toLong())
            return Resp(200, bytes, f.third)
        }

        if (method == "DELETE" && parts.size == 3) {
            val f = fileOwner(db, parts[2], bucket) ?: throw ApiError(404, "ficheiro nao existe")
            if (!service && !(logged && f.second == userId)) throw ApiError(403, "sem permissao")
            File(dir, f.first).delete()
            db.execSQL("DELETE FROM _minidb_files WHERE id=?", arrayOf<Any?>(parts[2]))
            return json(200, JSONObject().put("ok", true))
        }
        throw ApiError(405, "metodo nao suportado")
    }

    // ----------------------------------------------------------- API dos sites
    private fun projectRoute(
        method: String, parts: List<String>, q: Map<String, String>,
        h: Map<String, String>, bodyBytes: ByteArray
    ): Resp {
        val key = h["apikey"] ?: q["apikey"] ?: throw ApiError(401, "falta a chave (header apikey)")
        var pid: String? = null
        var service = false
        meta.rawQuery("SELECT id, sk FROM projects WHERE pk=? OR sk=?", arrayOf(key, key)).use {
            if (it.moveToFirst()) { pid = it.getString(0); service = it.getString(1) == key }
        }
        val projectId = pid ?: throw ApiError(401, "chave invalida")
        checkRate(key, projectId)
        val db = pdb(projectId)

        var userId: Long? = null
        val auth = h["authorization"] ?: ""
        if (auth.startsWith("Bearer ")) {
            db.rawQuery("SELECT user_id FROM _minidb_sessions WHERE token=? AND created>?",
                arrayOf(auth.substring(7), (System.currentTimeMillis() - 30L * 86400000L).toString())).use {
                if (it.moveToFirst()) userId = it.getLong(0)
            }
        }
        val logged = userId != null
        if (method != "GET" && method != "DELETE" && (parts[0] == "rest" || parts[0] == "storage")) checkQuota(projectId)

        when (parts[0]) {
            "sql" -> {
                if (method != "POST") throw ApiError(405, "use POST")
                if (!service) throw ApiError(403, "SQL direto exige a chave secreta")
                val b = body(bodyBytes)
                if (!Regex("^\\s*(select|pragma|with|explain)\\b", RegexOption.IGNORE_CASE).containsMatchIn(b.optString("sql", ""))) checkQuota(projectId)
                return json(200, runSql(db, b.optString("sql", ""), b.optJSONArray("params")))
            }
            "auth" -> if (parts.size == 2) {
                if (parts[1] == "logout" && method == "POST") {
                    if (auth.startsWith("Bearer ")) db.execSQL("DELETE FROM _minidb_sessions WHERE token=?", arrayOf<Any?>(auth.substring(7)))
                    return json(200, JSONObject().put("ok", true))
                }
                return auth(method, parts[1], db, userId, bodyBytes)
            }
            "storage" -> return storage(method, parts, h, db, projectId, service, userId, bodyBytes)
            "fn" -> if (parts.size == 2) {
                val row = functions.load(projectId, checkIdent(parts[1], "funcao")) ?: throw ApiError(404, "funcao nao existe")
                if (row.kind != "endpoint" || !row.enabled) throw ApiError(404, "funcao nao existe")
                val req = fnRequest(method, "/fn/" + parts[1], q, h, bodyBytes, if (service) "service" else "anon", userId, db)
                return fnResponse(functions.exec(projectId, row, req))
            }
            "rest" -> if (parts.size in 2..3) return rest(method, parts, q, db, service, logged, bodyBytes)
        }
        throw ApiError(404, "rota nao existe")
    }

    private fun auth(method: String, action: String, db: SQLiteDatabase, userId: Long?, bodyBytes: ByteArray): Resp {
        if (action == "me" && method == "GET") {
            val uid = userId ?: throw ApiError(401, "sem sessao")
            val email = db.rawQuery("SELECT email FROM _minidb_users WHERE id=?", arrayOf(uid.toString())).use {
                if (it.moveToFirst()) it.getString(0) else ""
            }
            return json(200, JSONObject().put("id", uid).put("email", email))
        }
        if ((action == "signup" || action == "login") && method == "POST") {
            val b = body(bodyBytes)
            val email = b.optString("email", "").trim().lowercase()
            val pw = b.optString("password", "")
            if (email.isEmpty() || pw.length < 6) throw ApiError(400, "email e senha (minimo 6 caracteres) obrigatorios")
            val uid: Long
            if (action == "signup") {
                val exists = db.rawQuery("SELECT 1 FROM _minidb_users WHERE email=?", arrayOf(email)).use { it.moveToFirst() }
                if (exists) throw ApiError(409, "email ja registado")
                val salt = token(8)
                db.execSQL("INSERT INTO _minidb_users(email, salt, hash, created) VALUES(?,?,?,?)",
                    arrayOf<Any?>(email, salt, hashPw(pw, salt), System.currentTimeMillis()))
                uid = db.rawQuery("SELECT last_insert_rowid()", null).use { it.moveToFirst(); it.getLong(0) }
            } else {
                var found: Long? = null
                db.rawQuery("SELECT id, salt, hash FROM _minidb_users WHERE email=?", arrayOf(email)).use {
                    if (it.moveToFirst() && hashPw(pw, it.getString(1)) == it.getString(2)) found = it.getLong(0)
                }
                uid = found ?: throw ApiError(401, "email ou senha errados")
            }
            val tok = token(32)
            db.execSQL("INSERT INTO _minidb_sessions VALUES(?,?,?)", arrayOf<Any?>(tok, uid, System.currentTimeMillis()))
            return json(200, JSONObject().put("token", tok)
                .put("user", JSONObject().put("id", uid).put("email", email)))
        }
        if (action == "password" && method == "POST") {
            val uid = userId ?: throw ApiError(401, "sem sessao")
            val b = body(bodyBytes)
            val np = b.optString("new_password", "")
            if (np.length < 6) throw ApiError(400, "senha nova: minimo 6 caracteres")
            var ok = false
            db.rawQuery("SELECT salt, hash FROM _minidb_users WHERE id=?", arrayOf(uid.toString())).use {
                if (it.moveToFirst() && hashPw(b.optString("old_password", ""), it.getString(0)) == it.getString(1)) ok = true
            }
            if (!ok) throw ApiError(401, "senha atual errada")
            val salt = token(8)
            db.execSQL("UPDATE _minidb_users SET salt=?, hash=? WHERE id=?", arrayOf<Any?>(salt, hashPw(np, salt), uid))
            db.execSQL("DELETE FROM _minidb_sessions WHERE user_id=?", arrayOf<Any?>(uid))
            return json(200, JSONObject().put("ok", true))
        }
        if (action == "reset" && method == "POST") {
            val b = body(bodyBytes)
            val email = b.optString("email", "").trim().lowercase()
            val code = b.optString("code", "")
            val np = b.optString("new_password", "")
            if (np.length < 6) throw ApiError(400, "senha nova: minimo 6 caracteres")
            var found: Long? = null
            db.rawQuery(
                "SELECT r.user_id FROM _minidb_resets r JOIN _minidb_users u ON u.id=r.user_id WHERE u.email=? AND r.code=? AND r.expires>?",
                arrayOf(email, code, System.currentTimeMillis().toString())
            ).use { if (it.moveToFirst()) found = it.getLong(0) }
            val uid = found ?: throw ApiError(400, "codigo invalido ou expirado")
            val salt = token(8)
            db.execSQL("UPDATE _minidb_users SET salt=?, hash=? WHERE id=?", arrayOf<Any?>(salt, hashPw(np, salt), uid))
            db.execSQL("DELETE FROM _minidb_sessions WHERE user_id=?", arrayOf<Any?>(uid))
            db.execSQL("DELETE FROM _minidb_resets WHERE user_id=?", arrayOf<Any?>(uid))
            return json(200, JSONObject().put("ok", true))
        }
        throw ApiError(404, "nao existe")
    }

    private fun rest(
        method: String, parts: List<String>, q: Map<String, String>,
        db: SQLiteDatabase, service: Boolean, logged: Boolean, bodyBytes: ByteArray
    ): Resp {
        val t = checkIdent(parts[1], "tabela")
        if (t.startsWith("_minidb") || t.startsWith("sqlite_")) throw ApiError(404, "tabela nao existe")
        if (!tableExists(db, t)) throw ApiError(404, "tabela nao existe")
        val access = accessOf(db, t)
        val canRead = service || access in setOf("read", "users", "open")
        val canWrite = service || access == "open" || (access == "users" && logged)
        val rowid = if (parts.size == 3) parts[2].toLongOrNull() ?: throw ApiError(400, "id invalido") else null

        if (method == "GET") {
            if (!canRead) throw ApiError(403, "tabela privada")
            val where = ArrayList<String>(); val args = ArrayList<String>()
            if (rowid != null) { where.add("rowid=?"); args.add(rowid.toString()) }
            for ((k, v) in q) {
                if (k in setOf("limit", "offset", "order", "apikey")) continue
                where.add("\"${checkIdent(k, "filtro")}\"=?"); args.add(v)
            }
            var sql = "SELECT rowid AS _rowid, * FROM \"$t\""
            if (where.isNotEmpty()) sql += " WHERE " + where.joinToString(" AND ")
            val order = q["order"]
            if (order != null) {
                val col = checkIdent(order.trimStart('-'), "order")
                sql += " ORDER BY \"$col\" " + if (order.startsWith("-")) "DESC" else "ASC"
            }
            sql += " LIMIT ${minOf(q["limit"]?.toIntOrNull() ?: 100, 1000)} OFFSET ${q["offset"]?.toIntOrNull() ?: 0}"
            val rows = db.rawQuery(sql, args.toTypedArray()).use { cursorObjects(it) }
            if (rowid != null) {
                if (rows.length() == 0) throw ApiError(404, "nao encontrado")
                return json(200, rows.get(0))
            }
            return json(200, rows)
        }

        if (!canWrite) throw ApiError(403, if (access == "private" || access == "read") "tabela so de leitura" else "precisa de login")
        val b = body(bodyBytes)

        if (method == "POST" && rowid == null) {
            val keys = b.keys().asSequence().toList()
            val sql = if (keys.isEmpty()) "INSERT INTO \"$t\" DEFAULT VALUES" else
                "INSERT INTO \"$t\"(" + keys.joinToString(",") { "\"${checkIdent(it, "coluna")}\"" } +
                    ") VALUES(" + keys.joinToString(",") { "?" } + ")"
            val vals = Array<Any?>(keys.size) { i -> val v = b.get(keys[i]); if (v == JSONObject.NULL) null else if (v is Boolean) (if (v) 1L else 0L) else v }
            db.execSQL(sql, vals)
            val id = db.rawQuery("SELECT last_insert_rowid()", null).use { it.moveToFirst(); it.getLong(0) }
            val row = db.rawQuery("SELECT rowid AS _rowid, * FROM \"$t\" WHERE rowid=?", arrayOf(id.toString())).use { cursorObjects(it) }
            return json(201, row.get(0))
        }
        if ((method == "PATCH" || method == "PUT") && rowid != null) {
            val keys = b.keys().asSequence().toList()
            if (keys.isEmpty()) throw ApiError(400, "nada para atualizar")
            val sql = "UPDATE \"$t\" SET " + keys.joinToString(",") { "\"${checkIdent(it, "coluna")}\"=?" } + " WHERE rowid=?"
            val vals = ArrayList<Any?>()
            for (k in keys) { val v = b.get(k); vals.add(if (v == JSONObject.NULL) null else if (v is Boolean) (if (v) 1L else 0L) else v) }
            vals.add(rowid)
            db.execSQL(sql, vals.toTypedArray())
            val row = db.rawQuery("SELECT rowid AS _rowid, * FROM \"$t\" WHERE rowid=?", arrayOf(rowid.toString())).use { cursorObjects(it) }
            if (row.length() == 0) throw ApiError(404, "nao encontrado")
            return json(200, row.get(0))
        }
        if (method == "DELETE" && rowid != null) {
            db.execSQL("DELETE FROM \"$t\" WHERE rowid=?", arrayOf<Any?>(rowid))
            return json(200, JSONObject().put("ok", true))
        }
        throw ApiError(405, "metodo nao suportado")
    }
}
