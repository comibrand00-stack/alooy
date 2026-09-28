package com.solarmovie.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class VsWasm {
    // Runs the rotating vidsrc decryptor module exactly like vsdec.js:
    // alloc(len) -> write envelope -> decrypt(ptr,len) -> read(ptr+12, outLen)
    // Returns decrypted stream URL lines, or empty list on any failure.
    fun decryptStreamUrls(wasmBytes: ByteArray, envelopeB64: String): List<String> {
        try {
            val enc = android.util.Base64.decode(envelopeB64.trim(), android.util.Base64.DEFAULT)
            if (enc.size < 13) return emptyList()
            val module = com.dylibso.chicory.wasm.Parser.parse(wasmBytes)
            val instance = com.dylibso.chicory.runtime.Instance.builder(module).build()
            val memory = instance.memory()
            val alloc = instance.export("alloc")
            val ptr = alloc.apply(enc.size.toLong())[0].toInt()
            memory.write(ptr, enc, 0, enc.size)
            val decrypt = instance.export("decrypt")
            val outLen = decrypt.apply(ptr.toLong(), enc.size.toLong())[0].toInt()
            if (outLen <= 0 || outLen > enc.size) return emptyList()
            val plain = memory.readBytes(ptr + 12, outLen)
            return String(plain, Charsets.UTF_8).lines()
                .map { it.trim() }
                .filter { it.startsWith("https://") }
        } catch (_: Throwable) {
            return emptyList()
        }
    }
}
