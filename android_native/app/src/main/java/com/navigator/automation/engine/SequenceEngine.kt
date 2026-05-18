package com.navigator.automation.engine

import android.content.Context
import com.navigator.automation.service.AutomationAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RunState { IDLE, RUNNING, PAUSED, DONE, ERROR }

data class EngineStatus(
    val state: RunState = RunState.IDLE,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val loopIteration: Int = 0,
    val message: String = ""
)

class SequenceEngine(
    private val context: Context,
    private val sequence: Sequence,
    private val scope: CoroutineScope
) {
    private val _status = MutableStateFlow(EngineStatus(totalSteps = sequence.steps.size))
    val status: StateFlow<EngineStatus> = _status

    private var job: Job? = null
    private var paused = false
    private var stopped = false

    fun start() {
        stopped = false
        paused = false
        job = scope.launch { runSequence() }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        emit(RunState.IDLE, message = "Stopped")
    }

    fun pause()  { paused = true;  emit(RunState.PAUSED,  message = "Paused") }
    fun resume() { paused = false; emit(RunState.RUNNING, message = "Running") }

    private fun emit(state: RunState, step: Int = _status.value.currentStep, message: String = "") {
        _status.value = _status.value.copy(state = state, currentStep = step, message = message)
    }

    private suspend fun runSequence() {
        val svc = AutomationAccessibilityService.instance
        if (svc == null) {
            emit(RunState.ERROR, message = "Accessibility service not active")
            return
        }

        emit(RunState.RUNNING, message = "Starting")

        val totalLoops = sequence.loopCount
        var iteration  = 0

        while (!stopped) {
            iteration++
            _status.value = _status.value.copy(loopIteration = iteration)

            for ((idx, step) in sequence.steps.withIndex()) {
                if (stopped) break

                // Pause check
                while (paused && !stopped) delay(200)
                if (stopped) break

                emit(RunState.RUNNING, step = idx, message = step.label())
                runStep(svc, step)
                delay(300) // brief settle between steps
            }

            if (totalLoops > 0 && iteration >= totalLoops) break
            if (!stopped && totalLoops != 1) {
                delay((sequence.loopDelaySeconds * 1000).toLong())
            }
        }

        if (!stopped) emit(RunState.DONE, message = "Finished")
    }

    private suspend fun runStep(svc: AutomationAccessibilityService, step: Step) {
        when (step) {
            is Step.TapText -> {
                withContext(Dispatchers.Default) {
                    val node = retryFind(svc, step.text)
                    if (node != null) svc.tapNode(node)
                }
            }

            is Step.WaitSeconds -> delay((step.seconds * 1000).toLong())

            is Step.TypeText -> withContext(Dispatchers.Default) { svc.typeText(step.text) }

            is Step.Swipe -> withContext(Dispatchers.Default) { svc.swipe(step.direction) }

            is Step.PressKey -> withContext(Dispatchers.Default) {
                when (step.key.lowercase()) {
                    "back"   -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    "home"   -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    "recent", "recents" ->
                        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                    "notifications" ->
                        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                    else -> {}
                }
            }

            is Step.LaunchApp -> {
                withContext(Dispatchers.Default) { svc.launchApp(step.target) }
                delay(2500) // let the app finish loading before next step
            }

            is Step.WatchCorners -> {
                // Scan for dismiss UI for up to timeoutSeconds
                val deadline = System.currentTimeMillis() + step.timeoutSeconds * 1_000L
                var found = false
                while (System.currentTimeMillis() < deadline && !stopped) {
                    val node = withContext(Dispatchers.Default) { svc.findDismissNode() }
                    if (node != null) {
                        withContext(Dispatchers.Default) { svc.tapNode(node) }
                        found = true
                        break
                    }
                    delay(500)
                }
                if (!found) {
                    // Fallback: try pressing back
                    svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
            }

            is Step.DismissAd -> {
                withContext(Dispatchers.Default) {
                    val node = svc.findDismissNode()
                    if (node != null) svc.tapNode(node)
                    else svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
            }

            is Step.CheckBranch -> {
                val triggered = withContext(Dispatchers.Default) {
                    svc.findNodeByText(step.triggerText) != null
                }
                if (triggered) {
                    val branch = SequenceRepository.load(context, step.thenSequence)
                    if (branch != null) {
                        val branchEngine = SequenceEngine(context, branch, scope)
                        branchEngine.start()
                        // Wait for branch to finish
                        while (branchEngine.status.value.state == RunState.RUNNING ||
                               branchEngine.status.value.state == RunState.PAUSED) {
                            delay(300)
                        }
                    }
                }
            }

            Step.PressBack ->
                svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

            Step.PressHome ->
                svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    /** Try to find a node by text up to 3 times with 400 ms between attempts. */
    private suspend fun retryFind(
        svc: AutomationAccessibilityService,
        text: String,
        attempts: Int = 3
    ): android.view.accessibility.AccessibilityNodeInfo? {
        repeat(attempts) {
            val node = svc.findNodeByText(text)
            if (node != null) return node
            delay(400)
        }
        return null
    }
}
