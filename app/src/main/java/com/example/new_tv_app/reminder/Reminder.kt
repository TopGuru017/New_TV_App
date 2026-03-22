package com.example.new_tv_app.reminder

import org.json.JSONObject

data class Reminder(
    val id: String,
    val streamId: String,
    val channelName: String,
    val channelIconUrl: String?,
    val programTitle: String,
    val programDescription: String,
    val startUnix: Long,
    val endUnix: Long,
    val epgImageUrl: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("streamId", streamId)
        put("channelName", channelName)
        put("channelIconUrl", channelIconUrl ?: "")
        put("programTitle", programTitle)
        put("programDescription", programDescription)
        put("startUnix", startUnix)
        put("endUnix", endUnix)
        put("epgImageUrl", epgImageUrl ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject): Reminder = Reminder(
            id = o.getString("id"),
            streamId = o.getString("streamId"),
            channelName = o.getString("channelName"),
            channelIconUrl = o.optString("channelIconUrl").takeIf { it.isNotEmpty() },
            programTitle = o.getString("programTitle"),
            programDescription = o.optString("programDescription", ""),
            startUnix = o.getLong("startUnix"),
            endUnix = o.getLong("endUnix"),
            epgImageUrl = o.optString("epgImageUrl").takeIf { it.isNotEmpty() },
        )
    }
}
