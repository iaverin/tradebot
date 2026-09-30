# Polymarket Order Signing

How the backend signs and submits orders to the Polymarket CLOB. This documents the
EIP-712 order signing, the POLY_1271 (ERC-7739) smart-wallet signature scheme, exchange/version
selection, and L2 request authentication.

Implementation:
- `arbitrage/service/PolymarketTradingService.java` — order assembly, version/exchange selection, POST `/order`, L2 auth.
- `arbitrage/service/PolymarketEip712Signer.java` — EIP-712 digest + signature construction.
- `arbitrage/service/PolymarketAuthL1Service.java` — derives the L2 API credentials (see [L2 request authentication](#l2-request-authentication)).

Verified against the official [`@polymarket/clob-client-v2`](https://github.com/Polymarket/clob-client-v2)
(`src/order-utils/exchangeOrderBuilderV2.ts`). `Eip712SignerParityTest` locks our signer to
that client's output byte-for-byte.

---

## 1. Overview

Placing an order is three steps:

1. **Build the order** — convert price/size/side into the on-chain order struct (6-decimal amounts).
2. **Sign the order** — produce an EIP-712 signature over the order struct against the correct
   exchange contract and domain version. For smart-contract wallets (POLY_1271) this is an
   ERC-7739 nested `TypedDataSign` signature, not a bare ECDSA signature.
3. **Submit** — POST the order JSON to `/order` with L2 (HMAC) authentication headers.

There are **two independent authentications** involved, do not confuse them:

| | Signs what | Algorithm | Purpose |
|---|---|---|---|
| **Order signature** | the order struct | EIP-712 (ECDSA / ERC-1271) | proves the maker authorized this specific order; verified on-chain at match time |
| **L2 auth headers** | the HTTP request | HMAC-SHA256 | proves the API caller owns the account; verified by the CLOB server per request |

---

## Cryptographic primitives

Everything below is built from five primitives. Java implementations come from **web3j**
(`org.web3j.crypto.Hash`, `Sign`, `StructuredDataEncoder`, `Keys`, `ECKeyPair`), the JDK
(`javax.crypto.Mac`, `java.util.Base64`), and `org.web3j.utils.Numeric` for byte/hex conversions.

### Keccak-256 (the hash used for all order hashing)

Every "hash" in the order-signing path is **Keccak-256**, producing a 32-byte digest. In code this
is `Hash.sha3(byte[])`.

> ⚠️ **Keccak-256 is not NIST SHA3-256.** They share the same sponge construction
> (Keccak-*f*[1600] permutation, rate 1088 bits / capacity 512 bits) but use a **different padding
> domain suffix**: original Keccak uses `0x01`, finalized SHA-3 uses `0x06`. They produce different
> digests for the same input. Ethereum standardized on the original Keccak, and web3j's `sha3` is
> Keccak-256 despite the method name. Using a real SHA3-256 anywhere here would silently break every
> hash.

It is used for: EIP-712 `typeHash` (hash of the type string), `hashStruct`, `domainSeparator`, the
final order digest, the ERC-7739 `TypedDataSign` struct hash, EIP-55 address checksumming
(`Keys.toChecksumAddress`), and Ethereum address derivation. It is **not** used for L2 auth (that is
SHA-256 via HMAC — see below).

### secp256k1 ECDSA (the signature)

Order signatures are ECDSA over the **secp256k1** curve — the same curve as Ethereum accounts.

- **Input:** the signature is computed over the already-formed 32-byte digest. The digest is **not
  re-hashed** — `Sign.signMessage(digest, keyPair, false)` is called with `needToHash = false`. (The
  hashing was already done by Keccak-256 when forming the EIP-712 digest.)
- **Recoverable signature:** produces three components — `r` (32 bytes), `s` (32 bytes), and a
  recovery id `v`. web3j returns `v` as the Ethereum-legacy value **27 or 28** (recovery id `0/1`
  plus 27). `ecrecover` / ERC-1271 verifiers use `v` to recover the public key, hence the signer
  address, from `(digest, r, s)`.
- **Low-s normalization (EIP-2):** secp256k1 signatures are malleable — both `s` and `n − s` are
  valid. Ethereum requires the **canonical low form** `s ≤ n/2`; web3j produces this automatically.
  This matters because ERC-1271 wallets and on-chain `ecrecover` reject high-`s` signatures.
- **Serialization:** the 65-byte signature is the concatenation `r ‖ s ‖ v` (in that order).

```java
Sign.SignatureData sig = Sign.signMessage(digest, credentials.getEcKeyPair(), false);
byte[] sig65 = concat(sig.getR(), sig.getS(), sig.getV());   // 32 + 32 + 1
```

### EIP-712 structured-data encoding

EIP-712 turns a typed struct into a 32-byte digest deterministically. The rules used here:

1. **Type string (`encodeType`)** — a canonical, whitespace-free description, e.g.
   `Order(uint256 salt,address maker,...)`. If a struct references other struct types, those are
   appended **sorted alphabetically** after the primary type (this is why
   `TypedDataSign(Order contents,...)Order(...)` has `Order` appended).
2. **`typeHash` = Keccak-256(typeString)**.
3. **`encodeData`** — each member is encoded into exactly **32 bytes**:
   - `uintN` / `uint256` → big-endian, left-padded to 32 bytes (`Numeric.toBytesPadded`).
   - `address` → the 20-byte address as a `uint160`, left-padded to 32 bytes (12 leading zero bytes).
   - `bytesN` (e.g. `bytes32`) → the value right in place, 32 bytes.
   - `uint8` (`side`, `signatureType`) → still a full 32-byte word.
   - **dynamic** `string` / `bytes` → replaced by `Keccak-256(contents)` (not used in `Order`, but
     used by the domain's `name`/`version` strings).
   - nested **struct** member → replaced by its own `hashStruct` (used for `TypedDataSign.contents`).
4. **`hashStruct(s) = Keccak-256(typeHash ‖ encodeData(s))`**.
5. **`domainSeparator = hashStruct(EIP712Domain)`**.
6. **Final digest** = `Keccak-256(0x19 ‖ 0x01 ‖ domainSeparator ‖ hashStruct(message))`.
   The `0x1901` prefix is the EIP-191 "version 0x01" tag that namespaces structured data so it can
   never collide with a signed transaction or a personal-sign message.

The signer builds steps 1–6 by hand (explicit `abi.encode` + Keccak-256) and **cross-checks** the
result against web3j's `StructuredDataEncoder.hashStructuredData()`; a mismatch throws
`Order EIP-712 digest mismatch`, catching any encoding drift before a bad signature is sent.

### HMAC-SHA256 (L2 request auth only)

The per-request L2 headers use **HMAC with SHA-256** (FIPS 198-1) — a keyed MAC, unrelated to the
order signing. The key is the API secret, **url-safe-base64-decoded** to raw bytes; the MAC is taken
over `timestamp + method + path + body` and the output is **url-safe base64 with `=` padding**. See
[L2 request authentication](#l2-request-authentication). (SHA-256 here is genuine NIST SHA-2, *not*
Keccak.)

### Base64 (url-safe, padded)

L2 auth uses the **URL/filename-safe** Base64 alphabet (`-` and `_` instead of `+` and `/`) per
RFC 4648 §5, **keeping the trailing `=` padding**. The secret is decoded with `Base64.getUrlDecoder`
and the signature encoded with `Base64.getUrlEncoder`. Stripping the padding causes
`401 Invalid api key`.

---

## 2. Order version & exchange selection

Polymarket runs multiple exchange contract versions. The CLOB tells clients which order version it
currently expects:

```
GET {clobUrl}/version  ->  {"version": 2}   # production today; default to 2 if absent
```

The order struct is identical for v2 and v3; **only the `verifyingContract` address and the EIP-712
domain `version` string change.** Signing against the wrong pair makes the server compute a
different order hash and reject with:

```
HTTP 400: invalid POLY_1271 signature: signature does not match order hash
```

Whether a market is **neg-risk** also changes the contract:

```
GET {clobUrl}/neg-risk?token_id=...  ->  {"neg_risk": true|false}
```

Selection matrix (Polygon, chainId 137):

| Order version | Market | `verifyingContract` | domain `version` |
|---|---|---|---|
| **2** | standard | `0xE111180000d2663C0091e4f400237545B87B996B` | `"2"` |
| **2** | neg-risk | `0xe2222d279d744050d28e00520010520000310F59` | `"2"` |
| **3** | standard **and** neg-risk | `0xe3333700cA9d93003F00f0F71f8515005F6c00Aa` | `"3"` |

(Domain `name` is always `"Polymarket CTF Exchange"`.) The backend resolves `version` once
(cached) via `orderVersion()` and `negRisk` per token via `isNegRisk()`, then picks the row above.

> Historical bug: the backend used to hard-code the v3 exchange/version for standard markets while
> production expects v2 — every standard order failed with "signature does not match order hash".

---

## 3. The order struct (EIP-712 `Order`)

```
Order(
  uint256 salt,
  address maker,
  address signer,
  uint256 tokenId,
  uint256 makerAmount,
  uint256 takerAmount,
  uint8   side,
  uint8   signatureType,
  uint256 timestamp,
  bytes32 metadata,
  bytes32 builder
)
```

Field meanings:

- **salt** — random uniqueness nonce. Sent as a JSON *number*; signed as `uint256`.
- **maker** — the funder/account holding the position. The deposit (smart-contract) wallet when set,
  otherwise the signing EOA.
- **signer** — the address whose signature authorizes the order. For **POLY_1271 `signer == maker`**
  (the wallet); for EOA/proxy/safe it is the signing EOA.
- **tokenId** — the ERC-1155 outcome token id (huge decimal string).
- **makerAmount / takerAmount** — 6-decimal base units (see §4).
- **side** — `0 = BUY`, `1 = SELL`. Signed as `uint8`. **Note:** in the POST JSON it is the *string*
  `"BUY"`/`"SELL"`, but in the EIP-712 message it is the integer.
- **signatureType** — `SignatureTypeV2`: `0 = EOA`, `1 = POLY_PROXY`, `2 = POLY_GNOSIS_SAFE`,
  `3 = POLY_1271`.
- **timestamp** — order creation time in **milliseconds** (`Date.now()` / `Instant.now().toEpochMilli()`).
- **metadata / builder** — `bytes32`, default to 32 zero bytes.

`expiration` and `taker` are part of the POST body but **not** part of the signed struct
(`expiration = "0"` means GTC / no expiry; `taker = zero address` means anyone can fill).

### EIP-712 domain

```
EIP712Domain(string name, string version, uint256 chainId, address verifyingContract)
name             = "Polymarket CTF Exchange"
version          = "2" or "3"   (see §2)
chainId          = 137
verifyingContract= exchange address (see §2)
```

### Order digest

Standard EIP-712:

```
domainSeparator = keccak256(abi.encode(
    keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"),
    keccak256(name), keccak256(version), chainId, verifyingContract))

structHash      = keccak256(abi.encode(
    keccak256(ORDER_TYPE_STRING),
    salt, maker, signer, tokenId, makerAmount, takerAmount,
    side, signatureType, timestamp, metadata, builder))

orderDigest     = keccak256(0x1901 ‖ domainSeparator ‖ structHash)
```

The signer cross-checks its manual ABI encoding against web3j's `StructuredDataEncoder` to catch
encoding drift (`verifyAgainstCanonical`).

---

## 4. Amount math (6 decimals)

USDC and CTF shares both use 6 decimals (`SCALE = 1e6`). For size `s` (shares) at price `p`:

| Side | makerAmount (give) | takerAmount (get) |
|---|---|---|
| **BUY** | `round(p · s · 1e6)` USDC | `s · 1e6` shares |
| **SELL** | `s · 1e6` shares | `round(p · s · 1e6)` USDC |

USDC amounts are rounded `HALF_UP` to an integer base unit. Minimum order is **$1 notional**.

---

## 5. Signature schemes by `signatureType`

### EOA (0) / POLY_PROXY (1) / POLY_GNOSIS_SAFE (2)

A bare 65-byte ECDSA signature over `orderDigest`:

```
signature = r ‖ s ‖ v          (v ∈ {27, 28})
```

The exchange contract maps `maker` → authorized signer for proxy/safe types.

### POLY_1271 (3) — deposit wallets (the default here)

Polymarket "DepositWallet" accounts are Solady ERC-1271 + UUPS smart-contract wallets that verify
signatures using the **ERC-7739 nested `TypedDataSign`** scheme. The returned signature is a wrapped
blob, **not** a bare ECDSA signature.

`signer == maker == <deposit wallet address>`.

**Account (wallet) EIP-712 domain** — fixed for DepositWallet, from its on-chain `eip712Domain()`:

```
name = "DepositWallet", version = "1", chainId = 137, salt = bytes32(0)
```

**Step 1 — contents hash** = the `Order` `structHash` from §3 (hash of the order being authorized).

**Step 2 — TypedDataSign struct.** The wallet domain fields are embedded as members. The type string
(Solady always includes `bytes32 salt`, member order matters):

```
TypedDataSign(Order contents,string name,string version,uint256 chainId,address verifyingContract,bytes32 salt)Order(...)

structHash_TDS = keccak256(abi.encode(
    keccak256(typedDataSignType),
    contentsHash,                       // the Order struct hash
    keccak256("DepositWallet"),         // account domain name
    keccak256("1"),                     // account domain version
    chainId,                            // 137
    maker,                              // account domain verifyingContract = the wallet
    bytes32(0)))                        // account domain salt
```

**Step 3 — nested digest & inner signature.** The *outer* separator is the **exchange (app) domain
separator** from §3, with the account domain embedded inside the struct above:

```
nestedDigest = keccak256(0x1901 ‖ APP_DOMAIN_SEPARATOR ‖ structHash_TDS)
innerSig      = ECDSA_sign(nestedDigest)        # 65 bytes, by the wallet's authorized EOA
```

**Step 4 — wrapped signature** (this is what is submitted):

```
signature = innerSig(65)
          ‖ APP_DOMAIN_SEPARATOR(32)
          ‖ contentsHash(32)
          ‖ contentsDescr             # the ORDER_TYPE_STRING bytes
          ‖ uint16_BE(len(contentsDescr))
```

On-chain (and in the CLOB's verification) the wallet's `isValidSignature(orderHash, signature)`
reconstructs the nested digest, `ecrecover`s `innerSig`, and checks the recovered address is an
authorized signer/owner of the wallet.

#### Session-signer envelope (`polymarket-session-signer = true`)

If the signing EOA is a **registered session signer** (not the wallet owner), the wrapped signature
is further wrapped in a Polymarket DepositWallet ERC-6492-style envelope:

```
abi.encode(bytes32(sessionSignerAddress), bytes32(0), wrappedSignature)
  ‖ 0x6492649264926492649264926492649264926492649264926492649264926492
```

> The official `clob-client-v2` does **not** emit this envelope. Only enable
> `POLY_SESSION_SIGNER=true` when the EOA is genuinely a non-owner session key; otherwise leave it
> `false` or the CLOB rejects the signature.

---

## 6. POST /order request body

```json
{
  "deferExec": false,
  "postOnly": false,
  "order": {
    "salt": 123456789,
    "maker": "0x...",
    "signer": "0x...",
    "taker": "0x0000000000000000000000000000000000000000",
    "tokenId": "1149394...",
    "makerAmount": "1000000",
    "takerAmount": "2000000",
    "side": "BUY",
    "signatureType": 3,
    "timestamp": "1700000000000",
    "expiration": "0",
    "metadata": "0x000...000",
    "builder": "0x000...000",
    "signature": "0x..."
  },
  "owner": "<L2 apiKey>",
  "orderType": "GTC"
}
```

Notes:
- `salt` is a JSON **number**; all other numerics are **strings**.
- `side` is the **string** `"BUY"`/`"SELL"` here (integer only inside the EIP-712 message).
- `owner` is the **L2 API key**, not an address.
- The server infers the market's neg-risk status and order version itself, so the body carries no
  version field — the signature must match the server's expectation (§2).
- The endpoint validates the request **body before L2 auth**, so a 401 can hide behind an earlier
  400.

A `200/201` returns `{success, orderID, status}`.

---

## L2 request authentication

Every order/data request carries L2 HMAC headers built in `addL2AuthHeaders`:

```
message   = timestamp + method + requestPath + body          # body omitted for GET
key       = base64url_decode(apiSecret)
signature = base64url_encode_WITH_PADDING( HMAC_SHA256(key, message) )
```

Headers:

```
POLY_ADDRESS    = signing EOA address
POLY_API_KEY    = apiKey
POLY_PASSPHRASE = passphrase
POLY_TIMESTAMP  = unix seconds
POLY_SIGNATURE  = signature
```

The `=` padding is **required** — stripping it returns `401 Invalid api key`. The
`{apiKey, secret, passphrase}` triple is created/derived once by `PolymarketAuthL1Service` using an
EIP-712 `ClobAuth` (L1) signature, then cached.

---

## 7. Configuration

| Property (env) | Meaning |
|---|---|
| `arbitrage.polymarket-clob-url` (`POLYMARKET_CLOB_URL`) | CLOB base URL |
| `arbitrage.polymarket-private-key` (`POLY_PRIVATE_KEY`) | signing EOA key |
| `arbitrage.polymarket-deposit-wallet-address` (`DEPOSIT_WALLET_ADDRESS`) | maker = smart-wallet for POLY_1271 |
| `arbitrage.polymarket-signature-type` (`SIGNATURE_TYPE`, default `3`) | `0`/`1`/`2`/`3` |
| `arbitrage.polymarket-session-signer` (`POLY_SESSION_SIGNER`, default `false`) | wrap in session-signer envelope (§5) |
| `arbitrage.polymarket-chain-id` (default `137`) | Polygon mainnet |
| `arbitrage.polymarket-trading-enabled` (`POLYMARKET_TRADING_ENABLED`, default `false`) | master switch; placeOrder no-ops when false |

---

## 8. Troubleshooting

| Symptom | Likely cause |
|---|---|
| `400 invalid POLY_1271 signature: signature does not match order hash` | wrong exchange/version pair (§2), wrong neg-risk pairing, or a stray session-signer envelope |
| `400 invalid order version, please use the latest clob-client` | old v1 order struct — use the v2/v3 struct in §3 |
| `401 Invalid api key` | L2 HMAC padding stripped, or stale/invalid API credentials |
| `Order EIP-712 digest mismatch` (exception) | manual ABI encoding diverged from canonical — bug in `PolymarketEip712Signer` |

To reproduce/validate signing offline, run the parity test (no network, no Spring):

```
./gradlew test --tests "hzpro.com.tradingdesk.arbitrage.service.Eip712SignerParityTest"
```
