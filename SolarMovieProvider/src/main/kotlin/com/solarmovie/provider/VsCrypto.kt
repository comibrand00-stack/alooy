package com.solarmovie.provider

// Pure-Kotlin replica of the vidsrc rotating decryptor (vsdec.js):
// the wasm module re-obfuscates every few minutes, but always keeps
//   key[i] = u32(mem[small + 4i]) XOR u32(mem[base + 4i])   (8 words)
// with (small, base) discoverable as 8x (const,load,const,load,xor)
// progressions in the code section, then standard ChaCha20-IETF
// (nonce = envelope[0:12], counter = 0).
// Every candidate output is validated (must be https URLs), so any
// future format change degrades to "no links" instead of garbage.
class VsCrypto {

    fun decryptStreamUrls(wasmBytes: ByteArray, envelopeB64: String): List<String> {
        try {
            val enc = try {
                android.util.Base64.decode(envelopeB64.trim(), android.util.Base64.DEFAULT)
            } catch (_: Exception) { return emptyList() }
            if (enc.size < 13) return emptyList()
            val img = buildImage(wasmBytes) ?: return emptyList()
            val code = codeSection(wasmBytes) ?: return emptyList()
            val runs = findRuns(code).take(4)
            if (runs.isEmpty()) return emptyList()
            val nonce = enc.copyOfRange(0, 12)
            val ct = enc.copyOfRange(12, enc.size)
            for (run in runs) {
                val key = ByteArray(32)
                for (i in 0 until 8) {
                    val a = readU32(img, run.small + i * 4) ?: return emptyList()
                    val b = readU32(img, run.base + i * 4) ?: return emptyList()
                    writeU32(key, i * 4, a xor b)
                }
                for (ctr in 0..1) {
                    val trial = chacha(key, nonce, ct, ctr, 200)
                    val head = String(trial, Charsets.UTF_8)
                    if (head.startsWith("https://") && (head.contains("m3u8") || head.contains(".mp4"))) {
                        val full = chacha(key, nonce, ct, ctr, ct.size)
                        return String(full, Charsets.UTF_8).lines()
                            .map { it.trim() }
                            .filter { it.startsWith("https://") }
                            .ifEmpty { return emptyList() }
                    }
                }
            }
            return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private data class Run(val small: Int, val base: Int)

    // ---------- wasm section helpers ----------

    private fun readU32(img: ByteArray, off: Int): Int? {
        if (off < 0 || off + 4 > img.size) return null
        return (img[off].toInt() and 0xFF) or
                ((img[off + 1].toInt() and 0xFF) shl 8) or
                ((img[off + 2].toInt() and 0xFF) shl 16) or
                ((img[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeU32(buf: ByteArray, off: Int, v: Int) {
        for (i in 0 until 4) buf[off + i] = ((v ushr (i * 8)) and 0xFF).toByte()
    }

    private fun readVarU32(buf: ByteArray, off: Int): Pair<Int, Int>? {
        var v = 0
        var s = 0
        var i = off
        while (true) {
            if (i >= buf.size) return null
            val b = buf[i++].toInt() and 0xFF
            v = v or ((b and 0x7F) shl s)
            if ((b and 0x80) == 0) break
            s += 7
            if (s > 35) return null
        }
        return Pair(v, i)
    }

    private fun readVarS32(buf: ByteArray, off: Int): Pair<Int, Int>? {
        var v = 0
        var s = 0
        var i = off
        var b = 0
        do {
            if (i >= buf.size) return null
            b = buf[i++].toInt() and 0xFF
            v = v or ((b and 0x7F) shl s)
            s += 7
            if (s > 35) return null
        } while ((b and 0x80) != 0)
        if (s < 32 && (b and 0x40) != 0) v = v or (-(1 shl s))
        return Pair(v, i)
    }

    // Linear memory image from active (flags & 1 == 0), const-init data segments.
    private fun buildImage(wasm: ByteArray): ByteArray? {
        return try {
            val img = ByteArray(262144)
            var off = 8
            while (off < wasm.size) {
                val id = wasm[off++].toInt() and 0xFF
                val r = readVarU32(wasm, off) ?: return null
                off = r.second
                if (id == 11) {
                    val end = off + r.first
                    var p = off
                    val c = readVarU32(wasm, p) ?: return null
                    p = c.second
                    repeat(c.first) {
                        val f = readVarU32(wasm, p) ?: return null
                        p = f.second
                        val flags = f.first
                        if ((flags and 1) == 0) {
                            if ((flags and 2) != 0) {
                                val t = readVarU32(wasm, p) ?: return null
                                p = t.second
                            }
                            var initOff = -1
                            if (p < wasm.size && wasm[p] == 0x41.toByte()) {
                                val v = readVarS32(wasm, p + 1) ?: return null
                                initOff = v.first
                            }
                            while (p < wasm.size && wasm[p] != 0x0b.toByte()) p++
                            if (p >= wasm.size) return null
                            p++
                            val sz = readVarU32(wasm, p) ?: return null
                            p = sz.second
                            if (initOff >= 0 && initOff < img.size) {
                                val n = minOf(sz.first, img.size - initOff, wasm.size - p)
                                if (n < 0) return null
                                wasm.copyInto(img, initOff, p, p + n)
                            }
                            p += sz.first
                        } else {
                            // passive segment: not auto-loaded, skip bytes
                            val sz = readVarU32(wasm, p) ?: return null
                            p = sz.second + sz.first
                        }
                    }
                    off = end
                } else {
                    off += r.first
                }
            }
            img
        } catch (_: Exception) { null }
    }

    private fun codeSection(wasm: ByteArray): ByteArray? {
        return try {
            var off = 8
            while (off < wasm.size) {
                val id = wasm[off++].toInt() and 0xFF
                val r = readVarU32(wasm, off) ?: return null
                off = r.second
                if (id == 10) return wasm.copyOfRange(off, off + r.first)
                off += r.first
            }
            null
        } catch (_: Exception) { null }
    }

    // One triple: i32.const S, i32.load, i32.const B, i32.load, xor/add/or/and
    private data class KeyTriple(val s: Int, val b: Int, val op: Int, val at: Int, val end: Int)

    private fun matchTriple(code: ByteArray, p: Int): KeyTriple? {
        if (p >= code.size || code[p] != 0x41.toByte()) return null
        val s = readVarS32(code, p + 1) ?: return null
        var q = s.second
        if (q >= code.size || (code[q] != 0x28.toByte() && code[q] != 0x2c.toByte())) return null
        q++
        var t = readVarU32(code, q) ?: return null
        q = t.second
        t = readVarU32(code, q) ?: return null
        q = t.second
        if (q >= code.size || code[q] != 0x41.toByte()) return null
        val b = readVarS32(code, q + 1) ?: return null
        q = b.second
        if (q >= code.size || (code[q] != 0x28.toByte() && code[q] != 0x2c.toByte())) return null
        q++
        t = readVarU32(code, q) ?: return null
        q = t.second
        t = readVarU32(code, q) ?: return null
        q = t.second
        if (q >= code.size) return null
        val op = code[q].toInt() and 0xFF
        if (op != 0x71 && op != 0x72 && op != 0x73 && op != 0x6a) return null
        return KeyTriple(s.first, b.first, op, p, q + 1)
    }

    private fun findRuns(code: ByteArray): List<Run> {
        val ms = mutableListOf<KeyTriple>()
        var p = 0
        while (p < code.size - 14) {
            if (code[p] == 0x41.toByte()) {
                val m = matchTriple(code, p)
                if (m != null) ms.add(m)
            }
            p++
        }
        val runs = mutableListOf<Run>()
        for (i in ms.indices) {
            val seq = mutableListOf(ms[i])
            for (j in i + 1 until ms.size) {
                if (seq.size >= 8) break
                val last = seq.last()
                if (ms[j].s == last.s + 4 && ms[j].b == last.b + 4 && ms[j].op == last.op) {
                    seq.add(ms[j])
                } else if (ms[j].at > last.at + 60) {
                    break
                }
            }
            if (seq.size >= 8) runs.add(Run(seq[0].s, seq[0].b))
        }
        return runs
    }

    // ---------- ChaCha20 (RFC 8439) ----------

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] = x[a] + x[b]; x[d] = rotl(x[d] xor x[a], 16)
        x[c] = x[c] + x[d]; x[b] = rotl(x[b] xor x[c], 12)
        x[a] = x[a] + x[b]; x[d] = rotl(x[d] xor x[a], 8)
        x[c] = x[c] + x[d]; x[b] = rotl(x[b] xor x[c], 7)
    }

    private fun block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val st = IntArray(16)
        st[0] = 0x61707865; st[1] = 0x3320646e; st[2] = 0x79622d32; st[3] = 0x6b206574
        for (i in 0 until 8) st[4 + i] = readU32(key, i * 4) ?: 0
        st[12] = counter
        st[13] = readU32(nonce, 0) ?: 0
        st[14] = readU32(nonce, 4) ?: 0
        st[15] = readU32(nonce, 8) ?: 0
        val w = st.clone()
        repeat(10) {
            quarter(w, 0, 4, 8, 12); quarter(w, 1, 5, 9, 13)
            quarter(w, 2, 6, 10, 14); quarter(w, 3, 7, 11, 15)
            quarter(w, 0, 5, 10, 15); quarter(w, 1, 6, 11, 12)
            quarter(w, 2, 7, 8, 13); quarter(w, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) writeU32(out, i * 4, w[i] + st[i])
        return out
    }

    private fun chacha(key: ByteArray, nonce: ByteArray, ct: ByteArray, counter0: Int, nbytes: Int): ByteArray {
        val n = minOf(nbytes, ct.size)
        val out = ByteArray(n)
        var pos = 0
        var c = counter0
        while (pos < n) {
            val ks = block(key, c++, nonce)
            val k = minOf(64, n - pos)
            for (i in 0 until k) out[pos + i] = (ct[pos + i].toInt() xor ks[i].toInt()).toByte()
            pos += k
        }
        return out
    }
}

