//! 线格式兼容层测试：固定向量、帧布局、篡改检测、错误密钥与入参校验。
//! 固定向量由独立参考实现（Python cryptography AESGCM，key=SHA-256(secret UTF-8)，
//! nonce||ciphertext||tag 后标准 base64）生成，用于锁定与 Go/Java 端的字节级一致。

use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use rust_compat::{derive_key, AesGcmCodec, CodecError, MIN_FRAME_LEN, NONCE_LEN, TAG_LEN};

const SECRET: &str = "flux-panel-test-secret";
const SECRET_CN: &str = "flux-panel-兼容层-密钥";

const V1_NONCE: [u8; NONCE_LEN] = [
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b,
];
const V1_PLAINTEXT: &str = "gost-flux aes-gcm codec fixed vector";
const V1_ENCODED: &str =
    "AAECAwQFBgcICQoLZj3GU4pKBi0kIl7h5yrXVD20dIHpHmxhimwajitHffpTkl54cnwKnNt79VZgWwUXVnE1aQ==";
const V1_KEY_SHA256: &str = "ac2bb70951f182a7bfe2f96bbb70c50b3eed91af73ce3e3ce1828b53982e976f";

const V2_NONCE: [u8; NONCE_LEN] = [
    0xf0, 0xe0, 0xd0, 0xc0, 0xb0, 0xa0, 0x90, 0x80, 0x70, 0x60, 0x50, 0x40,
];
const V2_PLAINTEXT: &str = "中文固定向量 + ascii 混合: flux-panel rust-compat 2026";
const V2_ENCODED: &str = "8ODQwLCgkIBwYFBA+iCMnVHnGi5JEkfxlrWI0ofSBkXy1owUAE/tTPzGi1u+qJZx/Llcd4Hv4cg2kok6NbSKfB+5OEes7CZVYmOJ34r3Q4OFgE3RSlJNd7Pi";
const V2_KEY_SHA256: &str = "c1719dc02fea6e6b3e4bfaa25aa7aef50e877dcf0c883643db74655219f7af36";

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn decode(encoded: &str) -> Vec<u8> {
    STANDARD.decode(encoded).expect("测试向量必须是合法 base64")
}

fn flip_bit(encoded: &str, index: usize, mask: u8) -> String {
    let mut frame = decode(encoded);
    frame[index] ^= mask;
    STANDARD.encode(frame)
}

#[test]
fn key_is_sha256_of_secret_utf8() {
    assert_eq!(hex(&derive_key(SECRET)), V1_KEY_SHA256);
    assert_eq!(hex(&derive_key(SECRET_CN)), V2_KEY_SHA256);
}

#[test]
fn fixed_vector_ascii_matches_reference() {
    let codec = AesGcmCodec::new(SECRET).unwrap();
    assert_eq!(
        codec
            .encrypt_with_nonce(&V1_NONCE, V1_PLAINTEXT.as_bytes())
            .unwrap(),
        V1_ENCODED
    );
    assert_eq!(codec.decrypt(V1_ENCODED).unwrap(), V1_PLAINTEXT.as_bytes());
    assert_eq!(codec.decrypt_str(V1_ENCODED).unwrap(), V1_PLAINTEXT);
}

#[test]
fn fixed_vector_utf8_chinese_matches_reference() {
    let codec = AesGcmCodec::new(SECRET_CN).unwrap();
    assert_eq!(
        codec
            .encrypt_with_nonce(&V2_NONCE, V2_PLAINTEXT.as_bytes())
            .unwrap(),
        V2_ENCODED
    );
    assert_eq!(codec.decrypt_str(V2_ENCODED).unwrap(), V2_PLAINTEXT);
}

#[test]
fn frame_layout_is_nonce_then_ciphertext_then_tag() {
    let codec = AesGcmCodec::new(SECRET).unwrap();
    let frame = decode(
        &codec
            .encrypt_with_nonce(&V1_NONCE, V1_PLAINTEXT.as_bytes())
            .unwrap(),
    );

    assert_eq!(frame.len(), NONCE_LEN + V1_PLAINTEXT.len() + TAG_LEN);
    assert_eq!(frame.len(), MIN_FRAME_LEN + V1_PLAINTEXT.len());
    assert_eq!(&frame[..NONCE_LEN], &V1_NONCE);
    assert_eq!(frame.len(), 64);
}

#[test]
fn random_nonce_roundtrip_is_unique_per_message() {
    let codec = AesGcmCodec::new(SECRET).unwrap();
    let mut seen = std::collections::HashSet::new();

    for i in 0..64 {
        let plaintext = format!("flux-panel message #{i}");
        let encoded = codec.encrypt_str(&plaintext).unwrap();
        let frame = decode(&encoded);
        assert_eq!(frame.len(), NONCE_LEN + plaintext.len() + TAG_LEN);
        assert!(seen.insert(frame[..NONCE_LEN].to_vec()), "nonce 重复");
        assert_eq!(codec.decrypt_str(&encoded).unwrap(), plaintext);
    }
    assert_eq!(seen.len(), 64);
}

#[test]
fn tampered_nonce_is_rejected() {
    let codec = AesGcmCodec::new(SECRET).unwrap();
    for index in 0..NONCE_LEN {
        let tampered = flip_bit(V1_ENCODED, index, 0x01);
        assert_eq!(
            codec.decrypt(&tampered).unwrap_err(),
            CodecError::DecryptFailed
        );
    }
}

