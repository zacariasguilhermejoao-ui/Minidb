package com.minidb.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import fi.iki.elonen.NanoHTTPD

const val LOCAL_PORT = 8080

/** Mantem o banco de dados vivo em segundo plano (com notificacao fixa). */
class ServerService : Service() {
    private var server: HttpServer? = null
    private var relay: Relay? = null
    private var engine: Engine? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (server == null) {
            val prefs = Store.prefs(this); Store.adminToken(this)
            val engine = Engine(this, prefs); this.engine = engine
            val r = Relay(engine, prefs); engine.relayState = { r.state }; relay = r
            server = HttpServer(engine, LOCAL_PORT).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            r.start(); engine.functions.startScheduler()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minidb:server").also { it.acquire() }
        }
        return START_STICKY
    }
    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("minidb", "MiniDB", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "minidb").setContentTitle("MiniDB ligado").setContentText("O teu banco de dados esta a funcionar").setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }
    override fun onDestroy() {
        relay?.stop(); engine?.functions?.stopScheduler(); server?.stop()
        wake?.let { if (it.isHeld) it.release() }; super.onDestroy()
    }
}
