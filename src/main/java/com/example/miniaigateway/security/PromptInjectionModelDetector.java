package com.example.miniaigateway.security;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class PromptInjectionModelDetector {

    private static final int MAX_LENGTH = 512;
    private static final float THRESHOLD = 0.5f;

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    @PostConstruct
    public void init() throws Exception {
        // 1. 初始化 ONNX Runtime
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();

        // 2. 从 classpath 加载模型到临时文件（ONNX Runtime 需要文件路径）
        File modelFile = extractResourceToTemp("models/wolf-defender/model.onnx", ".onnx");
        File tokenizerFile = extractResourceToTemp("models/wolf-defender/tokenizer.json", ".json");

        session = env.createSession(modelFile.getAbsolutePath(), options);
        tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile.toPath());

        log.info("Wolf Defender Small model loaded. Inputs: {}", session.getInputNames());
    }

    /**
     * 检测文本是否包含 Prompt 注入
     */
    public boolean isInjection(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        try {
            // 1. Tokenize
            var encoding = tokenizer.encode(text);
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();

            // 2. 截断到模型最大长度
            if (inputIds.length > MAX_LENGTH) {
                inputIds = Arrays.copyOf(inputIds, MAX_LENGTH);
                attentionMask = Arrays.copyOf(attentionMask, MAX_LENGTH);
            }

            // 3. 准备 ONNX 输入
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", createTensor(inputIds));
            inputs.put("attention_mask", createTensor(attentionMask));

            // 部分模型可能需要 token_type_ids，如果模型输入不包含则跳过
            if (session.getInputNames().contains("token_type_ids")) {
                long[] tokenTypeIds = new long[inputIds.length];
                inputs.put("token_type_ids", createTensor(tokenTypeIds));
            }

            // 4. 执行推理
            try (OrtSession.Result result = session.run(inputs)) {
                float[][] logits = (float[][]) result.get(0).getValue();
                float[] probs = softmax(logits[0]);

                // 类别 1 代表 INJECTION
                boolean injection = probs[1] >= THRESHOLD;
                if (injection) {
                    log.warn("Wolf Defender detected injection. Probability: {}", probs[1]);
                }
                return injection;
            }
        } catch (Exception e) {
            log.error("Prompt injection model inference failed: {}", e.getMessage(), e);
            // 安全兜底：模型异常时，为了安全默认放行或拦截，根据你的策略决定
            return false;
        }
    }

    private OnnxTensor createTensor(long[] data) throws OrtException {
        LongBuffer buffer = LongBuffer.wrap(data);
        long[] shape = {1, data.length};
        return OnnxTensor.createTensor(env, buffer, shape);
    }

    private float[] softmax(float[] logits) {
        float max = logits[0];
        for (int i = 1; i < logits.length; i++) {
            if (logits[i] > max) max = logits[i];
        }
        double[] exp = new double[logits.length];
        double sum = 0.0;
        for (int i = 0; i < logits.length; i++) {
            exp[i] = Math.exp(logits[i] - max);
            sum += exp[i];
        }
        float[] result = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            result[i] = (float) (exp[i] / sum);
        }
        return result;
    }

    /**
     * 从 classpath 提取资源到临时文件
     */
    private File extractResourceToTemp(String resourcePath, String suffix) throws Exception {
        try (var is = new ClassPathResource(resourcePath).getInputStream()) {
            File temp = File.createTempFile("wolf-defender-", suffix);
            temp.deleteOnExit();
            Files.copy(is, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return temp;
        }
    }
}