#[test]
fn tampered_ciphertext_is_rejected() {
    let codec = AesGcmCodec::new(SECRET).unwrap();
    let ciphertext_len = V1_PLAINTEXT.len();
    for offset in 0..ciphertext_len {
        let tampered = flip_bit(V1_ENCODED, NONCE_LEN + offset, 0x80);
        assert_eq!(
            codec.decrypt(&tampered).unwrap_err(),
            CodecError::DecryptFailed
        );
    }
}

#[test]
fn tampered_tag_is_rejected() {
    let codec = AesGcmCodec::new(SECRET).unwrap();
    let tag_start = NONCE_LEN + V1_PLAINTEXT.len();
    for offset in 0..TAG_LEN {
        let tampered = flip_bit(V1_ENCODED, tag_start + offset, 0x01);
        assert_eq!(
            codec.decrypt(&tampered).unwrap_err(),
            CodecError::DecryptFailed
        );
    }
}

#[test]
fn truncated_frame_is_rejected() {
    let codec = AesGcmCodec::new(SECRET).unwrap();

    let mut frame = decode(V1_ENCODED);
    frame.pop();
    assert_eq!(
        codec.decrypt(&STANDARD.encode(&frame)).unwrap_err(),
        CodecError::DecryptFailed
    );

    let short = STANDARD.encode([0u8; MIN_FRAME_LEN - 1]);
    assert_eq!(
        codec.decrypt(&short).unwrap_err(),
        CodecError::FrameTooShort {
            len: MIN_FRAME_LEN - 1
        }
    );
}

#[test]
fn appended_byte_shifts_tag_and_is_rejected() {
    let codec = AesGcmCodec::new(SECRET).unwrap();
    let mut frame = decode(V1_ENCODED);
    frame.push(0x00);
    assert_eq!(
        codec.decrypt(&STANDARD.encode(&frame)).unwrap_err(),
        CodecError::DecryptFailed
    );
}

#[test]
fn wrong_secret_is_rejected() {
    let other = AesGcmCodec::new("flux-panel-test-secret-2").unwrap();
    assert_eq!(
        other.decrypt(V1_ENCODED).unwrap_err(),
        CodecError::DecryptFailed
    );

    // 只差一个 UTF-8 字节的 secret 也必须失败
    let near = AesGcmCodec::new("flux-panel-test-secreu").unwrap();
    assert_eq!(
        near.decrypt(V1_ENCODED).unwrap_err(),
        CodecError::DecryptFailed
    );
}

#[test]
fn empty_secret_is_rejected() {
    assert!(matches!(AesGcmCodec::new(""), Err(CodecError::EmptySecret)));
    // 非空即可（含空白字符），与 Go/Java 的判空语义一致
    assert!(AesGcmCodec::new(" ").is_ok());
}

#[test]
fn empty_plaintext_is_rejected() {
    let codec = AesGcmCodec::new(SECRET).unwrap();
    assert_eq!(codec.encrypt(&[]).unwrap_err(), CodecError::EmptyPlaintext);
    assert_eq!(
        codec.encrypt_str("").unwrap_err(),
        CodecError::EmptyPlaintext
    );
    assert_eq!(
        codec.encrypt_with_nonce(&V1_NONCE, &[]).unwrap_err(),
        CodecError::EmptyPlaintext
    );
}

#[test]
fn empty_ciphertext_is_rejected() {
    let codec = AesGcmCodec::new(SECRET).unwrap();
    assert_eq!(codec.decrypt("").unwrap_err(), CodecError::EmptyCiphertext);
}

#[test]
fn malformed_base64_is_rejected() {
    let codec = AesGcmCodec::new(SECRET).unwrap();
    assert_eq!(
        codec.decrypt("not base64 @@").unwrap_err(),
        CodecError::InvalidBase64
    );
    // 本解码器要求标准 Base64 填充，与 Go StdEncoding 一致。
    // Java Base64 解码器对缺少填充的输入更宽容。
    let unpadded = V1_ENCODED.trim_end_matches('=');
    assert_eq!(
        codec.decrypt(unpadded).unwrap_err(),
        CodecError::InvalidBase64
    );
}

#[test]
fn non_utf8_plaintext_is_rejected_by_decrypt_str() {
    let codec = AesGcmCodec::new(SECRET).unwrap();
    let encoded = codec.encrypt(&[0xff, 0xfe, 0xfd]).unwrap();
    assert_eq!(codec.decrypt(&encoded).unwrap(), vec![0xff, 0xfe, 0xfd]);
    assert_eq!(
        codec.decrypt_str(&encoded).unwrap_err(),
        CodecError::InvalidUtf8
    );
}

#[test]
fn decrypts_independent_fixture_also_checked_by_go() {
    let codec = AesGcmCodec::new("fixture-secret-兼容").unwrap();
    let encoded = "AAECAwQFBgcICQoLIjM7VBZ59/ESDHlfckTlMvBrpShjcG5XQdA/SAoCM1s9TlTPZxZPuaT3I7TILtoxdn/Q15aEg06arONnjBwC";
    let expected = r#"{"type":"AddService","requestId":"fixture-001"}"#;
    assert_eq!(codec.decrypt_str(encoded).unwrap(), expected);
}

#[test]
fn binary_payload_roundtrip() {
    let codec = AesGcmCodec::new(SECRET).unwrap();
    let payload: Vec<u8> = (0..=255u8).cycle().take(4096).collect();
    let encoded = codec.encrypt(&payload).unwrap();
    assert_eq!(codec.decrypt(&encoded).unwrap(), payload);
}
