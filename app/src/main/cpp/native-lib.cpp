#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <cstring>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <zlib.h>

// 32-Byte Hardcoded Pre-Shared Key
const unsigned char AES_KEY[32] = {
        0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
        0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
        0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
        0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef
};

using EvpCtxPtr = std::unique_ptr<EVP_CIPHER_CTX, decltype(&::EVP_CIPHER_CTX_free)>;

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_radium_MainActivity_stringFromJNI(JNIEnv* env, jobject) {
    std::string version = "RADIUM ENGINE v2.2 [STRICT_TYPE_SAFE]";
    return env->NewStringUTF(version.c_str());
}

// ---- AES-256-GCM ENCRYPT (WITH ZLIB) ----
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_radium_MainActivity_processDataNative(JNIEnv* env, jobject, jstring input) {
    if (input == nullptr) return nullptr;
    const char *nativeString = env->GetStringUTFChars(input, nullptr);
    if (nativeString == nullptr) return nullptr;

    std::string plaintext(nativeString);
    env->ReleaseStringUTFChars(input, nativeString);

    // 1. ZLIB COMPRESSION
    uLongf compressed_len = compressBound(static_cast<uLong>(plaintext.length()));
    std::vector<unsigned char> compressed_data(compressed_len);
    
    if (compress(compressed_data.data(), &compressed_len, 
                 reinterpret_cast<const Bytef*>(plaintext.data()), 
                 static_cast<uLong>(plaintext.length())) != Z_OK) {
        return nullptr;
    }
    compressed_data.resize(compressed_len);

    // 2. PREPARE PAYLOAD [Original Size (4b)] + [Compressed Data]
    uint32_t original_size = static_cast<uint32_t>(plaintext.length());
    std::vector<unsigned char> pre_encryption_blob;
    pre_encryption_blob.reserve(4 + compressed_len);
    
    pre_encryption_blob.push_back(static_cast<unsigned char>((original_size >> 24) & 0xFF));
    pre_encryption_blob.push_back(static_cast<unsigned char>((original_size >> 16) & 0xFF));
    pre_encryption_blob.push_back(static_cast<unsigned char>((original_size >> 8) & 0xFF));
    pre_encryption_blob.push_back(static_cast<unsigned char>(original_size & 0xFF));
    pre_encryption_blob.insert(pre_encryption_blob.end(), compressed_data.begin(), compressed_data.end());

    // 3. AES-256 ENCRYPTION
    std::vector<unsigned char> iv(12);
    RAND_bytes(iv.data(), static_cast<int>(iv.size()));
    std::vector<unsigned char> tag(16);
    std::vector<unsigned char> ciphertext(pre_encryption_blob.size());

    EvpCtxPtr ctx(EVP_CIPHER_CTX_new(), ::EVP_CIPHER_CTX_free);
    EVP_EncryptInit_ex(ctx.get(), EVP_aes_256_gcm(), nullptr, nullptr, nullptr);
    EVP_EncryptInit_ex(ctx.get(), nullptr, nullptr, AES_KEY, iv.data());

    int len = 0;
    int ciphertext_total_len = 0;
    EVP_EncryptUpdate(ctx.get(), ciphertext.data(), &len, pre_encryption_blob.data(), static_cast<int>(pre_encryption_blob.size()));
    ciphertext_total_len = len;
    EVP_EncryptFinal_ex(ctx.get(), ciphertext.data() + len, &len);
    ciphertext_total_len += len;
    EVP_CIPHER_CTX_ctrl(ctx.get(), EVP_CTRL_GCM_GET_TAG, 16, tag.data());

    // 4. ASSEMBLY & JNI RETURN
    std::vector<int8_t> final_payload;
    final_payload.reserve(iv.size() + tag.size() + ciphertext_total_len);
    final_payload.insert(final_payload.end(), iv.begin(), iv.end());
    final_payload.insert(final_payload.end(), tag.begin(), tag.end());
    final_payload.insert(final_payload.end(), reinterpret_cast<int8_t*>(ciphertext.data()), reinterpret_cast<int8_t*>(ciphertext.data()) + ciphertext_total_len);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(final_payload.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(final_payload.size()), final_payload.data());
    return result;
}

// ---- AES-256-GCM DECRYPT (WITH ZLIB) ----
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_radium_MainActivity_decodeDataNative(JNIEnv* env, jobject, jbyteArray input) {
    jsize length = env->GetArrayLength(input);
    if (length < 28) return env->NewStringUTF("[ERR] Payload size failure");

    jbyte* buffer = env->GetByteArrayElements(input, nullptr);
    if (buffer == nullptr) return nullptr;

    std::vector<unsigned char> iv(reinterpret_cast<unsigned char*>(buffer), reinterpret_cast<unsigned char*>(buffer) + 12);
    std::vector<unsigned char> tag(reinterpret_cast<unsigned char*>(buffer) + 12, reinterpret_cast<unsigned char*>(buffer) + 28);
    int ciphertext_len = static_cast<int>(length - 28);
    std::vector<unsigned char> ciphertext(reinterpret_cast<unsigned char*>(buffer) + 28, reinterpret_cast<unsigned char*>(buffer) + length);
    env->ReleaseByteArrayElements(input, buffer, JNI_ABORT);

    // 1. AES-256 DECRYPTION
    std::vector<unsigned char> decrypted_blob(ciphertext_len);
    EvpCtxPtr ctx(EVP_CIPHER_CTX_new(), ::EVP_CIPHER_CTX_free);
    EVP_DecryptInit_ex(ctx.get(), EVP_aes_256_gcm(), nullptr, nullptr, nullptr);
    EVP_DecryptInit_ex(ctx.get(), nullptr, nullptr, AES_KEY, iv.data());

    int len = 0;
    int decrypted_blob_len = 0;
    EVP_DecryptUpdate(ctx.get(), decrypted_blob.data(), &len, ciphertext.data(), static_cast<int>(ciphertext.size()));
    decrypted_blob_len = len;
    EVP_CIPHER_CTX_ctrl(ctx.get(), EVP_CTRL_GCM_SET_TAG, 16, tag.data());
    int ret = EVP_DecryptFinal_ex(ctx.get(), decrypted_blob.data() + len, &len);

    if (ret <= 0) return env->NewStringUTF("[CORRUPT] AES Auth Failed.");
    decrypted_blob_len += len;

    if (decrypted_blob_len < 4) return env->NewStringUTF("[ERR] Header Truncated");

    // 2. ZLIB DECOMPRESSION
    uint32_t original_size = (static_cast<uint32_t>(decrypted_blob[0]) << 24) | 
                             (static_cast<uint32_t>(decrypted_blob[1]) << 16) | 
                             (static_cast<uint32_t>(decrypted_blob[2]) << 8)  | 
                             static_cast<uint32_t>(decrypted_blob[3]);

    std::vector<unsigned char> final_data(original_size);
    uLongf dest_len = static_cast<uLongf>(original_size);

    int z_res = uncompress(final_data.data(), &dest_len, 
                           decrypted_blob.data() + 4, 
                           static_cast<uLong>(decrypted_blob_len - 4));

    if (z_res != Z_OK) return env->NewStringUTF("[ERR] Zlib Failure");

    // 3. FINAL STRING CONSTRUCTION (Typo fixed: decryptedtext -> final_data)
    std::string result_str(reinterpret_cast<char*>(final_data.data()), dest_len);
    return env->NewStringUTF(("[VERIFIED]\n" + result_str).c_str());
}