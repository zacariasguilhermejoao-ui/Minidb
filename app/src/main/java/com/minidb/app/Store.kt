package com.minidb.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom

object Store {
    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("minidb", Context.MODE_PRIVATE)

    /** Token que so o proprio app conhece: protege o painel de admin. */
    fun adminToken(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("admin_token", null)?.let { return it }
        val b = ByteArray(24); SecureRandom().nextBytes(b)
        val t = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        p.edit().putString("admin_token", t).apply()
        return t
    }
}
