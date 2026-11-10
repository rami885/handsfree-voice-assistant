package com.handsfree.probes

import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService

class ProbeScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val start = SystemClock.elapsedRealtime()
        respondToCall(callDetails, CallResponse.Builder().setDisallowCall(false).build())
        val controller = (application as ProbesApp).controller
        controller.repo.emit("T1b", "screening", "allow_response_ms", "elapsed=${SystemClock.elapsedRealtime() - start}")
        if (controller.screeningEnabled && callDetails.callDirection == Call.Details.DIRECTION_INCOMING) controller.screened(callDetails.handle?.schemeSpecificPart)
    }
}
