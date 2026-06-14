---
title: ONNX Runtime on the JVM
domain: tech
tags: onnx, inference, jvm
---

ONNX Runtime provides high-performance inference for machine learning models across multiple platforms. The JVM binding exposes this capability through a JNI layer that bridges Java code to native ONNX Runtime libraries. This architecture enables Java applications to execute models trained in PyTorch, TensorFlow, or scikit-learn without Python runtime dependencies.

The core abstraction is OrtSession, which represents a loaded ONNX model ready for inference. Creating a session requires the model file path and optional session configuration. Sessions are thread-safe for inference but not for mutation, allowing concurrent execution across multiple threads. Each inference call accepts input tensors and returns output tensors, with the runtime handling device allocation and operator execution.

Tokenization presents a critical challenge. ONNX models expect numeric tensor inputs, but NLP applications work with text strings. The HuggingFace Tokenizers library provides a Rust-based tokenization implementation with JNI bindings. It loads tokenizer configuration files that define vocabulary, special tokens, and encoding strategies. The tokenizer converts input text to token IDs, which become the input tensors for the ONNX model. This two-stage pipeline — tokenize then infer — is standard for all NLP models running on ONNX Runtime.
