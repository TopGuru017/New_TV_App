package com.example.new_tv_app.iptv

data class VodCategory(
    val id: String,
    val name: String,
)

data class VodMovieItem(
    val streamId: String,
    val name: String,
    val coverUrl: String?,
    val plot: String?,
    val categoryId: String?,
    val containerExtension: String,
)

data class SeriesCategory(
    val id: String,
    val name: String,
)

data class SeriesShow(
    val seriesId: String,
    val name: String,
    val coverUrl: String?,
    val plot: String?,
    val categoryId: String?,
)
