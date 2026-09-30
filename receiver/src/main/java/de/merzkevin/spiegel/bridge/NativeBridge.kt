package de.merzkevin.spiegel.bridge

/** JNI entry points of libspiegel_native.so (native_bridge.cpp). */
object NativeBridge {
    init {
        System.loadLibrary("spiegel_native")
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
}
