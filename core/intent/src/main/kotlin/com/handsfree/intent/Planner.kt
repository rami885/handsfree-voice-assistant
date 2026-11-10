package com.handsfree.intent

import com.handsfree.guard.ActionSnapshot
import com.handsfree.guard.ToolSpec
import kotlinx.coroutines.delay

sealed interface PlanResult {
    data class Plan(val actions: List<ActionSnapshot>) : PlanResult
    data object Unsupported : PlanResult
}
interface Planner {
    suspend fun plan(text: String, tools: List<ToolSpec>): PlanResult
}
class FakePlanner(private val result: PlanResult = PlanResult.Unsupported, private val delayMs: Long = 0) : Planner {
    var calls: Int = 0
        private set
    var lastTools: List<ToolSpec> = emptyList()
        private set
    override suspend fun plan(text: String, tools: List<ToolSpec>): PlanResult {
        calls++
        lastTools = tools.toList()
        delay(delayMs)
        return result
    }
}
