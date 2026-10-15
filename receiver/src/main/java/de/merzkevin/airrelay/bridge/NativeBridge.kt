/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.bridge

/** JNI entry points of libairrelay_native.so (native_bridge.cpp). */
object NativeBridge {
    init {
        System.loadLibrary("airrelay_native")
    }

    /** Returns a handle, or 0 on failure. hwAddr: 6 bytes device id. */
    external fun nativeInit(
        callbacks: NativeCallbacks, hwAddr: ByteArray, name: String,
        width: Int, height: Int, fps: Int, advertiseAlac: Boolean,
    ): Long

    /** Bound port, or -1. port 0 lets the system choose. */
    external fun nativeStart(handle: Long, port: Int): Int
    external fun nativeStop(handle: Long)
    external fun nativeDestroy(handle: Long)
    external fun nativeDisconnect(handle: Long)

    /** Flat [key, value, key, value, ...] */
    external fun nativeTxtRecords(handle: Long, raop: Boolean): Array<String>?
    external fun nativeRaopServiceName(handle: Long): String?

    external fun nativeSetDefaultStreamValues(sampleRate: Int, framesPerBurst: Int)
    external fun nativeAudioStart(handle: Long): Boolean
    external fun nativeAudioStop(handle: Long)
    external fun nativeAudioFormat(handle: Long, ct: Int, spf: Int)
    external fun nativeSetGain(handle: Long, gainQ15: Int)

    /** Full path of the playfair helper program (ReceiverConfig.PLAYFAIR_HELPER in
     *  nativeLibraryDir). Set before the first mirroring session; false if the path is too long. */
    external fun nativeSetPlayfairHelper(path: String): Boolean
}
