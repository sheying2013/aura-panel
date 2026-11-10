//! AES-256-GCM 报文编解码兼容层。
//!
//! 线格式（与 go-gost/x/internal/util/crypto/aes.go、Java AESCrypto 一致）：
//!
//! ```text
//! base64_std( nonce[12] || ciphertext[n] || tag[16] )
//! ```
//!
//! 密钥：SHA-256(secret 的 UTF-8 字节)，32 字节，AES-256-GCM，无 AAD。
//! 加密时 nonce 随机生成；解密时按 12 字节切分 nonce，其余部分作为 ciphertext||tag
//! 交给 AEAD 校验，标签错误与密钥错误统一表现为解密失败。

use aes_gcm::aead::{Aead, AeadCore, OsRng};
use aes_gcm::{Aes256Gcm, Key, KeyInit, Nonce};
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use sha2::{Digest, Sha256};

/// GCM nonce 长度（96 bit，Go gcm.NonceSize() / Java GCM_IV_LENGTH）。
pub const NONCE_LEN: usize = 12;
/// GCM 认证标签长度（128 bit）。
pub const TAG_LEN: usize = 16;
/// 最短合法帧长度：nonce + 空明文对应的标签。
pub const MIN_FRAME_LEN: usize = NONCE_LEN + TAG_LEN;

/// 编解码错误。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CodecError {
    /// secret 为空字符串。
    EmptySecret,
    /// 待加密明文为空。
    EmptyPlaintext,
    /// 待解密报文为空字符串。
    EmptyCiphertext,
    /// 报文不是合法的标准 base64（含填充）。
    InvalidBase64,
    /// base64 解码后短于 MIN_FRAME_LEN。
    FrameTooShort { len: usize },
    /// AEAD 加密失败（明文超出 GCM 长度上限）。
    EncryptFailed,
    /// AEAD 解密失败：标签不匹配、密文被篡改或密钥错误。
    DecryptFailed,
    /// 解密结果不是合法 UTF-8。
    InvalidUtf8,
}

impl std::fmt::Display for CodecError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::EmptySecret => f.write_str("密钥不能为空"),
            Self::EmptyPlaintext => f.write_str("待加密数据不能为空"),
            Self::EmptyCiphertext => f.write_str("加密数据不能为空"),
            Self::InvalidBase64 => f.write_str("base64 解码失败"),
            Self::FrameTooShort { len } => {
                write!(f, "加密数据长度不足: {len} < {MIN_FRAME_LEN}")
            }
            Self::EncryptFailed => f.write_str("加密失败"),
            Self::DecryptFailed => f.write_str("解密失败"),
            Self::InvalidUtf8 => f.write_str("解密结果不是合法 UTF-8"),
        }
    }
}

impl std::error::Error for CodecError {}

/// 由 secret 推导 32 字节 AES-256 密钥：SHA-256(secret UTF-8)。
pub fn derive_key(secret: &str) -> [u8; 32] {
    let digest = Sha256::digest(secret.as_bytes());
    let mut key = [0u8; 32];
    key.copy_from_slice(&digest);
    key
}

/// 与 Go/Java 端对齐的报文编解码器。
pub struct AesGcmCodec {
    cipher: Aes256Gcm,
}

impl AesGcmCodec {
    /// 用 secret 构造编解码器；空 secret 直接拒绝（与 Go/Java 一致）。
    pub fn new(secret: &str) -> Result<Self, CodecError> {
        if secret.is_empty() {
            return Err(CodecError::EmptySecret);
        }
        let key = derive_key(secret);
        Ok(Self {
            cipher: Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key)),
        })
    }

    /// 加密并返回 base64(nonce||ciphertext||tag)，nonce 随机。
    pub fn encrypt(&self, plaintext: &[u8]) -> Result<String, CodecError> {
        let nonce = Aes256Gcm::generate_nonce(&mut OsRng);
        let mut nonce_bytes = [0u8; NONCE_LEN];
        nonce_bytes.copy_from_slice(&nonce);
        self.encrypt_with_nonce(&nonce_bytes, plaintext)
    }

    /// 使用指定 nonce 加密，仅供固定向量与互操作复现使用。
    pub fn encrypt_with_nonce(
        &self,
        nonce: &[u8; NONCE_LEN],
        plaintext: &[u8],
    ) -> Result<String, CodecError> {
        if plaintext.is_empty() {
            return Err(CodecError::EmptyPlaintext);
        }
        let sealed = self
            .cipher
            .encrypt(Nonce::from_slice(nonce), plaintext)
            .map_err(|_| CodecError::EncryptFailed)?;

        let mut frame = Vec::with_capacity(NONCE_LEN + sealed.len());
        frame.extend_from_slice(nonce);
        frame.extend_from_slice(&sealed);
        Ok(STANDARD.encode(frame))
    }

    /// 解密 base64(nonce||ciphertext||tag)。
    pub fn decrypt(&self, encoded: &str) -> Result<Vec<u8>, CodecError> {
        if encoded.is_empty() {
            return Err(CodecError::EmptyCiphertext);
        }
        let frame = STANDARD
            .decode(encoded)
            .map_err(|_| CodecError::InvalidBase64)?;
        if frame.len() < MIN_FRAME_LEN {
            return Err(CodecError::FrameTooShort { len: frame.len() });
        }
        let (nonce, sealed) = frame.split_at(NONCE_LEN);
        self.cipher
            .decrypt(Nonce::from_slice(nonce), sealed)
            .map_err(|_| CodecError::DecryptFailed)
    }

    /// 加密 UTF-8 字符串。
    pub fn encrypt_str(&self, plaintext: &str) -> Result<String, CodecError> {
        self.encrypt(plaintext.as_bytes())
    }

    /// 解密为 UTF-8 字符串。
    pub fn decrypt_str(&self, encoded: &str) -> Result<String, CodecError> {
        String::from_utf8(self.decrypt(encoded)?).map_err(|_| CodecError::InvalidUtf8)
    }
}
