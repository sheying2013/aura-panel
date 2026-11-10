# rust-compat

独立 Rust 库：AES-256-GCM 报文编解码兼容层。仅实现**编码层**，不包含传输、路由、协议栈或主控逻辑。

## 线格式（与 go-gost/x/internal/util/crypto/aes.go、Java AESCrypto 字节级一致）

    base64_standard( nonce[12] || ciphertext[n] || tag[16] )

- 密钥：SHA-256(secret 的 UTF-8 字节)，32 字节 → AES-256-GCM
- AAD：无
- nonce：加密时随机（12 字节），随报文前置传输
- base64：标准字母表 + 填充（Go StdEncoding / Java Base64.getEncoder）
- 拒绝：空 secret、空明文、空报文、短于 28 字节的帧、非法 base64、标签错误、密钥错误

## 用法

    use rust_compat::AesGcmCodec;

    let codec = AesGcmCodec::new("shared-secret")?;
    let encoded = codec.encrypt_str("hello")?;
    let plain = codec.decrypt_str(&encoded)?;

## 测试

    cargo test --offline

## 范围说明

本库只保证上述字节序列的编解码与 Go/Java 端一致；不声明整体主控、协议握手或商用网关兼容性。
