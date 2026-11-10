package com.handsfree.audio

import kotlinx.coroutines.flow.StateFlow

interface AudioRoute {
    val confirmed: StateFlow<Boolean>
    suspend fun open(): Boolean
    fun close()
}
