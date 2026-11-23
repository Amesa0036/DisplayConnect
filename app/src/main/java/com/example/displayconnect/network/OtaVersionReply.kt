package com.example.displayconnect.network

import kotlinx.coroutines.CompletableDeferred

/** VERSION has no request ID. Permit one request on one GATT generation only. */
class OtaVersionReply {
    private var connection: Any? = null
    private var pending: CompletableDeferred<OtaBootIdentity>? = null
    @Synchronized fun begin(owner: Any): CompletableDeferred<OtaBootIdentity> {
        check(pending == null) { "Consulta OTA pendiente" }
        return CompletableDeferred<OtaBootIdentity>().also { connection=owner; pending=it }
    }
    @Synchronized fun receive(owner: Any, line: String): Boolean {
        if (owner !== connection || pending == null || !line.startsWith("OTA:VERSION ")) return false
        val reply=pending ?: return false
        runCatching { OtaBootIdentity.parse(line) }.fold(
            onSuccess={reply.complete(it)}, onFailure={reply.completeExceptionally(it)})
        return true
    }
    @Synchronized fun finish(reply: CompletableDeferred<OtaBootIdentity>) {
        if (pending === reply) { pending=null;connection=null }
    }
    @Synchronized fun disconnected() {pending?.cancel();pending=null;connection=null}
}
