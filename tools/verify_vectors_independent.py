#!/usr/bin/env python3
"""Checks the frozen crypto vectors of 0.1.0 against an independent implementation
(pyca/cryptography plus hashlib), so the frozen file is not just "what the old code did"
but what the standards say. Run: python3 -I tools/verify_vectors_independent.py
Covers every deterministic line: SHA-512, MD5, AES-128-CTR (incl. counter carry and the
fresh-block rule), AES-128-CBC, AES-128-GCM with 16-byte IV, Ed25519 device keys and
signatures, RFC 8032 test 1, base64. Exit code 1 on any mismatch."""
import base64, hashlib, sys
from pathlib import Path
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization as ser

F = Path(__file__).resolve().parent.parent / "receiver/src/test/resources/native/crypto-vectors-0.1.0.txt"
v = dict(l.split(" ", 1) for l in F.read_text().splitlines())
KEY, IV = bytes(range(16)), bytes(range(0xF0, 0x100))
bad = checked = 0

def fill(n, seed):
    s, out = seed, bytearray()
    for _ in range(n):
        s ^= (s << 13) & 0xFFFFFFFF; s ^= s >> 17; s ^= (s << 5) & 0xFFFFFFFF
        out.append(s >> 24)
    return bytes(out)

def eq(name, want):
    global bad, checked
    checked += 1
    if v.get(name) != want:
        bad += 1
        print(f"MISMATCH {name}: frozen {v.get(name)} independent {want}")

big = fill(1000, 7)
eq("sha512.empty", hashlib.sha512(b"").hexdigest()); eq("sha512.abc", hashlib.sha512(b"abc").hexdigest())
eq("sha512.chunks1000", hashlib.sha512(big).hexdigest()); eq("sha512.len", "64")
eq("md5.empty", hashlib.md5(b"").hexdigest()); eq("md5.chunks1000", hashlib.md5(big).hexdigest()); eq("md5.len", "16")
for k, s in [("abc", b"abc"), ("empty", b""), ("digest", b"AirPlay:raop:3939")]:
    eq(f"get_md5.{k}", hashlib.md5(s).hexdigest())

def ks(iv, n): return Cipher(algorithms.AES(KEY), modes.CTR(iv)).encryptor().update(bytes(n))
def x(a, b): return bytes(p ^ q for p, q in zip(a, b)).hex()
inp = fill(128, 11); K = ks(IV, 512)
off = 0
for i, n in enumerate([1, 15, 16, 17, 31, 5, 43]):
    eq(f"ctr.seq.{i}.len{n}", x(inp[off:off + n], K[off:off + n])); off += n
eq("ctr.after_reset.20", x(inp[:20], K[:20]))
# fresh block: drop the rest of a partly used block (new context, as mirror_buffer.c uses it)
pos = 0
for name, n, fresh_after in [("a5", 5, 1), ("b5", 5, 1), ("c16", 16, 1), ("d3", 3, 2)]:
    eq(f"ctr.fresh.{name}", x(inp[:n], K[pos:pos + n])); pos += n
    if pos % 16: pos += 16 - pos % 16
eq("ctr.fresh.e16_twice", x(inp[:16], K[pos:pos + 16]))
eq("ctr.dec.7", x(inp[:7], K[:7]))
eq("ctr.dec.inplace40", x(inp[:40], K[7:47]))
eq("ctr.after_zero.9", x(inp[:9], K[47:56]))
eq("ctr.fresh_first.16", x(inp[:16], K[:16]))
eq("ctr.carry.48", x(inp[:48], ks(b"\xff" * 16, 48)))

c = fill(128, 13)
enc = Cipher(algorithms.AES(KEY), modes.CBC(IV)).encryptor().update(c[:48])
eq("cbc.enc.32", enc[:32].hex()); eq("cbc.enc.next16", enc[32:48].hex()); eq("cbc.enc.after_reset16", enc[:16].hex())
dec = Cipher(algorithms.AES(KEY), modes.CBC(IV)).decryptor().update(c[:64])
eq("cbc.dec.48", dec[:48].hex()); eq("cbc.dec.next16", dec[48:64].hex()); eq("cbc.dec.after_reset16", dec[:16].hex())
eq("cbc.dec.inplace32", dec[:32].hex()); eq("cbc.dec.zero_len_untouched", "aa" * 16)

gk, giv, gpt = fill(16, 17), fill(16, 19), fill(32, 23)
ct = AESGCM(gk).encrypt(giv, gpt, None)
eq("gcm.enc.ret", "32"); eq("gcm.enc.ct", ct[:32].hex() + "aa" * 16); eq("gcm.enc.tag", ct[32:].hex())
eq("gcm.dec.ret", "32"); eq("gcm.dec.pt", gpt.hex() + "aa" * 16)
giv2 = giv[:15] + bytes([(giv[15] + 1) & 0xFF])
ct2 = AESGCM(gk).encrypt(giv2, gpt, None)
eq("gcm.enc_iv15.ct", ct2[:32].hex()); eq("gcm.enc_iv15.tag", ct2[32:].hex())
ct5 = AESGCM(gk).encrypt(giv2, gpt[:5], None)
eq("gcm.enc5.ret", "5"); eq("gcm.enc5.ct", ct5[:5].hex()); eq("gcm.enc5.tag", ct5[5:].hex())
eq("gcm.badtag.ok", "1")

msg = fill(64, 29)
ids = ["00:00:00:00:00:00", "12:34:56:78:9A:BC", "A4:5E:60:E1:02:3F", "x", "",
       "62:40:D4:9A:D3:FA", "8E:61:8F:51:AD:E7", "0A:53:50:49:45:47"]
for i, d in enumerate(ids):
    k = Ed25519PrivateKey.from_private_bytes(hashlib.sha512(b"UxPlay-Persistent-Not-Secure-Public-Key" + d.encode()).digest()[:32])
    eq(f"ed.derived.{i}.pk", k.public_key().public_bytes(ser.Encoding.Raw, ser.PublicFormat.Raw).hex())
    eq(f"ed.derived.{i}.sig", k.sign(msg).hex())
eq("ed.rfc8032_1.pk", "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
eq("ed.rfc8032_1.sig", "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
pk = fill(32, 31)
for n in (32, 1, 2, 3):
    eq(f"b64.{n}", "[" + base64.b64encode(pk[:n]).decode() + "]")
print(f"{checked} frozen values checked independently, {bad} mismatches")
sys.exit(1 if bad else 0)
