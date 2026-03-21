package com.example.new_tv_app.iptv

data class PlayerAccount(
    val username: String,
    val status: String,
    val expDateUnix: Long?,
    val serverNowUnix: Long,
    val isTrial: Boolean,
    val maxConnections: Int,
    val activeConnections: Int,
    val allowedOutputFormats: List<String>,
    val createdAtUnix: Long?,
    val serverUrl: String,
    val serverProtocol: String,
)
