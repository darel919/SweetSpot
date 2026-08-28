package com.darelisme.sweetspot.audio.engine

internal class AudioOperationGate {
    private enum class Owner {
        NONE,
        TRANSIENT,
        PERSISTENT,
    }

    private var owner = Owner.NONE

    @Synchronized
    fun tryAcquireTransient(): Boolean {
        if (owner != Owner.NONE) return false
        owner = Owner.TRANSIENT
        return true
    }

    @Synchronized
    fun promoteToPersistent(): Boolean {
        if (owner != Owner.TRANSIENT) return false
        owner = Owner.PERSISTENT
        return true
    }

    @Synchronized
    fun releaseTransient() {
        if (owner == Owner.TRANSIENT) owner = Owner.NONE
    }

    @Synchronized
    fun releasePersistent() {
        if (owner == Owner.PERSISTENT) owner = Owner.NONE
    }

    @Synchronized
    fun forceRelease() {
        owner = Owner.NONE
    }

    @Synchronized
    fun isHeld(): Boolean = owner != Owner.NONE

    @Synchronized
    fun isPersistentHeld(): Boolean = owner == Owner.PERSISTENT
}
