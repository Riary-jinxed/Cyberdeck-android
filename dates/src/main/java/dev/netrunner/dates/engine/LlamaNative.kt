package dev.netrunner.dates.engine

/** llama.cpp, through cpp/llama_jni.cpp: GGUF models on the CPU. One thread at a time. */
object LlamaNative {

    /** Receives each piece of the answer (whole UTF-8 characters); false stops it. */
    fun interface Callback {
        fun onBytes(bytes: ByteArray): Boolean
    }

    init {
        System.loadLibrary("datesllama")
    }

    /** A handle to pass to the other calls, or 0 when the model cannot be loaded. */
    @JvmStatic external fun load(path: String, threads: Int, nCtx: Int): Long

    @JvmStatic external fun free(handle: Long)

    @JvmStatic external fun generate(
        handle: Long,
        system: String,
        roles: BooleanArray,
        texts: Array<String>,
        temperature: Float,
        maxTokens: Int,
        callback: Callback,
    ): Boolean

    /** Decodes the conversation ahead of the next answer, so that answer only pays for the new message. */
    @JvmStatic external fun prefill(handle: Long, system: String, roles: BooleanArray, texts: Array<String>): Boolean
}
