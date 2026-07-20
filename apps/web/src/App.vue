<script setup>
import { computed, onBeforeUnmount, ref } from "vue";

const examples = [
  "图中有没有道路？",
  "图中有多少建筑物？",
  "建筑物覆盖面积是多少？",
  "建筑物比道路多吗？"
];

const imageFile = ref(null);
const imagePreview = ref("");
const question = ref("");
const result = ref(null);
const errorMessage = ref("");
const submitting = ref(false);
const fileInput = ref(null);

const canSubmit = computed(() => Boolean(imageFile.value && question.value.trim() && !submitting.value));
const isMockResult = computed(() => result.value?.predictionOrigin === "mock_demo");
const isAnswered = computed(() => result.value?.status === "answered");

function setQuestion(value) {
  question.value = value;
}

function chooseFile() {
  fileInput.value?.click();
}

function onFileChange(event) {
  const [file] = event.target.files ?? [];
  if (!file) {
    return;
  }
  updateImage(file);
}

function onDrop(event) {
  const [file] = event.dataTransfer.files ?? [];
  if (!file) {
    return;
  }
  updateImage(file);
}

function updateImage(file) {
  if (!file.type.startsWith("image/")) {
    errorMessage.value = "请上传图片文件。";
    return;
  }
  if (imagePreview.value) {
    URL.revokeObjectURL(imagePreview.value);
  }
  imageFile.value = file;
  imagePreview.value = URL.createObjectURL(file);
  result.value = null;
  errorMessage.value = "";
}

function resetImage() {
  if (imagePreview.value) {
    URL.revokeObjectURL(imagePreview.value);
  }
  imagePreview.value = "";
  imageFile.value = null;
  result.value = null;
  if (fileInput.value) {
    fileInput.value.value = "";
  }
}

async function submitQuestion() {
  if (!canSubmit.value) {
    return;
  }

  submitting.value = true;
  result.value = null;
  errorMessage.value = "";

  try {
    const form = new FormData();
    form.append("image", imageFile.value);
    form.append("question", question.value.trim());
    const response = await fetch("/api/v1/vqa/answers", {
      method: "POST",
      body: form
    });
    const body = await response.json();
    if (!response.ok) {
      throw new Error(body.message || "服务暂时无法处理本次请求。");
    }
    result.value = body;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "请求失败，请稍后再试。";
  } finally {
    submitting.value = false;
  }
}

onBeforeUnmount(() => {
  if (imagePreview.value) {
    URL.revokeObjectURL(imagePreview.value);
  }
});
</script>

<template>
  <main class="page-shell">
    <header class="topbar">
      <a class="brand" href="/" aria-label="RS-VQA 首页">
        <span class="brand-mark">RS</span>
        <span>RS-VQA</span>
      </a>
      <span class="version-badge">MVP v0.1.0</span>
    </header>

    <section class="hero">
      <p class="eyebrow">REMOTE SENSING VISUAL QUESTION ANSWERING</p>
      <h1>上传图像，提出一个问题。</h1>
      <p class="hero-copy">
        面向 RSVQA-HR 已验证范围的遥感图像问答演示。输入自然，回答有边界。
      </p>
    </section>

    <section class="workspace" aria-label="遥感图像问答工作区">
      <article class="card input-card">
        <div class="card-heading">
          <div>
            <p class="step">01</p>
            <h2>上传遥感图像</h2>
          </div>
          <button v-if="imageFile" class="text-button" type="button" @click="resetImage">移除</button>
        </div>

        <div
          class="upload-zone"
          :class="{ 'has-image': imagePreview }"
          role="button"
          tabindex="0"
          @click="chooseFile"
          @keydown.enter.prevent="chooseFile"
          @keydown.space.prevent="chooseFile"
          @dragover.prevent
          @drop.prevent="onDrop"
        >
          <img v-if="imagePreview" :src="imagePreview" alt="已上传图像预览" />
          <template v-else>
            <span class="upload-icon">↑</span>
            <strong>点击或拖入一张图像</strong>
            <small>PNG、JPG、WEBP 等常见图片格式，最大 10 MiB</small>
          </template>
        </div>
        <input ref="fileInput" class="visually-hidden" type="file" accept="image/*" @change="onFileChange" />
        <p v-if="imageFile" class="file-name">{{ imageFile.name }}</p>
      </article>

      <article class="card question-card">
        <div class="card-heading">
          <div>
            <p class="step">02</p>
            <h2>提出问题</h2>
          </div>
          <span class="input-hint">支持中文或英文</span>
        </div>

        <label class="question-label" for="question">你想了解什么？</label>
        <textarea
          id="question"
          v-model="question"
          maxlength="300"
          rows="4"
          placeholder="例如：图中有没有道路？"
          @keydown.meta.enter.prevent="submitQuestion"
          @keydown.ctrl.enter.prevent="submitQuestion"
        />

        <div class="examples">
          <p>可以这样问</p>
          <div class="example-list">
            <button v-for="example in examples" :key="example" type="button" @click="setQuestion(example)">
              {{ example }}
            </button>
          </div>
        </div>

        <button class="submit-button" type="button" :disabled="!canSubmit" @click="submitQuestion">
          <span v-if="submitting" class="spinner" aria-hidden="true"></span>
          {{ submitting ? "正在分析…" : "获取回答" }}
        </button>
      </article>
    </section>

    <section v-if="errorMessage" class="message error-message" role="alert">
      {{ errorMessage }}
    </section>

    <section v-if="result" class="result-section" aria-live="polite">
      <article v-if="isMockResult" class="message mock-message">
        <strong>当前为 Mock 演示模式。</strong>
        下面的回答仅用于验证页面与服务闭环，不是本研究 ViLT predicted-soft 模型输出。
      </article>

      <article class="card answer-card">
        <div class="card-heading">
          <div>
            <p class="step">03</p>
            <h2>{{ isAnswered ? (isMockResult ? "模拟回答" : "模型回答") : "当前无法回答" }}</h2>
          </div>
          <span class="status-badge" :class="result.status">{{ result.status }}</span>
        </div>

        <p v-if="isAnswered" class="answer-value">{{ result.answer }}</p>
        <p v-else class="notice-value">{{ result.capabilityNotice }}</p>
        <p v-if="isAnswered" class="notice-value">{{ result.capabilityNotice }}</p>

        <details v-if="result.canonicalQuestion" class="technical-details">
          <summary>查看本次识别到的受支持问题</summary>
          <dl>
            <div>
              <dt>标准问题</dt>
              <dd>{{ result.canonicalQuestion }}</dd>
            </div>
            <div>
              <dt>问题类型</dt>
              <dd>{{ result.questionType }}</dd>
            </div>
            <div>
              <dt>输出来源</dt>
              <dd>{{ result.predictionOrigin }}</dd>
            </div>
          </dl>
        </details>
      </article>
    </section>

    <section class="boundary-note">
      <h2>本版能力边界</h2>
      <p>
        当前研究任务是特定答案词表上的闭集遥感问答，不支持任意开放式提问、目标检测、变化检测或法定面积测量。
        无法映射到已验证问题范围的输入会被明确提示，而不会被包装成可靠模型答案。
      </p>
    </section>
  </main>
</template>
